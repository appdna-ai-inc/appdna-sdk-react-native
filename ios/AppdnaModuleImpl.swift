import Foundation
import UIKit
import React
import AppDNASDK

/**
 * SPEC-070-B P2 — the Swift half of the iOS TurboModule.
 *
 * ## Why this is not the TurboModule itself
 *
 * A pure-Swift TurboModule is impossible. Codegen emits a C++ `NativeAppdnaModuleSpecJSI` plus an
 * ObjC `@protocol NativeAppdnaModuleSpec`, and registration returns a
 * `std::shared_ptr<facebook::react::TurboModule>` over C++/JSI/folly headers Swift cannot import.
 * Worse, the `emitOnX:` methods live on `NativeAppdnaModuleSpecBase`, an ObjC class with a C++
 * `EventEmitterCallback` ivar — Swift cannot subclass it either.
 *
 * So `AppdnaModule.mm` is the TurboModule, and it forwards every method here. This class is plain
 * ObjC-visible Swift: no JSI, no C++, no React-internal types beyond the promise blocks.
 *
 * Events go out through [eventSink] rather than through `emitOnX:` directly, for the same reason.
 */
@objc(AppdnaModuleImpl)
public final class AppdnaModuleImpl: NSObject {

    /// SPEC-070-B §7 — pinned literal, underscore not hyphen. Injected unconditionally in
    /// `parseOptions`, never read from the host's options: a host must not be able to set, spoof or
    /// omit its own attribution. `event-envelope.schema.ts` is `.catch('native')`, so a wrong tag
    /// does not error, is not logged, and is not metered — it just quietly lies in BigQuery.
    private static let frameworkTag = "react_native"

    /// Set by the ObjC++ adapter. Weak: the adapter owns this object.
    @objc public weak var eventSink: (any AppdnaEventSink)?

    /// PN row 3 — the token for the entitlements handler, so `invalidate()` can detach it. Before
    /// this, a reload left N handlers on the process-global singleton and delivered N-fold.
    private var entitlementObserverToken: UUID?

    /// The token for the WEB-entitlement handler. Same story as the one above, one namespace over:
    /// the SDK appends, so this must be tracked to be re-registrable and detachable.
    private var webEntitlementObserverToken: UUID?

    /// The `AppDNA.configUpdated` NotificationCenter observer. Must be removed in `invalidate()`:
    /// it captures this bridge-scoped object, and a stale one left attached across a JS reload
    /// delivers every config change N-fold — the same reload leak the forwarders below exist for.
    private var configObserver: NSObjectProtocol?

    /**
     * P3 — the eight veto hooks and every observe callback are routed by these.
     *
     * The SDK's delegate properties are `weak`, so the forwarders must be OWNED here. A local that
     * goes out of scope at the end of `configure` deallocates immediately and the delegate silently
     * becomes nil — a failure mode that looks exactly like "the events were never wired".
     */
    private var forwarders: [NSObject] = []
    private var invoker: AppdnaVetoInvoker?

    private func emit(_ name: String, _ payload: [String: Any]) {
        // The ObjC `AppdnaEventSink` protocol's `payload:` is `NSDictionary *`, which imports into
        // Swift as `[AnyHashable: Any]`. A `[String: Any]` bridges to that automatically — casting to
        // `NSDictionary` produces the wrong Swift type and does not convert.
        eventSink?.emitEventNamed(name, payload: payload)
    }

    // MARK: - Lifecycle / core

    @objc(configure:env:options:resolve:reject:)
    public func configure(
        _ apiKey: String,
        env: String,
        options: NSDictionary?,
        resolve: @escaping RCTPromiseResolveBlock,
        reject: @escaping RCTPromiseRejectBlock
    ) {
        // The enum is `production | sandbox` (Configuration.swift:4). `.staging` named a case that
        // has never existed; the old shim could not compile.
        let environment: Environment = env == "sandbox" ? .sandbox : .production
        let parsed = parseOptions(options as? [String: Any])
        AppDNA.configure(apiKey: apiKey, environment: environment, options: parsed)

        observeWebEntitlementChanges()
        observeConfigUpdates()
        registerDelegates(vetoTimeout: parsed.vetoTimeout)
        resolve(nil)
    }

