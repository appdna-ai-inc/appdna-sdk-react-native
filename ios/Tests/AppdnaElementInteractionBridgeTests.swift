import XCTest
@testable import appdna_sdk_react_native
import AppDNASDK

/// The React Native iOS bridge for `onElementInteraction`.
///
/// 1. **The refresh wait.** A `refresh` interaction has an 8 s SDK deadline. The bridge bounds every
///    host callback at `vetoTimeout` (5 s by default), so without the per-call floor a host answering a
///    "Show more" at 6 s would be cut off and the page would never arrive. The bridge must wait
///    `max(configured, core minimumBridgeTimeout)` for `refresh` — and ONLY for `refresh`: a
///    non-refresh interaction keeps the configured timeout.
/// 2. **The decode.** `dataContext` crosses the bridge through the CORE `decodeDataContext`, never
///    `anyMap` (which drops null members — "null removes a key" would silently vanish). Driven by the
///    shared fixture `config_overrides/element_interaction_data_context_decode.fixture.json`, the same
///    one the native iOS and Android runners read.
///
/// Both go through the REAL forwarder, the REAL invoker and the REAL pending-callback map.
final class AppdnaElementInteractionBridgeTests: XCTestCase {

    override func tearDown() {
        AppdnaHostCallbacks.shared.invalidateAll()
        super.tearDown()
    }

