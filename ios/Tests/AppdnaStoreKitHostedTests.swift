import XCTest
import StoreKit
import StoreKitTest
import UIKit
import AppDNASDK
@testable import appdna_sdk_react_native

/// SPEC-497 §3.10 fallback — the StoreKit half of the billing proof, APP-HOSTED.
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
/// renewal (not a purchase). NOT observable through public API, so not asserted here: event PROPERTIES
/// (charged / intro / trial price, `is_trial`) — iOS has no public event observer, and the SDK's
/// uploads go through its own `URLSession`, which a `URLProtocol` cannot intercept.
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
            throw XCTSkip("AppDNATestProducts.storekit is not in the test bundle (podspec test_spec.resources)")
        }
        session = try SKTestSession(contentsOf: url)
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
        let product = try XCTUnwrap(try await Product.products(for: [productId]).first)
        let result = try await product.purchase()
        guard case .success(.verified(let t)) = result else {
            throw XCTSkip("SKTestSession purchase did not succeed in the app-hosted target: \(result)")
        }
        return t
    }

    // MARK: - Ownership

    func testStoreKit2FinishesItsPurchaseAndARenewal() async throws {
        configure(.storeKit2)
        _ = try await AppDNA.billing.purchase("ai.appdna.test.monthly")
        try session.forceRenewalOfSubscription(productIdentifier: "ai.appdna.test.monthly")
        await becomeActive()
        let drained = await waitUntil { await self.unfinished("ai.appdna.test.monthly").isEmpty }
        XCTAssertTrue(drained, "storeKit2 owns transactions: the purchase and its renewal are finished")
    }

    func testRevenueCatNeverFinishesAndRefusesToBuyOrRestore() async throws {
        configure(.revenueCat)
        do {
            _ = try await AppDNA.billing.purchase("ai.appdna.test.monthly")
            XCTFail("revenueCat (not linked): the SDK must not buy")
        } catch {
            XCTAssertEqual(billingErrorType(error), "providerNotAvailable")
        }
        do {
            _ = try await AppDNA.billing.restorePurchases()
            XCTFail("revenueCat (not linked): the SDK must not restore")
        } catch {
            XCTAssertEqual(billingErrorType(error), "providerNotAvailable")
        }
        _ = try await hostBuyWithoutFinishing("ai.appdna.test.monthly")
        await becomeActive()                                             // baseline
        try session.forceRenewalOfSubscription(productIdentifier: "ai.appdna.test.monthly")
        await becomeActive()
        let left = await unfinished("ai.appdna.test.monthly")
        XCTAssertFalse(left.isEmpty, "the SDK finished nothing — the host's purchase and renewal stay unfinished")
        XCTAssertNotNil(UserDefaults.standard.object(forKey: "appdna.billing.last_sub_snapshot_v1"),
                        "the subscription snapshot is still persisted while events are suppressed")
    }

    func testAdaptyUnlinkedNeverFinishes() async throws {
        configure(.adapty(apiKey: "public_test_key"))
        do {
            _ = try await AppDNA.billing.purchase("ai.appdna.test.monthly")
            XCTFail("adapty (not linked): the SDK must not buy")
        } catch {
            XCTAssertEqual(billingErrorType(error), "providerNotAvailable")
        }
        _ = try await hostBuyWithoutFinishing("ai.appdna.test.monthly")
        await becomeActive()
        try session.forceRenewalOfSubscription(productIdentifier: "ai.appdna.test.monthly")
        await becomeActive()
        let left = await unfinished("ai.appdna.test.monthly")
        XCTAssertFalse(left.isEmpty, "adapty (unlinked) emits lifecycle events but never finishes")
        XCTAssertNotNil(UserDefaults.standard.object(forKey: "appdna.billing.last_sub_snapshot_v1"))
    }

    // MARK: - Restore without a server

    func testStoreKit2RestoreSucceedsWithNoServer() async throws {
        // The key is a placeholder and there is no AppDNA server here: a storeKit2 restore reads
        // `Transaction.currentEntitlements` and needs none (replaces device row C1-7i).
        configure(.storeKit2)
        _ = try await AppDNA.billing.purchase("ai.appdna.test.lifetime")
        let restored = try await AppDNA.billing.restorePurchases()
        XCTAssertTrue(restored.contains("ai.appdna.test.lifetime"))
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
        session.interruptedPurchasesEnabled = true
        _ = try? await AppDNA.billing.purchase("ai.appdna.test.coins")
        session.interruptedPurchasesEnabled = false
        guard let interrupted = session.allTransactions().last(where: { $0.productIdentifier == "ai.appdna.test.coins" }) else {
            throw XCTSkip("SKTestSession produced no interrupted transaction in the app-hosted target")
        }
        try session.resolveIssueForTransaction(identifier: interrupted.identifier)
        let delivered = await waitUntil(15) { self.recorder.count("ai.appdna.test.coins") >= 1 }
        XCTAssertTrue(delivered, "the late purchase reaches onPurchaseCompleted")
        let finished = await waitUntil { await self.unfinished("ai.appdna.test.coins").isEmpty }
        XCTAssertTrue(finished, "…then it is finished")
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        XCTAssertEqual(recorder.count("ai.appdna.test.coins"), 1, "delivered exactly once")
    }

    func testAskToBuyApprovalIsDeliveredOnceThenFinished() async throws {
        configure(.storeKit2)
        AppDNA.billing.setDelegate(recorder, deliversPurchases: true)
        session.askToBuyEnabled = true
        _ = try? await AppDNA.billing.purchase("ai.appdna.test.lifetime")   // pending: waiting for approval
        session.askToBuyEnabled = false
        guard let pending = session.allTransactions().last(where: { $0.productIdentifier == "ai.appdna.test.lifetime" }) else {
            throw XCTSkip("SKTestSession produced no Ask-to-Buy transaction in the app-hosted target")
        }
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
}