    /// The web-entitlement forwarder. Registered at most ONCE per module instance, and detached in
    /// `invalidate()`.
    ///
    /// This used to be an unguarded `AppDNA.onWebEntitlementChanged { … }` inline in `configure()`.
    /// The SDK APPENDS, so a host that called `configure()` twice — or shut the SDK down and
    /// re-configured it — got TWO web-entitlement handlers on the process-global singleton, and every
    /// change was delivered twice, forever. Android has guarded exactly this case since P2c
    /// (`AppdnaModule.kt`, remove-then-add on `webEntitlementListener`); `observeConfigUpdates()`
    /// below is guarded for the same reason. iOS was the one that was not.
    ///
    /// 🔴 REMOVE-then-ADD, not "return early if we already have a token".
    ///
    /// The early return was a one-way door. The token is cleared only in `invalidate()` (bridge
    /// teardown) — but native `AppDNA.shutdown()` calls `webEntitlementChangeHandlers.removeAll()`, and
    /// nothing told this object about it. So on the second `configure()` of a
    /// `configure → shutdown → configure` cycle, the wrapper saw a non-nil token and REFUSED to
    /// re-register, while native had nothing registered: `onWebEntitlementChanged` was dead for the
    /// rest of the process. Android re-registers on every configure (`AppdnaModule.kt`) — same JS, one
    /// platform deaf. Removing a token native has already dropped is a no-op, so this is still
    /// idempotent against a double `configure()` with no shutdown, which is what the guard was for.
    private func observeWebEntitlementChanges() {
        if let token = webEntitlementObserverToken {
            AppDNA.removeWebEntitlementChangedHandler(token)
            webEntitlementObserverToken = nil
        }
        webEntitlementObserverToken = AppDNA.onWebEntitlementChanged { [weak self] entitlement in
            // `?? NSNull()`, NOT `as Any`: `entitlement?.toMap()` is `[String: Any]?`, and for a
            // cleared web entitlement `Optional.none as Any` boxes as a `_SwiftValue` the bridge cannot
            // represent → the host reads `undefined` on iOS while Android sends `null` (`putNull` on
            // `mapOf("entitlement" to entitlement?.toMap())`, AppdnaModule.kt). The facade coalesces
            // `data.entitlement ?? null`, but so did `onPaywallPurchaseFailed`'s productId — R22 still
            // hardened that emit site rather than lean on the facade alone. Same class, same fix.
            self?.emit("onWebEntitlementChanged", ["entitlement": entitlement?.toMap() ?? NSNull()])
        }
    }

    /// Native posts `AppDNA.configUpdated` whenever remote config is refreshed. Nothing in this
    /// wrapper was listening, so `remoteConfig.onChanged` and `features.onChanged` — both of them
    /// public, both documented — never fired, on either platform. Worse, the facade's `getCached()`
    /// snapshot (which its own docs tell you to prefer for per-render reads) refreshes ON that event:
    /// it froze at whatever `primeSnapshot()` captured, so a flag flip reached an RN user only on the
    /// next cold start, while `await remoteConfig.get(key)` returned the new value — two APIs in one
    /// namespace disagreeing, silently.
    ///
    /// Both events fire off the one signal, which is exactly what native intends: the Android core's
    /// comment on `configUpdated` names RN and Flutter as its consumers.
    private func observeConfigUpdates() {
        if configObserver != nil { return }
        configObserver = NotificationCenter.default.addObserver(
            forName: AppDNA.configUpdated,
            object: nil,
            queue: .main,
        ) { [weak self] _ in
            self?.emit("onRemoteConfigChanged", [:])
            self?.emit("onFeatureFlagsChanged", [:])
        }
    }

    @objc(identify:traits:resolve:reject:)
    public func identify(
        _ userId: String,
        traits: NSDictionary?,
        resolve: @escaping RCTPromiseResolveBlock,
        reject: @escaping RCTPromiseRejectBlock
    ) {
        AppDNA.identify(userId: userId, traits: traits as? [String: Any])
        resolve(nil)
    }

    @objc(reset:reject:)
    public func reset(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        AppDNA.reset()
        resolve(nil)
    }

    /// W17 — fire-and-forget. Native enqueues, so a Promise per event would allocate for nothing.
    @objc(track:properties:)
    public func track(_ event: String, properties: NSDictionary?) {
        AppDNA.track(event: event, properties: properties as? [String: Any])
    }

    @objc(flush:reject:)
    public func flush(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        AppDNA.flush()
        resolve(nil)
    }

    @objc(setConsent:resolve:reject:)
    public func setConsent(_ analytics: Bool, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        AppDNA.setConsent(analytics: analytics)
        resolve(nil)
    }

    @objc(isConsentGranted:reject:)
    public func isConsentGranted(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(AppDNA.isConsentGranted())
    }

    /// N5 (ADR-002) — ADOPT-ANDROID, iOS NO-OP. Deliberately, and it says so rather than pretending.
    ///
    /// iOS has no `ForcedTheme` (the Android core's own comment reads "Mirrors iOS
    /// AppDNA.setForcedTheme (TBD)" — it was never built). Flutter's iOS plugin already answers these
    /// two as no-ops for the same reason; RN now matches instead of omitting them entirely, which is
    /// what let an Android host on Flutter force the theme while the same host on RN could not.
    ///
    /// It resolves rather than rejecting: a cross-platform host calls this on both platforms, and a
    /// rejection would force every caller to branch on `Platform.OS` for a feature that simply is not
    /// there yet. `getForcedTheme()` answers `null` — a true answer, not a fabricated one. When iOS
    /// implements ForcedTheme, both wrappers light up at once: they are generated from the same IR.
    @objc(setForcedTheme:resolve:reject:)
    public func setForcedTheme(_ theme: NSString?, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(nil)
    }

