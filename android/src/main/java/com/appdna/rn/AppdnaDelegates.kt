package com.appdna.rn

import ai.appdna.sdk.AppDNABillingDelegate
import ai.appdna.sdk.screens.AppDNAScreenDelegate
import ai.appdna.sdk.AppDNADeepLinkDelegate
import ai.appdna.sdk.AppDNAInAppMessageDelegate
import ai.appdna.sdk.AppDNAInitDelegate
import ai.appdna.sdk.generated.AppDNALifecycleDelegate
import ai.appdna.sdk.AppDNAPushDelegate
import ai.appdna.sdk.AppDNASurveyDelegate
import ai.appdna.sdk.PushPayload
import ai.appdna.sdk.SurveyResponse
import ai.appdna.sdk.TransactionInfo
import ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate
import ai.appdna.sdk.onboarding.ElementInteractionResult
import ai.appdna.sdk.onboarding.PermissionHandling
import ai.appdna.sdk.onboarding.StepAdvanceResult
import ai.appdna.sdk.onboarding.StepConfigOverride
import ai.appdna.sdk.paywalls.AppDNAPaywallDelegate
import ai.appdna.sdk.paywalls.PaywallAction

/**
 * SPEC-070-B P3 — the native→JS delegate forwarders.
 *
 * Every one of the ~30 SDK callbacks used to have a JS listener and no native emitter. The facade
 * subscribed; nothing ever fired. These classes are the missing half.
 *
 * ## Observe vs veto
 *
 * An **observe** callback is one-way: it becomes an `onXxx` TurboModule event and native returns
 * immediately. A **veto** callback has a return value native waits for, and there are eight of them.
 * Those go through [AppdnaVetoInvoker], which emits `onHostCallback` and awaits
 * `respondToHostCallback` with a per-hook timeout.
 *
 * ## Why the vetoes never block the SDK's thread
 *
 * The four onboarding hooks are `suspend` on the native interface, so awaiting is free. The other
 * four are synchronous — `shouldShowMessage(id): Boolean` cannot await anything. The native SDK
 * therefore exposes a **parallel async seam** for each (`asyncShouldShowMessage`,
 * `asyncShouldOpen`, `asyncOnScreenAction`, and `onPromoCodeSubmit`'s completion handler), consulted
 * in ADDITION to the sync delegate method. The sync forwarders below return the permissive answer
 * and defer the real decision to the async seam. That is not a workaround; it is the seam the core
 * SDK grew in SPEC-070-C precisely so a wrapper could answer a veto over a bridge.
 */

/** Emit an event to JS. Implemented by [AppdnaModule]. */
internal fun interface AppdnaEventEmitter {
    fun emit(event: String, payload: Map<String, Any?>)
}

// ── Onboarding ───────────────────────────────────────────────────────────────

