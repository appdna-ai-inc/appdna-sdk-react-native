package com.appdna.rn

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.ForcedTheme
import ai.appdna.sdk.AppDNAOptions
import ai.appdna.sdk.BillingProvider
import ai.appdna.sdk.Environment
import ai.appdna.sdk.LogLevel
import ai.appdna.sdk.paywalls.PaywallContext
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.module.annotations.ReactModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * SPEC-070-B P2 — the AppDNA TurboModule for Android.
 *
 * Extends the CODEGEN'D [NativeAppdnaModuleSpec] rather than `ReactContextBaseJavaModule`, so a
 * method that exists in `src/specs/NativeAppdnaModule.ts` but not here is a COMPILE error, and vice
 * versa. Before this, `supportedEvents()` returned exactly two names while ~30 JS listeners waited
 * on emitters that did not exist, and every `setDelegate` was a silent no-op.
 *
 * ## Threading (E10) — load-bearing
 *
 * TurboModule methods execute on the **JS thread**, and `getMethodQueue()` is ignored. So a native
 * call that blocks — `AppDNA.presentOnboarding` posts to the main looper and then
 * `latch.await(5, SECONDS)` when called off-main — would freeze rendering, timers, and every other
 * call for five seconds. Present-style calls are therefore dispatched onto the **UI thread**, where
 * native's own `Looper.myLooper() == mainLooper` check takes the latch-free path.
 */