    @objc(getForcedTheme:reject:)
    public func getForcedTheme(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(nil)
    }

    @objc(setLogLevel:)
    public func setLogLevel(_ level: String) {
        let mapped: LogLevel
        switch level {
        case "none": mapped = .none
        case "error": mapped = .error
        case "warning": mapped = .warning
        case "info": mapped = .info
        case "debug": mapped = .debug
        default: mapped = .warning
        }
        AppDNA.setLogLevel(mapped)
    }

    @objc(shutdown:reject:)
    public func shutdown(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        // `AppDNA.shutdown()` exists and flushes the queue first. Both wrappers used to no-op it.
        AppDNA.shutdown()
        resolve(nil)
    }

    @objc(getSdkVersion:reject:)
    public func getSdkVersion(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(AppDNA.sdkVersion)
    }

    @objc(diagnose:reject:)
    public func diagnose(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(AppDNA.diagnose())
    }

    /// D-k — the init-degraded seam ships with a consumer rather than as dead native API.
    @objc(getLastInitError:reject:)
    public func getLastInitError(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let err = AppDNA.lastInitError else { return resolve("null") }
        resolve(AppdnaJSON.encode([
            "type": String(describing: type(of: err)),
            "message": err.localizedDescription,
        ]))
    }

    /// D-h / AC-22 — populates `context.screen` on every subsequent event.
    @objc(notifyScreenAppeared:)
    public func notifyScreenAppeared(_ screenName: String) {
        AppDNA.notifyScreenAppeared(screenName)
    }

    @objc(onReady:reject:)
    public func onReady(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        AppDNA.onReady { resolve(nil) }
    }

    // MARK: - Remote config

    /// E2 — a config value of unknown shape crosses as a JSON string, parsed in the facade.
    @objc(getRemoteConfig:resolve:reject:)
    public func getRemoteConfig(_ key: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(AppdnaJSON.encode(AppDNA.getRemoteConfig(key: key)))
    }

    @objc(getAllRemoteConfig:reject:)
    public func getAllRemoteConfig(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(AppdnaJSON.encode(AppDNA.remoteConfig.getAll()))
    }

    @objc(refreshConfig:reject:)
    public func refreshConfig(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        AppDNA.remoteConfig.refresh()
        resolve(nil)
    }

    // MARK: - Feature flags

    @objc(isFeatureEnabled:resolve:reject:)
    public func isFeatureEnabled(_ flag: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(AppDNA.isFeatureEnabled(flag: flag))
    }

    @objc(getFeatureVariant:resolve:reject:)
    public func getFeatureVariant(_ flag: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(AppdnaJSON.encode(AppDNA.features.getVariant(flag)))
    }

    // MARK: - Experiments

    @objc(getExperimentVariant:resolve:reject:)
    public func getExperimentVariant(_ experimentId: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(AppdnaJSON.encode(AppDNA.getExperimentVariant(experimentId: experimentId)))
    }

    @objc(isInVariant:variantId:resolve:reject:)
    public func isInVariant(_ experimentId: String, variantId: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(AppDNA.isInVariant(experimentId: experimentId, variantId: variantId))
    }

    @objc(getExperimentConfig:key:resolve:reject:)
    public func getExperimentConfig(_ experimentId: String, key: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(AppdnaJSON.encode(AppDNA.getExperimentConfig(experimentId: experimentId, key: key)))
    }

    /// Native returns `(experimentId, variant)` tuples; the wire shape is `Object[]`.
    @objc(getExperimentExposures:reject:)
    public func getExperimentExposures(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        let exposures = AppDNA.experiments.getExposures().map {
            ["experimentId": $0.experimentId, "variant": $0.variant]
        }
        resolve(exposures)
    }

    // MARK: - Onboarding / paywall / surveys / messages

    /// ⚠ No `context:` argument. It had one, the ObjC++ adapter forwarded it, and this method never
    /// read it: `AppDNA.presentOnboarding(flowId:)` takes no context, and the SDK's own
    /// `OnboardingModule.present(flowId:from:context:)` discards the argument it is handed
    /// (AppDNA+Modules.swift). A host that passed `experimentOverrides` got a silent no-op. Removed
    /// from the IR (`sdk-methods.ts`) rather than left as a parameter that does nothing.
    @objc(presentOnboarding:resolve:reject:)
    public func presentOnboarding(
        _ flowId: String,
        resolve: @escaping RCTPromiseResolveBlock,
        reject: @escaping RCTPromiseRejectBlock
    ) {
        DispatchQueue.main.async {
            // Resolves false when no view controller is available. Report it rather than discarding
            // it — a silent no-op is how "the SDK does nothing" gets filed as a bug.
            resolve(AppDNA.presentOnboarding(flowId: flowId))
        }
    }