internal class OnboardingForwarder(
    private val emitter: AppdnaEventEmitter,
    private val invoker: AppdnaVetoInvoker,
) : AppDNAOnboardingDelegate {

    override fun onOnboardingStarted(flowId: String) {
        emitter.emit("onOnboardingStarted", mapOf("flowId" to flowId))
    }

    override fun onOnboardingStepChanged(flowId: String, stepId: String, stepIndex: Int, totalSteps: Int) {
        emitter.emit(
            "onOnboardingStepChanged",
            mapOf("flowId" to flowId, "stepId" to stepId, "stepIndex" to stepIndex, "totalSteps" to totalSteps),
        )
    }

    override fun onOnboardingCompleted(flowId: String, responses: Map<String, Any>) {
        emitter.emit("onOnboardingCompleted", mapOf("flowId" to flowId, "responses" to responses))
    }

    override fun onOnboardingDismissed(flowId: String, atStep: Int) {
        emitter.emit("onOnboardingDismissed", mapOf("flowId" to flowId, "atStep" to atStep))
    }

    override fun onPermissionResult(flowId: String, stepId: String, permissionType: String, granted: Boolean) {
        emitter.emit(
            "onPermissionResult",
            mapOf("flowId" to flowId, "stepId" to stepId, "permissionType" to permissionType, "granted" to granted),
        )
    }

    // ── The four async hooks ────────────────────────────────────────────────

    override suspend fun onBeforeStepAdvance(
        flowId: String,
        fromStepId: String,
        stepIndex: Int,
        stepType: String,
        responses: Map<String, Any>,
        stepData: Map<String, Any>?,
    ): StepAdvanceResult {
        val reply = invoker.invoke(
            "onBeforeStepAdvance",
            buildMap {
                put("flowId", flowId)
                put("fromStepId", fromStepId)
                put("stepIndex", stepIndex)
                put("stepType", stepType)
                put("responses", responses)
                stepData?.let { put("stepData", it) }
            },
        )
        // Native gates auth actions on delegate presence — no delegate means nobody can sign the user
        // in, so it stays on the step and shows an error. But this wrapper ALWAYS attaches a delegate at
        // configure(), so that gate never fires for RN, and a JS host with no `onBeforeStepAdvance`
        // used to get `.proceed` — advancing PAST THE CREDENTIAL STEP WITHOUT AUTHENTICATING ANYONE.
        // The delegate-presence check is a proxy for "will someone handle this"; for a wrapper it lies.
        // 🔴 AN AUTH ACTION MAY ONLY ADVANCE ON AN EXPLICIT HOST DECISION.
        //
        // This blocked on `isUnhandled` ALONE — the "no handler registered" sentinel. But JS declines
        // to answer in three other ways, and every one of them replies `"null"` (= "no opinion, apply
        // your default"), which decodes to Proceed:
        //
        //   1. the handler THREW (hostCallbacks.ts catches it and answers NO_OPINION);
        //   2. its promise REJECTED — the host's own sign-in call failed: backend 500, no network;
        //   3. it exceeded `vetoTimeout` (default 5s) — a SLOW auth backend.
        //
        // So the fix that stopped a NO-handler host from advancing past a credential step did nothing
        // for a host WITH a handler whose auth call fails or is slow. Tap "Continue with email", the
        // sign-in errors, and the SDK walks the user into the app. A failing backend was free entry.
        //
        // Proceed remains available to a host that means it — `{"type":"proceed"}` is an explicit
        // answer. Silence is not.
        //
        // 🔴 AND THERE WAS A FOURTH WAY TO SAY NOTHING: `{}`.
        //
        // Blocking on `isUnhandled || isNoOpinion` enumerated the ways of declining, and missed one: a
        // reply that IS a map with no `type` satisfied neither guard, fell into `stepAdvanceResult`'s
        // `?: "proceed"` default, and ADVANCED the user unauthenticated. Demand a POSITIVE decision
        // instead — enumerating silence is a losing game.
        if (isAuthAction(stepData) && !AppdnaVetoDecoder.isExplicitDecision(reply)) {
            return StepAdvanceResult.Block(AUTH_UNAVAILABLE_MESSAGE)
        }
        return AppdnaVetoDecoder.stepAdvanceResult(reply)
    }

    override suspend fun onBeforeStepRender(
        flowId: String,
        stepId: String,
        stepIndex: Int,
        stepType: String,
        responses: Map<String, Any>,
    ): StepConfigOverride? {
        val reply = invoker.invoke(
            "onBeforeStepRender",
            mapOf(
                "flowId" to flowId,
                "stepId" to stepId,
                "stepIndex" to stepIndex,
                "stepType" to stepType,
                "responses" to responses,
            ),
        )
        return AppdnaVetoDecoder.stepConfigOverride(reply)
    }

    override suspend fun onElementInteraction(
        flowId: String,
        stepId: String,
        blockId: String,
        action: String,
        value: String?,
        inputValues: Map<String, Any>,
    ): ElementInteractionResult? {
        val reply = invoker.invoke(
            "onElementInteraction",
            buildMap {
                put("flowId", flowId)
                put("stepId", stepId)
                put("blockId", blockId)
                put("action", action)
                value?.let { put("value", it) }
                put("inputValues", inputValues)
            },
        )
        return AppdnaVetoDecoder.elementInteractionResult(reply)
    }

    override suspend fun onPermissionRequest(permissionType: String): PermissionHandling? {
        val reply = invoker.invoke("onPermissionRequest", mapOf("permissionType" to permissionType))
        return AppdnaVetoDecoder.permissionHandling(reply)
    }
}

// ── Paywall ──────────────────────────────────────────────────────────────────

