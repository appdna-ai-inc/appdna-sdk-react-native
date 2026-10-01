package com.appdna.rn

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * SPEC-070-B E6 — teardown must not abandon anything it was in the middle of.
 *
 * `invalidate()` calls `scope.cancel()`. A cancelled coroutine does not run the rest of its body, so
 * before this every in-flight `configure` / `purchase` / `getProducts` / `restorePurchases` died
 * WITHOUT `promise.reject` — and a JS promise that is neither resolved nor rejected hangs its `await`
 * for the life of the process. A Metro reload mid-purchase left the host's checkout spinner turning,
 * forever, with no error anywhere to say why.
 *
 * The same scope carries the `onPromoCodeSubmit` veto, whose `completion` native is BLOCKING on: kill
 * the scope and the promo field spins for ever too. Its safe default is REJECT (a code nobody
 * validated is not a valid code), and that is what every abandonment path must now answer with.
 *
 * Robolectric's looper is PAUSED, so a coroutine launched on `Dispatchers.Main` is queued and does
 * not run until the looper is idled. Never idling it is exactly the "in flight" state under test —
 * and it is deterministic, not a sleep.
 */
@RunWith(RobolectricTestRunner::class)
class AppdnaTeardownTest {

    /** Records the first settlement: the reject code, or `"RESOLVED"` for a resolve. */
    private fun recordingPromise(settled: AtomicReference<String>): Promise {
        val answer = Answer<Any?> { invocation ->
            val code = when (invocation.method.name) {
                "resolve" -> "RESOLVED"
                else -> invocation.arguments.firstOrNull() as? String ?: "REJECTED"
            }
            settled.compareAndSet(null, code)
            null
        }
        return mock(Promise::class.java, answer)
    }

    @Test
    fun `invalidate rejects an in-flight promise rather than leaving it unsettled forever`() {
        val settled = AtomicReference<String>()
        val module = AppdnaModule(mock(ReactApplicationContext::class.java))

        // Queued on the paused main looper: the coroutine body has NOT run, so the promise is in
        // flight — precisely the state a Metro reload catches a purchase in.
        module.restorePurchases(recordingPromise(settled))
        assertEquals("the coroutine ran early — the test is not observing an in-flight promise", null, settled.get())

        try {
            module.invalidate()
        } catch (t: Throwable) {
            // The REST of teardown detaches delegates from the process-global SDK singleton and calls
            // RN's base-module `invalidate`, neither of which is initialised in a unit test. That is
            // irrelevant here: settling the pending promises is deliberately the FIRST thing
            // `invalidate()` does, and it is the only thing this test asserts.
        }

        assertEquals(
            "invalidate() left the promise unsettled — the JS `await` would hang for the life of the process",
            "SDK_INVALIDATED",
            settled.get(),
        )
    }

    @Test
    fun `a call that arrives after teardown is rejected, not swallowed`() {
        val settled = AtomicReference<String>()
        val module = AppdnaModule(mock(ReactApplicationContext::class.java))

        try {
            module.invalidate()
        } catch (t: Throwable) {
            // As above.
        }

        // `scope.launch` on a CANCELLED scope produces a job whose body never runs at all — no
        // exception, no `finally`, nothing. Without the `isActive` check in `launchSettling`, this
        // promise would simply never settle.
        module.getEntitlements(recordingPromise(settled))

        assertEquals("SDK_INVALIDATED", settled.get())
    }

    @Test
    fun `promo-code veto answers reject when the scope is already dead`() {
        val answers = mutableListOf<Boolean>()
        val forwarder = PaywallForwarder(
            AppdnaEventEmitter { _, _ -> },
            AppdnaVetoInvoker(1_000L) { },
            // The module's scope is cancelled: it could not launch the veto coroutine, so the body —
            // and its `finally` — never runs. The forwarder itself must answer.
            launchVeto = { false },
        )

        forwarder.onPromoCodeSubmit("pw_1", "SAVE20") { answers.add(it) }

        assertEquals(
            "the completion native is BLOCKING on was never called — the promo field spins forever",
            listOf(false),
            answers,
        )
    }

    @Test
    fun `promo-code veto answers reject when the scope dies mid-veto`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val answered = CountDownLatch(1)
        val answers = mutableListOf<Boolean>()

        val forwarder = PaywallForwarder(
            AppdnaEventEmitter { _, _ -> },
            // A 60 s timeout: the test must be decided by the CANCELLATION, never by the timer.
            AppdnaVetoInvoker(60_000L) { },
            launchVeto = { block ->
                scope.launch { block() }
                true
            },
        )

        forwarder.onPromoCodeSubmit("pw_1", "SAVE20") {
            answers.add(it)
            answered.countDown()
        }

        // The veto is now suspended, awaiting a JS reply that will never come. Tear the scope down
        // under it — a Metro reload with a promo code in flight.
        Thread.sleep(100)
        scope.cancel()

        assertTrue(
            "the veto was cancelled and `completion` was never called — native waits for it forever",
            answered.await(5, TimeUnit.SECONDS),
        )
        assertEquals(listOf(false), answers)
    }
}

/**
 * `session.set(k, null | undefined | NaN)` crosses as the JSON text "null". It stores nothing and
 * RESOLVES, as the Flutter bridge does. NEGATIVE CONTROL: the bridge rejected with INVALID_VALUE, so the
 * same call threw on React Native and did nothing on Flutter.
 *
 * The native session store is initialised here (`SessionDataStore.initialize`, what `configure` does): on an
 * unconfigured SDK `AppDNA.getSessionData` is null for every key, so "stores nothing" held whatever the
 * bridge did. The live-store control below proves a stored value IS read back.
 */
@RunWith(RobolectricTestRunner::class)
class AppdnaSessionNullTest {
    private fun promise(settled: AtomicReference<String>): Promise = mock(Promise::class.java, Answer<Any?> { invocation ->
        settled.compareAndSet(null, if (invocation.method.name == "resolve") "RESOLVED" else invocation.arguments.firstOrNull() as? String ?: "REJECTED")
        null
    })

    @org.junit.Before
    fun initStore() {
        ai.appdna.sdk.core.SessionDataStore.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ai.appdna.sdk.AppDNA.clearSessionData()
    }

    @Test
    fun `the store is live - a non-null value is stored and read back`() {
        val settled = AtomicReference<String>()
        AppdnaModule(mock(ReactApplicationContext::class.java)).setSessionData("r19_live", "\"x\"", promise(settled))
        assertEquals("RESOLVED", settled.get())
        assertEquals("x", ai.appdna.sdk.AppDNA.getSessionData("r19_live"))
    }

    @Test
    fun `a JSON null value resolves and stores nothing`() {
        val settled = AtomicReference<String>()
        val module = AppdnaModule(mock(ReactApplicationContext::class.java))
        module.setSessionData("r18_null", "null", promise(settled))
        assertEquals("RESOLVED", settled.get())
        assertEquals(null, ai.appdna.sdk.AppDNA.getSessionData("r18_null"))
    }

    @Test
    fun `a JSON null value leaves an existing value as it was`() {
        ai.appdna.sdk.AppDNA.setSessionData("r19_kept", "before")
        val settled = AtomicReference<String>()
        AppdnaModule(mock(ReactApplicationContext::class.java)).setSessionData("r19_kept", "null", promise(settled))
        assertEquals("RESOLVED", settled.get())
        assertEquals("before", ai.appdna.sdk.AppDNA.getSessionData("r19_kept"))
    }
}