    /// 🔴 RESOLVES A BOOLEAN — `false` means nothing was presented.
    ///
    /// This used to resolve SUCCESSFULLY whatever happened: an unknown paywall id, an unconfigured
    /// SDK, a runtime-locked SDK. `await AppDNA.paywall.present('typo_id')` reported success and no
    /// paywall ever appeared. `presentOnboarding` above has always reported this honestly.
    ///
    /// And the missing-host case no longer forks per platform: this rejected `NO_VIEW_CONTROLLER`
    /// while Android rejected `NO_ACTIVITY` for the SAME condition, so a host had to branch on
    /// `Platform.OS` to catch its own error. Both now resolve `false` — the answer they already give
    /// for every other reason the paywall did not appear.
    @objc(presentPaywall:context:resolve:reject:)
    public func presentPaywall(
        _ paywallId: String,
        context: NSDictionary?,
        resolve: @escaping RCTPromiseResolveBlock,
        reject: @escaping RCTPromiseRejectBlock
    ) {
        DispatchQueue.main.async {
            guard let top = AppDNA.topViewController() else { return resolve(false) }
            resolve(AppDNA.presentPaywall(
                id: paywallId,
                from: top,
                context: self.parsePaywallContext(context as? [String: Any], fallbackPlacement: "")
            ))
        }
    }

    /// N17 — an overload on iOS, a distinct name on Android. The wrapper exposes one name.
    @objc(presentPaywallByPlacement:context:resolve:reject:)
    public func presentPaywallByPlacement(
        _ placement: String,
        context: NSDictionary?,
        resolve: @escaping RCTPromiseResolveBlock,
        reject: @escaping RCTPromiseRejectBlock
    ) {
        DispatchQueue.main.async {
            guard let top = AppDNA.topViewController() else { return resolve(false) }
            resolve(AppDNA.presentPaywall(
                placement: placement,
                from: top,
                context: self.parsePaywallContext(context as? [String: Any], fallbackPlacement: placement)
            ))
        }
    }

    @objc(presentSurvey:resolve:reject:)
    public func presentSurvey(_ surveyId: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        AppDNA.surveys.present(surveyId)
        resolve(nil)
    }

    // MARK: - Session data / traits / location (P8)
    //
    // Both natives have shipped these all along; RN never wrapped them, which is why the docs
    // described methods that did not exist. Values cross as JSON (E2) — native takes `Any`, and
    // there is no codegen type for "any JSON value".

    @objc(setSessionData:valueJson:resolve:reject:)
    public func setSessionData(_ key: String, valueJson: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let value = AppdnaJSON.decode(valueJson) else {
            // Native's signature takes a non-optional `Any`; "store null" is not an operation either
            // SDK exposes. Refusing loudly beats storing a sentinel the host can never distinguish.
            reject("INVALID_VALUE", "setSessionData requires a non-null JSON value", nil)
            return
        }
        AppDNA.setSessionData(key: key, value: value)
        resolve(nil)
    }

    @objc(getSessionData:resolve:reject:)
    public func getSessionData(_ key: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(AppdnaJSON.encode(AppDNA.getSessionData(key: key)))
    }

    @objc(clearSessionData:reject:)
    public func clearSessionData(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        AppDNA.clearSessionData()
        resolve(nil)
    }

    @objc(getUserTraits:reject:)
    public func getUserTraits(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(AppdnaJSON.encode(AppDNA.getUserTraits()))
    }

    @objc(getLocationData:resolve:reject:)
    public func getLocationData(_ fieldId: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        let loc = AppDNA.getLocationData(fieldId: fieldId)
        resolve(AppdnaJSON.encode(loc.map(AppdnaMappers.map)))
    }

    // MARK: - Screens (P8 — the 9th delegate)
    //
    // §18.6 excluded AppDNAScreenDelegate because "RN has no screen surface until P4 lands
    // AppDNAScreenSlot". P4 landed it — but the slot is INLINE and raises nothing: the screen
    // delegate's observe events are fired by `ScreenManager`, the PRESENTED-screen path. RN could not
    // present a screen at all while iOS and Android both shipped the identical surface. That is what
    // the docs have been describing all along.
    //
    // The RESULT is delivered to `onScreenDismissed` / `onFlowCompleted` (events), not to the
    // promise: a screen can be dismissed long after the call settles, so a promise cannot carry it.

    // The Bool these resolve is the CONTRACT (sdk-methods.ts): false = there was nothing to present
    // from. Android returns the real answer (no `currentActivity` -> false); iOS used to hard-code
    // `true`, so `if (!(await screens.show(id))) showFallback()` ran the fallback on Android and
    // never on iOS — including when the screen genuinely did not appear. A promise that always
    // resolves the same value is not a result, it is a decoration.

    @objc(showScreen:resolve:reject:)
    public func showScreen(_ screenId: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        DispatchQueue.main.async {
            guard AppDNA.topViewController() != nil else {
                resolve(false)
                return
            }
            AppDNA.showScreen(screenId)
            resolve(true)
        }
    }

    @objc(showFlow:resolve:reject:)
    public func showFlow(_ flowId: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        DispatchQueue.main.async {
            guard AppDNA.topViewController() != nil else {
                resolve(false)
                return
            }
            AppDNA.showFlow(flowId)
            resolve(true)
        }
    }