internal class PaywallForwarder(
    private val emitter: AppdnaEventEmitter,
    private val invoker: AppdnaVetoInvoker,
    /**
     * The SDK's `onPromoCodeSubmit` is completion-based, so the veto needs a coroutine to live in.
     *
     * Returns `false` when it could not launch one — the module's scope is already cancelled
     * (teardown / reload). The caller must then answer the veto itself; see [onPromoCodeSubmit].
     */
    private val launchVeto: (suspend () -> Unit) -> Boolean,
) : AppDNAPaywallDelegate {

    override fun onPaywallPresented(paywallId: String) {
        emitter.emit("onPaywallPresented", mapOf("paywallId" to paywallId))
    }

    override fun onPaywallAction(paywallId: String, action: PaywallAction) {
        emitter.emit("onPaywallAction", mapOf("paywallId" to paywallId, "action" to action.value))
    }

    override fun onPaywallPurchaseStarted(paywallId: String, productId: String) {
        emitter.emit("onPaywallPurchaseStarted", mapOf("paywallId" to paywallId, "productId" to productId))
    }

    override fun onPaywallPurchaseCompleted(paywallId: String, productId: String, transaction: TransactionInfo) {
        emitter.emit(
            "onPaywallPurchaseCompleted",
            mapOf("paywallId" to paywallId, "productId" to productId, "transaction" to AppdnaMappers.map(transaction)),
        )
    }

    // 🔴 Override the WIDEST overload. The SDK calls the 4-arg form; a wrapper that overrides only the
    // 2-arg one still FIRES (the default chain funnels down to it) — but `errorType` and `productId` are
    // erased on the way, so the JS host is handed an opaque error and cannot tell a user cancel from a
    // declined card, nor which of two products failed. That is precisely the gap the discriminator was
    // added to close, surviving one layer up.
    override fun onPaywallPurchaseFailed(
        paywallId: String,
        error: Throwable,
        errorType: String,
        productId: String?,
    ) {
        emitter.emit(
            "onPaywallPurchaseFailed",
            mapOf(
                "paywallId" to paywallId,
                "error" to errorMessage(error),
                "errorType" to errorType,
                "productId" to productId,
            ),
        )
    }

    override fun onPaywallDismissed(paywallId: String) {
        emitter.emit("onPaywallDismissed", mapOf("paywallId" to paywallId))
    }

    override fun onPaywallRestoreStarted(paywallId: String) {
        emitter.emit("onPaywallRestoreStarted", mapOf("paywallId" to paywallId))
    }

    override fun onPaywallRestoreCompleted(paywallId: String, productIds: List<String>) {
        emitter.emit("onPaywallRestoreCompleted", mapOf("paywallId" to paywallId, "restoredProductIds" to productIds))
    }

    override fun onPaywallRestoreFailed(paywallId: String, error: Throwable) {
        emitter.emit("onPaywallRestoreFailed", mapOf("paywallId" to paywallId, "error" to errorMessage(error)))
    }

    override fun onPostPurchaseDeepLink(paywallId: String, url: String) {
        emitter.emit("onPostPurchaseDeepLink", mapOf("paywallId" to paywallId, "url" to url))
    }

    override fun onPostPurchaseNextStep(paywallId: String) {
        emitter.emit("onPostPurchaseNextStep", mapOf("paywallId" to paywallId))
    }

    /**
     * 🔴 The one hook whose default is **reject**.
     *
     * A timeout, an unregistered hook, or a saturated pending map all mean "the host did not validate
     * this code", and the only safe reading of that is *invalid*. Defaulting to `true` here is how a
     * paywall starts honouring any string a user types — the live defect this wrapper exists to not
     * repeat.
     */
    override fun onPromoCodeSubmit(paywallId: String, code: String, completion: (Boolean) -> Unit) {
        // E6 — `completion` MUST be called exactly once, on every path. The veto runs on the module's
        // coroutine scope, and that scope dies on teardown: before this, a reload mid-veto (or a code
        // submitted after one) left `completion` uncalled and the paywall's promo field spinning
        // forever, with no way for the user to cancel out of it. Every abandonment path now answers
        // with the hook's own safe default — REJECT the code, because "nobody validated it" can only
        // honestly read as invalid.
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        val answerOnce: (Boolean) -> Unit = { valid ->
            if (answered.compareAndSet(false, true)) completion(valid)
        }
        val launched = launchVeto {
            try {
                val reply = invoker.invoke("onPromoCodeSubmit", mapOf("paywallId" to paywallId, "code" to code))
                answerOnce(reply as? Boolean ?: false)
            } finally {
                // Runs on cancellation too: a coroutine cancelled while suspended inside `invoke`
                // unwinds through here, and the CAS makes a normal answer above idempotent.
                answerOnce(false)
            }
        }
        // The scope was already dead: `scope.launch` created a job whose body never ran, so the
        // `finally` above never will either.
        if (!launched) answerOnce(false)
    }

    private fun errorMessage(error: Throwable): String = error.message ?: error.toString()
}

