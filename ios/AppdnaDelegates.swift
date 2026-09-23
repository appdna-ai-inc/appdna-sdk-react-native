import Foundation
import AppDNASDK

/**
 * SPEC-070-B P3 — the native→JS delegate forwarders (iOS).
 *
 * Every one of the ~30 SDK callbacks used to have a JS listener and no native emitter. The facade
 * subscribed; nothing ever fired. These classes are the missing half.
 *
 * ## Observe vs veto
 *
 * An **observe** callback is one-way: it becomes an `onXxx` TurboModule event and native returns
 * immediately. A **veto** callback has a return value native waits for, and there are eight of them.
 * Those go through `AppdnaVetoInvoker`, which emits `onHostCallback` and awaits
 * `respondToHostCallback` under a timeout.
 *
 * ## Why the synchronous vetoes do not block
 *
 * The four onboarding hooks are `async` on the native protocol, so awaiting is free. The other four
 * are synchronous — `shouldShowMessage(messageId:) -> Bool` cannot await anything. The SDK therefore
 * exposes a parallel **async seam** for each (`asyncShouldShowMessage`, `asyncShouldOpen`,
 * `asyncOnScreenAction`, and `onPromoCodeSubmit`'s completion handler), consulted in ADDITION to the
 * synchronous delegate method. The sync forwarders below return the permissive answer and defer the
 * real decision to the async seam — the seam the core SDK grew in SPEC-070-C precisely so that a
 * wrapper could answer a veto over a bridge.
 */

/// Emit an event to JS. Implemented by `AppdnaModuleImpl` over its ObjC++ event sink.
typealias AppdnaEmit = (String, [String: Any]) -> Void

// MARK: - Onboarding

final class OnboardingForwarder: NSObject, AppDNAOnboardingDelegate {

    private let emit: AppdnaEmit
    private let invoker: AppdnaVetoInvoker

    init(emit: @escaping AppdnaEmit, invoker: AppdnaVetoInvoker) {
        self.emit = emit
        self.invoker = invoker
    }

    func onOnboardingStarted(flowId: String) {
        emit("onOnboardingStarted", ["flowId": flowId])
    }

    func onOnboardingStepChanged(flowId: String, stepId: String, stepIndex: Int, totalSteps: Int) {
        emit("onOnboardingStepChanged", [
            "flowId": flowId, "stepId": stepId, "stepIndex": stepIndex, "totalSteps": totalSteps,
        ])
    }

    func onOnboardingCompleted(flowId: String, responses: [String: Any]) {
        emit("onOnboardingCompleted", ["flowId": flowId, "responses": responses])
    }

    func onOnboardingDismissed(flowId: String, atStep: Int) {
        emit("onOnboardingDismissed", ["flowId": flowId, "atStep": atStep])
    }

    func onPermissionResult(flowId: String, stepId: String, permissionType: String, granted: Bool) {
        emit("onPermissionResult", [
            "flowId": flowId, "stepId": stepId, "permissionType": permissionType, "granted": granted,
        ])
    }

    // MARK: The four async hooks

