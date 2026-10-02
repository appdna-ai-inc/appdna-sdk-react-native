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

    // The session tests write the process-global session store, which is persisted (UserDefaults) and so
    // outlives the process. It is cleared on both sides of every test:
    // - in setUp, so each test starts empty whatever ran before it — including a previous run that crashed
    //   part-way, which ends the process without running tearDown and leaves its values on disk;
    // - in tearDown, so a test that stops early (a thrown error, or a failed assertion when
    //   `continueAfterFailure` is false — XCTest runs tearDown in both cases) leaves nothing behind for the
    //   test classes that run after it in this process.
    override func setUp() {
        super.setUp()
        AppDNA.clearSessionData()
    }

    override func tearDown() {
        AppdnaHostCallbacks.shared.invalidateAll()
        AppDNA.clearSessionData()
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

    /// A JS host whose reply is held until the test releases it — so "a reply at 6 s against a 5 s /
    /// 10 s wait" is decided by ordering on the scripted clock: the give-up fires first when the wait is
    /// shorter than the reply time, the reply lands first otherwise.
    private final class HeldReply {
        var callbackId: String?
        func release(_ json: String) {
            guard let id = callbackId else { return }
            AppdnaHostCallbacks.shared.respond(callbackId: id, resultJson: json)
        }
    }

    /// Drive the REAL forwarder + invoker with a JS reply "at `replyAt` seconds": whichever of the
    /// requested wait and the reply comes first on the scripted clock wins.
    @MainActor
    private func advanceWithReply(configured: TimeInterval, replyAt: TimeInterval, reply: String,
                                  action: String?) async -> (StepAdvanceResult, TimeInterval?) {
        let scheduler = ScriptedScheduler()
        let held = HeldReply()
        let invoker = AppdnaVetoInvoker(timeout: configured, scheduleTimeout: scheduler.schedule) { payload in
            held.callbackId = payload["callbackId"] as? String
        }
        let f = OnboardingForwarder(emit: { _, _ in }, invoker: invoker)
        async let result = advance(f, action: action)
        while scheduler.requested.isEmpty || held.callbackId == nil { await Task.yield() }
        let wait = scheduler.requested.first
        if let wait, replyAt < wait {
            held.release(reply)         // the reply lands inside the wait…
            await Task.yield()
            scheduler.fireAll()         // …and the give-up is then a no-op
        } else {
            scheduler.fireAll()         // the wait expires first…
            held.release(reply)         // …and the late reply is dropped
        }
        return (await result, wait)
    }

    @MainActor
    func testASixSecondNonAuthReplyTimesOutAtFiveButIsDeliveredAtTen() async {
        let before = timeoutsObserved()
        let (at5, wait5) = await advanceWithReply(configured: 5, replyAt: 6, reply: #"{"type":"stay"}"#, action: "next")
        XCTAssertEqual(wait5, 5)
        guard case .proceed = at5 else { return XCTFail("a timed-out non-auth hook falls back to proceed, got \(at5)") }
        XCTAssertEqual(timeoutsObserved(), before + 1, "the timeout reaches diagnose()")

        let (at10, wait10) = await advanceWithReply(configured: 10, replyAt: 6, reply: #"{"type":"stay"}"#, action: "next")
        XCTAssertEqual(wait10, 10)
        guard case .stay = at10 else { return XCTFail("the 6 s reply is inside a 10 s wait, got \(at10)") }
    }

    @MainActor
    func testSignInRepliesAtSixtyAndOneTwentyOne() async {
        let (at60, _) = await advanceWithReply(configured: 5, replyAt: 60, reply: #"{"type":"proceed"}"#, action: "social_login")
        guard case .proceed = at60 else { return XCTFail("a 60 s sign-in reply is inside the 120 s floor, got \(at60)") }
        let (at121, wait) = await advanceWithReply(configured: 5, replyAt: 121, reply: #"{"type":"proceed"}"#, action: "social_login")
        XCTAssertEqual(wait, 120)
        guard case .block = at121 else { return XCTFail("a 121 s sign-in reply is past the floor → block, got \(at121)") }
    }

    /// The shared fixture's `bridge_waits`, through PRODUCTION code: the real forwarder computes the wait
    /// and hands it to the invoker's scheduler; the test only reads what was requested.
    @MainActor
    func testTheSharedFixtureBridgeWaits() async throws {
        let fixture = try AppdnaElementInteractionBridgeTests.loadFixture("delegate_contracts/sign_in_bridge_timeout_floor.fixture.json")
        let action = try XCTUnwrap(fixture["action"] as? [String: Any])
        let waits = try XCTUnwrap(action["bridge_waits"] as? [[String: Any]])
        XCTAssertFalse(waits.isEmpty)
        for row in waits {
            let configured = try XCTUnwrap((row["configured_ms"] as? NSNumber)?.doubleValue) / 1000
            let stepAction = (row["step_data"] as? [String: Any])?["action"] as? String
            let expected = try XCTUnwrap((row["expect_wait_ms"] as? NSNumber)?.doubleValue) / 1000
            let scheduler = ScriptedScheduler()
            let f = forwarder(timeout: configured, scheduler: scheduler, reply: #"{"type":"proceed"}"#)
            _ = await advance(f, action: stepAction)
            XCTAssertEqual(scheduler.requested, [expected], "bridge_waits row \(row)")
        }
    }

    /// The bridge half of `delegate_contracts/skip_to_without_step_id_is_not_a_decision`: each JSON reply
    /// goes through the REAL invoker, auth gate and decoder. NEGATIVE CONTROL: with the old decoder a
    /// `{"type":"skipTo"}` on a sign-in step returned `.skipTo("")` (which advances) instead of `.block`.
    @MainActor
    func testTheSharedFixtureSkipToReplies() async throws {
        let fixture = try AppdnaElementInteractionBridgeTests.loadFixture("delegate_contracts/skip_to_without_step_id_is_not_a_decision.fixture.json")
        let cases = try XCTUnwrap((fixture["action"] as? [String: Any])?["cases"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        for (i, row) in cases.enumerated() {
            let replyObj = row["reply"] ?? NSNull()
            let reply: String = replyObj is NSNull ? "null"
                : String(data: try JSONSerialization.data(withJSONObject: replyObj), encoding: .utf8)!
            let action = (row["auth_action"] as? Bool) == true ? "email_login" : "next"
            let (result, _) = await advanceWithReply(configured: 5, replyAt: 0, reply: reply, action: action)
            let expected = try XCTUnwrap(row["expect_result"] as? [String: Any])
            let got: (String, String?)
            switch result {
            case .proceed: got = ("proceed", nil)
            case .proceedWithData: got = ("proceedWithData", nil)
            case .block: got = ("block", nil)
            case .skipTo(let id), .skipToWithData(let id, _): got = ("skipTo", id)
            case .stay: got = ("stay", nil)
            }
            XCTAssertEqual(got.0, expected["type"] as? String, "cases[\(i)] \(reply)")
            XCTAssertEqual(got.1, expected["step_id"] as? String, "cases[\(i)] step_id")
        }
    }

    private func timeoutsObserved() -> Int {
        let report = AppDNA.diagnose()
        guard let r = report.range(of: #"timed out (\d+) time"#, options: .regularExpression) else { return -1 }
        return Int(report[r].filter(\.isNumber)) ?? -1
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

    /// `session.set(k, null | undefined | NaN)` crosses as "null": it stores nothing and RESOLVES, as the
    /// Flutter bridge does. NEGATIVE CONTROL: it rejected with INVALID_VALUE.
    func testSessionSetWithANullValueResolvesAndStoresNothing() {
        let impl = AppdnaModuleImpl()
        var outcome: String?
        impl.setSessionData("r18_null", valueJson: "null",
                            resolve: { _ in outcome = "resolved" },
                            reject: { code, _, _ in outcome = "rejected: \(code ?? "?")" })
        XCTAssertEqual(outcome, "resolved")
        XCTAssertNil(AppDNA.getSessionData(key: "r18_null"))
    }

    /// Control for the test above: the native store is live, so a non-null value crosses, is stored and
    /// is read back — "stores nothing" is an answer, not a store that never holds anything.
    func testSessionSetWithAValueStoresIt() {
        let impl = AppdnaModuleImpl()
        var outcome: String?
        impl.setSessionData("r20_live", valueJson: "\"x\"",
                            resolve: { _ in outcome = "resolved" },
                            reject: { code, _, _ in outcome = "rejected: \(code ?? "?")" })
        XCTAssertEqual(outcome, "resolved")
        XCTAssertEqual(AppDNA.getSessionData(key: "r20_live") as? String, "x")
    }

    /// The existing-value control the Android bridge test has: a null over a stored value leaves the
    /// value as it was. NEGATIVE CONTROL: a bridge that stored the null (`NSNull()`) or removed the key passed
    /// the test above (a nil read) and fails this one.
    func testSessionSetWithANullValueLeavesAnExistingValueAsItWas() {
        AppDNA.setSessionData(key: "r20_kept", value: "before")
        let impl = AppdnaModuleImpl()
        var outcome: String?
        impl.setSessionData("r20_kept", valueJson: "null",
                            resolve: { _ in outcome = "resolved" },
                            reject: { code, _, _ in outcome = "rejected: \(code ?? "?")" })
        XCTAssertEqual(outcome, "resolved")
        XCTAssertEqual(AppDNA.getSessionData(key: "r20_kept") as? String, "before")
    }
}