    /// A "JS host" that answers `replyJson` after `delay` seconds.
    private func forwarder(timeout: TimeInterval, delay: TimeInterval, replyJson: String) -> OnboardingForwarder {
        let invoker = AppdnaVetoInvoker(timeout: timeout) { payload in
            guard let id = payload["callbackId"] as? String else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                AppdnaHostCallbacks.shared.respond(callbackId: id, resultJson: replyJson)
            }
        }
        return OnboardingForwarder(emit: { _, _ in }, invoker: invoker)
    }

    private func interact(_ f: OnboardingForwarder, action: String) async -> ElementInteractionResult? {
        await f.onElementInteraction(flowId: "f", stepId: "step_eir", blockId: "show_more",
                                     action: action, value: "more", inputValues: [:])
    }

    func testRefreshReplyAtSixSecondsIsDeliveredAndOtherActionsStillTimeOut() async {
        let reply = #"{"dataContext":{"recommendations":["e","f"]},"advance":false}"#
        // Both calls in parallel: the fake host answers each at 6 s, above the 5 s configured wait.
        async let refresh = interact(forwarder(timeout: 5.0, delay: 6.0, replyJson: reply), action: "refresh")
        async let otp = interact(forwarder(timeout: 5.0, delay: 6.0, replyJson: reply), action: "otp_entered")
        let (r, o) = await (refresh, otp)
        XCTAssertEqual(r?.dataContext?["recommendations"] as? [String], ["e", "f"],
                       "a refresh waits max(configured, 8 s) — the 6 s answer must arrive")
        XCTAssertNil(o, "a non-refresh interaction keeps the configured 5 s timeout")
    }

    func testRefreshStillEndsAtTheLargerConfiguredTimeout() {
        // The floor never SHORTENS a longer configured wait.
        XCTAssertEqual(max(12.0, ElementInteractionResult.minimumBridgeTimeout(action: "refresh") ?? 0), 12.0)
        XCTAssertEqual(max(5.0, ElementInteractionResult.minimumBridgeTimeout(action: "refresh") ?? 0), 8.0)
        XCTAssertEqual(max(5.0, ElementInteractionResult.minimumBridgeTimeout(action: "confirmed") ?? 0), 5.0)
    }

    // MARK: - decode (shared fixture)

    func testBridgeDecodesTheSharedDataContextFixture() throws {
        let fixture = try Self.loadFixture("config_overrides/element_interaction_data_context_decode.fixture.json")
        let setup = try XCTUnwrap(fixture["setup"] as? [String: Any])
        let session = try XCTUnwrap(setup["session_data"] as? [String: Any])
        let raw = try XCTUnwrap(session["host_interaction_reply"])
        // Exactly what the JS dispatcher sends back: the host's map, serialised, then parsed by the
        // invoker's `JSONSerialization` decode.
        let wire = try JSONSerialization.data(withJSONObject: raw)
        let reply = try JSONSerialization.jsonObject(with: wire, options: [.fragmentsAllowed])
        let result = try XCTUnwrap(AppdnaVetoDecoder.elementInteractionResult(reply))
        let expected = try XCTUnwrap(((fixture["expect"] as? [String: Any])?["state_after"] as? [String: Any])?["decoded_data_context"])
        let actual = try XCTUnwrap(result.dataContext)
        if let diff = Self.strictDiff(expected, actual, path: "$") { XCTFail("decoded_data_context (type-strict) \(diff)") }
        // The top-level null member is the REMOVAL MARKER — kept, not dropped.
        XCTAssertTrue(actual["banner"] is NSNull, "null member must reach core as NSNull")
    }

    func testDataContextNullMemberSurvivesTheWire() async {
        let f = forwarder(timeout: 2.0, delay: 0, replyJson: #"{"dataContext":{"banner":null,"n":1,"t":true}}"#)
        let r = await interact(f, action: "refresh")
        XCTAssertTrue(r?.dataContext?["banner"] is NSNull)
        XCTAssertTrue(Self.isBool(r?.dataContext?["t"]))
        XCTAssertFalse(Self.isBool(r?.dataContext?["n"]), "1 stays an integer, never true")
    }

    // MARK: - helpers

    static func isBool(_ v: Any?) -> Bool {
        guard let n = v as? NSNumber else { return false }
        return CFGetTypeID(n) == CFBooleanGetTypeID()
    }

    /// Structural, TYPE-STRICT: bool ≠ number, key order irrelevant, array order significant.
    static func strictDiff(_ e: Any, _ a: Any, path: String) -> String? {
        switch (e, a) {
        case (is NSNull, is NSNull): return nil
        case (let es as String, let as_ as String): return es == as_ ? nil : "\(path): \(es) ≠ \(as_)"
        case (let en as NSNumber, let an as NSNumber):
            if isBool(en) != isBool(an) { return "\(path): bool/number kind differs (\(en) vs \(an))" }
            return en == an ? nil : "\(path): \(en) ≠ \(an)"
        case (let ea as [Any], let aa as [Any]):
            if ea.count != aa.count { return "\(path): \(ea.count) ≠ \(aa.count) elements" }
            for (i, (x, y)) in zip(ea, aa).enumerated() { if let d = strictDiff(x, y, path: "\(path)[\(i)]") { return d } }
            return nil
        case (let eo as [String: Any], let ao as [String: Any]):
            if Set(eo.keys) != Set(ao.keys) { return "\(path): keys \(eo.keys.sorted()) ≠ \(ao.keys.sorted())" }
            for k in eo.keys.sorted() { if let d = strictDiff(eo[k]!, ao[k]!, path: "\(path).\(k)") { return d } }
            return nil
        default: return "\(path): type differs (\(e) vs \(a))"
        }
    }

    /// `<repo>/packages/sdk-shared-fixtures` next to this package, or `APPDNA_SDK_FIXTURES_DIR`.
    static func loadFixture(_ relative: String) throws -> [String: Any] {
        var roots: [URL] = []
        if let env = ProcessInfo.processInfo.environment["APPDNA_SDK_FIXTURES_DIR"] { roots.append(URL(fileURLWithPath: env)) }
        let pkg = AppdnaHandlerPassTests.packageRoot
        roots.append(pkg.deletingLastPathComponent().appendingPathComponent("sdk-shared-fixtures"))
        roots.append(pkg.appendingPathComponent("packages/sdk-shared-fixtures"))
        for r in roots {
            let url = r.appendingPathComponent(relative)
            if FileManager.default.fileExists(atPath: url.path) {
                let obj = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
                return try XCTUnwrap(obj as? [String: Any])
            }
        }
        XCTFail("fixture \(relative) not found under \(roots.map(\.path)) — set APPDNA_SDK_FIXTURES_DIR")
        throw NSError(domain: "fixture", code: 1)
    }
}