    func onBeforeStepAdvance(
        flowId: String,
        fromStepId: String,
        stepIndex: Int,
        stepType: String,
        responses: [String: Any],
        stepData: [String: Any]?
    ) async -> StepAdvanceResult {
        var args: [String: Any] = [
            "flowId": flowId,
            "fromStepId": fromStepId,
            "stepIndex": stepIndex,
            "stepType": stepType,
            "responses": responses,
        ]
        if let stepData { args["stepData"] = stepData }
        let reply = await invoker.invoke("onBeforeStepAdvance", args)

        // Native gates the AUTH actions on delegate presence — no delegate means nobody can sign the
        // user in, so it stays on the step and shows an error. But this wrapper ALWAYS attaches a
        // delegate at configure() (native emits during configure, so it must), and a JS host with no
        // `onBeforeStepAdvance` used to get `.proceed` — ADVANCING PAST THE CREDENTIAL STEP WITHOUT
        // AUTHENTICATING ANYONE, while a native host stayed put. Delegate-presence is a proxy for
        // "will someone handle this"; for a wrapper the proxy lies, so ask JS instead.
        // 🔴 AN AUTH ACTION MAY ONLY ADVANCE ON AN EXPLICIT HOST DECISION.
        //
        // This used to block on `isUnhandled` ALONE — the "no handler registered" sentinel. But there
        // are three other ways JS declines to answer, and every one of them replied `"null"` (= "no
        // opinion, apply your default"), which decodes to `.proceed`:
        //
        //   1. the handler THREW (hostCallbacks.ts catches and answers NO_OPINION);
        //   2. its promise REJECTED — i.e. the host's sign-in call failed: backend 500, no network;
        //   3. it exceeded `vetoTimeout` (default 5s) — i.e. a SLOW auth backend.
        //
        // So the fix that stopped a no-handler host advancing past a credential step did nothing for
        // a host WITH a handler whose auth call fails or is slow. The user taps "Continue with email",
        // the sign-in errors, and the SDK walks them into the app. A failing backend was free entry.
        //
        // The wrapper's own comment said native's default is "the conservative answer for each hook —
        // reject for a promo code, allow for the rest". For a CREDENTIAL step, allow is not
        // conservative. Nobody authenticated.
        //
        // `.proceed` remains available to a host that means it: `{"type":"proceed"}` is an explicit
        // answer and decodes as one. Silence is not.
        //
        // 🔴 AND THERE WAS A FOURTH WAY TO SAY NOTHING: `{}`.
        //
        // Blocking on `isUnhandled || isNoOpinion` enumerated the ways of declining — and missed one.
        // `isNoOpinion` means "the reply is not a map", so a reply that IS a map with no `type` (`{}`,
        // `{"ok":true}`, a handler returning its own result object) satisfied neither guard, fell into
        // `stepAdvanceResult`'s `?? "proceed"` default, and ADVANCED the user unauthenticated.
        //
        // Enumerating silence is a losing game; there is always one more way to be silent. So this now
        // demands a POSITIVE, RECOGNISED decision instead. `{"type":"proceed"}` still advances — a host
        // that means it can still say so.
        if AppdnaAuthActions.isAuthAction(stepData),
           !AppdnaVetoDecoder.isExplicitDecision(reply) {
            return .block(message: AppdnaAuthActions.unavailableMessage)
        }
        return AppdnaVetoDecoder.stepAdvanceResult(reply)
    }

    func onBeforeStepRender(
        flowId: String,
        stepId: String,
        stepIndex: Int,
        stepType: String,
        responses: [String: Any]
    ) async -> StepConfigOverride? {
        let reply = await invoker.invoke("onBeforeStepRender", [
            "flowId": flowId,
            "stepId": stepId,
            "stepIndex": stepIndex,
            "stepType": stepType,
            "responses": responses,
        ])
        return AppdnaVetoDecoder.stepConfigOverride(reply)
    }

    func onElementInteraction(
        flowId: String,
        stepId: String,
        blockId: String,
        action: String,
        value: String?,
        inputValues: [String: Any]
    ) async -> ElementInteractionResult? {
        var args: [String: Any] = [
            "flowId": flowId,
            "stepId": stepId,
            "blockId": blockId,
            "action": action,
            "inputValues": inputValues,
        ]
        if let value { args["value"] = value }
        return AppdnaVetoDecoder.elementInteractionResult(await invoker.invoke("onElementInteraction", args))
    }

    func onPermissionRequest(_ permissionType: String) async -> PermissionHandling? {
        let reply = await invoker.invoke("onPermissionRequest", ["permissionType": permissionType])
        return AppdnaVetoDecoder.permissionHandling(reply)
    }
}

// MARK: - Paywall

final class PaywallForwarder: NSObject, AppDNAPaywallDelegate {

    private let emit: AppdnaEmit
    private let invoker: AppdnaVetoInvoker

    init(emit: @escaping AppdnaEmit, invoker: AppdnaVetoInvoker) {
        self.emit = emit
        self.invoker = invoker
    }

    func onPaywallPresented(paywallId: String) {
        emit("onPaywallPresented", ["paywallId": paywallId])
    }

    func onPaywallAction(paywallId: String, action: PaywallAction) {
        emit("onPaywallAction", ["paywallId": paywallId, "action": action.rawValue])
    }

