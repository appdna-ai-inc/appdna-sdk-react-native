import XCTest
@testable import appdna_sdk_react_native
import AppDNASDK

/// SPEC-497 — the React Native iOS bridge halves.
///
/// 1. **§4.2 / §4.9 sign-in floor.** `onBeforeStepAdvance` for a sign-in action waits
///    `max(vetoTimeout, core 120 s)`; every other step keeps `vetoTimeout`. The invoker has no virtual
///    clock (it gives up via `DispatchQueue.main.asyncAfter`), so it takes an injectable timeout
///    scheduler: this test records the interval the bridge ASKS for and fires the give-up on demand.
/// 2. **§9.2 / §9.8 push forwarding.** A JS payload reaches the SDK UNTOUCHED on iOS — a nested
///    `action` stays a dictionary and a list stays an array, never `String(describing:)` — and the
///    nested deep link routes.
/// 3. **§13b.2 restore error contract.** `restorePurchases` rejects with the `billingErrorType` code
///    (was a fixed `RESTORE_ERROR`); under `none` that is `providerNotAvailable`, as is `purchase`.
/// 4. **§3.2 rule 6 / §4.2** `parseOptions`: a key-less Adapty is refused (core `fromWire`); a
///    non-positive `vetoTimeout` is the native default.
final class AppdnaSpec497BridgeTests: XCTestCase {

    override func tearDown() {
        AppdnaHostCallbacks.shared.invalidateAll()
        super.tearDown()
    }

    // MARK: - 1. sign-in floor (scripted scheduler)

    /// A scheduler that records each requested give-up interval and holds the callback.
    private final class ScriptedScheduler {
        var requested: [TimeInterval] = []
        var pending: [() -> Void] = []
        lazy var schedule: AppdnaVetoInvoker.TimeoutScheduler = { [unowned self] wait, fire in
            self.requested.append(wait)
            self.pending.append(fire)
        }
        func fireAll() { let p = pending; pending.removeAll(); p.forEach { $0() } }
    }

    private func forwarder(timeout: TimeInterval, scheduler: ScriptedScheduler, reply: String?) -> OnboardingForwarder {
        let invoker = AppdnaVetoInvoker(timeout: timeout, scheduleTimeout: scheduler.schedule) { payload in
            guard let reply, let id = payload["callbackId"] as? String else { return }
            DispatchQueue.main.async { AppdnaHostCallbacks.shared.respond(callbackId: id, resultJson: reply) }
        }
        return OnboardingForwarder(emit: { _, _ in }, invoker: invoker)
    }

    private func advance(_ f: OnboardingForwarder, action: String?) async -> StepAdvanceResult {
        await f.onBeforeStepAdvance(flowId: "flow", fromStepId: "step_a", stepIndex: 0, stepType: "question",
                                    responses: [:], stepData: action.map { ["action": $0] })
    }