@ReactModule(name = AppdnaModule.NAME)
class AppdnaModule(private val reactContext: ReactApplicationContext) :
    NativeAppdnaModuleSpec(reactContext) {

    /**
     * SPEC-070-B E6 — cancelled in [invalidate]. The old module leaked this scope: a `configure()`
     * after a Metro reload created a second one and never cancelled the first.
     */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * PN row 5 — retained so [invalidate] can remove it. Kotlin listener removal is REFERENCE
     * IDENTITY: a lambda literal allocates a fresh object per evaluation, so passing one to
     * `removeEntitlementsChangedListener` would silently remove nothing.
     */
    private var entitlementListener: ((List<ai.appdna.sdk.billing.Entitlement>) -> Unit)? = null

    /**
     * Held so `invalidate()` can detach them. Both capture this bridge-scoped module STRONGLY, and the
     * SDK singleton outlives every reload — so an un-detached listener leaks the module and its
     * ReactApplicationContext, and the stale copies keep emitting into a torn-down TurboModule.
     *
     * They are also the reason `registerDelegates` must be idempotent: native's `configure()` refuses a
     * second call, but this wrapper's registration ran unconditionally after it, so a host that called
     * configure() twice got TWO config collectors and TWO web-entitlement listeners — every change
     * delivered twice, forever.
     */
    private var webEntitlementListener: ((ai.appdna.sdk.webentitlements.WebEntitlement?) -> Unit)? = null
    private var configUpdatesJob: kotlinx.coroutines.Job? = null

    /**
     * P3 — the eight veto hooks and every observe callback are routed by these. Built in
     * [configure], because `vetoTimeout` is a configure option and a forwarder registered before it
     * would use the wrong timer.
     */
    private var invoker: AppdnaVetoInvoker? = null

    /**
     * E6 — every promise a coroutine on [scope] owes an answer to.
     *
     * `invalidate()` calls `scope.cancel()`. A cancelled coroutine does not run the rest of its body,
     * so an in-flight `configure` / `purchase` / `getProducts` / `restorePurchases` used to die
     * WITHOUT calling `promise.reject` — and a JS promise that never settles hangs its `await` for
     * the life of the process. A Metro reload mid-purchase left the host's checkout spinner turning
     * forever, with no error anywhere to say why.
     */
    private val pendingPromises: MutableSet<SettleOncePromise> =
        Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * A promise that settles exactly once.
     *
     * Teardown races the coroutine it is tearing down: [invalidate] rejects what is pending while the
     * cancelled coroutine may already be inside `promise.resolve`. Settling an RN Promise twice is a
     * native-module error, so the winner is decided by a CAS rather than by luck.
     */
    private class SettleOncePromise(private val promise: Promise) {
        private val settled = AtomicBoolean(false)

        fun resolve(value: Any?) {
            if (settled.compareAndSet(false, true)) promise.resolve(value)
        }

        fun reject(code: String, message: String?, cause: Throwable? = null) {
            if (settled.compareAndSet(false, true)) promise.reject(code, message, cause)
        }
    }

    companion object {
        const val NAME = "AppdnaModule"

        /** E6 — what a host sees when the bridge is torn down under an in-flight call. */
        internal const val INVALIDATED_CODE = "SDK_INVALIDATED"
        internal const val INVALIDATED_MESSAGE =
            "The AppDNA native module was torn down (reload or shutdown) before this call completed."

        /**
         * SPEC-070-B §7 — pinned literal, underscore not hyphen. Injected UNCONDITIONALLY in the
         * native bridge, never read from the host's options: a host must not be able to set, spoof,
         * or omit its own attribution. `event-envelope.schema.ts` is `.catch('native')`, so a wrong
         * tag does not error, is not logged, and is not metered — it just quietly lies in BigQuery.
         */
        private const val FRAMEWORK_TAG = "react_native"

        /**
         * The WRAPPER's own version (this package), not the native SDK's — `getSdkVersion()` reports
         * that one. Injected, never read from the host's options: the wrapper knows its own version
         * and a host has no business claiming a different one.
         *
         * Kept in lockstep with package.json by `check:wrapper-version-selfreport`. Flutter shipped
         * this constant stuck at 1.0.6 while publishing 1.0.8 — so `diagnose()` and every event
         * envelope reported a version that had not been released for two cycles, and nothing noticed.
         */
        private const val WRAPPER_VERSION = "1.0.18"
    }

    // ── Promise-owning coroutines (E6) ────────────────────────────────────────

    /**
     * Run [block] on [scope], and guarantee the promise settles — including when the scope dies.
     *
     * Three ways a promise used to be abandoned, all of them fixed here:
     *   1. the coroutine is cancelled mid-flight (Metro reload) → `CancellationException` → reject;
     *   2. `invalidate()` cancels a coroutine that is suspended and never resumes → [rejectPending];
     *   3. the scope was ALREADY cancelled when the call arrived → `scope.launch` returns a job whose
     *      body never runs at all (no exception, no `finally`) → the `isActive` check below.
     */
    private fun launchSettling(
        promise: Promise,
        errorCode: String,
        dispatcher: CoroutineContext = EmptyCoroutineContext,
        /**
         * Derive the reject CODE from the throwable, instead of using the one fixed [errorCode].
         *
         * Only `purchase()` needs it, and it needs it badly: a cancel, an ask-to-buy, a declined card
         * and a dead network all arrived as `PURCHASE_ERROR` with a LOCALIZED message, so a host had
         * to string-match prose in the device's language to decide whether to say anything at all.
         * When this is supplied it WINS over [errorCode] — it is total (`billingErrorType` returns
         * "unknown" rather than throwing), so [errorCode] is then only the cancellation fallback.
         */
        errorCodeFor: ((Throwable) -> String)? = null,
        block: suspend (SettleOncePromise) -> Unit,
    ) {
        val pending = SettleOncePromise(promise)
        pendingPromises.add(pending)
        scope.launch(dispatcher) {
            try {
                block(pending)
            } catch (e: CancellationException) {
                // `invalidate()` has almost certainly rejected this one already — the CAS makes that a
                // no-op — but a coroutine can be cancelled on its own, and an unsettled promise is
                // worse than any error code.
                pending.reject(INVALIDATED_CODE, INVALIDATED_MESSAGE, e)
                throw e
            } catch (e: Throwable) {
                pending.reject(errorCodeFor?.invoke(e) ?: errorCode, e.message, e)
            } finally {
                pendingPromises.remove(pending)
            }
        }
        if (!scope.isActive) rejectPending()
    }

    /** Reject every promise still owed an answer. Idempotent: [SettleOncePromise] settles once. */
    private fun rejectPending() {
        val snapshot = pendingPromises.toList()
        pendingPromises.removeAll(snapshot.toSet())
        snapshot.forEach { it.reject(INVALIDATED_CODE, INVALIDATED_MESSAGE) }
    }

    // ── Lifecycle / core ──────────────────────────────────────────────────────

    /**
     * W15 / AC-37 — `configure()` must not run on the JS thread.
     *
     * A TurboModule method body executes on the JS THREAD on Android (E10), and
     * `AppDNA.configure` opens SQLite, reads SharedPreferences and warms the config cache. Doing
     * that inline stalls JS for the duration — at app start, which is precisely when the JS thread
     * is busiest and a stall is most visible. iOS is already safe: its `configure` hops onto a
     * utility queue internally.
     *
     * Calling native off the JS thread is safe: the pieces that genuinely need the main looper
     * marshal themselves (`EventQueue.registerLifecycleObserver` posts to it "regardless of which
     * thread constructed EventQueue"), and `AppDNA.configure` guards reentrancy with `synchronized`
     * + `isConfiguring`.
     *
     * `parseOptions` stays on the JS thread deliberately: a `ReadableMap` is only valid on the
     * thread the bridge delivered it on, so reading it after this method returns is undefined
     * behaviour. It parses a handful of scalars — that is not the cost W15 is about.
     */
    override fun configure(apiKey: String, env: String, options: ReadableMap?, promise: Promise) {
        // The wire value is `sandbox`, matching the native enum and iOS. `staging` named a case
        // that has never existed on either platform.
        val environment = if (env == "sandbox") Environment.SANDBOX else Environment.PRODUCTION

        val parsed = try {
            parseOptions(options)
        } catch (e: Throwable) {
            promise.reject("CONFIGURE_ERROR", e.message, e)
            return
        }

        // ⚠ Recorded native-level asymmetry (not a wrapper bug): a HARD init failure (storage/DB) rejects
        // `CONFIGURE_ERROR` here, because Android's native `AppDNA.configure` runs init synchronously and
        // throws. iOS's `configure` is fire-and-forget (dispatches init to a background queue) and can
        // never reject — it surfaces the failure via `onInitDegraded` / `getLastInitError` (D-k). Matching
        // that in the wrapper is not possible without either swallowing the error (worse) or changing the
        // shared Android native SDK's error handling (cross-cutting, out of scope). Both platforms expose
        // the D-k channel; only the additional reject here is Android-specific. A host awaiting
        // `configure()` should still `try/catch` — the strictly-safer superset of the iOS contract.
        launchSettling(promise, "CONFIGURE_ERROR", Dispatchers.Default) { p ->
            AppDNA.configure(reactContext, apiKey, environment, parsed)
            registerDelegates(parsed.vetoTimeout)
            p.resolve(null)
        }
    }

    override fun identify(userId: String, traits: ReadableMap?, promise: Promise) {
        // E9.2: nulls survive as `JSONObject.NULL`. The old `mapValues { it.value as Any }` NPE'd on
        // `identify(id, { referrer: null })` while iOS kept NSNull and survived.
        AppDNA.identify(userId, AppdnaBridge.toPropertyMap(traits))
        promise.resolve(null)
    }

    override fun reset(promise: Promise) {
        AppDNA.reset()
        promise.resolve(null)
    }

    /** W17 — fire-and-forget: native enqueues, so a Promise per event would allocate for nothing. */
    override fun track(event: String, properties: ReadableMap?) {
        AppDNA.track(event, AppdnaBridge.toPropertyMap(properties))
    }

    override fun flush(promise: Promise) {
        AppDNA.flush()
        promise.resolve(null)
    }

    override fun setConsent(analytics: Boolean, promise: Promise) {
        AppDNA.setConsent(analytics)
        promise.resolve(null)
    }

    override fun isConsentGranted(promise: Promise) {
        promise.resolve(AppDNA.isConsentGranted())
    }

    override fun setLogLevel(level: String) {
        AppDNA.setLogLevel(level)
    }

    /**
     * N5 (ADR-002) — ADOPT-ANDROID, iOS no-op. Matches what Flutter already did.
     *
     * Android has ForcedTheme; iOS never implemented it. Flutter EXPOSED it anyway (with the iOS
     * plugin answering nil, documented); React Native exposed NOTHING. So an Android host on Flutter
     * could force the theme and the same host on RN could not — on a spec titled "full native parity".
     * The two wrappers omitted DIFFERENTLY, and neither omission was written down, which is exactly
     * how they could diverge unseen.
     *
     * An unrecognised value CLEARS the override rather than throwing: a bad theme string must not
     * break a host, and "system" is the honest fallback.
     */
    override fun setForcedTheme(theme: String?, promise: Promise) {
        AppDNA.setForcedTheme(
            when (theme?.lowercase()) {
                "light" -> ForcedTheme.LIGHT
                "dark" -> ForcedTheme.DARK
                "system" -> ForcedTheme.SYSTEM
                else -> null
            },
        )
        promise.resolve(null)
    }

    override fun getForcedTheme(promise: Promise) {
        promise.resolve(AppDNA.getForcedTheme()?.name?.lowercase())
    }

    override fun shutdown(promise: Promise) {
        AppDNA.shutdown()
        promise.resolve(null)
    }

    override fun getSdkVersion(promise: Promise) {
        promise.resolve(AppDNA.sdkVersion)
    }

    override fun diagnose(promise: Promise) {
        promise.resolve(AppDNA.diagnose())
    }

    /** D-k — the init-degraded seam ships with a consumer rather than as dead native API. */
    override fun getLastInitError(promise: Promise) {
        val err = AppDNA.lastInitError
        promise.resolve(
            if (err == null) {
                "null"
            } else {
                AppdnaBridge.toJson(
                    mapOf(
                        "type" to err::class.java.simpleName,
                        "message" to (err.message ?: ""),
                    ),
                )
            },
        )
    }

    /** D-h / AC-22 — populates `context.screen` on every subsequent event. */
    override fun notifyScreenAppeared(screenName: String) {
        AppDNA.notifyScreenAppeared(screenName)
    }

    override fun onReady(promise: Promise) {
        AppDNA.onReady { promise.resolve(null) }
    }

    // ── Remote config ─────────────────────────────────────────────────────────

    /**
     * E9.1 — resolves a JSON **string**, not a raw `Map`. `promise.resolve(map)` throws at the
     * bridge, so an object-valued flag worked on iOS and Flutter and crashed on RN Android.
     */
    override fun getRemoteConfig(key: String, promise: Promise) {
        promise.resolve(AppdnaBridge.toJson(AppDNA.getRemoteConfig(key)))
    }

    override fun getAllRemoteConfig(promise: Promise) {
        promise.resolve(AppdnaBridge.toJson(AppDNA.remoteConfig.getAll()))
    }

    override fun refreshConfig(promise: Promise) {
        AppDNA.remoteConfig.refresh()
        promise.resolve(null)
    }

    // ── Feature flags ─────────────────────────────────────────────────────────

    override fun isFeatureEnabled(flag: String, promise: Promise) {
        promise.resolve(AppDNA.isFeatureEnabled(flag))
    }

    override fun getFeatureVariant(flag: String, promise: Promise) {
        promise.resolve(AppdnaBridge.toJson(AppDNA.features.getVariant(flag)))
    }

    // ── Experiments ───────────────────────────────────────────────────────────

    override fun getExperimentVariant(experimentId: String, promise: Promise) {
        promise.resolve(AppdnaBridge.toJson(AppDNA.getExperimentVariant(experimentId)))
    }

    override fun isInVariant(experimentId: String, variantId: String, promise: Promise) {
        promise.resolve(AppDNA.isInVariant(experimentId, variantId))
    }

    override fun getExperimentConfig(experimentId: String, key: String, promise: Promise) {
        promise.resolve(AppdnaBridge.toJson(AppDNA.getExperimentConfig(experimentId, key)))
    }

    override fun getExperimentExposures(promise: Promise) {
        val exposures = AppDNA.experiments.getExposures().map {
            mapOf<String, Any?>("experimentId" to it.experimentId, "variant" to it.variant)
        }
        promise.resolve(AppdnaBridge.toWritableArray(exposures))
    }

    // ── Onboarding / paywall / surveys / messages ─────────────────────────────

    /**
     * ⚠ No `context` parameter. It had one — and never read it. `AppDNA.presentOnboarding` takes no
     * context, and `OnboardingModule.present(activity, flowId, context)` drops the argument it is
     * given (AppDNAModules.kt), so there was nothing to forward it TO. A host that passed
     * `experimentOverrides` got a silent no-op on both platforms. Removed from the IR
     * (`sdk-methods.ts`) rather than left as a parameter that does nothing.
     */
    override fun presentOnboarding(flowId: String, promise: Promise) {
        // E10: dispatch onto the UI thread so native's own main-looper check takes the latch-free
        // path. Blocking here would freeze the JS thread for the latch's five seconds.
        val activity = reactContext.currentActivity ?: return promise.resolve(false)
        activity.runOnUiThread {
            promise.resolve(AppDNA.presentOnboarding(activity, flowId))
        }
    }

    /**
     * 🔴 RESOLVES A BOOLEAN — `false` means nothing was presented.
     *
     * This used to resolve SUCCESSFULLY whatever happened: an unknown paywall id, an unconfigured SDK,
     * a runtime-locked SDK. `await AppDNA.paywall.present('typo_id')` reported success and no paywall
     * ever appeared. `presentOnboarding` above has always reported this honestly; so does `showScreen`.
     *
     * And the missing-host case no longer forks per platform: this rejected `NO_ACTIVITY` while iOS
     * rejected `NO_VIEW_CONTROLLER` for the SAME condition, so a host had to branch on `Platform.OS` to
     * catch its own error. Both now resolve `false` — the same answer they give for every other reason
     * the paywall did not appear, and the same answer `presentOnboarding` already gives.
     */
    override fun presentPaywall(paywallId: String, context: ReadableMap?, promise: Promise) {
        val activity = reactContext.currentActivity ?: return promise.resolve(false)
        // Parse on the JS thread — this method's own contract, stated in `configure` above: a
        // ReadableMap is only valid on the thread the bridge delivered it on. Reading it inside
        // `runOnUiThread` reads it after this method has RETURNED, on another thread, which is
        // undefined behaviour and can hand the paywall an empty customData with no error anywhere.
        val paywallContext = parsePaywallContext(context, fallbackPlacement = "")
        activity.runOnUiThread {
            promise.resolve(AppDNA.presentPaywall(activity, paywallId, paywallContext))
        }
    }

    /** N17 — an iOS overload, a distinct Android name. The wrapper exposes one name for both. */
    override fun presentPaywallByPlacement(placement: String, context: ReadableMap?, promise: Promise) {
        val activity = reactContext.currentActivity ?: return promise.resolve(false)
        // Same as `presentPaywall`: read the ReadableMap on the thread that delivered it.
        val paywallContext = parsePaywallContext(context, fallbackPlacement = placement)
        activity.runOnUiThread {
            promise.resolve(AppDNA.presentPaywallByPlacement(activity, placement, paywallContext))
        }
    }

    override fun presentSurvey(surveyId: String, promise: Promise) {
        AppDNA.surveys.present(surveyId)
        promise.resolve(null)
    }

    // ── Session data / traits / location (P8) ────────────────────────────────
    //
    // The value crosses as JSON (E2). `AppdnaBridge.fromJson` returns a Kotlin value; native takes
    // `Any`, so a null decodes to "no value" rather than throwing.

    override fun setSessionData(key: String, valueJson: String, promise: Promise) {
        val value = AppdnaBridge.fromJson(valueJson)
        if (value == null) {
            // `setSessionData(k, null)` is not "store null" — native's signature takes a non-null
            // `Any`. Clearing one key is not an operation either SDK exposes, so refusing loudly
            // beats silently storing a sentinel the host will never be able to distinguish.
            promise.reject("INVALID_VALUE", "setSessionData requires a non-null JSON value")
            return
        }
        AppDNA.setSessionData(key, value)
        promise.resolve(null)
    }

    override fun getSessionData(key: String, promise: Promise) {
        promise.resolve(AppdnaBridge.toJson(AppDNA.getSessionData(key)))
    }

    override fun clearSessionData(promise: Promise) {
        AppDNA.clearSessionData()
        promise.resolve(null)
    }

    override fun getUserTraits(promise: Promise) {
        promise.resolve(AppdnaBridge.toJson(AppDNA.getUserTraits()))
    }

    override fun getLocationData(fieldId: String, promise: Promise) {
        val loc = AppDNA.getLocationData(fieldId)
        promise.resolve(AppdnaBridge.toJson(loc?.let { AppdnaMappers.map(it) }))
    }

    // ── Screens (P8 — the 9th delegate) ───────────────────────────────────────
    //
    // The native `showScreen`/`showFlow` take a completion callback, but the RESULT is ALSO delivered
    // to `AppDNAScreenDelegate.onScreenDismissed`/`onFlowCompleted` — and a screen can be dismissed
    // long after the promise settles. Routing the result through the delegate (an EVENT) rather than
    // the promise is what keeps one result with one source: a promise that resolved on presentation
    // cannot also carry a dismissal that has not happened yet.

    override fun showScreen(screenId: String, promise: Promise) {
        // E10, same as presentOnboarding: present-style calls go on the UI thread so native's own
        // main-looper check takes the latch-free path. Calling straight through would block the JS
        // thread for the latch's five seconds. `false` when there is no foreground Activity to
        // present from — the host learns nothing happened instead of silently believing it did.
        val activity = reactContext.currentActivity ?: return promise.resolve(false)
        activity.runOnUiThread {
            AppDNA.showScreen(screenId)
            promise.resolve(true)
        }
    }

    override fun showFlow(flowId: String, promise: Promise) {
        val activity = reactContext.currentActivity ?: return promise.resolve(false)
        activity.runOnUiThread {
            AppDNA.showFlow(flowId)
            promise.resolve(true)
        }
    }

    override fun dismissScreen(promise: Promise) {
        val activity = reactContext.currentActivity ?: return promise.resolve(null)
        activity.runOnUiThread {
            AppDNA.dismissScreen()
            promise.resolve(null)
        }
    }

    override fun previewScreen(json: String, promise: Promise) {
        val activity = reactContext.currentActivity ?: return promise.resolve(false)
        activity.runOnUiThread {
            promise.resolve(AppDNA.previewScreen(json))
        }
    }

    override fun enableNavigationInterception(screens: ReadableArray?, promise: Promise) {
        // `null` means intercept every screen — NOT "intercept none". Passing an empty list instead
        // would silently mean the opposite of what the host asked for.
        val list = screens?.toArrayList()?.mapNotNull { it as? String }
        AppDNA.enableNavigationInterception(list)
        promise.resolve(null)
    }

    override fun disableNavigationInterception(promise: Promise) {
        AppDNA.disableNavigationInterception()
        promise.resolve(null)
    }

    override fun suppressMessages(suppress: Boolean) {
        AppDNA.inAppMessages.suppressDisplay(suppress)
    }

    // ── Billing ───────────────────────────────────────────────────────────────

    /**
     * 🔴 The reject CODE is the SDK's own `billingErrorType` discriminator — `userCancelled`,
     * `pending`, `productNotFound`, `verificationFailed`, `networkError`, `serverError`,
     * `providerNotAvailable`, `unknown` — not one blanket `PURCHASE_ERROR`.
     *
     * Every failure used to arrive as `PURCHASE_ERROR` with a LOCALIZED message, so a host that wanted
     * to do the one thing every store app does — stay silent when the user taps Cancel, offer a retry
     * when the card is declined — had to regex English prose on a device that might be in Japanese.
     * The discriminator has existed the whole time and is already handed to
     * `onPaywallPurchaseFailed(errorType:)`; it was simply `internal`, so this module could not see it.
     *
     * It is passed through VERBATIM, with no translation table — a table is a thing that can fork, and
     * iOS emits these same strings.
     */
    override fun purchase(productId: String, offerToken: String?, promise: Promise) {
        // Play's purchase flow is Activity-bound. Without one there is nothing to launch from, and a
        // silent no-op would look like a user who dismissed the sheet. ⚠ Recorded platform-inherent
        // asymmetry (not a wrapper bug): iOS StoreKit needs no view controller, so iOS `purchase()` has
        // no such precondition and proceeds — `NO_ACTIVITY` is Android-only by design and is NOT part of
        // the shared `billingErrorType` set. Every SHARED failure (cancel/declined/product-not-found/…)
        // rejects with the identical `billingErrorType` string on both platforms.
        val activity = reactContext.currentActivity
            ?: return promise.reject("NO_ACTIVITY", "purchase() requires a foreground Activity")
        val options = offerToken?.let { ai.appdna.sdk.billing.PurchaseOptions(offerToken = it) }
        launchSettling(
            promise,
            "PURCHASE_ERROR",
            errorCodeFor = { ai.appdna.sdk.billing.billingErrorType(it) },
        ) { p ->
            val result = AppDNA.billing.purchase(activity, productId, options)
            p.resolve(AppdnaBridge.toWritableMap(AppdnaMappers.map(result)))
        }
    }

    override fun restorePurchases(promise: Promise) {
        launchSettling(promise, "RESTORE_ERROR") { p ->
            // `List<String>` — restored product ids, NOT entitlements.
            p.resolve(AppdnaBridge.toWritableArray(AppDNA.billing.restorePurchases()))
        }
    }

    override fun getProducts(productIds: ReadableArray, promise: Promise) {
        // Read the ReadableArray HERE, on the thread the bridge delivered it on — the same invariant
        // `configure` and `presentPaywall` state and honour. Reading it inside `scope.launch` reads it
        // after this method has RETURNED, from another thread, which is undefined behaviour: the
        // bridge may already have recycled the backing buffer, so `getProducts([...])` could query the
        // store for a garbled or empty id list and resolve `[]` with no error anywhere.
        val ids = AppdnaBridge.toStringList(productIds)
        launchSettling(promise, "PRODUCTS_ERROR") { p ->
            val products = AppDNA.billing.getProducts(ids)
            p.resolve(AppdnaBridge.toWritableArray(products.map { AppdnaMappers.map(it) }))
        }
    }

    override fun hasActiveSubscription(promise: Promise) {
        launchSettling(promise, "SUBSCRIPTION_ERROR") { p ->
            p.resolve(AppDNA.billing.hasActiveSubscription())
        }
    }

    override fun getEntitlements(promise: Promise) {
        launchSettling(promise, "ENTITLEMENTS_ERROR") { p ->
            val entitlements = AppDNA.billing.getEntitlements()
            p.resolve(AppdnaBridge.toWritableArray(entitlements.map { AppdnaMappers.map(it) }))
        }
    }

    /**
     * 🔴 IDEMPOTENT. It was not, and the iOS twin's comment claimed Android could not have this bug
     * ("its `shutdown()` nulls the billing manager, taking its listeners with it") — a statement about
     * Android, written in a Swift file, that is only true ACROSS A SHUTDOWN.
     *
     * Nothing stops a host calling `startEntitlementObserver()` twice without one: a component
     * re-mount, a re-subscribe, a Fast Refresh in dev. Each call appended another closure to
     * `entitlementCache`'s listener list — `AppDNA.billing.onEntitlementsChanged` only ever ADDS — so
     * every entitlement change then emitted `onEntitlementsChanged` N times, and a host that grants
     * on that event grants N times.
     *
     * `removeEntitlementsChangedListener` existed the whole time (AppDNAModules.kt:427); this simply
     * never called it. It also drains the pending-listener queue, so an observer registered before the
     * billing manager existed is dropped too, rather than being flushed in later behind our back.
     */
    override fun startEntitlementObserver(promise: Promise) {
        entitlementListener?.let { AppDNA.billing.removeEntitlementsChangedListener(it) }
        val listener: (List<ai.appdna.sdk.billing.Entitlement>) -> Unit = { entitlements ->
            emitOnEntitlementsChanged(
                AppdnaBridge.toWritableMap(
                    mapOf("entitlements" to entitlements.map { AppdnaMappers.map(it) }),
                ),
            )
        }
        entitlementListener = listener
        AppDNA.billing.onEntitlementsChanged(listener)
        promise.resolve(null)
    }

    // ── Push ──────────────────────────────────────────────────────────────────

    /** ⚠ The namespace is `AppDNA.push` on Android and `AppDNA.pushModule` on iOS — a naming split. */
    override fun requestPushPermission(promise: Promise) {
        launchSettling(promise, "PUSH_PERMISSION_ERROR") { p ->
            p.resolve(AppDNA.push.requestPermission(reactContext.currentActivity))
        }
    }

    override fun getPushToken(promise: Promise) {
        promise.resolve(AppdnaBridge.toJson(AppDNA.push.getToken()))
    }

    /** N9 — an FCM `String` here; hex-encoded APNs `Data` on iOS. One signature, two meanings. */
    override fun setPushToken(token: String, promise: Promise) {
        AppDNA.setPushToken(token)
        promise.resolve(null)
    }

    override fun setPushPermission(granted: Boolean, promise: Promise) {
        AppDNA.setPushPermission(granted)
        promise.resolve(null)
    }

    override fun trackPushDelivered(pushId: String, promise: Promise) {
        AppDNA.trackPushDelivered(pushId)
        promise.resolve(null)
    }

    override fun trackPushTapped(pushId: String, action: String?, promise: Promise) {
        AppDNA.trackPushTapped(pushId, action)
        promise.resolve(null)
    }

    // ── Deep links / web entitlements ─────────────────────────────────────────

    /** N15 — a `String` here, a `URL` on iOS. */
    override fun handleDeepLink(url: String, promise: Promise) {
        AppDNA.deepLinks.handleURL(url)
        promise.resolve(null)
    }

    override fun checkDeferredDeepLink(promise: Promise) {
        AppDNA.checkDeferredDeepLink { deepLink ->
            promise.resolve(AppdnaBridge.toJson(deepLink?.toMap()))
        }
    }

    override fun getWebEntitlement(promise: Promise) {
        promise.resolve(AppdnaBridge.toJson(AppDNA.webEntitlement?.toMap()))
    }

    // ── Host-veto reply channel (P3 routes the hooks; this is the seam) ───────

    override fun respondToHostCallback(callbackId: String, resultJson: String) {
        AppdnaHostCallbacks.respond(callbackId, resultJson)
    }

    // ── Delegates (P3) ───────────────────────────────────────────────────────

    /**
     * Attach every forwarder to the native SDK.
     *
     * All of them, unconditionally, at `configure` — not lazily when JS subscribes. A TurboModule
     * emitter property gives native no subscribe signal, so there is nothing to be lazy about, and
     * emitting into zero listeners costs a `WritableMap` that is immediately dropped.
     *
     * The three synchronous vetoes (`shouldShowMessage`, `shouldOpen`, `onScreenAction`) cannot await
     * a bridge round trip, so each is registered on the SDK's parallel **async seam**, which
     * `MessageManager` / `DeepLinksModule` / `ScreenManager` consult in addition to the sync delegate
     * method. Both can suppress; only the async one can wait.
     *
     * @param vetoTimeoutSeconds from `AppDNAOptions.vetoTimeout` — never a literal, per E7.
     */
    private fun registerDelegates(vetoTimeoutSeconds: Long) {
        val emitter = AppdnaEventEmitter { event, payload -> emitEventNamed(event, payload) }
        val veto = AppdnaVetoInvoker(vetoTimeoutSeconds * 1000L) { payload ->
            emitEventNamed("onHostCallback", payload)
        }
        invoker = veto

        AppDNA.onboarding.setDelegate(OnboardingForwarder(emitter, veto))
        // Returns FALSE when the scope is already dead. `scope.launch` on a cancelled scope creates a
        // job whose body never runs — no exception, no `finally` — so a promo-code veto launched after
        // teardown would leave native's `completion` uncalled and the promo field spinning forever.
        // The forwarder answers `false` (reject the code) when this says the veto could not run.
        AppDNA.paywall.setDelegate(
            PaywallForwarder(emitter, veto) { block ->
                if (!scope.isActive) {
                    false
                } else {
                    scope.launch { block() }
                    true
                }
            },
        )
        AppDNA.surveys.setDelegate(SurveyForwarder(emitter))
        AppDNA.inAppMessages.setDelegate(InAppMessageForwarder(emitter))
        AppDNA.push.setDelegate(PushForwarder(emitter))
        AppDNA.billing.setDelegate(BillingForwarder(emitter))
        AppDNA.deepLinks.setDelegate(DeepLinkForwarder(emitter))
        // The 9th delegate. `AppDNA.screenDelegate` is a var whose setter forwards to
        // ScreenManager.setDelegate — the presented-screen path, which is what actually fires these.
        AppDNA.screenDelegate = ScreenForwarder(emitter)
        AppDNA.setInitDelegate(InitForwarder(emitter))
        // SPEC-404. iOS attached this; Android did not, so `lifecycle.setDelegate(...)` — the same JS,
        // the same signature — fired on one platform and was silently deaf on the other.
        AppDNA.setLifecycleDelegate(LifecycleForwarder(emitter))

        // Native refreshes remote config and announces it on `configUpdated`. Nothing observed it, so
        // `remoteConfig.onChanged` / `features.onChanged` never fired and the facade's `getCached()`
        // snapshot — which refreshes ON this event — stayed frozen until the next cold start while
        // `await remoteConfig.get(key)` returned the new value.
        configUpdatesJob?.cancel()
        configUpdatesJob = scope.launch {
            AppDNA.configUpdated.collect {
                emitter.emit("onRemoteConfigChanged", emptyMap())
                emitter.emit("onFeatureFlagsChanged", emptyMap())
            }
        }

        // The web-entitlement observer, likewise iOS-only until now: a subscription bought on the web
        // and unlocked mid-session updated the UI on iOS and not on Android.
        webEntitlementListener?.let { AppDNA.removeWebEntitlementListener(it) }
        val webListener: (ai.appdna.sdk.webentitlements.WebEntitlement?) -> Unit = { entitlement ->
            emitter.emit("onWebEntitlementChanged", mapOf("entitlement" to entitlement?.toMap()))
        }
        webEntitlementListener = webListener
        AppDNA.onWebEntitlementChanged(webListener)

        // 🔴 `shouldShowMessage` defaults to ALLOW on timeout; `onPromoCodeSubmit` to REJECT. A
        // uniform default here is how a paywall silently starts accepting unvalidated promo codes.
        AppDNA.inAppMessages.setAsyncShouldShowMessage { messageId ->
            veto.invoke("shouldShowMessage", mapOf("messageId" to messageId)) as? Boolean ?: true
        }
        AppDNA.deepLinks.asyncShouldOpen = { url, params ->
            veto.invoke("shouldOpen", mapOf("url" to url, "params" to params)) as? Boolean ?: true
        }
        AppDNA.asyncOnScreenAction = { screenId, action ->
            veto.invoke(
                "onScreenAction",
                mapOf("screenId" to screenId, "action" to action),
            ) as? Boolean ?: true
        }
    }

    /**
     * Fan an event out to its generated emitter.
     *
     * An event with no emitter is spec drift, not a runtime condition — the whole point of the
     * codegen'd spec is that the two sets cannot disagree. `check:rn-facade-parity` (P6) asserts this
     * `when` covers `SDK_EVENTS` exactly, in both directions.
     */
    private fun emitEventNamed(name: String, payload: Map<String, Any?>) {
        emitEventNamed(name, AppdnaBridge.toWritableMap(payload))
    }

    private fun emitEventNamed(name: String, payload: WritableMap) {
        when (name) {
            "onInitDegraded" -> emitOnInitDegraded(payload)
            "onRemoteConfigChanged" -> emitOnRemoteConfigChanged(payload)
            "onFeatureFlagsChanged" -> emitOnFeatureFlagsChanged(payload)
            "onOnboardingStarted" -> emitOnOnboardingStarted(payload)
            "onOnboardingStepChanged" -> emitOnOnboardingStepChanged(payload)
            "onOnboardingCompleted" -> emitOnOnboardingCompleted(payload)
            "onOnboardingDismissed" -> emitOnOnboardingDismissed(payload)
            "onPermissionResult" -> emitOnPermissionResult(payload)
            "onPaywallPresented" -> emitOnPaywallPresented(payload)
            "onPaywallAction" -> emitOnPaywallAction(payload)
            "onPaywallPurchaseStarted" -> emitOnPaywallPurchaseStarted(payload)
            "onPaywallPurchaseCompleted" -> emitOnPaywallPurchaseCompleted(payload)
            "onPaywallPurchaseFailed" -> emitOnPaywallPurchaseFailed(payload)
            "onPaywallDismissed" -> emitOnPaywallDismissed(payload)
            "onPaywallRestoreStarted" -> emitOnPaywallRestoreStarted(payload)
            "onPaywallRestoreCompleted" -> emitOnPaywallRestoreCompleted(payload)
            "onPaywallRestoreFailed" -> emitOnPaywallRestoreFailed(payload)
            "onPostPurchaseDeepLink" -> emitOnPostPurchaseDeepLink(payload)
            "onPostPurchaseNextStep" -> emitOnPostPurchaseNextStep(payload)
            "onPurchaseCompleted" -> emitOnPurchaseCompleted(payload)
            "onPurchaseFailed" -> emitOnPurchaseFailed(payload)
            "onRestoreCompleted" -> emitOnRestoreCompleted(payload)
            "onEntitlementsChanged" -> emitOnEntitlementsChanged(payload)
            "onBillingUnavailable" -> emitOnBillingUnavailable(payload)
            "onScreenPresented" -> emitOnScreenPresented(payload)
            "onScreenDismissed" -> emitOnScreenDismissed(payload)
            "onFlowCompleted" -> emitOnFlowCompleted(payload)
            "onSurveyPresented" -> emitOnSurveyPresented(payload)
            "onSurveyCompleted" -> emitOnSurveyCompleted(payload)
            "onSurveyDismissed" -> emitOnSurveyDismissed(payload)
            "onMessageShown" -> emitOnMessageShown(payload)
            "onMessageAction" -> emitOnMessageAction(payload)
            "onMessageDismissed" -> emitOnMessageDismissed(payload)
            "onPushTokenRegistered" -> emitOnPushTokenRegistered(payload)
            "onPushReceived" -> emitOnPushReceived(payload)
            "onPushTapped" -> emitOnPushTapped(payload)
            "onDeepLinkReceived" -> emitOnDeepLinkReceived(payload)
            "onWebEntitlementChanged" -> emitOnWebEntitlementChanged(payload)
            "onSdkRuntimeLocked" -> emitOnSdkRuntimeLocked(payload)
            "onSdkRuntimeUnlocked" -> emitOnSdkRuntimeUnlocked(payload)
            "onHostCallback" -> emitOnHostCallback(payload)
            else -> throw IllegalStateException("AppDNA: no TurboModule emitter for event '$name'")
        }
    }

    // ── Teardown (E6 / E11) ──────────────────────────────────────────────────

    /**
     * Every native listener registered here is registered on the process-global `AppDNA` singleton
     * and captures this bridge-scoped module. A Metro reload or a second `configure()` would
     * otherwise leave the old closures attached and deliver every event N-fold.
     *
     * ⚠ Fast Refresh runs neither `invalidate()` nor `configure()`. Only a true reload does.
     */
    override fun invalidate() {
        // FIRST, before anything can throw: settle every promise a coroutine on `scope` still owes an
        // answer to. `scope.cancel()` below kills those coroutines where they stand, and a JS promise
        // that is neither resolved nor rejected hangs its `await` for the life of the process — a
        // reload mid-purchase used to leave the host's checkout spinner turning with no error to
        // explain it.
        rejectPending()

        entitlementListener?.let { AppDNA.billing.removeEntitlementsChangedListener(it) }
        entitlementListener = null

        // Every forwarder captures this bridge-scoped module. Leaving them attached to the
        // process-global singleton across a reload is what delivers each event N-fold.
        AppDNA.onboarding.setDelegate(null)
        AppDNA.paywall.setDelegate(null)
        AppDNA.surveys.setDelegate(null)
        AppDNA.inAppMessages.setDelegate(null)
        AppDNA.push.setDelegate(null)
        AppDNA.billing.setDelegate(null)
        AppDNA.deepLinks.setDelegate(null)
        AppDNA.setInitDelegate(null)
        AppDNA.setLifecycleDelegate(null)
        webEntitlementListener?.let { AppDNA.removeWebEntitlementListener(it) }
        webEntitlementListener = null
        configUpdatesJob?.cancel()
        configUpdatesJob = null
        AppDNA.inAppMessages.setAsyncShouldShowMessage(null)
        AppDNA.deepLinks.asyncShouldOpen = null
        AppDNA.asyncOnScreenAction = null
        AppDNA.screenDelegate = null
        invoker = null
        // E6: drain the pending veto map, rejecting each — a JS side that no longer exists will
        // never answer, and native would otherwise await forever.
        AppdnaHostCallbacks.invalidateAll()
        scope.cancel()
        super.invalidate()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * ⚠ `internal` so `AppdnaParseOptionsTest` can reach it (AC-11). A jest test cannot see a
     * native `?? 3600`, and neither can a Dart one — only a native unit test can.
     */
    internal fun parseOptions(map: ReadableMap?): AppDNAOptions {
        val values = AppdnaBridge.toValueMap(map) ?: emptyMap()
        val defaults = AppDNAOptions()

        val logLevel = when (values["logLevel"] as? String) {
            "none" -> LogLevel.NONE
            "error" -> LogLevel.ERROR
            "warning" -> LogLevel.WARNING
            "info" -> LogLevel.INFO
            "debug" -> LogLevel.DEBUG
            else -> defaults.logLevel
        }

        return AppDNAOptions(
            // E7: never a literal. `?? 300` was how the wrappers drifted 12× off the native TTL.
            flushInterval = (values["flushInterval"] as? Number)?.toLong() ?: defaults.flushInterval,
            batchSize = (values["batchSize"] as? Number)?.toInt() ?: defaults.batchSize,
            configTTL = (values["configTTL"] as? Number)?.toLong() ?: defaults.configTTL,
            logLevel = logLevel,
            notificationIcon = (values["notificationIcon"] as? Number)?.toInt() ?: defaults.notificationIcon,
            // §7 rule 1: injected unconditionally, NOT read from `values`. A host cannot spoof it.
            framework = FRAMEWORK_TAG,
            frameworkVersion = WRAPPER_VERSION,
            // AC-21: Android gained billingProvider in 1.0.42, so the host's choice finally arrives.
            billingProvider = BillingProvider.fromWire(values["billingProvider"]) ?: defaults.billingProvider,
            requireConsent = values["requireConsent"] as? Boolean ?: defaults.requireConsent,
            vetoTimeout = (values["vetoTimeout"] as? Number)?.toLong() ?: defaults.vetoTimeout,
        )
    }

    /**
     * D-s — all four fields. `customData` reaches the `paywall_view` properties bag natively.
     *
     * 🔴 This used to `return null` when `placement` was absent, discarding the ENTIRE context —
     * customData, experiment and variant with it — for the most natural call there is:
     * `presentByPlacement('home_upsell', { customData: {...} })`, where the placement is already
     * argument #1 and TS declares the context's own `placement` optional. Silent. See the iOS twin.
     */
    private fun parsePaywallContext(map: ReadableMap?, fallbackPlacement: String): PaywallContext? {
        val values = AppdnaBridge.toValueMap(map)
        // 🔴 AND IT STILL DROPPED THE PLACEMENT FOR THE COMMONEST CALL OF ALL.
        //
        // The fix above covered "a context with no placement". It did not cover NO CONTEXT — and
        // `presentByPlacement('upgrade')` is exactly that: the context argument is optional in TS, so
        // a host that has nothing else to say simply omits it. `null` in, `null` out, and native's
        // `paywall_view` then records `placement = context?.placement ?: "unknown"`
        // (PaywallManager.kt:206). Every by-placement paywall view from an RN host landed in BigQuery
        // as `unknown` — the one dimension the event exists to carry, thrown away one line after the
        // wrapper was handed it. Found by SharedFixtureBridgeTest driving the REAL native path;
        // invisible to a jest test, which sees only that the argument was forwarded.
        if (values == null) {
            return if (fallbackPlacement.isEmpty()) null else PaywallContext(placement = fallbackPlacement)
        }
        val placement = values["placement"] as? String ?: fallbackPlacement
        @Suppress("UNCHECKED_CAST")
        val custom = values["customData"] as? Map<String, Any>
        return PaywallContext(
            placement = placement,
            experiment = values["experiment"] as? String,
            variant = values["variant"] as? String,
            customData = custom,
        )
    }
}