// ── Surveys, messages, push, billing, deep links, init ───────────────────────

/**
 * The 9th delegate (P8). `onScreenAction` is deliberately NOT overridden here — it is a VETO and
 * rides `AppDNA.asyncOnScreenAction` (the host-callback seam), exactly as §18.6 ruled. Implementing
 * it in both places would ask the host twice and let the two answers disagree.
 */
internal class ScreenForwarder(private val emitter: AppdnaEventEmitter) : AppDNAScreenDelegate {
    override fun onScreenPresented(screenId: String) {
        emitter.emit("onScreenPresented", mapOf("screenId" to screenId))
    }

    override fun onScreenDismissed(screenId: String, result: Map<String, Any?>) {
        emitter.emit("onScreenDismissed", mapOf("screenId" to screenId, "result" to result))
    }

    override fun onFlowCompleted(flowId: String, result: Map<String, Any?>) {
        emitter.emit("onFlowCompleted", mapOf("flowId" to flowId, "result" to result))
    }
}

internal class SurveyForwarder(private val emitter: AppdnaEventEmitter) : AppDNASurveyDelegate {
    override fun onSurveyPresented(surveyId: String) {
        emitter.emit("onSurveyPresented", mapOf("surveyId" to surveyId))
    }

    /** `[{questionId, answer}]` — not Flutter's orphan `SurveyResult`, which native never emitted. */
    override fun onSurveyCompleted(surveyId: String, responses: List<SurveyResponse>) {
        emitter.emit(
            "onSurveyCompleted",
            mapOf("surveyId" to surveyId, "responses" to responses.map { AppdnaMappers.map(it) }),
        )
    }

    override fun onSurveyDismissed(surveyId: String) {
        emitter.emit("onSurveyDismissed", mapOf("surveyId" to surveyId))
    }
}

internal class InAppMessageForwarder(private val emitter: AppdnaEventEmitter) : AppDNAInAppMessageDelegate {
    override fun onMessageShown(messageId: String, trigger: String) {
        emitter.emit("onMessageShown", mapOf("messageId" to messageId, "trigger" to trigger))
    }

    override fun onMessageAction(messageId: String, action: String, data: Map<String, Any>?) {
        emitter.emit("onMessageAction", buildMap {
            put("messageId", messageId)
            put("action", action)
            data?.let { put("data", it) }
        })
    }

    override fun onMessageDismissed(messageId: String) {
        emitter.emit("onMessageDismissed", mapOf("messageId" to messageId))
    }

    /**
     * The sync veto cannot await a bridge round trip, so it allows and defers to
     * `AppDNA.inAppMessages.setAsyncShouldShowMessage`, which `MessageManager` consults as well.
     * Both can suppress; only one of them can wait.
     */
    override fun shouldShowMessage(messageId: String): Boolean = true
}

internal class PushForwarder(private val emitter: AppdnaEventEmitter) : AppDNAPushDelegate {
    override fun onPushTokenRegistered(token: String) {
        emitter.emit("onPushTokenRegistered", mapOf("token" to token))
    }

    override fun onPushReceived(notification: PushPayload, inForeground: Boolean) {
        emitter.emit(
            "onPushReceived",
            mapOf("payload" to AppdnaMappers.map(notification), "inForeground" to inForeground),
        )
    }

    override fun onPushTapped(notification: PushPayload, actionId: String?) {
        emitter.emit("onPushTapped", buildMap {
            put("payload", AppdnaMappers.map(notification))
            actionId?.let { put("actionId", it) }
        })
    }
}

