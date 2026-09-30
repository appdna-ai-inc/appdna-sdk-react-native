import XCTest
import StoreKit
import StoreKitTest
import UIKit
import AppDNASDK
@testable import appdna_sdk_react_native

/// SPEC-497 §3.10 fallback — the StoreKit half of the billing proof, APP-HOSTED.
///
/// Named `AppdnaAA…` so XCTest (alphabetical) runs it FIRST in the bundle. Measured on the bridge: when
/// an earlier test class has already configured and shut down the SDK in this process, the late-purchase
/// cases (interrupted / Ask-to-Buy / RN forwarder) see no `Transaction.updates` report at all; run first,
/// they pass. That ordering dependence is reported as a core finding, not hidden here.
///
/// The core SDK's `StoreKitSessionTests` run in the hostless SPM test target, where `Product.purchase()`
/// on an `SKTestSession` fails with an unknown StoreKit error, so they skip. The spec's fallback is the
/// only app-hosted iOS test target in the repo: this pod's `test_spec` (`requires_app_host = true`),
/// which links `AppDNASDK`. It cannot `@testable import` the SDK, so everything below uses PUBLIC API
/// only — `AppDNA.configure(…, options: AppDNAOptions(billingProvider:))`, `AppDNA.billing.*`, a public
/// billing delegate, StoreKit's own `Transaction.unfinished` — and drives reconciliation only through
/// public triggers (`configure`, whose observer start reconciles, and `didBecomeActiveNotification`).
///
/// Covered: ownership (storeKit2 finishes; revenueCat never; adapty-unlinked never), restore with no
/// server, a lifetime re-buy (no second `onPurchaseCompleted`), an interrupted purchase and an
/// Ask-to-Buy approval (each delivered ONCE through `onPurchaseCompleted`, then finished), and a forced
/// renewal (not a purchase). The no-network restore counts the SDK's requests by putting a counting
/// `URLProtocol` into every default `URLSessionConfiguration` (see `withCountingProtocol`). NOT asserted
/// here: event PROPERTIES (charged / intro / trial price, `is_trial`) — iOS has no public event observer,
/// and every counted request is failed, so no upload body is ever inspected.
final class AppdnaAAStoreKitHostedTests: XCTestCase {

    private var session: SKTestSession!
    private var recorder: DeliveryRecorder!

    /// A public billing delegate that counts `onPurchaseCompleted` per product.
    final class DeliveryRecorder: AppDNABillingDelegate {
        private let lock = NSLock()
        private var counts: [String: Int] = [:]
        func onPurchaseCompleted(productId: String, transaction: TransactionInfo) {
            lock.lock(); counts[productId, default: 0] += 1; lock.unlock()
        }
        func count(_ productId: String) -> Int { lock.lock(); defer { lock.unlock() }; return counts[productId] ?? 0 }
    }

    override func setUpWithError() throws {
        try super.setUpWithError()
        AppDNA.shutdown()
        for key in ["appdna.billing.last_sub_snapshot_v1", "appdna.pending_deliveries_v1", "appdna.purchase_owner_map_v1"] {
            UserDefaults.standard.removeObject(forKey: key)
        }
        guard let url = Bundle(for: Self.self).url(forResource: "AppDNATestProducts", withExtension: "storekit") else {
            XCTFail("AppDNATestProducts.storekit is not in the test bundle (podspec test_spec.resources)")
            throw NSError(domain: "AppdnaAAStoreKitHostedTests", code: 1)
        }
        // The process-wide session opened by the principal class, before any test touched StoreKit.
        session = try AppdnaTestObservation.storeKitSession ?? SKTestSession(contentsOf: url)
        session.resetToDefaultState()
        session.disableDialogs = true
        session.clearTransactions()
        recorder = DeliveryRecorder()
    }

    /// The highest transaction id any earlier test in this process produced. `clearTransactions()`
    /// restarts SKTestSession ids, but the SDK's delivery queue remembers reported transaction ids for
    /// the life of the process — a real store never reuses an id, a test session does. Late-purchase
    /// tests first move the session past every id already seen (`burnSeenIds`).
    private static var maxSeenId: UInt = 0

    override func tearDown() {
        if let session {
            Self.maxSeenId = max(Self.maxSeenId, session.allTransactions().map(\.identifier).max() ?? 0)
        }
        AppDNA.billing.setDelegate(nil)
        AppDNA.shutdown()
        session?.clearTransactions()
        super.tearDown()
    }

