package com.appdna.rn

import ai.appdna.sdk.AppDNA
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.CxxCallbackImpl
import com.facebook.react.bridge.JavaOnlyArray
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The React Native **integration** fixture runner.
 *
 * ## Why this file exists, and what AC-24 originally asked for
 *
 * AC-24 asks that "the RN runner asserts against `expect`, not the bridge call shape". At the JEST
 * level that is **not achievable, and not because it is hard**. Per ADR-001 the RN TS layer is a THIN
 * WRAPPER: no rendering, no business logic, no storage. In a jest test the native module is MOCKED
 * AWAY, so the only thing on the JS side of the boundary is the call. A fixture's `expect` block
 * asserts NATIVE behaviour — an audience rule evaluated, a DTO parsed, an event envelope built, a
 * step advanced — and none of that exists in the wrapper. Asking jest to assert it would only ever be
 * satisfied by re-implementing the SDK inside the test and asserting the mirror, which is precisely
 * the fiction the fixture-suite post-mortem was written about.
 *
 * The coverage a wrapper CAN honestly provide is one level down: drive the fixture through the REAL
 * bridged `AppdnaModule` method — the same one the JS facade calls — into the REAL, configured native
 * `AppDNA` singleton, and assert the fixture's `expect` block against what NATIVE actually did. That
 * is this file. `__tests__/sharedFixtures.test.ts` keeps the jest leg (facade → correct bridge call,
 * correct args); this leg proves the call, once it lands, produces the behaviour the fixture pins.
 *
 * Nothing here re-implements the SDK. Every assertion reads an SDK OUTPUT:
 *
 *   - **events** — the envelopes the native `EventQueue` actually persisted, read out of the SDK's OWN
 *     `EventDatabase` (the one its queue is writing to — asked for, never guessed at). The wrapper
 *     configures through its own bridged options; nothing is uploaded (Robolectric reports no active
 *     network, so the network-sized batch is 0 — `batchSize = 0` itself is ignored by the SDK, as any value
 *     below 1 is), and the store is the truth-of-record.
 *   - **delegate_calls** — captured at the JS BOUNDARY. The generated `NativeAppdnaModuleSpec`'s
 *     `emitOnX` methods all funnel into `BaseJavaModule.mEventEmitterCallback`, and that field is
 *     replaced here with a recorder. So the path native-SDK-delegate → forwarder → `emitEventNamed` →
 *     codegen'd emitter → JS is exercised END TO END, and what is recorded is exactly the `(name,
 *     payload)` a JS listener would receive.
 *   - **state_after** — bridged getters (`getUserTraits`), the value the bridged promise resolved
 *     (`presentPaywallByPlacement` → Boolean), the Activity the SDK actually started, or the SDK's own
 *     envelope. Never an echo of the fixture's input: `assertExpectations` FAILS on a `state_after`
 *     key no observer produced, so a vacuous assertion is impossible.
 *
 * Plus one invariant no other runner can assert: every envelope produced while driven through this
 * wrapper must carry `device.framework == "react_native"`. On iOS/Android core it is
 * `native`; if the wrapper ever stopped injecting it, every RN event would land in BigQuery
 * mis-attributed and nothing would error.
 *
 * ## Which fixtures the wrapper can drive — and why the rest are native-only
 *
 * A wrapper's bridged surface IS the host's API surface. So the fixtures it can drive end-to-end are
 * exactly those whose `action` is a HOST API CALL. A host cannot "tap a button" inside a native
 * onboarding step, cannot hand the SDK a raw FCM payload (its own `FirebaseMessagingService` does),
 * cannot ask the SDK to evaluate a bare audience rule set or interpolate a template — those are not
 * API, they are internals reached only from inside a native render/decision path. That is a
 * STRUCTURAL boundary drawn by ADR-001, not a gap in this runner, and it is recorded, fixture by
 * fixture, in `scripts/check-fixture-coverage.ts` (`RN_NATIVE_ONLY`) — a decision, not a deletion.
 *
 * ## No skips
 *
 * A fixture that claims `rn` and reaches [unsupported] FAILS. There is no skiplist and no soft-skip;
 * `pnpm check:fixture-runner-skips` enforces that statically, and this runner is registered with it.
 *
 * ## The singleton, and the two traps it set
 *
 * `AppDNA` is a PROCESS-GLOBAL whose `configure()` is honoured exactly once — and Robolectric hands
 * every test method a FRESH files dir, while the SDK keeps using whichever one it was configured
 * under. Both traps below produced a runner that passed alone and failed for the next reader:
 *
 *   1. A PARAMETERIZED runner (one method per fixture) configured the SDK during fixture #1 and then
 *      read an empty store for #2 and #3 — the SDK was still writing into dir-1. Fixed by running the
 *      fixtures inside ONE method, against ONE configure, in a stable dir. Each fixture is isolated
 *      from the last by the SDK's own bridged `reset()`, a cleared paywall cache and a cleared event
 *      store, and every failure is reported, not just the first.
 *   2. READING THE STORE BY PATH (`File(noBackupFilesDir, "appdna_events.db")`) worked when this class
 *      ran alone and reported "native emitted []" for every fixture the moment ANY other test class
 *      configured the SDK first. Fixed by asking the SDK where its database is instead of reasoning
 *      about it — see [eventDatabase].
 *
 * © 2026 AppDNA AI, Inc.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedFixtureBridgeTest {

    private lateinit var activity: Activity
    private lateinit var module: AppdnaModule

    /** The fixture currently being driven — every failure message names it. */
    private lateinit var fixtureName: String
    private lateinit var fixtureJson: JSONObject

    /** Every `(event, payload)` the wrapper pushed across the bridge to JS, in order. */
    private val emitted = ConcurrentLinkedQueue<Pair<String, JSONObject>>()

    /** SDK outputs observed by the driver. A key absent here makes its `state_after` assertion FAIL. */
    private val state = LinkedHashMap<String, Any?>()

    /** The last `(type, value)` the core push-tap router reported through its `routeSink` test seam. */
    @Volatile
    private var routed: Pair<String, String>? = null

    // ── Harness ──────────────────────────────────────────────────────────────────

    /**
     * A kind, a key, a config this runner cannot drive is a HOLE IN THE PROOF, and a hole in the proof
     * must look like one. Never a skip, never a print, never a pass.
     */
    private fun unsupported(what: String): Nothing =
        throw AssertionError(
            "[$fixtureName] NO RN-WRAPPER DRIVER: $what.\n" +
                "  A fixture that claims `rn` must be driven through a REAL bridged AppdnaModule method\n" +
                "  into the live native SDK. Either write the driver, or remove `rn` from the fixture's\n" +
                "  `platforms` list and record WHY in RN_NATIVE_ONLY (scripts/check-fixture-coverage.ts).",
        )

    /**
     * `Arguments.createMap()` / `createArray()` build `WritableNative*` objects, whose class init loads
     * React Native's C++ library — absent under Robolectric. Every forwarder emit goes through
     * `AppdnaBridge.toWritableMap`, so without this the emit throws inside the SDK's delegate fan-out
     * (which swallows it) and NO delegate call ever reaches the recorder. The Java-only maps are RN's
     * own test doubles with the same `ReadableMap` / `WritableMap` contract. (No fixture claiming `rn`
     * asserted a delegate call before the push fixtures, so this was never exercised.)
     */
    private var argumentsMock: MockedStatic<Arguments>? = null

    @Before
    fun setUp() {
        argumentsMock = org.mockito.Mockito.mockStatic(Arguments::class.java, org.mockito.Mockito.CALLS_REAL_METHODS).also { m ->
            m.`when`<WritableMap> { Arguments.createMap() }.thenAnswer { JavaOnlyMap() }
            m.`when`<WritableArray> { Arguments.createArray() }.thenAnswer { JavaOnlyArray() }
        }
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        val reactContext = mock(ReactApplicationContext::class.java)
        org.mockito.Mockito.`when`(reactContext.applicationContext)
            .thenReturn(RuntimeEnvironment.getApplication())
        // Present-style bridged methods resolve `false` without a foreground Activity — they would
        // never reach native at all. Give them the real one Robolectric just created.
        org.mockito.Mockito.`when`(reactContext.currentActivity).thenReturn(activity)

        module = AppdnaModule(reactContext)
        installEmitterRecorder(module)

        // CONFIGURE THROUGH THE WRAPPER — its own bridged method, its own options parsing.
        //
        // No event leaves the device: Robolectric reports no active network, so the network-sized batch is
        // 0 and the queue never uploads (`batchSize = 0` is passed through but ignored — the SDK ignores any
        // value below 1), and `flushInterval` keeps the timer away. The SQLite store stays the
        // truth-of-record for the whole run.
        val options = JavaOnlyMap().apply {
            putInt("batchSize", 0)
            putDouble("flushInterval", 86_400.0)
            putString("logLevel", "none")
        }
        val ready = CountDownLatch(1)
        // `adn_test_placeholder` — the allowlisted placeholder `check:example-no-key` recognises, and
        // the one AppdnaHandlerPassTest already uses. Any other `adn_test_*` token is (correctly) read
        // as a committed key in code we publish.
        module.configure("adn_test_placeholder", "sandbox", options, mock(Promise::class.java, Answer { null }))
        AppDNA.onReady { ready.countDown() }
        val deadline = System.currentTimeMillis() + 20_000
        while (ready.count > 0L && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(20)
        }
        assertTrue(
            "the SDK never reached READY in 20s — every bridged call below would be made against a " +
                "dead singleton, and every fixture would be asserting nothing",
            ready.count == 0L,
        )
    }

    /** The JVM default zone before a `setup.device_timezone` fixture replaced it (restored in tearDown). */
    private var savedZone: java.util.TimeZone? = null

    @After
    fun tearDown() {
        savedZone?.let { java.util.TimeZone.setDefault(it) }
        savedZone = null
        argumentsMock?.close()
        argumentsMock = null
        runCatching { setRouteSink(null) }
        // Detach this module's forwarders from the process-global singleton, and leave no events
        // behind for whatever test class Robolectric runs next in this sandbox. Defensive: if setUp
        // never got the SDK to READY, there is no store to clear and the real failure is the one the
        // assertion in setUp already raised — do not bury it under a reflection NPE.
        if (::module.isInitialized) module.invalidate()
        runCatching { clearPersistedEvents() }
        idle()
    }

    /** Undo everything the previous fixture did to the singleton. */
    private fun isolate() {
        // `reset()` is the wrapper's OWN bridged method: it clears the identity, the traits, the
        // experiment exposures and the persisted session data.
        module.reset(mock(Promise::class.java, Answer { null }))
        clearPaywallCache(remoteConfigManager())
        clearPersistedEvents()
        emitted.clear()
        state.clear()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Replace the codegen'd spec's event-emitter callback with a recorder.
     *
     * `BaseJavaModule.mEventEmitterCallback` is a `protected CxxCallbackImpl` that RN's C++ layer
     * installs; every `emitOnX(...)` in `NativeAppdnaModuleSpec` calls `mEventEmitterCallback.invoke(
     * "<event>", payload)`. A unit test has no C++ layer, so the field is null and any emission NPEs.
     * Installing a recorder is therefore not a bypass of the bridge — it IS the bridge's last hop,
     * standing in for the JS runtime. Everything upstream of it (the native SDK's delegate, the
     * wrapper's forwarder, `emitEventNamed`'s dispatch table, the generated emitter) is the real code.
     */
    private fun installEmitterRecorder(target: AppdnaModule) {
        val answer = Answer<Any?> { invocation ->
            // `invoke(Object... args)` — Mockito hands varargs back either flattened or as the array.
            val raw = invocation.arguments
            val args: Array<*> = if (raw.size == 1 && raw[0] is Array<*>) raw[0] as Array<*> else raw
            val name = args.getOrNull(0) as? String
            if (name != null) {
                emitted.add(name to readableToJson(args.getOrNull(1) as? ReadableMap))
            }
            null
        }
        val recorder = mock(CxxCallbackImpl::class.java, answer)
        val field = com.facebook.react.bridge.BaseJavaModule::class.java
            .getDeclaredField("mEventEmitterCallback")
        field.isAccessible = true
        field.set(target, recorder)
    }

    // ── The event store: what native actually persisted ─────────────────────────

    /**
     * The SDK's OWN `EventDatabase` — the one its `EventQueue` is actually writing to.
     *
     * 🔴 This used to open `File(app.noBackupFilesDir, "appdna_events.db")` by hand, reasoning from
     * `NoBackupContext` (EventDatabase.kt:29-52) about where the file MUST be. It passed on its own
     * and failed the moment another test class ran first — because `AppDNA` is a process-global
     * whose `configure()` is honoured ONCE, and Robolectric hands every test a FRESH files dir. So
     * the SDK kept writing into the dir belonging to whichever test configured it, while this runner
     * read an empty store from today's. Every fixture reported "native emitted []" and the runner
     * would have been reporting the SDK broken when the SDK was fine — the mirror-image of a false
     * green, and just as useless.
     *
     * Reasoning about where the file is was the mistake. Ask the object that owns it:
     * `AppDNA.eventTracker.eventQueue.eventDatabase`, then call its own `loadAll()` / `clearAll()`.
     * Wherever it opened its database, that is the database this reads. (All three are `internal` to
     * the SDK's Kotlin module — a different module cannot name them — so the chain goes through
     * reflection; the METHODS are public in the bytecode and are the SDK's own.)
     */
    private fun eventDatabase(): Any {
        val appdna = AppDNA
        val tracker = appdna::class.java.getDeclaredField("eventTracker")
            .apply { isAccessible = true }.get(appdna)
            ?: unsupported("the SDK reported READY but has no EventTracker")
        val queue = tracker::class.java.getDeclaredField("eventQueue")
            .apply { isAccessible = true }.get(tracker)
            ?: unsupported("the SDK's EventTracker has no EventQueue — nothing can be persisted")
        return queue::class.java.getDeclaredField("eventDatabase")
            .apply { isAccessible = true }.get(queue)
            ?: unsupported("the SDK's EventQueue has no EventDatabase")
    }

    @Suppress("UNCHECKED_CAST")
    private fun persistedEnvelopes(): List<JSONObject> {
        val db = eventDatabase()
        val rows = db::class.java.getMethod("loadAll").invoke(db) as List<String>
        return rows.map { JSONObject(it) }
    }

    /**
     * Drop everything the SETUP produced (`sdk_initialized`, `session_start`, the `identify` that
     * seeds `user_traits`…) — and everything an earlier test class left behind. The fixture's
     * `expect.events` are the events the ACTION causes; the Android core runner gets the same
     * isolation by building a fresh EventDatabase per fixture.
     */
    private fun clearPersistedEvents() {
        val db = eventDatabase()
        db::class.java.getMethod("clearAll").invoke(db)
    }

    // ── The run ──────────────────────────────────────────────────────────────────

    @Test
    fun everyRnFixtureIsDrivenThroughTheBridgeIntoLiveNative() {
        val fixtures = rnFixtures()
        // A loader that came back empty would make this method green having asserted nothing — the
        // exact disease. The floor is also enforced independently by `check:fixture-coverage`.
        assertTrue(
            "no fixture in packages/sdk-shared-fixtures claims `rn` — this runner would be green over " +
                "an empty set",
            fixtures.isNotEmpty(),
        )

        val failures = LinkedHashMap<String, String>()
        for ((name, json) in fixtures) {
            fixtureName = name
            fixtureJson = json
            isolate()
            try {
                runOneFixture()
                println("  ✓ $name — driven through the bridge into live native")
            } catch (t: Throwable) {
                failures[name] = (t.message ?: t::class.java.name) + "\n      at " + t.stackTrace.take(4).joinToString("\n      at ")
            }
        }

        assertTrue(
            "AC-24 — ${failures.size} of ${fixtures.size} rn fixture(s) FAILED when driven through the " +
                "real AppdnaModule into the live native SDK:\n" +
                failures.entries.joinToString("\n\n") { "  [${it.key}]\n    ${it.value}" },
            failures.isEmpty(),
        )
    }

    private fun runOneFixture() {
        // The handled-key set is process-wide and the Android tap key is persisted, so two
        // push fixtures sharing a push_id would dedup each other. The core's own reset, by reflection.
        if (fixtureJson.optString("category") == "push_payload") {
            resetPushIdempotency()
            routed = null
            setRouteSink { type, value -> routed = type to value }
        }
        applySetup()

        // Everything above was setup. The fixture measures the ACTION.
        clearPersistedEvents()
        emitted.clear()

        val action = fixtureJson.getJSONObject("action")
        when (val kind = action.getString("kind")) {
            "identify" -> driveIdentify(action)
            "report_paying_user" -> driveReportPayingUser(action)
            "track_event" -> driveTrackEvent(action)
            "show_paywall" -> driveShowPaywall(action)
            "classify_push" -> driveClassifyPush(action)
            "tap_push" -> driveTapPush(action)
            "receive_push" -> driveReceivePush(action)
            else -> unsupported("no driver for action.kind=$kind")
        }
        idle()

        assertExpectations()
    }

    /**
     * The console-published config the SDK "will receive". In production it arrives from Firestore;
     * offline-first hosts ship it in `assets/appdna-config.json`, which `AppDNA.configure` feeds to
     * `RemoteConfigManager.loadBundledConfig` (AppDNA.kt:1918-1927). This drives that same SDK entry
     * point with the same map.
     *
     * It goes through REFLECTION, and it has to: `RemoteConfigManager` is `internal` to the SDK's
     * Kotlin module, so this module — a different Gradle module — cannot so much as name the type.
     * (The Android core runner reaches these seams directly because it lives INSIDE that module.) The
     * alternative, writing `assets/appdna-config.json`, would ship a config file inside the published
     * wrapper, and Robolectric reads the merged MAIN asset dir, not the test one.
     *
     * Config DELIVERY is out-of-band in every runner on every platform. What must go through real
     * code is the ACTION and the ASSERTION, and they do: the action is a bridged `AppdnaModule`
     * method, and the assertion reads what native produced.
     */
    private fun applySetup() {
        val setup = fixtureJson.optJSONObject("setup") ?: JSONObject()

        setup.optJSONObject("config")?.let { cfg ->
            val manager = remoteConfigManager()
            val bundled = mutableMapOf<String, Any>()
            // `loadBundledConfig` keys paywalls by id, as the Firestore collection does.
            cfg.optJSONArray("paywalls")?.let { arr ->
                bundled["paywalls"] = (0 until arr.length()).associate { i ->
                    val p = arr.getJSONObject(i)
                    p.getString("id") to p.toValue()
                }
            }
            if (bundled.isEmpty()) {
                unsupported(
                    "setup.config has no shape this wrapper can deliver to native. Only `paywalls` is " +
                        "reachable through a bundled-config load today",
                )
            }
            // `loadBundledConfig` only fills EMPTY caches by design, and `AppDNA` is a process-global
            // singleton that Robolectric keeps across the parameterized runs — so clear first, or the
            // second paywall fixture would silently assert the FIRST fixture's config and pass.
            clearPaywallCache(manager)
            manager::class.java
                .getMethod("loadBundledConfig", Map::class.java)
                .invoke(manager, bundled)
        }

        // `setup.device_timezone` — installed as the JVM default zone (what native's
        // `TimeZone.getDefault()` reports) and restored in tearDown. Native reads it through the
        // platform API; nothing here hands it to the SDK or the wrapper.
        setup.optString("device_timezone", "").takeIf { it.isNotEmpty() }?.let { id ->
            val zone = java.util.TimeZone.getTimeZone(id)
            assertEquals("[$fixtureName] setup.device_timezone '$id' is not an IANA zone", id, zone.id)
            if (savedZone == null) savedZone = java.util.TimeZone.getDefault()
            java.util.TimeZone.setDefault(zone)
        }

        val traits = setup.optJSONObject("user_traits")
        if (traits != null && traits.length() > 0) {
            // The traits reach native through the WRAPPER's own `identify` — there is no other host
            // API that sets them, on any platform.
            module.identify("fixture_user", jsonToReadableMap(traits), mock(Promise::class.java, Answer { null }))
            idle()
        }
    }

    /** `AppDNA.remoteConfig.manager` — an `internal` field of a public module object. */
    private fun remoteConfigManager(): Any {
        val holder = AppDNA.remoteConfig
        val field = holder::class.java.getDeclaredField("manager").apply { isAccessible = true }
        return field.get(holder)
            ?: unsupported("the SDK reported READY but exposes no RemoteConfigManager")
    }

    private fun clearPaywallCache(manager: Any) {
        val field = manager::class.java.getDeclaredField("paywalls").apply { isAccessible = true }
        field.set(manager, emptyMap<String, Any>())
    }

    // ── Drivers — every one of them a REAL bridged AppdnaModule method ───────────

    private fun driveIdentify(action: JSONObject) {
        val userId = action.getString("userId")
        val traits = action.optJSONObject("traits") ?: JSONObject()

        module.identify(userId, jsonToReadableMap(traits), mock(Promise::class.java, Answer { null }))
        idle()

        // `getUserTraits` is a bridged READ — the same one JS calls. It resolves the traits as JSON.
        state["user_traits"] = capture { p -> module.getUserTraits(p) }?.let { JSONObject(it as String).toValue() }
        // The identity the SDK stamped on the envelope it just built. `user.user_id` is the SDK's own
        // statement of who it thinks the user is (EventSchema.kt:65).
        state["user_id"] = persistedEnvelopes().lastOrNull()?.optJSONObject("user")?.optStringOrNull("user_id")
    }

    /**
     * The host asserting a paying user, through the REAL bridged method into the live native SDK.
     *
     * This is the driver the jest runner cannot be: jest asserts that the bridge was CALLED, which
     * says nothing about what the native SDK then emitted. Here the event is read back off the
     * envelope the SDK actually built, so the property shape the billing pipeline depends on
     * (`source: "host"`, price in MAJOR units, and NO user id) is checked against the real thing.
     *
     * `priceCents` crosses as a boxed Double because codegen types an optional TS number that way.
     */
    private fun driveReportPayingUser(action: JSONObject) {
        val productId = action.optStringOrNull("productId")
        val priceCents = if (action.has("priceCents")) action.getDouble("priceCents") else null
        val currency = action.optStringOrNull("currency")

        module.reportPayingUser(productId, priceCents, currency, mock(Promise::class.java, Answer { null }))
        idle()
    }

    private fun driveTrackEvent(action: JSONObject) {
        val name = action.optString("event_name", "")
        if (name.isEmpty()) unsupported("track_event fixture has no `event_name`")
        val props = action.optJSONObject("properties") ?: JSONObject()

        // `context.screen` is populated by the SDK's screen provider. On a native host the
        // NavigationInterceptor drives it; an RN host has no native navigation stack, so the wrapper
        // bridges `notifyScreenAppeared` — and THAT is the code path under test here.
        val screen = props.optStringOrNull("screen_name")
        if (screen != null) module.notifyScreenAppeared(screen)

        module.track(name, jsonToReadableMap(props))
        idle()

        // The screen the SDK actually stamped, read back off the envelope it wrote — not the string
        // this driver passed in. An echo of the input would assert nothing.
        state["current_screen"] = persistedEnvelopes().lastOrNull()
            ?.optJSONObject("context")?.optStringOrNull("screen")
    }

    private fun driveShowPaywall(action: JSONObject) {
        val placement = action.optStringOrNull("placement")
            ?: unsupported(
                "show_paywall without a `placement`. A `trigger_node_id` paywall is presented from " +
                    "INSIDE a native onboarding flow graph — there is no host API, on any platform, that " +
                    "fires an onboarding paywall-trigger node",
            )

        // The bridged method resolves a Boolean: did anything get presented? It is
        // `PaywallManager.hasPaywallForPlacement`, which runs the SAME selector the presentation runs —
        // the real audience-rule filter, over the real user traits set by `identify` above.
        state["is_presenting"] = capture { p -> module.presentPaywallByPlacement(placement, null, p) }
        idle()

        // Which paywall it chose, read off the Activity the SDK actually started. Independent of the
        // event assertion below: the selector could emit the right event and launch the wrong screen.
        state["active_paywall_id"] = shadowOf(activity).nextStartedActivity
            ?.getStringExtra("paywall_id")
    }

    // ── push drivers — the module's isAppDNAMessage / handlePushTap / handlePushMessage ──

    private fun driveClassifyPush(action: JSONObject) {
        if (action.optString("payload_shape") == "apns") {
            unsupported("an APNs userInfo never reaches Android — this fixture must not claim `rn`")
        }
        val payload = jsonToReadableMap(action.getJSONObject("payload"))
        state["is_appdna"] = capture { p -> module.isAppDNAMessage(payload, p) }
    }

    private fun driveTapPush(action: JSONObject) {
        val payload = jsonToReadableMap(action.getJSONObject("payload"))
        val actionId = action.optStringOrNull("action_id")
        val before = postedCount()
        state["returned"] = capture { p -> module.handlePushTap(payload, actionId, p) }
        settlePush()
        state["routed"] = routed?.let { mapOf("type" to it.first, "value" to it.second) }
        state["notification_posted"] = postedCount() > before
    }

    private fun driveReceivePush(action: JSONObject) {
        if (action.optString("via") != "handleMessageData") {
            unsupported("receive_push without via=handleMessageData — a raw FCM RemoteMessage has no host entry point")
        }
        val payload = jsonToReadableMap(action.getJSONObject("payload"))
        val before = postedCount()
        state["returned"] = capture { p -> module.handlePushMessage(payload, p) }
        settlePush()
        state["routed"] = routed?.let { mapOf("type" to it.first, "value" to it.second) }
        state["notification_posted"] = postedCount() > before
    }

    /**
     * Let the push's routing run to the end. A deep link asks the JS host first (`shouldOpen`, an
     * `onHostCallback`); this runner is that host and answers "open" through the module's own
     * `respondToHostCallback`, exactly as `hostCallbacks.ts` would.
     */
    private fun settlePush() {
        val want = fixtureJson.getJSONObject("expect")
        val wantEvents = want.optJSONArray("events")?.length() ?: 0
        val wantCalls = want.optJSONArray("delegate_calls")?.length() ?: 0
        val answered = HashSet<String>()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            for ((name, payload) in emitted.toList()) {
                if (name != "onHostCallback") continue
                val id = payload.optString("callbackId")
                if (id.isNotEmpty() && answered.add(id)) {
                    module.respondToHostCallback(id, if (payload.optString("hook") == "shouldOpen") "true" else "null")
                }
            }
            val pushEvents = persistedEnvelopes().count { !isRoutedScreenDismissal(it) }
            if (pushEvents >= wantEvents && projectedPushCalls().size >= wantCalls) {
                Thread.sleep(50); idle()
                return
            }
            Thread.sleep(20)
        }
    }

    /**
     * The projection: `onHostCallback` dropped, `payload` unwrapped into the args, `push_id` →
     * `pushId`. (The RN push forwarders emit `{payload: {...}, …}`; the fixtures name the core's fields.)
     * The routed destination's own `onScreenDismissed` for the routed `show_screen` id (a screen this
     * runner's config does not have is dismissed at once) is left out for the same reason as its
     * `screen_dismissed` event; every other call still counts.
     */
    private fun projectedPushCalls(): List<Pair<String, Map<String, Any?>>> = emitted.toList()
        .filter { (name, payload) ->
            val r = routed
            name != "onHostCallback" &&
                !(r != null && r.first == "show_screen" && name == "onScreenDismissed" && payload.optString("screenId") == r.second)
        }
        .map { (name, payload) ->
            val out = LinkedHashMap<String, Any?>()
            payload.toValue().forEach { (k, v) -> if (k != "payload") out[k] = v }
            (payload.optJSONObject("payload"))?.toValue()?.forEach { (k, v) -> out[k] = v }
            if (out.containsKey("push_id") && !out.containsKey("pushId")) out["pushId"] = out.remove("push_id")
            name to out
        }

    private fun isRoutedScreenDismissal(e: JSONObject): Boolean {
        val r = routed ?: return false
        return fixtureJson.optString("category") == "push_payload" && r.first == "show_screen" &&
            e.optString("event_name") == "screen_dismissed" && e.optJSONObject("properties")?.optString("screen_id") == r.second
    }

    private fun postedCount(): Int {
        val nm = RuntimeEnvironment.getApplication().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(nm).allNotifications.size
    }

    /** `PushIdempotency.resetForTesting()` — `internal` to the SDK module, `@JvmName` for exactly this call. */
    private fun resetPushIdempotency() {
        val cls = Class.forName("ai.appdna.sdk.integrations.PushIdempotency")
        val instance = cls.getDeclaredField("INSTANCE").get(null)
        cls.getDeclaredMethod("resetForTesting").apply { isAccessible = true }.invoke(instance)
    }

    /** `PushTapRouter.routeSink` — an `internal @JvmField`, so a static field of that name. */
    private fun setRouteSink(sink: ((String, String) -> Unit)?) {
        val cls = Class.forName("ai.appdna.sdk.integrations.PushTapRouter")
        cls.getDeclaredField("routeSink").apply { isAccessible = true }.set(null, sink)
    }

    // ── — the wrapper-reachable half of the `*_fails_loudly` fixtures ──────────────────

    /**
     * `billing/paywall_purchase_no_provider_fails_loudly` and `…_revenuecat_fails_loudly` drive a paywall
     * tap inside the native PaywallManager, which no wrapper API can trigger without UI. What a HOST can
     * reach is `AppDNA.billing.purchase` on the same provider: configured through the module's own
     * `configure` with that `billingProvider`, `purchase()` must REJECT with the code the fixture names
     * as the delegate's `errorType` — and `restorePurchases()` with `providerNotAvailable` (the restore
     * error contract).
     */
    @Test
    fun failsLoudlyFixturesRejectPurchaseWithTheFixtureErrorType() {
        val failures = mutableListOf<String>()
        try {
            // Every `purchase` fixture whose id ends in `_fails_loudly` — the ruling's own
            // selector (check-fixture-coverage.ts `case 'purchase'`), so a new one joins by itself.
            val loud = failsLoudlyFixtures()
            assertEquals("§3.9 has exactly two refusing-provider purchase fixtures (none, revenueCat)", 2, loud.size)
            for ((id, fixture) in loud) {
                val provider = fixture.getJSONObject("setup").getJSONObject("config").getString("billing_provider")
                val args = fixture.getJSONObject("expect").getJSONArray("delegate_calls").getJSONObject(0).getJSONObject("args")
                reconfigure(provider)
                val code = rejectionCode { p -> module.purchase(args.optString("productId", "plan_monthly"), null, p) }
                if (code != args.getString("errorType")) failures += "[$id] purchase rejected with '$code', expected '${args.getString("errorType")}'"
                val restore = rejectionCode { p -> module.restorePurchases(p) }
                if (restore != "providerNotAvailable") failures += "[$id] restore rejected with '$restore', expected 'providerNotAvailable'"
            }
        } finally {
            runCatching { AppDNA.shutdown() }
            idle()
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * The RN forwarder is registered NOT delivering (a queued purchase emitted
     * to a JS side with no `onPurchaseCompleted` would be lost), and only `billingDelegateReady(true)`
     * makes it the delegate the delivery-queue drain calls; `false` takes that back. Before native
     * `configure` there is no forwarder, and the call is a no-op.
     */
    @Test
    fun billingDelegateReadyFlipsWhetherTheForwarderTakesQueuedPurchases() {
        assertTrue("configure registers the billing forwarder", billingListener() is BillingForwarder)
        assertEquals("registered NOT delivering", null, deliveringDelegate())
        module.billingDelegateReady(true)
        idle()
        assertTrue("ready → the drain may call it", deliveringDelegate() is BillingForwarder)
        module.billingDelegateReady(false)
        idle()
        assertEquals("not ready → the drain may not", null, deliveringDelegate())

        // A queued late purchase: nothing reaches JS while not ready; exactly one delivery after.
        waitFor(20_000) { drainReady() }
        assertTrue("the SDK never became ready to drain its delivery queue", drainReady())
        seedDeliveryQueue(listOf("tok-rn-1"))
        emitted.clear()
        module.billingDelegateReady(false)
        waitFor(1_500) { false }
        assertEquals("not ready → no delivery", 0, emitted.count { it.first == "onPurchaseCompleted" })
        assertEquals(listOf("tok-rn-1"), queuedDeliveryTokens())
        module.billingDelegateReady(true)
        waitFor(5_000) { emitted.any { it.first == "onPurchaseCompleted" } && queuedDeliveryTokens().isEmpty() }
        assertEquals("ready → delivered exactly once", 1, emitted.count { it.first == "onPurchaseCompleted" })
        assertEquals(emptyList<String>(), queuedDeliveryTokens())
        module.billingDelegateReady(false)

        // A module that never configured has no forwarder: a no-op, not a crash, and it changes nothing.
        val before = billingListener()
        AppdnaModule(mock(ReactApplicationContext::class.java)).billingDelegateReady(true)
        assertTrue(billingListener() === before)
        assertEquals(null, deliveringDelegate())
    }

    private fun waitFor(ms: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(20)
        }
    }

    private fun billingManager(): Any? = AppDNA.billing.let { b ->
        b::class.java.declaredMethods.first { it.name.startsWith("getManager") }.apply { isAccessible = true }.invoke(b)
    }

    private fun drainReady(): Boolean {
        val mgr = billingManager() ?: return false
        val completion = mgr::class.java.getDeclaredField("completion").apply { isAccessible = true }.get(mgr)
        return completion::class.java.getDeclaredField("drainReady").apply { isAccessible = true }.getBoolean(completion)
    }

    private fun localStorage(): Any = Class.forName("ai.appdna.sdk.storage.LocalStorage")
        .getConstructor(android.content.Context::class.java).newInstance(RuntimeEnvironment.getApplication())

    /** The core's persisted delivery queue (`appdna.pending_deliveries_v1`), as `PurchaseLedger` stores it. */
    private fun seedDeliveryQueue(tokens: List<String>) {
        val arr = JSONArray()
        for (t in tokens) {
            arr.put(JSONObject().apply {
                put("purchaseToken", t)
                put("transactionId", "GPA.$t")
                put("productId", "coins_100")
                put("purchaseTime", 1_700_000_000_000L)
                put("quantity", 1)
                put("ownerToken", JSONObject.NULL)
                put("queuedAt", System.currentTimeMillis())
            })
        }
        val s = localStorage()
        s::class.java.getMethod("setString", String::class.java, String::class.java)
            .invoke(s, "appdna.pending_deliveries_v1", arr.toString())
    }

    private fun queuedDeliveryTokens(): List<String> {
        val s = localStorage()
        val raw = s::class.java.getMethod("getString", String::class.java)
            .invoke(s, "appdna.pending_deliveries_v1") as String? ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("purchaseToken") }
    }

    /** `BillingModule.billingListener` / `deliveringDelegate()` — `internal`, so found by name prefix. */
    private fun billingListener(): Any? {
        val billing = AppDNA.billing
        val getter = billing::class.java.declaredMethods.first { it.name.startsWith("getBillingListener") }
        return getter.apply { isAccessible = true }.invoke(billing)
    }

    private fun deliveringDelegate(): Any? {
        val billing = AppDNA.billing
        val m = billing::class.java.declaredMethods.first { it.name.startsWith("deliveringDelegate") }
        return m.apply { isAccessible = true }.invoke(billing)
    }

    /**
     * The `*_fails_loudly` set, chosen by SHAPE (a `purchase` fixture whose setup provider is one
     * that must refuse — `none` / `revenueCat`), never by id (`check:fixture-runner-skips`).
     */
    private fun failsLoudlyFixtures(): List<Pair<String, JSONObject>> =
        File(fixturesRoot(), "billing").listFiles().orEmpty()
            .filter { it.name.endsWith(".fixture.json") }
            .sortedBy { it.name }
            .map { it.name.removeSuffix(".fixture.json") to JSONObject(it.readText()) }
            .filter { (_, json) ->
                json.getJSONObject("action").getString("kind") == "purchase" &&
                    json.optJSONObject("setup")?.optJSONObject("config")?.optString("billing_provider") in REFUSING_PROVIDERS
            }

    /** Shut the singleton down and configure it again through the module, with [provider]. */
    private fun reconfigure(provider: String) {
        runCatching { module.invalidate() }
        AppDNA.shutdown()
        idle()
        val reactContext = mock(ReactApplicationContext::class.java)
        org.mockito.Mockito.`when`(reactContext.applicationContext).thenReturn(RuntimeEnvironment.getApplication())
        org.mockito.Mockito.`when`(reactContext.currentActivity).thenReturn(activity)
        module = AppdnaModule(reactContext)
        installEmitterRecorder(module)
        val options = JavaOnlyMap().apply {
            putInt("batchSize", 0)
            putString("logLevel", "none")
            putString("billingProvider", provider)
        }
        val ready = CountDownLatch(1)
        module.configure("adn_test_placeholder", "sandbox", options, mock(Promise::class.java, Answer { null }))
        AppDNA.onReady { ready.countDown() }
        val deadline = System.currentTimeMillis() + 20_000
        while (ready.count > 0L && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(20)
        }
        assertTrue("the SDK never reached READY with billingProvider=$provider", ready.count == 0L)
    }

    /** Run a bridged method that must REJECT; return its code. A resolve fails the test. */
    private fun rejectionCode(call: (Promise) -> Unit): String? {
        var code: String? = null
        var settled = false
        val promise = mock(
            Promise::class.java,
            Answer<Any?> { invocation ->
                if (invocation.method.name == "resolve") {
                    throw AssertionError("the bridged call RESOLVED (${invocation.arguments.toList()}) — it must be refused")
                }
                code = invocation.arguments.firstOrNull() as? String
                settled = true
                null
            },
        )
        call(promise)
        val deadline = System.currentTimeMillis() + 10_000
        while (!settled && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(10)
        }
        assertTrue("the bridged call never settled", settled)
        return code
    }

    /** Run a bridged method and return the value its promise resolved with. */
    private fun capture(call: (Promise) -> Unit): Any? {
        var resolved: Any? = null
        var settled = false
        val promise = mock(
            Promise::class.java,
            Answer<Any?> { invocation ->
                if (invocation.method.name == "resolve") {
                    resolved = invocation.arguments.firstOrNull()
                    settled = true
                } else {
                    throw AssertionError(
                        "[$fixtureName] the bridged call REJECTED (${invocation.arguments.toList()}) — " +
                            "a JS `await` would have thrown here, so the fixture cannot be asserted",
                    )
                }
                null
            },
        )
        call(promise)
        idle()
        // A UI-thread hop (present-style calls) settles on the looper; give it one.
        val deadline = System.currentTimeMillis() + 5_000
        while (!settled && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(10)
        }
        assertTrue(
            "[$fixtureName] a bridged method took a Promise and NEVER SETTLED it — a JS `await` on it " +
                "hangs for the life of the process",
            settled,
        )
        return resolved
    }

    // ── Assertions ───────────────────────────────────────────────────────────────

    private fun assertExpectations() {
        val expect = fixtureJson.getJSONObject("expect")
        // A push tap routed to `show_screen <id>` hands the id to the live SDK's ScreenManager, which —
        // with no such screen in this runner's config — emits `screen_dismissed` for THAT id. Only that
        // exact event (and the matching `onScreenDismissed`, below) belongs to the routed destination
        // rather than the push path the fixture pins; the route is asserted via `state_after.routed`.
        val envelopes = persistedEnvelopes().filterNot { isRoutedScreenDismissal(it) }

        // Wrapper-only invariant. No other runner can assert it: on iOS/Android core
        // the tag is `native`. A wrapper that stops injecting it mis-attributes every RN event in
        // BigQuery — `event-envelope.schema.ts` is `.catch('native')`, so nothing errors and nothing
        // is logged.
        for (e in envelopes) {
            assertEquals(
                "[$fixtureName] event '${e.optString("event_name")}' carries framework=" +
                    "'${e.optJSONObject("device")?.optString("framework")}' — every event emitted through " +
                    "the RN wrapper must be tagged `react_native`",
                "react_native",
                e.optJSONObject("device")?.optString("framework"),
            )
        }

        val expectedEvents = expect.optJSONArray("events") ?: JSONArray()
        assertEquals(
            "[$fixtureName] event count (native emitted ${envelopes.map { it.optString("event_name") }})",
            expectedEvents.length(),
            envelopes.size,
        )
        for (i in 0 until expectedEvents.length()) {
            val expected = expectedEvents.getJSONObject(i)
            val envelope = envelopes[i]
            val name = envelope.optString("event_name")
            assertEquals("[$fixtureName] event[$i].name", expected.getString("name"), name)
            val expectedProps = expected.optJSONObject("properties") ?: continue
            // `properties.context.*` resolves against the envelope's own context block…
            val actual = (envelope.optJSONObject("properties") ?: JSONObject()).toValue().toMutableMap()
            actual["context"] = envelope.optJSONObject("context")?.toValue()
            // …and `properties.device.*` against its device block.
            actual["device"] = envelope.optJSONObject("device")?.toValue()
            for (key in expectedProps.keys()) {
                assertValue("[$fixtureName] event[$i]($name).properties.$key", expectedProps.opt(key), actual[key])
            }
        }

        // The delegate calls, as JS receives them: `(event, payload)` off the generated emitter.
        val expectedCalls = expect.optJSONArray("delegate_calls") ?: JSONArray()
        if (fixtureJson.optString("category") == "push_payload") {
            // Per-category rule: push delegate calls compare ORDER-INSENSITIVELY, after the
            // projection (payload unwrapped, push_id → pushId, onHostCallback dropped).
            val remaining = projectedPushCalls().toMutableList()
            assertEquals(
                "[$fixtureName] delegate-call count (the wrapper emitted ${remaining.map { it.first }} to JS)",
                expectedCalls.length(),
                remaining.size,
            )
            for (i in 0 until expectedCalls.length()) {
                val exp = expectedCalls.getJSONObject(i)
                val args = exp.optJSONObject("args") ?: JSONObject()
                val at = remaining.indexOfFirst { (name, a) ->
                    name == exp.getString("name") && args.keys().asSequence().all { k ->
                        val ev = args.opt(k)
                        if (ev is JSONObject) canon(ev) == canon((a[k] as? Map<*, *>)?.filterKeys { ev.has(it.toString()) }) else canon(ev) == canon(a[k])
                    }
                }
                assertTrue("[$fixtureName] no delegate call matches $exp among $remaining", at >= 0)
                remaining.removeAt(at)
            }
        } else {
        val actualCalls = emitted.toList()
        assertEquals(
            "[$fixtureName] delegate-call count (the wrapper emitted ${actualCalls.map { it.first }} to JS)",
            expectedCalls.length(),
            actualCalls.size,
        )
        for (i in 0 until expectedCalls.length()) {
            val expected = expectedCalls.getJSONObject(i)
            val (name, payload) = actualCalls[i]
            assertEquals("[$fixtureName] delegate[$i].name", expected.getString("name"), name)
            val expectedArgs = expected.optJSONObject("args") ?: continue
            for (key in expectedArgs.keys()) {
                assertValue("[$fixtureName] delegate[$i]($name).args.$key", expectedArgs.opt(key), payload.opt(key))
            }
        }
        }

        expect.optJSONObject("state_after")?.let { expectedState ->
            for (key in expectedState.keys()) {
                assertTrue(
                    "[$fixtureName] state_after.$key — no driver observed this key, so asserting it " +
                        "would be vacuous. Observe it from an SDK output, or drop `rn` from the fixture",
                    state.containsKey(key),
                )
                assertValue("[$fixtureName] state_after.$key", expectedState.opt(key), state[key])
            }
        }

        val expectedErrors = expect.optJSONArray("errors") ?: JSONArray()
        assertEquals(
            "[$fixtureName] this fixture expects ${expectedErrors.length()} error(s); the RN wrapper " +
                "surfaces native errors as promise REJECTIONS, and `capture` fails on one — so a fixture " +
                "with expected errors cannot be driven here",
            0,
            expectedErrors.length(),
        )
    }

    /**
     * Canonical, type-loose comparison — the fixtures are hand-authored JSON, so `"2"`/`2` and
     * `70`/`70.0` mean the same thing. A nested object is projected onto the keys the fixture names
     * (the SDK legitimately carries more: `client_seq`, `session_id`, …); scalars and arrays compare
     * whole.
     */
    private fun assertValue(label: String, expected: Any?, actual: Any?) {
        if (expected is JSONObject) {
            val projected = when (actual) {
                is Map<*, *> -> actual.filterKeys { expected.has(it.toString()) }
                is JSONObject -> actual.toValue().filterKeys { expected.has(it) }
                else -> null
            }
            assertEquals(label, canon(expected), canon(projected))
            return
        }
        assertEquals(label, canon(expected), canon(actual))
    }

    private fun canon(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is Boolean -> v.toString()
        is Number -> if (v.toDouble() == Math.floor(v.toDouble()) && !v.toDouble().isInfinite()) {
            v.toLong().toString()
        } else {
            v.toDouble().toString()
        }
        is String -> v.toDoubleOrNull()?.let { canon(it) } ?: v
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.opt(it)) }
        is JSONObject -> canon(v.toValue())
        is List<*> -> v.joinToString(",", "[", "]") { canon(it) }
        is Map<*, *> -> v.entries.sortedBy { it.key.toString() }
            .joinToString(",", "{", "}") { (k, value) -> "$k=${canon(value)}" }
        else -> v.toString()
    }

    // ── JSON ⇄ bridge ────────────────────────────────────────────────────────────

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key, null)?.takeIf { it.isNotEmpty() } else null

    private fun JSONObject.toValue(): Map<String, Any?> = keys().asSequence().associateWith { k ->
        when (val v = get(k)) {
            JSONObject.NULL -> null
            is JSONObject -> v.toValue()
            is JSONArray -> v.toValue()
            else -> v
        }
    }

    private fun JSONArray.toValue(): List<Any?> = (0 until length()).map {
        when (val v = get(it)) {
            JSONObject.NULL -> null
            is JSONObject -> v.toValue()
            is JSONArray -> v.toValue()
            else -> v
        }
    }

    /** The `ReadableMap` the JS bridge would have delivered for this fixture's JSON. */
    private fun jsonToReadableMap(json: JSONObject): ReadableMap {
        val map = JavaOnlyMap()
        for (key in json.keys()) {
            when (val v = json.get(key)) {
                JSONObject.NULL -> map.putNull(key)
                is Boolean -> map.putBoolean(key, v)
                is Int -> map.putInt(key, v)
                is Long -> map.putDouble(key, v.toDouble())
                is Double -> map.putDouble(key, v)
                is String -> map.putString(key, v)
                is JSONObject -> map.putMap(key, jsonToReadableMap(v) as WritableMap)
                is JSONArray -> map.putArray(key, jsonToWritableArray(v))
                else -> unsupported("no bridge encoding for ${v::class.java.name} at key '$key'")
            }
        }
        return map
    }

    private fun jsonToWritableArray(json: JSONArray): WritableArray {
        val arr = JavaOnlyArray()
        for (i in 0 until json.length()) {
            when (val v = json.get(i)) {
                JSONObject.NULL -> arr.pushNull()
                is Boolean -> arr.pushBoolean(v)
                is Int -> arr.pushInt(v)
                is Long -> arr.pushDouble(v.toDouble())
                is Double -> arr.pushDouble(v)
                is String -> arr.pushString(v)
                is JSONObject -> arr.pushMap(jsonToReadableMap(v) as WritableMap)
                is JSONArray -> arr.pushArray(jsonToWritableArray(v))
                else -> unsupported("no bridge encoding for ${v::class.java.name} at index $i")
            }
        }
        return arr
    }

    /** The payload as JS receives it. `AppdnaBridge.toWritableMap` produced it; read it straight back. */
    private fun readableToJson(map: ReadableMap?): JSONObject {
        if (map == null) return JSONObject()
        // Recursively: a nested map/array inside a Java-only map is a `ReadableMap` / `ReadableArray`,
        // not a `java.util.Map`, and `JSONObject(Map)` would silently drop it.
        return JSONObject().also { o -> map.toHashMap().forEach { (k, v) -> o.put(k, jsonValue(v)) } }
    }

    private fun jsonValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is ReadableMap -> readableToJson(v)
        is com.facebook.react.bridge.ReadableArray -> JSONArray().also { a -> v.toArrayList().forEach { a.put(jsonValue(it)) } }
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), jsonValue(x)) } }
        is List<*> -> JSONArray().also { a -> v.forEach { a.put(jsonValue(it)) } }
        else -> v
    }

    /**
     * Every fixture whose `platforms` list claims `rn`. The list IS the filter — a runner-side
     * carve-out ("…and not category X") is how a platform quietly stops honouring its own claim.
     *
     * Identical resolution order to the Android core's SharedFixtureTest + AppdnaHandlerPassTest.
     */
    private fun rnFixtures(): List<Pair<String, JSONObject>> =
        fixturesRoot().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".fixture.json") }
            .sortedBy { it.path }
            .mapNotNull { file ->
                val json = JSONObject(file.readText(Charsets.UTF_8))
                val platforms = json.optJSONArray("platforms") ?: JSONArray()
                val applies = (0 until platforms.length()).any { platforms.getString(it) == "rn" }
                if (applies) file.name.removeSuffix(".fixture.json") to json else null
            }
            .toList()

    private fun fixturesRoot(): File {
        System.getenv("APPDNA_SDK_FIXTURES_DIR")?.let {
            val f = File(it)
            if (f.isDirectory) return f
        }
        var here: File? = File(".").canonicalFile
        repeat(12) {
            val candidate = File(here, "packages/sdk-shared-fixtures")
            if (candidate.isDirectory) return candidate
            here = here?.parentFile
        }
        val codespace = File("/workspaces/appdna-ai/packages/sdk-shared-fixtures")
        if (codespace.isDirectory) return codespace
        error("Could not locate packages/sdk-shared-fixtures. Set APPDNA_SDK_FIXTURES_DIR.")
    }
}

/** Billing providers under which an SDK purchase must be refused. */
private val REFUSING_PROVIDERS = setOf("none", "revenueCat")