internal class BillingForwarder(private val emitter: AppdnaEventEmitter) : AppDNABillingDelegate {
    override fun onPurchaseCompleted(productId: String, transaction: TransactionInfo) {
        emitter.emit(
            "onPurchaseCompleted",
            mapOf("productId" to productId, "transaction" to AppdnaMappers.map(transaction)),
        )
    }

    override fun onPurchaseFailed(productId: String, error: Throwable) {
        emitter.emit("onPurchaseFailed", mapOf("productId" to productId, "error" to (error.message ?: error.toString())))
    }

    /**
     * ⚠ `onEntitlementsChanged` is deliberately NOT overridden here.
     *
     * The SDK fans entitlement changes out through TWO independent paths: this delegate method (fired
     * by `RevenueCatBridge` on Android; never fired at all on iOS) and the explicit observer behind
     * `AppDNA.billing.onEntitlementsChanged(callback)` (live on both). Overriding both would deliver
     * every change TWICE to a RevenueCat-configured Android host and once to an iOS one.
     *
     * `startEntitlementObserver()` is the single source, because it is the only one that fires on
     * both platforms. See `AppdnaModule.startEntitlementObserver`.
     */
    override fun onRestoreCompleted(restoredProducts: List<String>) {
        emitter.emit("onRestoreCompleted", mapOf("restoredProducts" to restoredProducts))
    }

    /** N8 — Android-only. iOS never emits it, and the facade documents that. */
    override fun onBillingUnavailable() {
        emitter.emit("onBillingUnavailable", emptyMap())
    }
}

internal class DeepLinkForwarder(private val emitter: AppdnaEventEmitter) : AppDNADeepLinkDelegate {
    override fun onDeepLinkReceived(url: String, params: Map<String, String>) {
        emitter.emit("onDeepLinkReceived", mapOf("url" to url, "params" to params))
    }
}

/**
 * SPEC-404 — the backend runtime lock. iOS wired this; Android did not, and
 * `AppDNA.lifecycle.setDelegate(...)` is one facade with one signature, so the same JS fired on iOS
 * and was silently deaf on Android. When the backend hard-suspends an SDK key, an Android host
 * showed no "service unavailable" state and never learned the lock had cleared.
 */
internal class LifecycleForwarder(private val emitter: AppdnaEventEmitter) : AppDNALifecycleDelegate {
    override fun onSdkRuntimeLocked(reason: String, lockedAt: String) {
        emitter.emit("onSdkRuntimeLocked", mapOf("reason" to reason, "lockedAt" to lockedAt))
    }

    override fun onSdkRuntimeUnlocked() {
        emitter.emit("onSdkRuntimeUnlocked", emptyMap())
    }
}

internal class InitForwarder(private val emitter: AppdnaEventEmitter) : AppDNAInitDelegate {
    override fun onInitDegraded(reason: Throwable) {
        emitter.emit(
            "onInitDegraded",
            mapOf("type" to reason::class.java.simpleName, "message" to (reason.message ?: reason.toString())),
        )
    }
}

/**
 * The actions that need the HOST to perform a side effect the SDK cannot: sign in, register, send an
 * OTP. Mirrors the core's delegate-required actions (iOS `AuthActionPolicy.delegateRequiredActions`
 * + Android `AUTH_ACTIONS_REQUIRING_DELEGATE` and `emitSocialLoginAction`'s own guard), kept in step
 * by `check:auth-action-parity` — which, when this comment first claimed it, DID NOT EXIST. It does
 * now, and it caught the drift the comment was pretending to prevent.
 */
internal val AUTH_ACTIONS = setOf(
    // 🔴 `social_login` was MISSING here (and on iOS). Android's core enforces it in
    // `emitSocialLoginAction`'s own null-delegate guard rather than in
    // AUTH_ACTIONS_REQUIRING_DELEGATE — but a WRAPPER always attaches a delegate, so that guard
    // never fires for RN and this set is the only thing standing between an unhandled
    // "Continue with Google" tap and an advanced, unauthenticated flow.
    "social_login",
    "login", "register", "reset_password", "magic_link", "verify_email", "resend_verification",
    "enable_biometric", "email_login", "request_otp", "verify_otp", "logout", "change_password",
    "set_new_password", "delete_account", "update_profile",
)