    func onPaywallPurchaseStarted(paywallId: String, productId: String) {
        emit("onPaywallPurchaseStarted", ["paywallId": paywallId, "productId": productId])
    }

    func onPaywallPurchaseCompleted(paywallId: String, productId: String, transaction: TransactionInfo) {
        emit("onPaywallPurchaseCompleted", [
            "paywallId": paywallId, "productId": productId, "transaction": AppdnaMappers.map(transaction),
        ])
    }

    // 🔴 Override the WIDEST overload — see the Kotlin twin. Implementing only the 2-arg form still
    // fires (the protocol default chains down to it) while silently dropping `errorType` + `productId`.
    func onPaywallPurchaseFailed(
        paywallId: String,
        error: Error,
        errorType: String,
        productId: String?
    ) {
        emit("onPaywallPurchaseFailed", [
            "paywallId": paywallId,
            "error": error.localizedDescription,
            "errorType": errorType,
            // `?? NSNull()`, NOT `as Any`: for a product-less failure (networkError/serverError/unknown
            // surfaced before a product resolves), `productId as Any` boxes `Optional.none` as a
            // `_SwiftValue` that the RCTTurboModule bridge cannot represent → the host reads `undefined`
            // on iOS while Android sends `null` (`putNull`). The delegate contract is `productId: string
            // | null` (never undefined); this matches the `withNulls`/`last_action` convention.
            "productId": productId ?? NSNull(),
        ])
    }

    func onPaywallDismissed(paywallId: String) {
        emit("onPaywallDismissed", ["paywallId": paywallId])
    }

    func onPaywallRestoreStarted(paywallId: String) {
        emit("onPaywallRestoreStarted", ["paywallId": paywallId])
    }

    func onPaywallRestoreCompleted(paywallId: String, productIds: [String]) {
        emit("onPaywallRestoreCompleted", ["paywallId": paywallId, "restoredProductIds": productIds])
    }

    func onPaywallRestoreFailed(paywallId: String, error: Error) {
        emit("onPaywallRestoreFailed", ["paywallId": paywallId, "error": error.localizedDescription])
    }

    func onPostPurchaseDeepLink(paywallId: String, url: String) {
        emit("onPostPurchaseDeepLink", ["paywallId": paywallId, "url": url])
    }

    func onPostPurchaseNextStep(paywallId: String) {
        emit("onPostPurchaseNextStep", ["paywallId": paywallId])
    }

    /**
     * 🔴 The one hook whose default is **reject**.
     *
     * A timeout, an unregistered hook, or a saturated pending map all mean "the host did not validate
     * this code", and the only safe reading of that is *invalid*. Defaulting to `true` here is how a
     * paywall starts honouring any string a user types — the live defect this wrapper exists to not
     * repeat.
     */
    func onPromoCodeSubmit(paywallId: String, code: String, completion: @escaping (Bool) -> Void) {
        Task {
            let reply = await invoker.invoke("onPromoCodeSubmit", ["paywallId": paywallId, "code": code])
            // The paywall's promo field re-renders from this callback. Android's forwarder answers on
            // `Dispatchers.Main`; iOS has to say so explicitly.
            await MainActor.run { completion(reply as? Bool ?? false) }
        }
    }
}

// MARK: - Surveys, messages, push, billing, deep links, init

/**
 The 9th delegate (P8). `onScreenAction` is a VETO and rides `AppDNA.asyncOnScreenAction` (the
 host-callback seam), exactly as §18.6 ruled — but the protocol still requires it, so it returns
 `true` here: the async seam is the one that actually asks the host, and answering `false` from both
 would let the two answers disagree about the same action.
 */
final class ScreenForwarder: NSObject, AppDNAScreenDelegate {
    private let emit: AppdnaEmit
    init(emit: @escaping AppdnaEmit) { self.emit = emit }

    func onScreenPresented(screenId: String) {
        emit("onScreenPresented", ["screenId": screenId])
    }

    func onScreenDismissed(screenId: String, result: ScreenResult) {
        emit("onScreenDismissed", ["screenId": screenId, "result": AppdnaMappers.map(result)])
    }