    @objc(dismissScreen:reject:)
    public func dismissScreen(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        DispatchQueue.main.async {
            AppDNA.dismissScreen()
            resolve(nil)
        }
    }

    @objc(previewScreen:resolve:reject:)
    public func previewScreen(_ json: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        DispatchQueue.main.async {
            // Resolve `false` when there is no host to present into — matching `showScreen`/`showFlow`
            // above (:439/:451) and Android's `currentActivity ?: resolve(false)`. Native
            // `previewScreen` returns `true` for any parseable JSON regardless of a top VC, so without
            // this guard iOS resolved `true` while Android resolved `false` for the same "valid JSON,
            // no host" input — a console-preview caller checking the boolean saw success on iOS and
            // failure on Android.
            guard AppDNA.topViewController() != nil else {
                resolve(false)
                return
            }
            // Forward native's real answer: false when the JSON did not parse. Resolving `true`
            // unconditionally told a console-preview caller its malformed payload had rendered.
            resolve(AppDNA.previewScreen(json: json))
        }
    }

    @objc(enableNavigationInterception:resolve:reject:)
    public func enableNavigationInterception(_ screens: NSArray?, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        // `nil` means intercept EVERY screen — not "intercept none". Coercing a missing list to `[]`
        // would silently mean the opposite of what the host asked for.
        let list = screens?.compactMap { $0 as? String }
        AppDNA.enableNavigationInterception(forScreens: list)
        resolve(nil)
    }

    @objc(disableNavigationInterception:reject:)
    public func disableNavigationInterception(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        AppDNA.disableNavigationInterception()
        resolve(nil)
    }

    @objc(suppressMessages:)
    public func suppressMessages(_ suppress: Bool) {
        AppDNA.inAppMessages.suppressDisplay(suppress)
    }

    // MARK: - Billing

    /// - Parameter offerToken: a Google Play concept. StoreKit has no equivalent, so iOS ignores it
    ///   and says so rather than silently dropping a parameter the host believes is honored.
    @objc(purchase:offerToken:resolve:reject:)
    public func purchase(
        _ productId: String,
        offerToken: NSString?,
        resolve: @escaping RCTPromiseResolveBlock,
        reject: @escaping RCTPromiseRejectBlock
    ) {
        if offerToken != nil {
            NSLog("[AppDNA] offerToken is a Google Play Billing concept and is ignored on iOS.")
        }
        Task {
            do {
                let result = try await AppDNA.billing.purchase(productId)
                resolve(AppdnaMappers.map(result))
            } catch {
                // 🔴 The CODE is the SDK's own `billingErrorType` discriminator — `userCancelled`,
                // `pending`, `productNotFound`, `verificationFailed`, `networkError`, `serverError`,
                // `providerNotAvailable`, `unknown` — not one blanket `PURCHASE_ERROR`.
                //
                // Every failure used to arrive as `PURCHASE_ERROR` with a LOCALIZED message, so a host
                // that wanted to do the one thing every store app does — stay silent when the user taps
                // Cancel, offer a retry when the card is declined — had to regex English prose on a
                // device that might be in Japanese. The discriminator has existed the whole time and is
                // already handed to `onPaywallPurchaseFailed(errorType:)`.
                //
                // Passed through VERBATIM, with no translation table: a table is a thing that can fork,
                // and Android emits these same strings.
                reject(billingErrorType(error), error.localizedDescription, error)
            }
        }
    }

    @objc(restorePurchases:reject:)
    public func restorePurchases(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        Task {
            do {
                // `[String]` — restored product ids, NOT entitlements.
                let productIds: [String] = try await AppDNA.billing.restorePurchases()
                resolve(productIds)
            } catch {
                reject("RESTORE_ERROR", error.localizedDescription, error)
            }
        }
    }

    @objc(getProducts:resolve:reject:)
    public func getProducts(_ productIds: NSArray, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        let ids = productIds.compactMap { $0 as? String }
        Task {
            do {
                let products = try await AppDNA.billing.getProducts(ids)
                resolve(products.map(AppdnaMappers.map))
            } catch {
                reject("PRODUCTS_ERROR", error.localizedDescription, error)
            }
        }
    }

    @objc(hasActiveSubscription:reject:)
    public func hasActiveSubscription(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        Task { resolve(await AppDNA.billing.hasActiveSubscription()) }
    }

    @objc(getEntitlements:reject:)
    public func getEntitlements(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        Task { resolve(await AppDNA.billing.getEntitlements().map(AppdnaMappers.map)) }
    }