/** The same copy native shows. A dead button is worse than a refusal; say why nothing happened. */
internal const val AUTH_UNAVAILABLE_MESSAGE = "Sign-in isn't available right now. Please try again later."

internal fun isAuthAction(stepData: Map<String, Any>?): Boolean =
    (stepData?.get("action") as? String) in AUTH_ACTIONS

// ── Reply decoding ───────────────────────────────────────────────────────────

/**
 * JS reply map → the concrete native return type. Every unknown or missing shape falls back to the
 * SDK's own default, which is what a host that never registered the hook would get.
 *
 * The wire shapes are identical to Flutter's (`AppdnaPlugin.swift` / `AppdnaPlugin.kt`), so a host
 * that ports a veto handler between the two SDKs does not have to relearn them.
 */
internal object AppdnaVetoDecoder {

    /** Did JS reply "I have no handler for this hook"? See `UNHANDLED` in hostCallbacks.ts. */
    fun isUnhandled(reply: Any?): Boolean =
        (reply as? Map<*, *>)?.get("__appdna_unhandled") == true

    /**
     * The host DECLINED TO DECIDE: `"null"` — the wire form of "no opinion, apply your default".
     *
     * JS sends this when a registered handler throws or its promise rejects (`hostCallbacks.ts`
     * catches both), and native synthesises it when the veto exceeds `vetoTimeout`. For most hooks
     * "apply your default" is exactly right. For an AUTH action it means nobody authenticated — and
     * the default there is to advance, which is the one answer that must never be given.
     */
    fun isNoOpinion(reply: Any?): Boolean = reply !is Map<*, *>

    /** The decision types [stepAdvanceResult] actually understands. */
    private val KNOWN_DECISIONS = setOf(
        "proceed", "proceedWithData", "block", "skipTo", "skipToWithData", "stay",
    )

    /**
     * Did the host make an EXPLICIT, RECOGNISED decision?
     *
     * 🔴 `{}` IS A MAP, SO IT WAS NEITHER "unhandled" NOR "no opinion" — AND IT ADVANCED.
     *
     * The auth gate blocked on `isUnhandled || isNoOpinion`. `isNoOpinion` is "the reply is not a map",
     * so a reply that IS a map but carries no `type` — `{}`, or `{"ok": true}`, or a handler returning
     * its own result object by mistake — satisfied neither guard. [stepAdvanceResult] then read
     * `map["type"] as? String ?: "proceed"` and **proceeded**: the user walked past the credential step
     * with nobody having authenticated them.
     *
     * A returned-but-shapeless object is not a decision; it is a host that has not answered. On an auth
     * action there is one safe reading of "did not answer", and it is not "let them in". So the gate
     * demands a POSITIVE answer rather than enumerating the ways of saying nothing — there is always
     * one more way of saying nothing.
     */
    fun isExplicitDecision(reply: Any?): Boolean {
        val map = reply as? Map<*, *> ?: return false
        if (map["__appdna_unhandled"] == true) return false
        val type = map["type"] as? String ?: return false
        return type in KNOWN_DECISIONS
    }

    /**
     * `{type:"proceed"|"proceedWithData"|"block"|"skipTo"|"stay", …}` → default `Proceed`.
     *
     * `skipToWithData` is an ACCEPTED ALIAS of `skipTo`, not a second encoding: the canonical wire
     * shape is `{type:"skipTo", stepId, data?}`, and `data` is what promotes it to `SkipTo(stepId,
     * data)`. The alias exists because the published docs named `skipToWithData` for a case no
     * decoder had — so a host that followed them fell to `else` and the user SILENTLY ADVANCED to
     * the next step instead of skipping. A mis-route with no error and no log is worse than a
     * rejection; accepting the string those hosts already send costs one line.
     */
    fun stepAdvanceResult(reply: Any?): StepAdvanceResult {
        val map = reply as? Map<*, *> ?: return StepAdvanceResult.Proceed
        return when (map["type"] as? String ?: "proceed") {
            "proceedWithData" -> StepAdvanceResult.ProceedWithData(anyMap(map["data"]))
            "block" -> StepAdvanceResult.Block(map["message"] as? String ?: "")
            "skipTo", "skipToWithData" -> {
                val stepId = map["stepId"] as? String ?: ""
                val data = anyMap(map["data"])
                if (data.isEmpty()) StepAdvanceResult.SkipTo(stepId) else StepAdvanceResult.SkipTo(stepId, data)
            }
            "stay" -> StepAdvanceResult.Stay(map["message"] as? String)
            else -> StepAdvanceResult.Proceed
        }
    }