    func onFlowCompleted(flowId: String, result: FlowResult) {
        emit("onFlowCompleted", ["flowId": flowId, "result": AppdnaMappers.map(result)])
    }

    /// Not the host's answer — `AppDNA.asyncOnScreenAction` is. Allow, and let the async seam veto.
    func onScreenAction(screenId: String, action: SectionAction) -> Bool { true }
}

final class SurveyForwarder: NSObject, AppDNASurveyDelegate {
    private let emit: AppdnaEmit
    init(emit: @escaping AppdnaEmit) { self.emit = emit }

    func onSurveyPresented(surveyId: String) {
        emit("onSurveyPresented", ["surveyId": surveyId])
    }

    /// `[{questionId, answer}]` — not Flutter's orphan `SurveyResult`, which native never emitted.
    func onSurveyCompleted(surveyId: String, responses: [SurveyResponse]) {
        emit("onSurveyCompleted", ["surveyId": surveyId, "responses": responses.map(AppdnaMappers.map)])
    }

    func onSurveyDismissed(surveyId: String) {
        emit("onSurveyDismissed", ["surveyId": surveyId])
    }
}

final class InAppMessageForwarder: NSObject, AppDNAInAppMessageDelegate {
    private let emit: AppdnaEmit
    init(emit: @escaping AppdnaEmit) { self.emit = emit }

    func onMessageShown(messageId: String, trigger: String) {
        emit("onMessageShown", ["messageId": messageId, "trigger": trigger])
    }

    func onMessageAction(messageId: String, action: String, data: [String: Any]?) {
        var payload: [String: Any] = ["messageId": messageId, "action": action]
        if let data { payload["data"] = data }
        emit("onMessageAction", payload)
    }

    func onMessageDismissed(messageId: String) {
        emit("onMessageDismissed", ["messageId": messageId])
    }

    /// The sync veto cannot await a bridge round trip, so it allows and defers to
    /// `AppDNA.inAppMessages.asyncShouldShowMessage`, which `MessageManager` consults as well. Both
    /// can suppress; only one of them can wait.
    func shouldShowMessage(messageId: String) -> Bool { true }
}

final class PushForwarder: NSObject, AppDNAPushDelegate {
    private let emit: AppdnaEmit
    init(emit: @escaping AppdnaEmit) { self.emit = emit }

    func onPushTokenRegistered(token: String) {
        emit("onPushTokenRegistered", ["token": token])
    }

    func onPushReceived(notification: PushPayload, inForeground: Bool) {
        emit("onPushReceived", ["payload": AppdnaMappers.map(notification), "inForeground": inForeground])
    }

    func onPushTapped(notification: PushPayload, actionId: String?) {
        var payload: [String: Any] = ["payload": AppdnaMappers.map(notification)]
        if let actionId { payload["actionId"] = actionId }
        emit("onPushTapped", payload)
    }
}

final class BillingForwarder: NSObject, AppDNABillingDelegate {
    private let emit: AppdnaEmit
    init(emit: @escaping AppdnaEmit) { self.emit = emit }

    func onPurchaseCompleted(productId: String, transaction: TransactionInfo) {
        emit("onPurchaseCompleted", ["productId": productId, "transaction": AppdnaMappers.map(transaction)])
    }

    func onPurchaseFailed(productId: String, error: Error) {
        emit("onPurchaseFailed", ["productId": productId, "error": error.localizedDescription])
    }

    /// ⚠ `onEntitlementsChanged` is deliberately NOT implemented here.
    ///
    /// The SDK fans entitlement changes out through two independent paths: this delegate method
    /// (fired by `RevenueCatBridge` on Android, and by nothing at all on iOS) and the explicit
    /// observer behind `AppDNA.billing.onEntitlementsChanged(_:)` (live on both). Implementing both
    /// would deliver every change twice to a RevenueCat-configured Android host.
    ///
    /// `startEntitlementObserver()` is the single source, because it is the only one that fires on
    /// both platforms. The protocol's default no-op keeps this class conforming.
    func onRestoreCompleted(restoredProducts: [String]) {
        emit("onRestoreCompleted", ["restoredProducts": restoredProducts])
    }