    /// Idempotent — re-arming must REPLACE the handler, never add a second one.
    ///
    /// `AppDNA.billing.onEntitlementsChanged` APPENDS into a token→handler dictionary, and this used
    /// to store the new token straight over the old one: the previous handler stayed registered with
    /// nothing left that could remove it. `AppDNAScreenSlot`'s sibling latch in `src/index.ts`
    /// (`resetEntitlementObserver()` on `shutdown()`) deliberately re-arms so the next subscriber
    /// re-sends this call — so `configure → shutdown → configure` registered a SECOND handler while
    /// the first was still live, and every entitlement change from then on emitted
    /// `onEntitlementsChanged` twice. N cycles, N duplicate grants per purchase.
    ///
    /// This comment used to add "(Android never had this: its `shutdown()` nulls the billing manager,
    /// taking its listeners with it.)" — a claim about Android, asserted in a Swift file, and only
    /// true ACROSS A SHUTDOWN. A plain re-subscribe (re-mount, Fast Refresh) stacked listeners there
    /// too. Android is idempotent now for the same reason this is.
    @objc(startEntitlementObserver:reject:)
    public func startEntitlementObserver(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        if let token = entitlementObserverToken {
            AppDNA.billing.removeEntitlementsChangedHandler(token)
            entitlementObserverToken = nil
        }
        entitlementObserverToken = AppDNA.billing.onEntitlementsChanged { [weak self] entitlements in
            self?.emit("onEntitlementsChanged", ["entitlements": entitlements.map(AppdnaMappers.map)])
        }
        resolve(nil)
    }

    // MARK: - Push

    @objc(requestPushPermission:reject:)
    public func requestPushPermission(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        Task { resolve(await AppDNA.pushModule.requestPermission()) }
    }

    @objc(getPushToken:reject:)
    public func getPushToken(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(AppdnaJSON.encode(AppDNA.pushModule.getToken()))
    }

    /// N9 — a hex-encoded APNs `Data` here; the FCM string on Android. One signature, two meanings.
    @objc(setPushToken:resolve:reject:)
    public func setPushToken(_ token: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let data = Self.hexStringToData(token) else {
            return reject("BAD_TOKEN", "setPushToken expects a hex-encoded APNs device token on iOS", nil)
        }
        AppDNA.setPushToken(data)
        resolve(nil)
    }

    @objc(setPushPermission:resolve:reject:)
    public func setPushPermission(_ granted: Bool, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        AppDNA.setPushPermission(granted: granted)
        resolve(nil)
    }

    @objc(trackPushDelivered:resolve:reject:)
    public func trackPushDelivered(_ pushId: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        AppDNA.trackPushDelivered(pushId: pushId)
        resolve(nil)
    }

    @objc(trackPushTapped:action:resolve:reject:)
    public func trackPushTapped(_ pushId: String, action: NSString?, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        AppDNA.trackPushTapped(pushId: pushId, action: action as String?)
        resolve(nil)
    }

    // MARK: - Deep links / web entitlements

    /// N15 — a `URL` here, a `String` on Android.
    @objc(handleDeepLink:resolve:reject:)
    public func handleDeepLink(_ url: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        // Resolve-don't-fork (the same cross-platform reconciliation already applied to presentPaywall's
        // NO_VIEW_CONTROLLER/NO_ACTIVITY fork): Android's handleDeepLink never validates and ALWAYS
        // resolves, and `handleURL` is called fire-and-forget — so a string that `URL(string:)` refuses
        // must NOT become an iOS-only promise rejection (an unhandled rejection / redbox on iOS while
        // Android no-ops for the identical input). Drop the unparseable string and resolve, matching
        // Android's observable outcome; the host never has to branch on `Platform.OS`.
        //
        // ⚠ Recorded native-shape asymmetry (edge-case only): for a string `URL(string:)` refuses but
        // Android's SDK would still take, the SIDE EFFECT differs — Android forwards the raw String to
        // its String-typed core (firing `onDeepLinkReceived` + the allowlist), while iOS cannot build a
        // `URL` to hand its URL-typed core, so it drops it. `URL(string:)` is lenient, so this bites only
        // genuinely malformed links that route to nothing useful anyway; it is not fixable in the wrapper
        // without an iOS core that accepts a raw String.
        guard let parsed = URL(string: url) else {
            return resolve(nil)
        }
        AppDNA.deepLinks.handleURL(parsed)
        resolve(nil)
    }

    @objc(checkDeferredDeepLink:reject:)
    public func checkDeferredDeepLink(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        AppDNA.checkDeferredDeepLink { deepLink in
            resolve(AppdnaJSON.encode(deepLink?.toMap()))
        }
    }

    @objc(getWebEntitlement:reject:)
    public func getWebEntitlement(resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(AppdnaJSON.encode(AppDNA.webEntitlement?.toMap()))
    }

    // MARK: - Host-veto reply channel (P3 routes the hooks; this is the seam)

    @objc(respondToHostCallback:resultJson:)
    public func respondToHostCallback(_ callbackId: String, resultJson: String) {
        AppdnaHostCallbacks.shared.respond(callbackId: callbackId, resultJson: resultJson)
    }

    // MARK: - Delegates (P3)