    /** map-or-null → `StepConfigOverride?`, field by field. */
    fun stepConfigOverride(reply: Any?): StepConfigOverride? {
        // 🔴 THE SENTINEL IS NOT AN OVERRIDE. `{"__appdna_unhandled":true}` IS a map, so this returned
        // a non-null override with every field null — for every step, on every RN host with no
        // `onBeforeStepRender` (the default). Android's `copy(...)` merge made that harmless; iOS's
        // field-by-field rebuild silently dropped `chat_config`. Both are fixed, but "no handler
        // registered" must not decode as an instruction to override anything.
        if (isUnhandled(reply)) return null

        val map = reply as? Map<*, *> ?: return null
        return StepConfigOverride(
            fieldDefaults = map["fieldDefaults"]?.let { anyMap(it) },
            title = map["title"] as? String,
            subtitle = map["subtitle"] as? String,
            ctaText = map["ctaText"] as? String,
            // SPEC-448 §B — replaces the removed `layoutOverrides`, which nothing ever read.
            fieldOptions = decodeFieldOptions(map["fieldOptions"]),
            // SPEC-452 — the `{{hook_data.…}}` payload. `anyMap`, not a plain cast: a bridged
            // nested map fails a direct cast at depth, and this value is nested by definition —
            // hosts send objects and arrays of objects here.
            dataContext = map["dataContext"]?.let { anyMap(it) },
            // SPEC-451 — a one-line forward into the core decoder, which is all a wrapper may be.
            mapRoutes = ai.appdna.sdk.onboarding.StepConfigOverride.decodeMapRoutes(map["mapRoutes"]),
        )
    }

    /** map-or-null → `ElementInteractionResult?`. `fieldConfigPatches` is `blockId → (key → value)`. */
    fun elementInteractionResult(reply: Any?): ElementInteractionResult? {
        val map = reply as? Map<*, *> ?: return null
        val patches = (map["fieldConfigPatches"] as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
            val inner = value as? Map<*, *> ?: return@mapNotNull null
            key.toString() to anyMap(inner)
        }?.toMap()
        return ElementInteractionResult(
            fieldConfigPatches = patches,
            inputValuePatches = map["inputValuePatches"]?.let { anyMap(it) },
            advance = map["advance"] as? Boolean ?: false,
        )
    }

    /** `{type:"handledByHost",granted:Bool}` short-circuits the OS prompt; null runs the native flow. */
    fun permissionHandling(reply: Any?): PermissionHandling? {
        val map = reply as? Map<*, *> ?: return null
        return when (map["type"] as? String ?: "proceed") {
            "handledByHost" -> PermissionHandling.HandledByHost(map["granted"] as? Boolean ?: false)
            else -> PermissionHandling.Proceed
        }
    }

    /**
     * `Map<String, Any>` — the native DTOs cannot hold a Kotlin null. A JSON `null` inside a veto
     * reply is dropped rather than crashing the step: the host asked for a key to be absent, and an
     * absent key is exactly what the SDK's own no-delegate path produces.
     */
    private fun anyMap(value: Any?): Map<String, Any> {
        val map = value as? Map<*, *> ?: return emptyMap()
        return map.entries.mapNotNull { (key, inner) ->
            if (inner == null) null else key.toString() to inner
        }.toMap()
    }
}

/**
 * SPEC-448 §B — `[blockId: [option maps]]` from the bridge into typed options.
 *
 * A one-line forward into the core, which is all a wrapper is allowed to be. The previous version
 * reached into `OnboardingConfigParser` -- `internal`, so from this separate Gradle module it did
 * not compile at all -- and the tempting fix, re-parsing the maps here, would have put a second
 * option parser in a wrapper that ADR-001 says holds no logic.
 */
private fun decodeFieldOptions(raw: Any?): Map<String, List<ai.appdna.sdk.onboarding.InputOption>>? =
    ai.appdna.sdk.onboarding.StepConfigOverride.decodeFieldOptions(raw)