    // N8 — `onBillingUnavailable` is Android-only. iOS's protocol has no such method, and inventing
    // one that never fires would be worse than the honest asymmetry the facade documents.
}

final class DeepLinkForwarder: NSObject, AppDNADeepLinkDelegate {
    private let emit: AppdnaEmit
    init(emit: @escaping AppdnaEmit) { self.emit = emit }

    func onDeepLinkReceived(url: URL, params: [String: String]) {
        emit("onDeepLinkReceived", ["url": url.absoluteString, "params": params])
    }
}

final class InitForwarder: NSObject, AppDNAInitDelegate {
    private let emit: AppdnaEmit
    init(emit: @escaping AppdnaEmit) { self.emit = emit }

    func onInitDegraded(reason: Error) {
        emit("onInitDegraded", [
            "type": String(describing: type(of: reason)),
            "message": reason.localizedDescription,
        ])
    }
}

final class LifecycleForwarder: NSObject, AppDNALifecycleDelegate {
    private let emit: AppdnaEmit
    init(emit: @escaping AppdnaEmit) { self.emit = emit }

    func onSdkRuntimeLocked(reason: String, lockedAt: String) {
        emit("onSdkRuntimeLocked", ["reason": reason, "lockedAt": lockedAt])
    }

    func onSdkRuntimeUnlocked() {
        emit("onSdkRuntimeUnlocked", [:])
    }
}

// MARK: - Reply decoding

/**
 * JS reply → the concrete native return type. Every unknown or missing shape falls back to the SDK's
 * own default, which is exactly what a host that never registered the hook would get.
 *
 * The wire shapes are identical to Flutter's, so a host porting a veto handler between the two SDKs
 * does not have to relearn them.
 */
/// The actions that need the HOST to do something the SDK cannot: sign in, register, send an OTP.
/// Mirrors `AuthActionPolicy.delegateRequiredActions` in the core renderer.
enum AppdnaAuthActions {
    /// 🔴 `social_login` was MISSING from this set, on both wrappers, and it is the reason
    /// `check:auth-action-parity` now exists (a comment claimed that gate kept these in step; the
    /// gate did not exist). The core requires a delegate for 16 actions; this listed 15. So an RN
    /// host with no `onBeforeStepAdvance` handler had "Continue with Google" ADVANCE THE FLOW with
    /// nobody authenticated — while a native host in the same situation stayed on the step.
    static let all: Set<String> = [
        "social_login",
        "login", "register", "reset_password", "magic_link", "verify_email", "resend_verification",
        "enable_biometric", "email_login", "request_otp", "verify_otp", "logout", "change_password",
        "set_new_password", "delete_account", "update_profile",
    ]

    /// The same copy native shows. A dead button is worse than a refusal; say why nothing happened.
    static let unavailableMessage = "Sign-in isn't available right now. Please try again later."

    static func isAuthAction(_ stepData: [String: Any]?) -> Bool {
        guard let action = stepData?["action"] as? String else { return false }
        return all.contains(action)
    }
}

enum AppdnaVetoDecoder {
    /// Did JS reply "I have no handler for this hook"? See `UNHANDLED` in hostCallbacks.ts.
    static func isUnhandled(_ reply: Any?) -> Bool {
        (reply as? [String: Any])?["__appdna_unhandled"] as? Bool == true
    }

    /// The host DECLINED TO DECIDE: `"null"` — the wire form of "no opinion, apply your default".
    ///
    /// JS sends this when a registered handler throws or its promise rejects (`hostCallbacks.ts`
    /// catches both), and native synthesises it when the veto exceeds `vetoTimeout`. For most hooks
    /// "apply your default" is exactly right. For an AUTH action it means nobody authenticated, and
    /// the default (advance) is the one answer that must not be given.
    static func isNoOpinion(_ reply: Any?) -> Bool {
        reply == nil || reply is NSNull || !(reply is [String: Any])
    }

    /// The decision types `stepAdvanceResult` actually understands.
    static let knownDecisions: Set<String> = [
        "proceed", "proceedWithData", "block", "skipTo", "skipToWithData", "stay",
    ]

