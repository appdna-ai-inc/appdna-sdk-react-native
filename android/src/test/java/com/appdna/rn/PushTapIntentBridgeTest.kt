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
     * Round 27 (7): a React instance that outlives its activity (the activity finished with Back while the
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
     * Round 25: host JS that reads the launch intent's extras and branches on `handleTap` must see
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
     * Round 26 (1): native handles a copy, so the launch intent stays a live tap, and only the persisted
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
     * Round 26 (2): a tap on a notification the previous SDK version posted (no marker, no key) cannot be
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