    /**
     * Attach every forwarder to the native SDK.
     *
     * All of them, unconditionally, at `configure` — not lazily when JS subscribes. A TurboModule
     * emitter property gives native no subscribe signal, so there is nothing to be lazy about, and
     * emitting into zero listeners costs a dictionary that is immediately dropped.
     *
     * The three synchronous vetoes (`shouldShowMessage`, `shouldOpen`, `onScreenAction`) cannot await
     * a bridge round trip, so each goes on the SDK's parallel async seam, which `MessageManager` /
     * `DeepLinksModule` / `ScreenManager` consult in addition to the sync delegate method.
     *
     * - Parameter vetoTimeout: from `AppDNAOptions.vetoTimeout` — never a literal, per E7.
     */
    private func registerDelegates(vetoTimeout: TimeInterval) {
        let emit: AppdnaEmit = { [weak self] name, payload in self?.emit(name, payload) }
        let veto = AppdnaVetoInvoker(timeout: vetoTimeout) { [weak self] payload in
            self?.emit("onHostCallback", payload)
        }
        invoker = veto

        let onboarding = OnboardingForwarder(emit: emit, invoker: veto)
        let paywall = PaywallForwarder(emit: emit, invoker: veto)
        let survey = SurveyForwarder(emit: emit)
        let messages = InAppMessageForwarder(emit: emit)
        let push = PushForwarder(emit: emit)
        let billing = BillingForwarder(emit: emit)
        let deepLinks = DeepLinkForwarder(emit: emit)
        let initDelegate = InitForwarder(emit: emit)
        let lifecycle = LifecycleForwarder(emit: emit)
        // The 9th delegate. `AppDNA.screenDelegate` is WEAK, so it must be retained by `forwarders` —
        // otherwise it deallocates the instant registerDelegates returns and every screen event is
        // silently lost, which is precisely the dead-delegate class this spec exists to remove.
        let screen = ScreenForwarder(emit: emit)
        forwarders = [onboarding, paywall, survey, messages, push, billing, deepLinks, initDelegate, lifecycle, screen]

        AppDNA.onboarding.setDelegate(onboarding)
        AppDNA.paywall.setDelegate(paywall)
        AppDNA.surveys.setDelegate(survey)
        AppDNA.inAppMessages.setDelegate(messages)
        AppDNA.pushModule.setDelegate(push)
        AppDNA.billing.setDelegate(billing)
        AppDNA.deepLinks.setDelegate(deepLinks)
        AppDNA.initDelegate = initDelegate
        AppDNA.lifecycleDelegate = lifecycle
        AppDNA.screenDelegate = screen

        // 🔴 `shouldShowMessage` defaults to ALLOW on timeout; `onPromoCodeSubmit` to REJECT. A
        // uniform default here is how a paywall silently starts accepting unvalidated promo codes.
        AppDNA.inAppMessages.asyncShouldShowMessage = { messageId in
            await veto.invoke("shouldShowMessage", ["messageId": messageId]) as? Bool ?? true
        }
        AppDNA.deepLinks.asyncShouldOpen = { url, params in
            await veto.invoke("shouldOpen", ["url": url.absoluteString, "params": params]) as? Bool ?? true
        }
        AppDNA.asyncOnScreenAction = { screenId, action in
            await veto.invoke(
                "onScreenAction",
                ["screenId": screenId, "action": AppdnaMappers.map(action)]
            ) as? Bool ?? true
        }
    }

    // MARK: - Teardown (E6 / E11)

    /// Called from the adapter's `invalidate`. Every listener registered here lives on the
    /// process-global `AppDNA` singleton and captures this bridge-scoped object; a reload would
    /// otherwise leave them attached and deliver each event N-fold.
    @objc public func invalidate() {
        if let token = entitlementObserverToken {
            AppDNA.billing.removeEntitlementsChangedHandler(token)
            entitlementObserverToken = nil
        }
        if let token = webEntitlementObserverToken {
            AppDNA.removeWebEntitlementChangedHandler(token)
            webEntitlementObserverToken = nil
        }
        if let observer = configObserver {
            NotificationCenter.default.removeObserver(observer)
            configObserver = nil
        }

        // Every forwarder captures this bridge-scoped object. Leaving them attached to the
        // process-global singleton across a reload is what delivers each event N-fold.
        AppDNA.onboarding.setDelegate(nil)
        AppDNA.paywall.setDelegate(nil)
        AppDNA.surveys.setDelegate(nil)
        AppDNA.inAppMessages.setDelegate(nil)
        AppDNA.pushModule.setDelegate(nil)
        AppDNA.billing.setDelegate(nil)
        AppDNA.deepLinks.setDelegate(nil)
        AppDNA.initDelegate = nil
        AppDNA.lifecycleDelegate = nil
        AppDNA.screenDelegate = nil
        AppDNA.inAppMessages.asyncShouldShowMessage = nil
        AppDNA.deepLinks.asyncShouldOpen = nil
        AppDNA.asyncOnScreenAction = nil
        forwarders.removeAll()
        invoker = nil
        // A JS side that no longer exists will never answer a pending veto, and native awaits forever.
        AppdnaHostCallbacks.shared.invalidateAll()
        eventSink = nil
    }

