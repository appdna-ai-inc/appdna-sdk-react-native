import XCTest
@testable import appdna_sdk_react_native
import AppDNASDK

/// 🔴 A FAILING AUTH BACKEND WAS AN UNLOCKED DOOR.
///
/// Native refuses to advance a credential step when no delegate can handle it. A WRAPPER always
/// attaches a delegate, so that gate never fires for React Native — the wrapper has to re-make the
/// decision by asking JS. It did, and it blocked on ONE answer: `{"__appdna_unhandled":true}`, the
/// sentinel meaning "no handler registered".
///
/// But a host that HAS a handler can still fail to answer, in three ways, and every one of them
/// replies `"null"` — the wire form of "no opinion, apply your default":
///
///   1. the handler THREW (the JS dispatcher catches it and answers NO_OPINION);
///   2. its promise REJECTED — which is exactly what happens when the host's own sign-in call fails:
///      backend 500, no network, expired token;
///   3. it exceeded `vetoTimeout` (default 5s) — a SLOW auth backend.
///
/// And native's default for a step advance is `.proceed`. So the fix that stopped a NO-handler host
/// walking past a credential step did nothing for the far more common case: a host that implements
/// auth properly, whose auth is simply DOWN. Tap "Continue with email", the sign-in errors, and the
/// SDK lets the user into the app.
///
/// The wrapper's own comment said native's default was "the conservative answer for each hook —
/// reject for a promo code, allow for the rest". For a credential step, allow is not conservative.
///
/// An auth action now advances only on an EXPLICIT host decision. Silence is not consent.
final class AppdnaAuthActionGateTests: XCTestCase {

    override func tearDown() {
        AppdnaHostCallbacks.shared.invalidateAll()
        super.tearDown()
    }

    /// Drive the REAL forwarder through the REAL invoker and the REAL pending-callback map, with a
    /// "JS side" that answers `replyJson`. This exercises the wire, not a stub of it.
    private func advance(reply replyJson: String, action: String?) async -> StepAdvanceResult {
        let invoker = AppdnaVetoInvoker(timeout: 2.0) { payload in
            guard let id = payload["callbackId"] as? String else { return }
            AppdnaHostCallbacks.shared.respond(callbackId: id, resultJson: replyJson)
        }
        let forwarder = OnboardingForwarder(emit: { _, _ in }, invoker: invoker)
        return await forwarder.onBeforeStepAdvance(
            flowId: "f1",
            fromStepId: "s_email",
            stepIndex: 3,
            stepType: "form",
            responses: [:],
            stepData: action.map { ["action": $0] }
        )
    }

    /// Exactly what the JS dispatcher sends for a handler that threw or whose promise rejected, and
    /// what native synthesises when the veto times out. All three arrive here identically.
    private let noOpinion = "null"

    private let unhandled = #"{"__appdna_unhandled":true}"#

    func testAHandlerThatDeclinesToAnswerBlocksEveryAuthAction() async {
        for action in AppdnaAuthActions.all {
            let result = await advance(reply: noOpinion, action: action)
            guard case .block = result else {
                return XCTFail(
                    "'\(action)' ADVANCED on a no-opinion reply — the host's sign-in call failed or "
                        + "timed out and the user was let through with nobody authenticated"
                )
            }
        }
    }

    /// The no-handler case, still covered — the two must not be conflated, and both must block.
    func testNoHandlerStillBlocksEveryAuthAction() async {
        for action in AppdnaAuthActions.all {
            guard case .block = await advance(reply: unhandled, action: action) else {
                return XCTFail("'\(action)' advanced with no handler registered")
            }
        }
    }

    /// Blocking every step whenever a hook throws would turn one bad handler into a dead app. Only
    /// the credential steps get the strict treatment.
    func testANoOpinionReplyOnANonAuthActionStillProceeds() async {
        guard case .proceed = await advance(reply: noOpinion, action: nil) else {
            return XCTFail("a non-auth step must still take native's default")
        }
        guard case .proceed = await advance(reply: noOpinion, action: "next") else {
            return XCTFail("a non-auth step must still take native's default")
        }
    }

