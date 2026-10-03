import XCTest
import AppDNASDK
@testable import appdna_sdk_react_native

/**
 `onPromoCodeSubmit`'s default is **REJECT**, on iOS.

 ## Why this test exists

 Seven of the eight veto hooks default to *allow* on a silent host: a host that registered no handler,
 or answered too slowly, should not be able to break an onboarding flow. `onPromoCodeSubmit` is the
 eighth and it is the opposite — and the asymmetry is not decorative. It was a LIVE defect on both
 natives: a paywall with no delegate ran a *"basic non-empty check fallback"* that marked **any
 non-blank string** as a valid promo code, printed "Code applied!", and folded it into the purchase
 metadata under a comment reading *"fold validated promo code into purchase metadata"*. The store
 price is set by `productId` so there was no store-side discount — but a host backend that trusts that
 metadata field applies a discount nobody validated.

 A timeout, an unregistered hook, a saturated pending map, and a host that answers `null` all mean the
 same thing: **the host did not validate this code.** The only safe reading of that is *invalid*.

 Nothing asserted this on iOS. Android's equivalent is proven in `AppdnaTeardownTest`; Swift had no
 test target at all, so the one hook whose default is a security property was checked on exactly one
 of the two platforms it ships on.

 Each case drives the REAL `AppdnaVetoInvoker` + the REAL `PaywallForwarder` — the objects
 `registerDelegates` actually installs — not a re-implementation of their logic.
 */
final class AppdnaPromoDefaultTests: XCTestCase {

    override func tearDown() {
        // The pending map is a process-global singleton; a leaked resolver would leak into the next
        // test and answer somebody else's veto.
        AppdnaHostCallbacks.shared.invalidateAll()
        super.tearDown()
    }

    /// Drive the real forwarder and capture the boolean native would have been handed.
    private func submitPromo(
        timeout: TimeInterval,
        host: @escaping (String) -> Void,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> Bool? {
        let answered = expectation(description: "native's completion handler was called")
        var answer: Bool?

        let invoker = AppdnaVetoInvoker(timeout: timeout) { payload in
            // This is the `onHostCallback` event that would have gone to JS. The `host` closure plays
            // JS: it either answers through the real `respondToHostCallback` path, or stays silent.
            guard let callbackId = payload["callbackId"] as? String else {
                return XCTFail("the veto request carried no callbackId", file: file, line: line)
            }
            host(callbackId)
        }
        let forwarder = PaywallForwarder(emit: { _, _ in }, invoker: invoker)

        forwarder.onPromoCodeSubmit(paywallId: "pw_1", code: "SAVE20") { granted in
            answer = granted
            answered.fulfill()
        }

        // The completion is delivered on the MainActor, so the wait has to spin the main run loop —
        // which `waitForExpectations` does.
        wait(for: [answered], timeout: 10)
        return answer
    }

    // MARK: - The default

    func testASilentHostRejectsTheCode() {
        // 🔴 THE ASSERTION. JS never answers — a host that registered no `onPromoCodeSubmit` handler,
        // or one whose JS thread is wedged. Native is BLOCKING on this completion: the promo field
        // spins until it is called, so "do nothing" is not an option; something must answer, and the
        // only safe answer is no.
        let answer = submitPromo(timeout: 0.5, host: { _ in /* silence */ })
        XCTAssertEqual(
            answer, false,
            "a promo code no host validated was ACCEPTED — this is §11.7, the live defect, reintroduced"
        )
    }

    func testAHostAnsweringNullRejectsTheCode() {
        // `null` is not "yes". It is the same non-answer as silence, arriving faster.
        let answer = submitPromo(timeout: 5) { callbackId in
            AppdnaHostCallbacks.shared.respond(callbackId: callbackId, resultJson: "null")
        }
        XCTAssertEqual(answer, false)
    }

    func testAHostAnsweringANonBooleanRejectsTheCode() {
        // `reply as? Bool ?? false`. A host that returns `"yes"` or `{ok: true}` has not returned a
        // veto decision, and a truthy-looking value must not be coerced into one.
        let answer = submitPromo(timeout: 5) { callbackId in
            AppdnaHostCallbacks.shared.respond(callbackId: callbackId, resultJson: "\"yes\"")
        }
        XCTAssertEqual(answer, false)
    }

    // MARK: - The controls
    //
    // Without these, every assertion above would still pass if `onPromoCodeSubmit` were hardcoded to
    // `completion(false)` — which would be a different bug (no promo code could ever be redeemed) and
    // an equally invisible one.

    func testAHostAnsweringTrueAcceptsTheCode() {
        let answer = submitPromo(timeout: 5) { callbackId in
            AppdnaHostCallbacks.shared.respond(callbackId: callbackId, resultJson: "true")
        }
        XCTAssertEqual(answer, true, "a host that validated the code was overruled — the veto is not wired")
    }

    func testAHostAnsweringFalseRejectsTheCode() {
        let answer = submitPromo(timeout: 5) { callbackId in
            AppdnaHostCallbacks.shared.respond(callbackId: callbackId, resultJson: "false")
        }
        XCTAssertEqual(answer, false)
    }

    // MARK: - The timer belongs to the wrapper

    func testTheTimeoutIsHonoredRatherThanAwaitedForever() {
        // The SDK awaits a host veto FOREVER; the timer has always been the wrapper's job. A JS
        // `setTimeout` cannot do it — it is throttled in the background and destroyed by a Metro
        // reload (E5) — so native would await forever and the paywall would hang mid-purchase.
        let started = Date()
        let answer = submitPromo(timeout: 0.4, host: { _ in /* silence */ })
        let elapsed = Date().timeIntervalSince(started)

        XCTAssertEqual(answer, false)
        XCTAssertGreaterThanOrEqual(elapsed, 0.4, "answered BEFORE the timeout — the host never got its chance")
        XCTAssertLessThan(elapsed, 5, "the timeout did not fire — native would have awaited forever")
    }
}
