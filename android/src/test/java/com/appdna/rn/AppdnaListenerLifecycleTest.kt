package com.appdna.rn

import ai.appdna.sdk.AppDNA
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **the N-fold-delivery property itself**.
 *
 * Every native listener the bridge registers is registered on the process-global `AppDNA` singleton
 * and captures a bridge-scoped module. A Metro reload destroys the JS side and creates a new module;
 * `invalidate()` is the only thing that detaches the old one. Leave a single listener attached across
 * a reload and the singleton now holds TWO, both live, and every entitlement change is delivered
 * twice — to a host that grants entitlements on that event, that is granting twice.
 *
 * ## What was — and was not — proven
 *
 * `AppdnaTeardownTest` proves `invalidate()` settles in-flight promises. `delegateLifecycle.test.ts`
 * proves the JS half ("100 remounts … must leave 1"). AC-19 asks for the NATIVE half — *"a Robolectric
 * `invalidate()`→`configure()` check"* — and nothing did it. The teardown CODE existed
 * (`AppdnaModule.kt:781-793`); what did not exist was anything that would notice if it stopped
 * working. Delete the `removeEntitlementsChangedListener` line and, until this file, the whole suite
 * stayed green.
 *
 * ## The oracle
 *
 * The listener count on the REAL singleton, read out of the SDK's own registries by reflection. Not a
 * count of the wrapper's own bookkeeping — that is the thing under test, and asking it to grade itself
 * would prove nothing. The two homes a listener can be in (the pre-init queue on `BillingModule`, and
 * `EntitlementCache.changeListeners` once billing exists) are BOTH counted, because which one a
 * listener lands in depends on whether the bootstrap coroutine has reached the billing manager yet —
 * a race the test must not be sensitive to, and a place a leaked listener could otherwise hide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppdnaListenerLifecycleTest {

    private val live = mutableListOf<AppdnaModule>()

    private fun context(): ReactApplicationContext {
        val ctx = mock(ReactApplicationContext::class.java)
        // AppDNA.configure needs a real Android Context; Robolectric supplies one.
        org.mockito.Mockito.`when`(ctx.applicationContext).thenReturn(RuntimeEnvironment.getApplication())
        return ctx
    }

    private fun noopPromise(): Promise = mock(Promise::class.java, Answer<Any?> { null })

    private fun newModule(): AppdnaModule = AppdnaModule(context()).also { live.add(it) }

    /** Tear a module down. The delegate/`super.invalidate()` tail needs an initialised RN runtime. */
    private fun tearDown(module: AppdnaModule) {
        try {
            module.invalidate()
        } catch (t: Throwable) {
            // The listener detach is deliberately near the TOP of `invalidate()` — before anything that
            // can throw in a unit test — so it has already run. The count below is what proves it, and
            // swallowing here does not weaken it: a missing detach still shows up as an extra listener.
        }
        live.remove(module)
    }

    /** Drive the real `configure()` and wait for it to settle (it runs off the caller's thread). */
    private fun configure(module: AppdnaModule) {
        val settled = CountDownLatch(1)
        val promise = mock(Promise::class.java, Answer<Any?> { settled.countDown(); null })
        module.configure("adn_test_placeholder", "sandbox", JavaOnlyMap(), promise)
        assertEquals(
            true,
            settled.await(20, TimeUnit.SECONDS),
        )
    }

    /**
     * How many entitlement listeners the process-global SDK is holding, across BOTH of the registries
     * one can end up in.
     */
    private fun entitlementListenerCount(): Int {
        val billing = AppDNA.billing

        val pending = billing.javaClass
            .getDeclaredField("pendingEntitlementListeners")
            .apply { isAccessible = true }
            .get(billing) as Collection<*>

        val manager = billing.javaClass
            .getDeclaredField("manager")
            .apply { isAccessible = true }
            .get(billing)

        val attached: Int = manager?.let { mgr ->
            val cache = mgr.javaClass
                .getDeclaredField("entitlementCache")
                .apply { isAccessible = true }
                .get(mgr)
            val listeners = cache.javaClass
                .getDeclaredField("changeListeners")
                .apply { isAccessible = true }
                .get(cache) as Collection<*>
            listeners.size
        } ?: 0

        return pending.size + attached
    }

    @After
    fun cleanUp() {
        // The singleton outlives the test. Leaving a module attached would hand the next test in this
        // sandbox a phantom listener — the very bug under test, self-inflicted.
        live.toList().forEach { tearDown(it) }
        AppDNA.shutdown()
    }

    @Test
    fun `invalidate then configure leaves exactly ONE entitlement listener on the singleton`() {
        // The literal AC-19 sequence: a reload is `invalidate()` on the dying module followed by
        // `configure()` on the new one.
        val before = entitlementListenerCount()

        val first = newModule()
        configure(first)
        first.startEntitlementObserver(noopPromise())
        assertEquals("the observer did not attach at all", before + 1, entitlementListenerCount())

        tearDown(first)
        assertEquals(
            "invalidate() left the old module's listener attached to the process-global singleton",
            before,
            entitlementListenerCount(),
        )

        val second = newModule()
        configure(second)
        second.startEntitlementObserver(noopPromise())

        assertEquals(
            "the reloaded module is the SECOND listener on the singleton — every entitlement change is now delivered twice",
            before + 1,
            entitlementListenerCount(),
        )
    }

    @Test
    fun `100 reloads leave 1 listener, not 100`() {
        // The JS half of this property is proven in `delegateLifecycle.test.ts` ("100 remounts … must
        // leave 1"). This is the native half: the count that actually decides how many times a host's
        // `onEntitlementsChanged` fires lives on the SDK singleton, not in JS.
        val before = entitlementListenerCount()

        repeat(100) {
            val module = newModule()
            module.startEntitlementObserver(noopPromise())
            tearDown(module)
        }

        assertEquals(
            "every reload left its listener behind — 100 live modules, each fanning the same event out again",
            before,
            entitlementListenerCount(),
        )

        // …and the surviving module is still attached exactly once.
        val current = newModule()
        current.startEntitlementObserver(noopPromise())
        assertEquals(before + 1, entitlementListenerCount())
    }

    @Test
    fun `startEntitlementObserver is idempotent within one module`() {
        // Nothing stops a host calling it twice without a reload: a component re-mount, a re-subscribe,
        // a Fast Refresh in dev (which runs NEITHER `invalidate()` nor `configure()`). Each call used to
        // append another closure — `onEntitlementsChanged` only ever ADDS.
        val before = entitlementListenerCount()
        val module = newModule()

        repeat(5) { module.startEntitlementObserver(noopPromise()) }

        assertEquals(
            "five subscribes left five listeners — the host's grant path runs five times per change",
            before + 1,
            entitlementListenerCount(),
        )
    }
}