    /// A host that means it can still say so. `{"type":"proceed"}` is an ANSWER; `"null"` is not.
    func testAnExplicitHostDecisionIsObeyedOnAnAuthAction() async {
        guard case .proceed = await advance(reply: #"{"type":"proceed"}"#, action: "email_login") else {
            return XCTFail("an explicit proceed from the host must be honoured — it is the host's call")
        }
        guard case .block(let message) = await advance(
            reply: #"{"type":"block","message":"Bad password"}"#, action: "email_login"
        ) else {
            return XCTFail("an explicit block must be honoured")
        }
        XCTAssertEqual(message, "Bad password")
    }

    /// 🔴 THE FOURTH WAY OF SAYING NOTHING — AND THE GATE LET IT THROUGH.
    ///
    /// The gate blocked on `isUnhandled || isNoOpinion`, an ENUMERATION of the ways a host declines to
    /// answer. `isNoOpinion` means "the reply is not a map", so a reply that IS a map with no `type`
    /// satisfied neither guard: `stepAdvanceResult` read `(map["type"] as? String) ?? "proceed"` and
    /// **proceeded**, walking the user past the credential step with nobody having authenticated them.
    ///
    /// None of these are exotic. `{}` is what a handler returns when a branch forgets its return value;
    /// `{"ok":true}` and `{"user":…}` are what one returns after handing back its OWN result object
    /// instead of a veto decision. Every one read as "yes, let them in".
    ///
    /// The gate now demands a POSITIVE, RECOGNISED decision. There is always one more way to say
    /// nothing; there is only one way to say proceed.
    func testAMapShapedReplyThatDecidesNothingBlocksEveryAuthAction() async {
        let saysNothing = [
            "{}",                            // handler fell off the end of a branch
            #"{"ok":true}"#,                 // handler returned its own result object
            #"{"user":{"id":"u1"}}"#,        // ...with the signed-in user in it, even
            #"{"type":"banana"}"#,           // a decision type the decoder does not know
        ]

        for reply in saysNothing {
            for action in AppdnaAuthActions.all {
                guard case .block = await advance(reply: reply, action: action) else {
                    return XCTFail(
                        "'\(action)' ADVANCED on the reply \(reply) — a map carrying no recognised "
                            + "decision is a host that did not answer, and the user was let through "
                            + "unauthenticated"
                    )
                }
            }
        }
    }

    /// ...and the same replies on a NON-auth step must still take native's default. Blocking every step
    /// whose handler returns a stray object would turn one sloppy handler into a dead app.
    func testAMapShapedReplyThatDecidesNothingStillProceedsOnANonAuthAction() async {
        guard case .proceed = await advance(reply: "{}", action: "next") else {
            return XCTFail("a non-auth step must still take native's default")
        }
        guard case .proceed = await advance(reply: #"{"ok":true}"#, action: nil) else {
            return XCTFail("a non-auth step must still take native's default")
        }
    }

    /// A JSON null inside a veto-reply object must be STRIPPED, not kept as `NSNull`. Android's
    /// `AppdnaVetoDecoder.anyMap` drops it ("an absent key is exactly what the no-delegate path
    /// produces"), so iOS must too, via the same `anyMap`. Otherwise a null in `proceedWithData.data`
    /// (and, through the same helper, in `StepConfigOverride`/`ElementInteractionResult`) reaches the
    /// SDK as an explicit `NSNull` override on iOS ONLY — a different rendered step / merged response
    /// from the identical host reply.
    func testNestedNullInAVetoReplyIsStrippedLikeAndroid() async {
        let result = await advance(
            reply: #"{"type":"proceedWithData","data":{"promo_code":null,"keep":"yes"}}"#,
            action: nil
        )
        guard case let .proceedWithData(data) = result else {
            return XCTFail("expected .proceedWithData, got \(result)")
        }
        XCTAssertEqual(data["keep"] as? String, "yes")
        XCTAssertNil(data["promo_code"], "a null-valued key must be dropped (Android parity), not kept as NSNull")
    }
}