    @MainActor
    func testSignInActionAsksForTheFloorAndAReplyBeforeItIsDelivered() async {
        let scheduler = ScriptedScheduler()
        let result = await advance(forwarder(timeout: 5, scheduler: scheduler, reply: #"{"type":"proceed"}"#), action: "social_login")
        XCTAssertEqual(scheduler.requested, [120], "a sign-in waits max(5 s, 120 s)")
        guard case .proceed = result else { return XCTFail("the reply before the floor must be delivered, got \(result)") }
    }

    @MainActor
    func testSignInTimeoutAtTheFloorBlocks() async {
        let scheduler = ScriptedScheduler()
        let f = forwarder(timeout: 5, scheduler: scheduler, reply: nil)
        async let result = advance(f, action: "email_login")
        // Let the invoker register and schedule, then fire the give-up as if 120 s had passed.
        while scheduler.pending.isEmpty { await Task.yield() }
        XCTAssertEqual(scheduler.requested, [120])
        scheduler.fireAll()
        let r = await result
        guard case .block(let message) = r else { return XCTFail("a sign-in timeout must block, got \(r)") }
        XCTAssertEqual(message, "Sign-in isn't available right now. Please try again later.")
    }

    @MainActor
    func testNonAuthStepKeepsTheConfiguredTimeoutAndALargerConfiguredWins() async {
        let s1 = ScriptedScheduler()
        _ = await advance(forwarder(timeout: 5, scheduler: s1, reply: #"{"type":"proceed"}"#), action: "next")
        XCTAssertEqual(s1.requested, [5])
        let s2 = ScriptedScheduler()
        _ = await advance(forwarder(timeout: 150, scheduler: s2, reply: #"{"type":"proceed"}"#), action: "social_login")
        XCTAssertEqual(s2.requested, [150], "a configured wait longer than the floor is kept")
        let s3 = ScriptedScheduler()
        _ = await advance(forwarder(timeout: 5, scheduler: s3, reply: #"{"type":"proceed"}"#), action: nil)
        XCTAssertEqual(s3.requested, [5])
    }

    func testTheSharedFixtureBridgeWaits() throws {
        let fixture = try AppdnaElementInteractionBridgeTests.loadFixture("delegate_contracts/sign_in_bridge_timeout_floor.fixture.json")
        let action = try XCTUnwrap(fixture["action"] as? [String: Any])
        let waits = try XCTUnwrap(action["bridge_waits"] as? [[String: Any]])
        XCTAssertFalse(waits.isEmpty)
        for row in waits {
            let configured = try XCTUnwrap((row["configured_ms"] as? NSNumber)?.doubleValue) / 1000
            let stepData = row["step_data"] as? [String: Any]
            let expected = try XCTUnwrap((row["expect_wait_ms"] as? NSNumber)?.doubleValue) / 1000
            // The call-site expression, verbatim (AppdnaDelegates.swift onBeforeStepAdvance).
            XCTAssertEqual(max(configured, StepAdvanceResult.minimumBridgeTimeout(stepData: stepData) ?? 0), expected)
        }
    }

    // MARK: - 2. push payload untouched

    func testPushPayloadReachesTheSdkUntouched() {
        let js: NSDictionary = [
            "appdna": "1",
            "push_id": "p_nested",
            "badge": 5,
            "action": ["type": "deep_link", "value": "x://y"],
            "actions": [["id": "b1", "action_type": "deep_link", "action_value": "x://b"]],
        ]
        let userInfo = AppdnaModuleImpl.pushUserInfo(js)
        XCTAssertEqual((userInfo["action"] as? [String: Any])?["value"] as? String, "x://y",
                       "a nested action stays a dictionary — never a String(describing:)")
        XCTAssertEqual((userInfo["actions"] as? [Any])?.count, 1, "a list stays an array")
        XCTAssertNotNil(userInfo["badge"] as? NSNumber, "a number stays a number on iOS")
        XCTAssertTrue(AppDNA.pushModule.isAppDNAMessage(userInfo))
        XCTAssertFalse(AppDNA.pushModule.isAppDNAMessage(AppdnaModuleImpl.pushUserInfo(["push_id": "p"])))
    }

    /// Records the deep link the SDK routes a forwarded tap to.
    private final class DeepLinkRecorder: AppDNADeepLinkDelegate {
        let routed: XCTestExpectation
        var url: URL?
        init(_ e: XCTestExpectation) { routed = e }
        func onDeepLinkReceived(url: URL, params: [String: String]) {
            self.url = url
            routed.fulfill()
        }
    }

    func testForwardedTapWithANestedActionRoutesTheDeepLink() throws {
        let impl = AppdnaModuleImpl()
        configure(impl, options: [:])
        defer { AppDNA.shutdown() }
        let routed = expectation(description: "the nested deep link routed")
        let recorder = DeepLinkRecorder(routed)
        AppDNA.deepLinks.setDelegate(recorder)
        let tapped = expectation(description: "handlePushTap resolved")
        var answer: Any?
        impl.handlePushTap(
            ["appdna": "1", "push_id": "p_route_\(UUID().uuidString)", "action": ["type": "deep_link", "value": "x://y"]] as NSDictionary,
            actionId: nil,
            resolve: { v in answer = v; tapped.fulfill() },
            reject: { c, _, _ in XCTFail("rejected \(c ?? "?")") }
        )
        // The deep link waits on the shouldOpen veto (no JS host here → the 5 s default "open") plus the 0.5 s routing delay.
        wait(for: [tapped, routed], timeout: 20)
        XCTAssertEqual(answer as? Bool, true)
        XCTAssertEqual(recorder.url?.absoluteString, "x://y")
    }

    // MARK: - 3. restore / purchase reject with the billingErrorType code

    func testRestoreAndPurchaseRejectWithProviderNotAvailableUnderNone() {
        let impl = AppdnaModuleImpl()
        configure(impl, options: ["billingProvider": "none"])
        defer { AppDNA.shutdown() }

        let restored = expectation(description: "restore rejected")
        impl.restorePurchases(
            resolve: { _ in XCTFail("restore must be refused under billingProvider none") },
            reject: { code, _, _ in
                XCTAssertEqual(code, "providerNotAvailable", "the restore error contract — was a fixed RESTORE_ERROR")
                restored.fulfill()
            }
        )
        let purchased = expectation(description: "purchase rejected")
        impl.purchase(
            "plan_monthly", offerToken: nil,
            resolve: { _ in XCTFail("purchase must be refused under billingProvider none") },
            reject: { code, _, _ in
                XCTAssertEqual(code, "providerNotAvailable")
                purchased.fulfill()
            }
        )
        wait(for: [restored, purchased], timeout: 30)
    }

    // MARK: - 4. parseOptions

    func testKeylessAdaptyIsRefusedAndNonPositiveVetoTimeoutIsTheDefault() {
        let impl = AppdnaModuleImpl()
        let defaults = AppDNAOptions()
        XCTAssertEqual(impl.parseOptions(["billingProvider": "adapty"]).billingProvider, defaults.billingProvider)
        XCTAssertEqual(impl.parseOptions(["billingProvider": ["type": "adapty"]]).billingProvider, defaults.billingProvider)
        XCTAssertEqual(impl.parseOptions(["billingProvider": ["type": "adapty", "apiKey": "k"]]).billingProvider,
                       BillingProvider.adapty(apiKey: "k"))
        XCTAssertEqual(impl.parseOptions(["vetoTimeout": NSNumber(value: 0)]).vetoTimeout, defaults.vetoTimeout)
        XCTAssertEqual(impl.parseOptions(["vetoTimeout": NSNumber(value: -1)]).vetoTimeout, defaults.vetoTimeout)
        XCTAssertEqual(impl.parseOptions(["vetoTimeout": NSNumber(value: 30)]).vetoTimeout, 30)
    }

    /// `billingDelegateReady` before configure is a no-op (no forwarder yet) and never crashes.
    func testBillingDelegateReadyBeforeConfigureIsANoOp() {
        let impl = AppdnaModuleImpl()
        impl.billingDelegateReady(true)
        impl.billingDelegateReady(false)
    }

    // MARK: - helpers

    private func configure(_ impl: AppdnaModuleImpl, options: [String: Any]) {
        AppDNA.shutdown()
        let ready = expectation(description: "the SDK reached ready")
        impl.configure("adn_test_placeholder", env: "sandbox", options: options as NSDictionary,
                       resolve: { _ in }, reject: { _, _, _ in })
        AppDNA.onReady { ready.fulfill() }
        wait(for: [ready], timeout: 120)
    }
}
