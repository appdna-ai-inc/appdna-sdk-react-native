package com.appdna.rn

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReactApplicationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicReference

/**
 * A `ReadableArray` may only be read on the thread the bridge delivered it on.
 *
 * `configure` and `presentPaywall` both parse their `ReadableMap` BEFORE dispatching, and both say so
 * in a comment. `getProducts` did not: it read the `ReadableArray` INSIDE `scope.launch`, i.e. after
 * the method had already returned, from a coroutine — the one place in the module that violated its
 * own stated invariant. The bridge is free to recycle the backing buffer by then, so
 * `getProducts([...])` could query the store with a garbled or empty id list and resolve `[]` with no
 * error anywhere. A bug that only shows up under memory pressure, on a device, in production.
 *
 * The assertion is the THREAD the array is read on — the property the invariant is about — and it is
 * deterministic: Robolectric's looper is PAUSED, so a coroutine queued on `Dispatchers.Main` has not
 * run when `getProducts` returns. Had the read stayed inside the launch, nothing would have touched
 * the array at all by the time the assertions run.
 */
@RunWith(RobolectricTestRunner::class)
class AppdnaGetProductsThreadTest {

    @Test
    fun `getProducts reads the ReadableArray on the calling (JS) thread`() {
        val readOn = AtomicReference<Thread>()
        val record = { readOn.compareAndSet(null, Thread.currentThread()) }

        val productIds = mock(ReadableArray::class.java)
        `when`(productIds.size()).thenAnswer { record(); 2 }
        `when`(productIds.getString(0)).thenAnswer { record(); "premium_monthly" }
        `when`(productIds.getString(1)).thenAnswer { record(); "premium_yearly" }

        val module = AppdnaModule(mock(ReactApplicationContext::class.java))
        val callingThread = Thread.currentThread()

        module.getProducts(productIds, mock(Promise::class.java))

        assertNotNull(
            "getProducts never touched the ReadableArray before returning — it deferred the read into " +
                "a coroutine, where the array is no longer valid (E10)",
            readOn.get(),
        )
        assertEquals(
            "the ReadableArray was read off the thread that delivered it (E10)",
            callingThread,
            readOn.get(),
        )
    }
}
