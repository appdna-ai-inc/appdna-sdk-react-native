import XCTest
import StoreKit
import StoreKitTest
import UIKit
import AppDNASDK
@testable import appdna_sdk_react_native

/// SPEC-497 §3.10 fallback — the StoreKit half of the billing proof, APP-HOSTED.
///
/// Runs in whatever order XCTest picks — after other classes that configure and shut the SDK down in
/// this process. That order once lost every late purchase: `SKTestSession` reuses transaction ids, and
/// the core queue kept its reported set in memory across `shutdown()` → `configure()`, so an id the
/// PREVIOUS run had reported was finished silently even though `setUp` had cleared the persisted store.
/// Core now reloads the persisted store on every `configure()`; `testLatePurchaseAfterShutdownAndReconfigure…`
/// covers the in-process restart itself.
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
/// renewal (not a purchase). The no-network restore records the SDK's requests by putting a recording
/// `URLProtocol` into every default `URLSessionConfiguration` (see `withCountingProtocol`): its result needs
/// no server, and its only call is the background `/billing/verify` of what it granted (§17-4). NOT asserted
/// here: event PROPERTIES (charged / intro / trial price, `is_trial`) — iOS has no public event observer,
/// and no recorded request is ever answered, so no upload body is ever inspected.
final class AppdnaStoreKitHostedTests: XCTestCase {

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
            throw NSError(domain: "AppdnaStoreKitHostedTests", code: 1)
        }
        // The process-wide session opened by the principal class, before any test touched StoreKit.
        session = try AppdnaTestObservation.storeKitSession ?? SKTestSession(contentsOf: url)
        session.resetToDefaultState()
        session.disableDialogs = true
        session.clearTransactions()
        recorder = DeliveryRecorder()
    }

    override func tearDown() {
        AppDNA.billing.setDelegate(nil)
        AppDNA.shutdown()
        session?.clearTransactions()
        super.tearDown()
    }

    // MARK: - Helpers

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

    private func waitUntil(_ timeout: TimeInterval = 30, _ condition: () async -> Bool) async -> Bool {
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
            throw NSError(domain: "AppdnaStoreKitHostedTests", code: 2)
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
        _ = await waitUntil(20) { self.sessionIds("ai.appdna.test.monthly").count >= 2 }
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
        _ = await waitUntil(20) { self.sessionIds("ai.appdna.test.monthly").count >= 2 }
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
        _ = await waitUntil(20) { self.sessionIds("ai.appdna.test.monthly").count >= 2 }
        let ids = sessionIds("ai.appdna.test.monthly")
        XCTAssertGreaterThanOrEqual(ids.count, 2, "the renewal was not created")
        await assertStillUnfinished(ids, "ai.appdna.test.monthly", "adapty (unlinked) emits but never finishes")
        XCTAssertTrue(snapshotProducts().contains("ai.appdna.test.monthly"), "\(snapshotProducts())")
    }

    // MARK: - Restore without a server

    /// Answers every AppDNA request the SDK's default-configuration session makes with a network error and
    /// records the path of each one that is not an event upload (`/events`: event traffic is batched on its
    /// own timer and may legitimately flush during a restore). While `stallsVerify` is on, a
    /// `/billing/verify` request gets NO answer until `releaseStalled()` — a restore that waited for server
    /// verification would then never return.
    final class CountingURLProtocol: URLProtocol {
        private static let lock = NSLock()
        private static var _paths: [String] = []
        private static var _stallsVerify = false
        private static var _stalled: [CountingURLProtocol] = []
        static var paths: [String] { lock.lock(); defer { lock.unlock() }; return _paths }
        static var count: Int { paths.count }
        static func reset() { lock.lock(); _paths = []; lock.unlock() }
        static func stallVerify(_ on: Bool) { lock.lock(); _stallsVerify = on; lock.unlock() }
        /// Fails every held `/billing/verify` request, so nothing is left in flight after the test.
        static func releaseStalled() {
            lock.lock(); let held = _stalled; _stalled = []; _stallsVerify = false; lock.unlock()
            for p in held { p.fail() }
        }
        override class func canInit(with request: URLRequest) -> Bool {
            guard let url = request.url, let host = url.host, host.contains("appdna") else { return false }
            if !url.path.contains("/events") { lock.lock(); _paths.append(url.path); lock.unlock() }
            return true
        }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            Self.lock.lock()
            let hold = Self._stallsVerify && (request.url?.path.hasSuffix("/billing/verify") ?? false)
            if hold { Self._stalled.append(self) }
            Self.lock.unlock()
            if !hold { fail() }
        }
        private func fail() { client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet)) }
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

    func testStoreKit2RestoreNeedsNoServerAndOnlyQueuesVerification() async throws {
        // C1-7i + §17-4: a storeKit2 restore reads `Transaction.currentEntitlements`, so its result needs no
        // AppDNA server. Every transaction it grants is ALSO queued for `POST /billing/verify` — in the
        // background, never awaited (docs/sdks/ios/billing.mdx "verifies each transaction … on the AppDNA
        // server"). So: the restore returns its products while `/billing/verify` is held unanswered and every
        // other request fails, and `/billing/verify` is the only AppDNA call it causes.
        try await withCountingProtocol {
            try await restoreWithoutNetwork()
        }
    }

    private func restoreWithoutNetwork() async throws {
        CountingURLProtocol.reset()
        defer { CountingURLProtocol.releaseStalled() }
        configure(.storeKit2)
        _ = try await AppDNA.billing.purchase("ai.appdna.test.lifetime")
        // The purchase's own background verification retries (the API client retries a failed request after
        // 1, 2 and 4 s). Wait until the SDK has been quiet for 6 s — longer than the longest retry gap — so
        // every request counted below is the restore's: while the purchase's send is in flight the queue skips
        // a second send of the same transaction, and a retry of the purchase's send would pass for the restore's.
        let quiet = await waitUntilQuiet(seconds: 6, timeout: 60)
        XCTAssertTrue(quiet, "the SDK never went quiet after the purchase (calls: \(CountingURLProtocol.paths))")
        // Control: the counter SEES the SDK's non-event requests (the bootstrap and the purchase's
        // verification above), so an empty list below means "no call", not "a call it could not see".
        XCTAssertTrue(CountingURLProtocol.paths.contains { $0.hasSuffix("/billing/verify") },
                      "the counter did not see the purchase's /billing/verify — the checks below would prove nothing (calls: \(CountingURLProtocol.paths))")
        CountingURLProtocol.reset()
        CountingURLProtocol.stallVerify(true)

        let done = Flag()
        let restore = Task { () throws -> [String] in
            defer { done.set() }
            return try await AppDNA.billing.restorePurchases()
        }
        // A held request ends only at the API client's 30 s timeout (then it is retried), so a restore that waits
        // for it takes > 30 s; 25 s leaves a loaded simulator room for a restore that does not.
        let returned = await waitUntil(25) { done.isSet }
        if !returned {
            XCTFail("restorePurchases() did not return while /billing/verify was unanswered — the restore waits for the server (calls: \(CountingURLProtocol.paths))")
            CountingURLProtocol.releaseStalled()           // let it finish, so the test does not hang
        }
        let restored = try await restore.value
        XCTAssertTrue(restored.contains("ai.appdna.test.lifetime"), "restored: \(restored)")

        // The background verification of the restored transaction is sent (not awaited by the restore).
        let verified = await waitUntil(30) { CountingURLProtocol.paths.contains { $0.hasSuffix("/billing/verify") } }
        XCTAssertTrue(verified, "the restored transaction was not queued for /billing/verify (calls: \(CountingURLProtocol.paths))")
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        let others = CountingURLProtocol.paths.filter { !$0.hasSuffix("/billing/verify") }
        XCTAssertEqual(others, [], "a storeKit2 restore made AppDNA network call(s) other than the background /billing/verify")
    }

    /// True once no new request was counted for `seconds`.
    private func waitUntilQuiet(seconds: TimeInterval, timeout: TimeInterval) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        var last = CountingURLProtocol.count
        var since = Date()
        while Date() < deadline {
            try? await Task.sleep(nanoseconds: 250_000_000)
            let now = CountingURLProtocol.count
            if now != last { last = now; since = Date() } else if Date().timeIntervalSince(since) >= seconds { return true }
        }
        return false
    }

    /// Set once, read from any thread.
    final class Flag: @unchecked Sendable {
        private let lock = NSLock()
        private var value = false
        func set() { lock.lock(); value = true; lock.unlock() }
        var isSet: Bool { lock.lock(); defer { lock.unlock() }; return value }
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
        configure(.storeKit2)
        AppDNA.billing.setDelegate(recorder, deliversPurchases: true)
        try await makeInterruptedPurchaseLate("ai.appdna.test.coins")
        let delivered = await waitUntil(45) { self.recorder.count("ai.appdna.test.coins") >= 1 }
        XCTAssertTrue(delivered, "the late purchase reaches onPurchaseCompleted")
        let finished = await waitUntil { await self.unfinished("ai.appdna.test.coins").isEmpty }
        XCTAssertTrue(finished, "…then it is finished")
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        XCTAssertEqual(recorder.count("ai.appdna.test.coins"), 1, "delivered exactly once")
    }

    /// A host that restarts the SDK in-process (`shutdown()` → `configure()`, e.g. sign-out → sign-in)
    /// must still get exactly one delivery of a late purchase that completes afterwards.
    func testLatePurchaseAfterShutdownAndReconfigureIsDeliveredOnce() async throws {
        configure(.storeKit2)
        AppDNA.shutdown()
        configure(.storeKit2)
        AppDNA.billing.setDelegate(recorder, deliversPurchases: true)
        try await makeInterruptedPurchaseLate("ai.appdna.test.coins")
        let delivered = await waitUntil(45) { self.recorder.count("ai.appdna.test.coins") >= 1 }
        XCTAssertTrue(delivered, "the late purchase reaches onPurchaseCompleted after a restart (queue: \(storedQueue()))")
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
        configure(.storeKit2)
        AppDNA.billing.setDelegate(recorder, deliversPurchases: true)
        session.askToBuyEnabled = true
        _ = try? await AppDNA.billing.purchase("ai.appdna.test.lifetime")   // pending: waiting for approval
        session.askToBuyEnabled = false
        let pending = try XCTUnwrap(session.allTransactions().first { $0.productIdentifier == "ai.appdna.test.lifetime" && $0.pendingAskToBuyConfirmation },
                                    "SKTestSession produced no Ask-to-Buy transaction")
        try session.approveAskToBuyTransaction(identifier: pending.identifier)
        let delivered = await waitUntil(45) { self.recorder.count("ai.appdna.test.lifetime") >= 1 }
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
        // The late purchase is reported and queued; the RN forwarder is registered NOT delivering. Polled, not a
        // fixed sleep: under load the observer can take longer than any fixed wait to queue it.
        let queued = await waitUntil(45) { self.storedQueue().contains("ai.appdna.test.coins") }
        XCTAssertTrue(queued, "the late purchase never reached the delivery queue: \(storedQueue())")
        try? await Task.sleep(nanoseconds: 1_000_000_000)   // a delivery would follow the queueing at once
        XCTAssertEqual(js.count("onPurchaseCompleted"), 0, "no JS onPurchaseCompleted yet → nothing may be delivered")
        let queuedBefore = storedQueue()
        XCTAssertTrue(queuedBefore.contains("ai.appdna.test.coins"),
                      "the late purchase must be HELD in the delivery queue before JS is ready: \(queuedBefore)")

        impl.billingDelegateReady(true)
        let delivered = await waitUntil(45) { js.count("onPurchaseCompleted") >= 1 }
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
        return [AppdnaStoreKitHostedTests.CountingURLProtocol.self] + (original ?? [])
    }
}
