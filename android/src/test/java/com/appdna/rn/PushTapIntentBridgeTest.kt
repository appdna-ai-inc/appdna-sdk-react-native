package com.appdna.rn

import ai.appdna.sdk.AppDNA
import android.app.Activity
import android.content.Intent
import android.os.Looper
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.CxxCallbackImpl
import com.facebook.react.bridge.JavaOnlyArray
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * A tap on a notification the native SDK displayed opens the launch activity with the tap in its
 * intent — through `onNewIntent` while the app runs, or as the launch intent on a cold start. Nothing on
 * the JS side sees that intent (RNFirebase reports only notifications FCM displayed) and the module
 * listened to no activity event, so on Android these button, body and reply taps were never tracked or
 * routed and JS `onPushTapped` never fired.
 *
 * Drives the REAL module (its activity-event listener and its bridged `configure`) into the live native
 * SDK, and asserts native OUTPUTS: the route the core push-tap router took, the activity's intent left
 * recognisable to host JS (native handles a copy), and the `onPushTapped` the module pushed across the bridge.
 *
 * NEGATIVE CONTROL: with `routePushTap` reduced to `return` (no hand-off to native) the last three tests fail;
 * without the `addActivityEventListener` call in `init` the first one does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PushTapIntentBridgeTest {

    private lateinit var reactContext: ReactApplicationContext
    private lateinit var module: AppdnaModule
    private val emitted = ConcurrentLinkedQueue<String>()
    private val routes = mutableListOf<Pair<String, String>>()
    private var argumentsMock: MockedStatic<Arguments>? = null

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun tapIntent(pushId: String, deliveryId: String) = Intent(Intent.ACTION_MAIN).apply {
        putExtra("appdna", "1")
        putExtra("push_id", pushId)
        putExtra("delivery_id", deliveryId)
    }

    /** Builds the module with [launchIntent] as the foreground activity's intent. */
    private fun newModule(launchIntent: Intent) {
        val activity: Activity = Robolectric.buildActivity(Activity::class.java, launchIntent).setup().get()
        reactContext = mock(ReactApplicationContext::class.java)
        Mockito.`when`(reactContext.applicationContext).thenReturn(RuntimeEnvironment.getApplication())
        Mockito.`when`(reactContext.currentActivity).thenReturn(activity)
        module = AppdnaModule(reactContext)
        val recorder = mock(CxxCallbackImpl::class.java, Answer<Any?> { invocation ->
            val raw = invocation.arguments
            val args: Array<*> = if (raw.size == 1 && raw[0] is Array<*>) raw[0] as Array<*> else raw
            (args.getOrNull(0) as? String)?.let { emitted += it }
            null
        })
        com.facebook.react.bridge.BaseJavaModule::class.java.getDeclaredField("mEventEmitterCallback")
            .apply { isAccessible = true }.set(module, recorder)
    }

    private fun configureAndWait() {
        val options = JavaOnlyMap().apply {
            putInt("batchSize", 0)
            putDouble("flushInterval", 86_400.0)
            putString("logLevel", "none")
        }
        val ready = CountDownLatch(1)
        module.configure("adn_test_placeholder", "sandbox", options, mock(Promise::class.java, Answer { null }))
        AppDNA.onReady { ready.countDown() }
        val deadline = System.currentTimeMillis() + 20_000
        while (ready.count > 0L && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(20)
        }
        assertTrue("the SDK never reached READY in 20 s", ready.count == 0L)
        settle()
    }

    private fun settle() {
        repeat(5) {
            idle()
            Thread.sleep(50)
        }
        idle()
    }

    @Before
    fun setUp() {
        argumentsMock = Mockito.mockStatic(Arguments::class.java, Mockito.CALLS_REAL_METHODS).also { m ->
            m.`when`<WritableMap> { Arguments.createMap() }.thenAnswer { JavaOnlyMap() }
            m.`when`<WritableArray> { Arguments.createArray() }.thenAnswer { JavaOnlyArray() }
        }
        runCatching { AppDNA.shutdown() }
        idle()
        PendingPushTaps.resetForTest()
        val idem = Class.forName("ai.appdna.sdk.integrations.PushIdempotency")
        idem.getDeclaredMethod("resetForTesting").apply { isAccessible = true }
            .invoke(idem.getDeclaredField("INSTANCE").get(null))
        Class.forName("ai.appdna.sdk.integrations.PushTapRouter").getDeclaredField("routeSink")
            .apply { isAccessible = true }
            .set(null, { type: String, value: String -> routes += type to value })
    }

    @After
    fun tearDown() {
        Class.forName("ai.appdna.sdk.integrations.PushTapRouter").getDeclaredField("routeSink")
            .apply { isAccessible = true }.set(null, null)
        if (::module.isInitialized) runCatching { module.invalidate() }
        PendingPushTaps.resetForTest()
        runCatching { AppDNA.shutdown() }
        argumentsMock?.close()
        idle()
    }

    @Test
    fun `the module listens to activity intents and stops on invalidate`() {
        newModule(Intent(Intent.ACTION_MAIN))
        Mockito.verify(reactContext).addActivityEventListener(module.pushTapIntentListener)
        Mockito.verify(reactContext).addLifecycleEventListener(module.pushTapIntentListener)
        runCatching { module.invalidate() }
        Mockito.verify(reactContext).removeActivityEventListener(module.pushTapIntentListener)
        Mockito.verify(reactContext).removeLifecycleEventListener(module.pushTapIntentListener)
    }

    /**
     * A React instance that outlives its activity (the activity finished with Back while the
     * process lived). A tap starts a NEW activity whose launch intent carries it — no `onNewIntent`, and
     * `configure` already ran — and nothing handed it over. Now the host resume hands the current
     * activity's intent over; resuming again (and a re-`configure`) does not hand it over twice.
     * NEGATIVE CONTROL: with `onHostResume` empty nothing is routed.
     */
    @Test
    fun `warm start - a new activity's tap is handed over on host resume, once`() {
        newModule(Intent(Intent.ACTION_MAIN))
        configureAndWait()
        assertTrue(routes.isEmpty())

        val tap = tapIntent("p-warm", "d-warm").apply {
            putExtra("action_type", "deep_link")
            putExtra("action_value", "https://example.com/warm")
        }
        val next: Activity = Robolectric.buildActivity(Activity::class.java, tap).setup().get()
        Mockito.`when`(reactContext.currentActivity).thenReturn(next)
        module.pushTapIntentListener.onHostResume()
        settle()
        assertEquals(listOf("deep_link" to "https://example.com/warm"), routes)
        assertEquals(1, emitted.count { it == "onPushTapped" })

        module.pushTapIntentListener.onHostPause()
        module.pushTapIntentListener.onHostResume()
        AppDNA.shutdown()
        idle()
        configureAndWait()
        assertEquals("routed once", 1, routes.size)
        assertEquals("onPushTapped once", 1, emitted.count { it == "onPushTapped" })
        assertEquals("the activity's intent keeps the marker", "1", tap.getStringExtra("appdna"))
    }

    @Test
    fun `a tap that arrives through onNewIntent is handled natively and reaches JS`() {
        newModule(Intent(Intent.ACTION_MAIN))
        configureAndWait()
        val intent = tapIntent("p-new", "d-new").apply {
            putExtra("action_id", "btn_open")
            putExtra("action_type", "open_url")
            putExtra("action_value", "https://example.com/button")
        }

        module.pushTapIntentListener.onNewIntent(intent)
        settle()

        assertEquals(listOf("deep_link" to "https://example.com/button"), routes)
        assertEquals("the activity's intent keeps the marker", "1", intent.getStringExtra("appdna"))
        assertEquals(1, emitted.count { it == "onPushTapped" })
    }

    @Test
    fun `a cold start from a tap - the launch intent is handled at configure`() {
        val launch = tapIntent("p-cold", "d-cold").apply {
            putExtra("action_type", "deep_link")
            putExtra("action_value", "https://example.com/cold")
        }
        newModule(launch)
        configureAndWait()

        assertEquals(listOf("deep_link" to "https://example.com/cold"), routes)
        assertEquals("the launch intent keeps the marker", "1", launch.getStringExtra("appdna"))
        assertEquals(1, emitted.count { it == "onPushTapped" })
    }

    /**
     * Host JS that reads the launch intent's extras and branches on `handleTap` must see
     * the tap as AppDNA's (`true`), never route it a second time — and a re-run (the next `configure`)
     * is deduplicated by the persisted claim.
     * NEGATIVE CONTROL: with `routePushTap` handing native the activity's own intent (no copy) the
     * marker is gone, `isAppDNAMessage` and `handlePushTap` answer `false`, and the host would route twice.
     */
    @Test
    fun `a cold-start tap stays recognisable to host JS and is routed once`() {
        val launch = tapIntent("p-host", "d-host").apply {
            putExtra("action_type", "deep_link")
            putExtra("action_value", "https://example.com/host")
        }
        newModule(launch)
        configureAndWait()
        assertEquals(listOf("deep_link" to "https://example.com/host"), routes)

        val fromIntent = JavaOnlyMap().apply {
            for (k in launch.extras!!.keySet()) launch.getStringExtra(k)?.let { putString(k, it) }
        }
        val answers = mutableListOf<Any?>()
        val recorder = mock(Promise::class.java, Answer<Any?> { inv ->
            if (inv.method.name == "resolve") answers += inv.arguments[0]
            null
        })
        module.isAppDNAMessage(fromIntent, recorder)
        module.handlePushTap(fromIntent, null, recorder)
        settle()
        assertEquals("host JS sees an AppDNA tap, already handled", listOf<Any?>(true, true), answers)

        module.routePushTap(launch) // the next configure hands the launch intent over again: ignored
        settle()
        assertEquals("routed once", 1, routes.size)
        assertEquals("onPushTapped once", 1, emitted.count { it == "onPushTapped" })
    }

    /**
     * Native handles a copy, so the launch intent stays a live tap, and only the persisted
     * claim (the last 32 tap keys) kept a re-`configure` from routing it again — after 33 later taps it
     * fired again. NEGATIVE CONTROL: without the [PushTapIntentLedger] check in `routePushTap` the launch
     * tap is routed twice.
     */
    @Test
    fun `a re-configure after more than 32 taps does not re-fire the launch tap`() {
        val launch = tapIntent("p-launch33", "d-launch33").apply {
            putExtra("action_type", "deep_link")
            putExtra("action_value", "https://example.com/launch33")
        }
        newModule(launch)
        configureAndWait()
        val launchRoutes = { routes.count { it.second == "https://example.com/launch33" } }
        assertEquals(1, launchRoutes())

        repeat(33) { i -> module.pushTapIntentListener.onNewIntent(tapIntent("p-later-$i", "d-later-$i")) }
        settle()
        assertEquals(34, emitted.count { it == "onPushTapped" })

        AppDNA.shutdown()
        idle()
        configureAndWait()
        assertEquals("the launch tap is routed once", 1, launchRoutes())
        assertEquals("onPushTapped once per tap", 34, emitted.count { it == "onPushTapped" })
        assertEquals("the launch intent keeps the marker", "1", launch.getStringExtra("appdna"))
    }

    /**
     * A tap on a notification the previous SDK version posted (no marker, no key) cannot be
     * deduplicated by native, and the module strips only the copy, so every re-`configure` routed the
     * launch intent again. NEGATIVE CONTROL: without the ledger check in `routePushTap` it is routed twice.
     */
    @Test
    fun `a legacy unkeyed launch tap is routed once across a re-configure`() {
        val launch = Intent(Intent.ACTION_MAIN).apply {
            putExtra("push_id", "")
            putExtra("action_type", "deep_link")
            putExtra("action_value", "https://example.com/legacy-launch")
            putExtra("screen_id", "")
            putExtra("deep_link", "")
        }
        newModule(launch)
        configureAndWait()
        val legacyRoutes = { routes.count { it.second == "https://example.com/legacy-launch" } }
        assertEquals("handled at configure", 1, legacyRoutes())

        AppDNA.shutdown()
        idle()
        configureAndWait()
        assertEquals("a re-configure does not route it again", 1, legacyRoutes())
        // A reload re-creates the module; the ledger is process-wide.
        runCatching { module.invalidate() }
        newModule(launch)
        configureAndWait()
        assertEquals("nor does a re-created module", 1, legacyRoutes())
    }

    /**
     * Before `configure`, each intent the module handed over (each `onHostResume` of a new
     * activity, each `onNewIntent`) used to leave its own closure in native's `onReady` list, kept until
     * ready, so the list grew. Now non-taps never wait and the taps wait behind ONE native callback; every
     * tap is still handled once at configure.
     * NEGATIVE CONTROL: with `routePushTap` registering `AppDNA.onReady` per intent again, the list grows by 40.
     */
    @Test
    fun `before configure the native ready list stays bounded and every tap is still handled once`() {
        newModule(Intent(Intent.ACTION_MAIN))
        val before = nativeReadyCallbackCount()
        repeat(20) { i ->
            val tap = tapIntent("p-pre-$i", "d-pre-$i")
            val other = Intent(Intent.ACTION_VIEW).apply { putExtra("k", "v$i") }
            for (intent in listOf(tap, other)) {
                val next: Activity = Robolectric.buildActivity(Activity::class.java, intent).setup().get()
                Mockito.`when`(reactContext.currentActivity).thenReturn(next)
                module.pushTapIntentListener.onHostResume()
            }
        }
        idle()
        assertTrue("native kept ${nativeReadyCallbackCount() - before} closures", nativeReadyCallbackCount() - before <= 1)
        assertEquals(20, PendingPushTaps.pendingCountForTest())

        configureAndWait()
        assertEquals("each tap handled once", 20, emitted.count { it == "onPushTapped" })
        assertEquals(0, PendingPushTaps.pendingCountForTest())
    }

    /**
     * Native throwing while it handles a waiting tap threw out of an `onReady` closure on the
     * main thread, which crashes the app. NEGATIVE CONTROL: without the catch in `PendingPushTaps.drain`
     * the exception reaches the looper and this test fails.
     */
    @Test
    fun `a tap native throws on does not crash the main thread`() {
        newModule(Intent(Intent.ACTION_MAIN))
        PendingPushTaps.handle = { throw IllegalStateException("native threw") }
        module.pushTapIntentListener.onNewIntent(tapIntent("p-throw", "d-throw"))
        configureAndWait()
        assertEquals(0, PendingPushTaps.pendingCountForTest())
    }

    /**
     * A tap that waited for `configure`, then JS `shutdown()` and a new `configure` (another user,
     * after a sign-out): the tap belonged to the session that ended. The native `onReady` closure outlives
     * `shutdown()` and drained it into the new session; the iOS SDK clears its own buffer at `shutdown()`.
     * A tap that arrives after the `shutdown()` is still delivered. NEGATIVE CONTROL: without
     * `PendingPushTaps.clearOnShutdown()` in `shutdown`, two taps reach JS — the assertion fails.
     */
    @Test
    fun `a tap waiting for configure is dropped by shutdown, not delivered to the next session`() {
        newModule(Intent(Intent.ACTION_MAIN))
        val old = tapIntent("p-old", "d-old")
        module.pushTapIntentListener.onNewIntent(old)
        idle()
        assertEquals(1, PendingPushTaps.pendingCountForTest())

        module.shutdown(mock(Promise::class.java, Answer { null }))
        assertEquals("shutdown() empties the queue", 0, PendingPushTaps.pendingCountForTest())
        module.pushTapIntentListener.onNewIntent(tapIntent("p-new", "d-new"))
        idle()

        configureAndWait()
        assertEquals("only the tap of the new session reached JS", 1, emitted.count { it == "onPushTapped" })
        module.pushTapIntentListener.onNewIntent(old)   // never handed over again
        settle()
        assertEquals(1, emitted.count { it == "onPushTapped" })

        // The dropped tap is NOT reported as handled: host JS that forwards its extras through
        // `AppDNA.push.handleTap` gets it tracked and routed now, once — the tap is not lost.
        val oldData = JavaOnlyMap().apply {
            putString("appdna", "1"); putString("push_id", "p-old"); putString("delivery_id", "d-old")
            putString("action_type", "deep_link"); putString("action_value", "https://example.com/old")
        }
        var answer: Any? = null
        module.handlePushTap(oldData, null, mock(Promise::class.java, Answer { inv ->
            if (inv.method.name == "resolve") answer = inv.arguments[0]; null }))
        settle()
        assertEquals("the host's handleTap handles the dropped tap now", true, answer)
        assertEquals(listOf("deep_link" to "https://example.com/old"), routes)
        assertEquals(2, emitted.count { it == "onPushTapped" })
    }

    /**
     * `resetForTest` cleared the queue but not `drainRegistered`, so after a test that queued
     * a tap and never reached ready, a queued tap registered no drain. NEGATIVE CONTROL: without
     * `drainRegistered = false` in `resetForTest`, no closure is registered and the assertion fails.
     */
    @Test
    fun `resetForTest re-arms the drain registration`() {
        newModule(Intent(Intent.ACTION_MAIN))
        module.pushTapIntentListener.onNewIntent(tapIntent("p-a", "d-a"))
        PendingPushTaps.resetForTest()
        nativeReadyCallbacks().clear()
        module.pushTapIntentListener.onNewIntent(tapIntent("p-b", "d-b"))
        assertEquals("a tap queued after the reset registered no drain", 1, nativeReadyCallbacks().size)
    }

    /**
     * A drain posted to the main thread while the SDK was ready (`AppDNA.onReady` posts at
     * once) runs AFTER a JS `shutdown()` that came in on the native-modules thread. It used to hand the tap that
     * arrived after the shutdown to the shut-down SDK, which dropped it: never tracked, never routed. Now a drain
     * registered before a shutdown hands nothing over and waits for the next ready.
     * NEGATIVE CONTROL: without the `shutdownGeneration` check in `drain`, the held drain hands `p-after` to the
     * shut-down SDK — the first assertion fails (one hand-over) and the tap never reaches JS.
     */
    @Test
    fun `a drain posted before shutdown hands nothing to the shut-down SDK`() {
        newModule(Intent(Intent.ACTION_MAIN))
        configureAndWait()
        var held: (() -> Unit)? = null
        PendingPushTaps.onReady = { cb -> if (held == null) held = cb else AppDNA.onReady(cb) }
        var handedOver = 0
        PendingPushTaps.handle = { handedOver++; AppDNA.handlePushTap(it) }

        module.pushTapIntentListener.onNewIntent(tapIntent("p-before", "d-before"))   // its drain is "posted"
        module.shutdown(mock(Promise::class.java, Answer { null }))
        module.pushTapIntentListener.onNewIntent(tapIntent("p-after", "d-after").apply {
            putExtra("action_type", "deep_link")
            putExtra("action_value", "https://example.com/after")
        })
        assertEquals(1, PendingPushTaps.pendingCountForTest())

        held!!.invoke()   // the posted drain runs now, after the shutdown
        idle()
        assertEquals("a tap was handed to the shut-down SDK", 0, handedOver)
        assertEquals("the tap that arrived after the shutdown still waits", 1, PendingPushTaps.pendingCountForTest())

        configureAndWait()
        val deadline = System.currentTimeMillis() + 10_000
        while (routes.isEmpty() && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(20) }
        assertEquals(listOf("deep_link" to "https://example.com/after"), routes)
        assertEquals(1, emitted.count { it == "onPushTapped" })
    }

    /**
     * JS `shutdown()` (native-modules thread) while the main thread hands a tap to native:
     * the native shutdown waits for that hand-over. NEGATIVE CONTROL: without `handoverLock` in
     * `shutdownNative`, `shutdown()` completes while the tap is being handed over.
     */
    @Test
    fun `shutdown waits for a tap being handed to native`() {
        newModule(Intent(Intent.ACTION_MAIN))
        configureAndWait()
        val shutdownDone = CountDownLatch(1)
        var shutdownRanDuringHandover: Boolean? = null
        PendingPushTaps.handle = {
            // The main thread is handing this tap to native; JS calls shutdown() on its own thread now.
            Thread { module.shutdown(mock(Promise::class.java, Answer { null })); shutdownDone.countDown() }.start()
            shutdownRanDuringHandover = shutdownDone.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            true
        }
        module.pushTapIntentListener.onNewIntent(tapIntent("p-race", "d-race"))
        idle()   // the posted drain runs on the main thread
        assertEquals("shutdown() ran while a tap was being handed to native", false, shutdownRanDuringHandover)
        assertTrue("shutdown() never finished", shutdownDone.await(10, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Suppress("UNCHECKED_CAST")
    private fun nativeReadyCallbacks(): MutableList<Any?> =
        AppDNA::class.java.getDeclaredField("readyCallbacks").apply { isAccessible = true }.get(AppDNA) as MutableList<Any?>

    private fun nativeReadyCallbackCount(): Int =
        (AppDNA::class.java.getDeclaredField("readyCallbacks").apply { isAccessible = true }.get(AppDNA) as List<*>).size

    @Test
    fun `a tap that arrives before configure waits for it`() {
        newModule(Intent(Intent.ACTION_MAIN))
        val intent = tapIntent("p-early", "d-early")
        module.pushTapIntentListener.onNewIntent(intent)
        settle()
        assertEquals("routed before the SDK was configured", "1", intent.getStringExtra("appdna"))

        configureAndWait()
        assertEquals("handled once the SDK is ready", 1, emitted.count { it == "onPushTapped" })
    }
}