    // MARK: - Helpers

    /// ⚠ `internal`, not `private`: `AppdnaParseOptionsTests` reaches it through `@testable import`
    /// (AC-11). A jest test cannot see a native `?? 3600`, and neither can a Dart one.
    /// The WRAPPER's own version (this package), not the native SDK's. Injected, never read from the
    /// host's options. Kept in lockstep with package.json by `check:wrapper-version-selfreport`.
    static let wrapperVersion = "1.0.15"

    internal func parseOptions(_ dict: [String: Any]?) -> AppDNAOptions {
        let values = dict ?? [:]
        let defaults = AppDNAOptions()

        let logLevel: LogLevel
        switch values["logLevel"] as? String {
        case "none": logLevel = .none
        case "error": logLevel = .error
        case "warning": logLevel = .warning
        case "info": logLevel = .info
        case "debug": logLevel = .debug
        default: logLevel = defaults.logLevel
        }

        // `billingProvider` crosses as a bare string, or as a tagged map for the associated-value
        // adapty case. A bare "adapty" carries no apiKey, so it is refused rather than keyless.
        let billingProvider: BillingProvider
        if let map = values["billingProvider"] as? [String: Any],
           map["type"] as? String == "adapty",
           let apiKey = map["apiKey"] as? String, !apiKey.isEmpty {
            billingProvider = .adapty(apiKey: apiKey)
        } else {
            switch values["billingProvider"] as? String {
            case "revenueCat": billingProvider = .revenueCat
            case "storeKit2": billingProvider = .storeKit2
            case "none": billingProvider = .none
            default: billingProvider = defaults.billingProvider
            }
        }

        return AppDNAOptions(
            // E7: never a literal. `?? 300` is how the wrappers drifted 12× off the native TTL.
            flushInterval: values["flushInterval"] as? TimeInterval ?? defaults.flushInterval,
            batchSize: values["batchSize"] as? Int ?? defaults.batchSize,
            configTTL: values["configTTL"] as? TimeInterval ?? defaults.configTTL,
            logLevel: logLevel,
            billingProvider: billingProvider,
            // §7 rule 1: injected unconditionally, NOT read from `values`. A host cannot spoof it.
            framework: Self.frameworkTag,
            frameworkVersion: Self.wrapperVersion,
            requireConsent: values["requireConsent"] as? Bool ?? defaults.requireConsent,
            vetoTimeout: values["vetoTimeout"] as? TimeInterval ?? defaults.vetoTimeout
        )
    }

    /// D-s — all four fields. `customData` reaches the `paywall_view` properties bag natively.
    ///
    /// 🔴 THIS USED TO DISCARD THE WHOLE CONTEXT WHEN `placement` WAS ABSENT.
    ///
    /// `PaywallContext.placement` is optional in TS (types.ts) and non-optional natively, and the
    /// wrapper "resolved" that mismatch with `guard let placement … else { return nil }` — so
    ///
    ///     AppDNA.paywall.presentByPlacement('home_upsell', { customData: { source: 'home' } })
    ///
    /// — the natural call, since placement is already argument #1 — threw away customData, experiment
    /// and variant, in silence. `customData` never reached the `paywall_view` properties bag, which is
    /// the entire point of the field. No error, no log, nothing to notice. That is the same defect as
    /// the experiment/variant dead surface documented in types.ts, one layer down: the type said a
    /// field was optional and the code treated it as mandatory.
    ///
    /// `fallbackPlacement` is what the caller already knows: the placement argument for
    /// `presentByPlacement`, and "" for `presentPaywall(id:)`, which has no placement by definition.
    private func parsePaywallContext(_ dict: [String: Any]?, fallbackPlacement: String) -> PaywallContext? {
        // 🔴 AND IT STILL DROPPED THE PLACEMENT FOR THE COMMONEST CALL OF ALL. The fix above covered
        // "a context with no placement"; it did not cover NO CONTEXT — and `presentByPlacement(
        // 'upgrade')` is exactly that, the argument being optional in TS. `nil` in, `nil` out, and
        // native's `paywall_view` then records `placement = context?.placement ?? "unknown"`. Every
        // by-placement paywall view from an RN host landed in BigQuery as `unknown`. The Android twin
        // was identical, and is where SharedFixtureBridgeTest caught it by driving the real native
        // path; this is the same fix, and it is UNVERIFIED here (no Swift toolchain in the Codespace).
        guard let dict else {
            return fallbackPlacement.isEmpty ? nil : PaywallContext(placement: fallbackPlacement)
        }
        return PaywallContext(
            placement: (dict["placement"] as? String) ?? fallbackPlacement,
            experiment: dict["experiment"] as? String,
            variant: dict["variant"] as? String,
            customData: dict["customData"] as? [String: Any]
        )
    }

    private static func hexStringToData(_ hex: String) -> Data? {
        guard hex.count % 2 == 0, !hex.isEmpty else { return nil }
        var data = Data(capacity: hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            data.append(byte)
            index = next
        }
        return data
    }
}