    /// Did the host make an EXPLICIT, RECOGNISED decision?
    ///
    /// 🔴 `{}` IS A DICTIONARY, SO IT WAS NEITHER "unhandled" NOR "no opinion" — AND IT ADVANCED.
    ///
    /// The auth gate blocked on `isUnhandled || isNoOpinion`. `isNoOpinion` is "the reply is not a
    /// map", so a reply that IS a map but carries no `type` — `{}`, or `{"ok": true}`, or a handler
    /// that returns its own result object by mistake — passed both checks. `stepAdvanceResult` then
    /// read `(map["type"] as? String) ?? "proceed"` and **proceeded**: the user walked past the
    /// credential step with nobody having authenticated them.
    ///
    /// A returned-but-shapeless object is not a decision; it is a host that has not answered the
    /// question. On an auth action there is exactly one safe reading of "did not answer", and it is
    /// not "let them in". So the gate now demands a POSITIVE answer rather than enumerating the ways
    /// of saying nothing — there is always one more way of saying nothing.
    static func isExplicitDecision(_ reply: Any?) -> Bool {
        guard let map = reply as? [String: Any] else { return false }
        if map["__appdna_unhandled"] as? Bool == true { return false }
        guard let type = map["type"] as? String else { return false }
        return knownDecisions.contains(type)
    }


    /// `{type:"proceed"|"proceedWithData"|"block"|"skipTo"|"stay", …}` → default `.proceed`.
    ///
    /// `skipToWithData` is an ACCEPTED ALIAS of `skipTo`, not a second encoding: the canonical wire
    /// shape is `{type:"skipTo", stepId, data?}`, and `data` is what promotes it to
    /// `.skipToWithData`. The alias exists because the published docs named `skipToWithData` for a
    /// case no decoder had — so a host that followed them fell to `default` and the user SILENTLY
    /// ADVANCED to the next step instead of skipping. A mis-route with no error and no log is worse
    /// than a rejection; accepting the string those hosts already send costs one line.
    static func stepAdvanceResult(_ reply: Any?) -> StepAdvanceResult {
        guard let map = reply as? [String: Any] else { return .proceed }
        switch (map["type"] as? String) ?? "proceed" {
        case "proceedWithData":
            return .proceedWithData(anyMap(map["data"]) ?? [:])
        case "block":
            return .block(message: (map["message"] as? String) ?? "")
        case "skipTo", "skipToWithData":
            let stepId = (map["stepId"] as? String) ?? ""
            if let data = anyMap(map["data"]), !data.isEmpty {
                return .skipToWithData(stepId: stepId, data: data)
            }
            return .skipTo(stepId: stepId)
        case "stay":
            return .stay(message: map["message"] as? String)
        default:
            return .proceed
        }
    }

    /// map-or-null → `StepConfigOverride?`, field by field.
    ///
    /// 🔴 THE SENTINEL IS NOT AN OVERRIDE.
    ///
    /// `{"__appdna_unhandled":true}` IS a map, so `reply as? [String: Any]` succeeded and this returned
    /// a NON-NIL override with every field nil — for every step, on every RN host that does not
    /// implement `onBeforeStepRender`, which is the default. iOS then merged that empty override into
    /// each StepConfig, and the merger rebuilt the struct field-by-field and quietly forgot
    /// `chat_config`, so the authored chat background vanished on every interactive_chat step.
    ///
    /// The merger is non-destructive now, so the damage is undone. But "no handler registered" is not
    /// an instruction to override anything, and it must not decode as one — the next field somebody
    /// forgets would be the next RN-only defect.
    static func stepConfigOverride(_ reply: Any?) -> StepConfigOverride? {
        if isUnhandled(reply) { return nil }
        guard let map = reply as? [String: Any] else { return nil }
        return StepConfigOverride(
            fieldDefaults: anyMap(map["fieldDefaults"]),
            title: map["title"] as? String,
            subtitle: map["subtitle"] as? String,
            ctaText: map["ctaText"] as? String,
            // SPEC-448 §B — replaces the removed `layoutOverrides`, which nothing ever read.
            fieldOptions: decodeFieldOptions(map["fieldOptions"]),
            // SPEC-452 — the `{{hook_data.…}}` payload. `anyMap` (not a plain cast) because a
            // bridged nested dictionary fails `as? [String: Any]` at depth, and this value is
            // nested by definition: hosts send objects and arrays of objects here.
            dataContext: anyMap(map["dataContext"]),
            // SPEC-451 — a one-line forward into the core decoder, which is all a wrapper may be.
            mapRoutes: StepConfigOverride.decodeMapRoutes(map["mapRoutes"])
        )
    }