    // MARK: - Helpers

    /// Finished consumable purchases until the session's next transaction id is new to this process.
    private func burnSeenIds() async throws {
        let products = try await Product.products(for: ["ai.appdna.test.coins"])
        let coins = try XCTUnwrap(products.first)
        var attempts = 0
        while (session.allTransactions().map(\.identifier).max() ?? 0) <= Self.maxSeenId, attempts < 200 {
            attempts += 1
            if case .success(.verified(let t)) = try await coins.purchase() { await t.finish() }
        }
    }

    private func configure(_ provider: BillingProvider) {
        let ready = expectation(description: "ready")
        AppDNA.configure(apiKey: "adn_test_placeholder", environment: .sandbox,
                         options: AppDNAOptions(logLevel: .none, billingProvider: provider))
        AppDNA.onReady { ready.fulfill() }
        wait(for: [ready], timeout: 120)
    }

    private func unfinished(_ productId: String) async -> [UInt64] {
        var ids: [UInt64] = []
        for await result in Transaction.unfinished {
            if case .verified(let t) = result, t.productID == productId { ids.append(t.id) }
        }
        return ids
    }

    private func waitUntil(_ timeout: TimeInterval = 10, _ condition: () async -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await condition() { return true }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        return await condition()
    }

    private func becomeActive() async {
        await MainActor.run {
            NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        }
        try? await Task.sleep(nanoseconds: 500_000_000)
    }

    /// A HOST purchase that the host deliberately does not finish (the revenueCat / Adapty shape).
    private func hostBuyWithoutFinishing(_ productId: String) async throws -> Transaction {
        let products = try await Product.products(for: [productId])
        let product = try XCTUnwrap(products.first)
        let result = try await product.purchase()
        guard case .success(.verified(let t)) = result else {
            XCTFail("the host's own SKTestSession purchase did not succeed: \(result)")
            throw NSError(domain: "AppdnaAAStoreKitHostedTests", code: 2)
        }
        return t
    }

    /// The ids of every transaction the session holds for `productId` (purchases, renewals, approvals).
    private func sessionIds(_ productId: String) -> [UInt64] {
        session.allTransactions().filter { $0.productIdentifier == productId }.map { UInt64($0.identifier) }
    }

    /// Assert the SDK finished NONE of `ids`, after giving its observer time to act (~2 s, with a
    /// foreground in between — the reconcile trigger).
    private func assertStillUnfinished(_ ids: [UInt64], _ productId: String, _ why: String,
                                       file: StaticString = #filePath, line: UInt = #line) async {
        XCTAssertFalse(ids.isEmpty, "no transaction ids captured — \(why)", file: file, line: line)
        await becomeActive()
        try? await Task.sleep(nanoseconds: 2_000_000_000)
        let left = Set(await unfinished(productId))
        for id in ids {
            XCTAssertTrue(left.contains(id), "transaction \(id) was finished — \(why)", file: file, line: line)
        }
    }

