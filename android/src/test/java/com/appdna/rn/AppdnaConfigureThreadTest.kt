package com.appdna.rn

import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * `configure()` must not run on the JS thread.
 *
 * A TurboModule method body executes on the JS THREAD on Android (E10), and `AppDNA.configure` opens
 * SQLite, reads SharedPreferences and warms the config cache. Running that inline stalls JS at app
 * start — exactly when the JS thread is busiest and a stall is most visible. iOS is already safe
 * (its `configure` hops onto a utility queue internally); Android was not.
 *
 * The assertion is the THREAD the promise settles on, not a stopwatch. A millisecond budget would be
 * flaky on shared CI hardware, while "did the work leave the caller's thread" is exactly the property
 * W15 asks for and is deterministic: had `configure` run inline, it could only have settled on the
 * calling thread.
 *
 * Whether native configure SUCCEEDS here is irrelevant — a reject records the thread just as a
 * resolve does, and the threading contract is what is under test. The promise is a Mockito mock with
 * a catch-all Answer rather than a hand-written stub: `Promise` has ten `reject` overloads, and a
 * stub that misses one stops compiling every time RN adds another.
 */
@RunWith(RobolectricTestRunner::class)
class AppdnaConfigureThreadTest {

    @Test
    fun `configure settles off the calling (JS) thread`() {
        val settledOn = AtomicReference<Thread>()
        val latch = CountDownLatch(1)

        // Any call on the promise (resolve OR any reject overload) records the settling thread.
        val recordThread = Answer<Any?> {
            if (settledOn.compareAndSet(null, Thread.currentThread())) latch.countDown()
            null
        }
        val promise = mock(Promise::class.java, recordThread)

        val ctx = mock(ReactApplicationContext::class.java)
        // AppDNA.configure needs a real Android Context; Robolectric supplies one.
        org.mockito.Mockito.`when`(ctx.applicationContext)
            .thenReturn(RuntimeEnvironment.getApplication())

        val module = AppdnaModule(ctx)
        val callingThread = Thread.currentThread()

        module.configure("adn_test_placeholder", "sandbox", JavaOnlyMap(), promise)

        assertTrue(
            "configure() never settled its promise within 10s",
            latch.await(10, TimeUnit.SECONDS),
        )
        assertNotEquals(
            "configure() settled on the CALLING thread — it ran inline and blocked the JS thread (W15)",
            callingThread,
            settledOn.get(),
        )
    }
}