    /// map-or-null → `ElementInteractionResult?`. `fieldConfigPatches` is decoded element by element:
    /// a single `as? [String: [String: Any]]` cast fails on a bridged nested dictionary.
    static func elementInteractionResult(_ reply: Any?) -> ElementInteractionResult? {
        guard let map = reply as? [String: Any] else { return nil }
        var patches: [String: [String: Any]]?
        if let raw = map["fieldConfigPatches"] as? [String: Any] {
            var out: [String: [String: Any]] = [:]
            for (key, value) in raw {
                if let inner = anyMap(value) { out[key] = inner }
            }
            patches = out
        }
        return ElementInteractionResult(
            fieldConfigPatches: patches,
            inputValuePatches: anyMap(map["inputValuePatches"]),
            // #657 — replacement options for a refresh; same decoder as the render-time override.
            fieldOptions: decodeFieldOptions(map["fieldOptions"]),
            advance: (map["advance"] as? Bool) ?? false
        )
    }

    /// `{type:"handledByHost",granted:Bool}` short-circuits the OS prompt; nil runs the native flow.
    static func permissionHandling(_ reply: Any?) -> PermissionHandling? {
        guard let map = reply as? [String: Any] else { return nil }
        switch (map["type"] as? String) ?? "proceed" {
        case "handledByHost":
            return .handledByHost(granted: (map["granted"] as? Bool) ?? false)
        default:
            return .proceed
        }
    }

    /// A veto-reply sub-object with JSON-null (`NSNull`) entries dropped — mirrors Android's
    /// `AppdnaVetoDecoder.anyMap` (AppdnaDelegates.kt:601). A `null` value inside a veto reply means
    /// "no override for this key", and an ABSENT key is exactly what the SDK's own no-delegate path
    /// produces. Without this, iOS decoded (via `.fragmentsAllowed`) a nested JSON null as `NSNull` and
    /// KEPT it, so a host reply like `{"fieldDefaults":{"promo_code":null}}` CLEARED the field default on
    /// iOS while Android left it untouched — a different rendered step from identical config + reply.
    /// Returns nil only when the value is not an object (an absent key), preserving the present-vs-absent
    /// distinction; a present object simply loses its null-valued entries.
    private static func anyMap(_ value: Any?) -> [String: Any]? {
        // Mirror Android's `map[key]?.let { anyMap(it) }` exactly: a truly ABSENT key (or explicit null)
        // → nil (preserving the outer present-vs-absent distinction), a key PRESENT as a non-object value
        // → empty map (Android's `anyMap` returns `emptyMap()` for a non-`Map`), an object → itself with
        // null entries dropped. The non-object→[:] branch only matters for a malformed reply that
        // violates the host's own types (e.g. `{"fieldDefaults":42}`); valid inputs are unaffected.
        guard let present = value, !(present is NSNull) else { return nil }
        guard let map = present as? [String: Any] else { return [:] }
        return map.filter { !($0.value is NSNull) }
    }
}

/// SPEC-448 §B — `[blockId: [option maps]]` from the bridge into typed options, decoded against
/// the SAME `InputOption` the config parser uses so the two cannot drift.
private func decodeFieldOptions(_ raw: Any?) -> [String: [InputOption]]? {
    guard let byBlock = raw as? [String: Any] else { return nil }
    var out: [String: [InputOption]] = [:]
    for (blockId, list) in byBlock {
        guard let arr = list as? [[String: Any]],
              let data = try? JSONSerialization.data(withJSONObject: arr),
              let options = try? JSONDecoder().decode([InputOption].self, from: data)
        else { continue }
        out[blockId] = options
    }
    return out.isEmpty ? nil : out
}