    /// The persisted subscription snapshot, decoded: product id → state. The SDK keeps computing and
    /// saving it while lifecycle events are suppressed (§3.2 rule 4).
    private func snapshotProducts() -> Set<String> {
        guard let data = UserDefaults.standard.data(forKey: "appdna.billing.last_sub_snapshot_v1"),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [] }
        return Set(obj.keys)
    }

    /// A refused `AppDNA.billing.purchase` must not reach StoreKit at all: no new session transaction.
    private func assertRefusedWithoutAStoreCall(_ productId: String, file: StaticString = #filePath, line: UInt = #line) async {
        let before = session.allTransactions().count
        do {
            _ = try await AppDNA.billing.purchase(productId)
            XCTFail("the SDK must not buy under a non-owning provider", file: file, line: line)
        } catch {
            XCTAssertEqual(billingErrorType(error), "providerNotAvailable", file: file, line: line)
        }
        XCTAssertEqual(session.allTransactions().count, before, "a refused purchase made a store call", file: file, line: line)
    }

    // MARK: - Ownership

    func testStoreKit2FinishesItsPurchaseAndARenewal() async throws {
        configure(.storeKit2)
        _ = try await AppDNA.billing.purchase("ai.appdna.test.monthly")
        try session.forceRenewalOfSubscription(productIdentifier: "ai.appdna.test.monthly")
        _ = await waitUntil(5) { self.sessionIds("ai.appdna.test.monthly").count >= 2 }
        XCTAssertGreaterThanOrEqual(sessionIds("ai.appdna.test.monthly").count, 2,
                                    "the renewal was not created — the drain below would only prove the purchase")
        await becomeActive()
        let drained = await waitUntil { await self.unfinished("ai.appdna.test.monthly").isEmpty }
        XCTAssertTrue(drained, "storeKit2 owns transactions: the purchase and its renewal are finished")
    }

    func testRevenueCatNeverFinishesAndRefusesToBuyOrRestore() async throws {
        configure(.revenueCat)
        await assertRefusedWithoutAStoreCall("ai.appdna.test.monthly")
        do {
            _ = try await AppDNA.billing.restorePurchases()
            XCTFail("revenueCat (not linked): the SDK must not restore")
        } catch {
            XCTAssertEqual(billingErrorType(error), "providerNotAvailable")
        }
        _ = try await hostBuyWithoutFinishing("ai.appdna.test.monthly")
        await becomeActive()                                             // baseline
        try session.forceRenewalOfSubscription(productIdentifier: "ai.appdna.test.monthly")
        _ = await waitUntil(5) { self.sessionIds("ai.appdna.test.monthly").count >= 2 }
        let ids = sessionIds("ai.appdna.test.monthly")                   // the purchase AND the renewal
        XCTAssertGreaterThanOrEqual(ids.count, 2, "the renewal was not created")
        await assertStillUnfinished(ids, "ai.appdna.test.monthly", "revenueCat: the SDK never finishes")
        XCTAssertTrue(snapshotProducts().contains("ai.appdna.test.monthly"),
                      "the snapshot keeps the subscription while events are suppressed: \(snapshotProducts())")
    }

    func testRevenueCatNeverFinishesAnAskToBuyApproval() async throws {
        configure(.revenueCat)
        session.askToBuyEnabled = true
        let products = try await Product.products(for: ["ai.appdna.test.lifetime"])
        let product = try XCTUnwrap(products.first)
        _ = try await product.purchase()                                 // pending: waiting for approval
        session.askToBuyEnabled = false
        let pending = try XCTUnwrap(session.allTransactions().first { $0.productIdentifier == "ai.appdna.test.lifetime" && $0.pendingAskToBuyConfirmation },
                                    "no Ask-to-Buy transaction")
        try session.approveAskToBuyTransaction(identifier: pending.identifier)
        await assertStillUnfinished(sessionIds("ai.appdna.test.lifetime"), "ai.appdna.test.lifetime",
                                    "revenueCat: an approved Ask-to-Buy belongs to the host's billing SDK")
    }

    func testAdaptyUnlinkedNeverFinishes() async throws {
        configure(.adapty(apiKey: "public_test_key"))
        await assertRefusedWithoutAStoreCall("ai.appdna.test.monthly")
        _ = try await hostBuyWithoutFinishing("ai.appdna.test.monthly")
        await becomeActive()
        try session.forceRenewalOfSubscription(productIdentifier: "ai.appdna.test.monthly")
        _ = await waitUntil(5) { self.sessionIds("ai.appdna.test.monthly").count >= 2 }
        let ids = sessionIds("ai.appdna.test.monthly")
        XCTAssertGreaterThanOrEqual(ids.count, 2, "the renewal was not created")
        await assertStillUnfinished(ids, "ai.appdna.test.monthly", "adapty (unlinked) emits but never finishes")
        XCTAssertTrue(snapshotProducts().contains("ai.appdna.test.monthly"), "\(snapshotProducts())")
    }

    // MARK: - Restore without a server

    /// Fails every AppDNA request the SDK's default-configuration session makes, and counts those that are
    /// not event uploads (`/events`): event traffic is batched on its own timer and may legitimately flush
    /// during a restore, while a restore itself must call nothing.
    final class CountingURLProtocol: URLProtocol {
        private static let lock = NSLock()
        private static var _count = 0
        static var count: Int { lock.lock(); defer { lock.unlock() }; return _count }
        static func reset() { lock.lock(); _count = 0; lock.unlock() }
        override class func canInit(with request: URLRequest) -> Bool {
            guard let url = request.url, let host = url.host, host.contains("appdna") else { return false }
            if !url.path.contains("/events") { lock.lock(); _count += 1; lock.unlock() }
            return true
        }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() { client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet)) }
        override func stopLoading() {}
    }

    /// The SDK builds its `URLSession` from `URLSessionConfiguration.default`, and a globally registered
    /// `URLProtocol` is not consulted by such a session — so the counter is put INTO every default
    /// configuration's `protocolClasses` for the duration of the test (the getter is swapped on the
    /// concrete configuration class, then restored).
    private func withCountingProtocol<T>(_ body: () async throws -> T) async rethrows -> T {
        let cls: AnyClass = type(of: URLSessionConfiguration.default)
        let original = class_getInstanceMethod(cls, #selector(getter: URLSessionConfiguration.protocolClasses))!
        let replacement = class_getInstanceMethod(URLSessionConfiguration.self,
                                                  #selector(URLSessionConfiguration.appdnaTest_protocolClasses))!
        method_exchangeImplementations(original, replacement)
        defer { method_exchangeImplementations(original, replacement) }
        return try await body()
    }

    func testStoreKit2RestoreSucceedsAndMakesNoNetworkCall() async throws {
        // C1-7i: a storeKit2 restore reads `Transaction.currentEntitlements` and needs no AppDNA server.
        try await withCountingProtocol {
            try await restoreWithoutNetwork()
        }
    }

    private func restoreWithoutNetwork() async throws {
        CountingURLProtocol.reset()
        configure(.storeKit2)
        _ = try await AppDNA.billing.purchase("ai.appdna.test.lifetime")
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        // Control: the counter SEES the SDK's non-event requests (the bootstrap above), so a zero
        // below means "no call", not "a call it could not see".
        XCTAssertGreaterThan(CountingURLProtocol.count, 0, "the counter cannot see the SDK's session — the zero below would prove nothing")
        CountingURLProtocol.reset()
        let restored = try await AppDNA.billing.restorePurchases()
        XCTAssertTrue(restored.contains("ai.appdna.test.lifetime"))
        XCTAssertEqual(CountingURLProtocol.count, 0, "a storeKit2 restore made \(CountingURLProtocol.count) AppDNA network call(s)")
    }

    private func storedQueue() -> String {
        guard let data = UserDefaults.standard.data(forKey: "appdna.pending_deliveries_v1") else { return "<none>" }
        return String(data: data, encoding: .utf8) ?? "<\(data.count) bytes>"
    }

    // MARK: - Re-buy, late purchases, renewal

    func testLifetimeRebuyGivesNoSecondOnPurchaseCompleted() async throws {
        configure(.storeKit2)
        AppDNA.billing.setDelegate(recorder, deliversPurchases: true)
        let first = try await AppDNA.billing.purchase("ai.appdna.test.lifetime")
        try? await Task.sleep(nanoseconds: 500_000_000)
        let afterFirst = recorder.count("ai.appdna.test.lifetime")
        let second = try await AppDNA.billing.purchase("ai.appdna.test.lifetime")
        XCTAssertEqual(second.productId, first.productId, "purchase() still returns the TransactionInfo")
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        XCTAssertEqual(recorder.count("ai.appdna.test.lifetime"), afterFirst,
                       "a re-buy of an owned item is a restore, not a second purchase")
    }

    func testInterruptedPurchaseIsDeliveredOnceThenFinished() async throws {
        try await burnSeenIds()
        configure(.storeKit2)
        AppDNA.billing.setDelegate(recorder, deliversPurchases: true)
        try await makeInterruptedPurchaseLate("ai.appdna.test.coins")
        let delivered = await waitUntil(15) { self.recorder.count("ai.appdna.test.coins") >= 1 }
        XCTAssertTrue(delivered, "the late purchase reaches onPurchaseCompleted")
        let finished = await waitUntil { await self.unfinished("ai.appdna.test.coins").isEmpty }
        XCTAssertTrue(finished, "…then it is finished")
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        XCTAssertEqual(recorder.count("ai.appdna.test.coins"), 1, "delivered exactly once")
    }

    /// An interrupted purchase through the SDK, then resolved: it completes later, through
    /// `Transaction.updates` — a late purchase.
    private func makeInterruptedPurchaseLate(_ productId: String) async throws {
        session.interruptedPurchasesEnabled = true
        _ = try? await AppDNA.billing.purchase(productId)
        session.interruptedPurchasesEnabled = false
        let interrupted = try XCTUnwrap(session.allTransactions().first { $0.productIdentifier == productId && $0.hasPurchaseIssue },
                                        "SKTestSession produced no interrupted transaction")
        try session.resolveIssueForTransaction(identifier: interrupted.identifier)
    }

    func testAskToBuyApprovalIsDeliveredOnceThenFinished() async throws {
        try await burnSeenIds()
        configure(.storeKit2)
        AppDNA.billing.setDelegate(recorder, deliversPurchases: true)
        session.askToBuyEnabled = true
        _ = try? await AppDNA.billing.purchase("ai.appdna.test.lifetime")   // pending: waiting for approval
        session.askToBuyEnabled = false
        let pending = try XCTUnwrap(session.allTransactions().first { $0.productIdentifier == "ai.appdna.test.lifetime" && $0.pendingAskToBuyConfirmation },
                                    "SKTestSession produced no Ask-to-Buy transaction")
        try session.approveAskToBuyTransaction(identifier: pending.identifier)
        let delivered = await waitUntil(15) { self.recorder.count("ai.appdna.test.lifetime") >= 1 }
        XCTAssertTrue(delivered, "the approved purchase reaches onPurchaseCompleted")
        let finished = await waitUntil { await self.unfinished("ai.appdna.test.lifetime").isEmpty }
        XCTAssertTrue(finished, "…then it is finished")
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        XCTAssertEqual(recorder.count("ai.appdna.test.lifetime"), 1, "delivered exactly once")
    }

    func testForcedRenewalIsNotAPurchase() async throws {
        configure(.storeKit2)
        AppDNA.billing.setDelegate(recorder, deliversPurchases: true)
        _ = try await AppDNA.billing.purchase("ai.appdna.test.monthly")
        try? await Task.sleep(nanoseconds: 500_000_000)
        let before = recorder.count("ai.appdna.test.monthly")
        try session.forceRenewalOfSubscription(productIdentifier: "ai.appdna.test.monthly")
        await becomeActive()
        try? await Task.sleep(nanoseconds: 2_000_000_000)
        XCTAssertEqual(recorder.count("ai.appdna.test.monthly"), before, "a renewal is not reported as a purchase")
    }

    // MARK: - The RN forwarder: no delivery until JS is ready (D-R40-1, R41)

    /// Records what the RN module emits to JS.
    final class EmitRecorder: NSObject, AppdnaEventSink {
        private let lock = NSLock()
        private var names: [String] = []
        func emitEventNamed(_ name: String, payload: [AnyHashable: Any]) {
            lock.lock(); names.append(name); lock.unlock()
        }
        func count(_ name: String) -> Int { lock.lock(); defer { lock.unlock() }; return names.filter { $0 == name }.count }
        func all() -> [String] { lock.lock(); defer { lock.unlock() }; return names }
    }

    func testRNForwarderTakesNoQueuedPurchaseUntilJSIsReady() async throws {
        try await burnSeenIds()
        let impl = AppdnaModuleImpl()
        let js = EmitRecorder()
        impl.eventSink = js
        let ready = expectation(description: "ready")
        impl.configure("adn_test_placeholder", env: "sandbox", options: ["logLevel": "none"] as NSDictionary,
                       resolve: { _ in }, reject: { _, _, _ in })
        AppDNA.onReady { ready.fulfill() }
        await fulfillment(of: [ready], timeout: 120)
        defer { impl.invalidate() }

        try await makeInterruptedPurchaseLate("ai.appdna.test.coins")
        // The late purchase is reported and queued; the RN forwarder is registered NOT delivering.
        try? await Task.sleep(nanoseconds: 4_000_000_000)
        XCTAssertEqual(js.count("onPurchaseCompleted"), 0, "no JS onPurchaseCompleted yet → nothing may be delivered")
        let queuedBefore = storedQueue()
        XCTAssertTrue(queuedBefore.contains("ai.appdna.test.coins"),
                      "the late purchase must be HELD in the delivery queue before JS is ready: \(queuedBefore)")

        impl.billingDelegateReady(true)
        let delivered = await waitUntil(15) { js.count("onPurchaseCompleted") >= 1 }
        XCTAssertTrue(delivered, "billingDelegateReady(true) drains the queue (queue before: \(queuedBefore); after: \(storedQueue()); emitted: \(js.all()))")
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        XCTAssertEqual(js.count("onPurchaseCompleted"), 1, "delivered exactly once")
    }
}

extension URLSessionConfiguration {
    /// Swapped in for `protocolClasses` by `withCountingProtocol` (after the swap, calling this selector
    /// runs the ORIGINAL getter).
    @objc dynamic func appdnaTest_protocolClasses() -> [AnyClass]? {
        let original = self.appdnaTest_protocolClasses()
        return [AppdnaAAStoreKitHostedTests.CountingURLProtocol.self] + (original ?? [])
    }
}
