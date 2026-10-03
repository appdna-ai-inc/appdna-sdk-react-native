import Foundation
import StoreKitTest
import UserNotifications
import AppDNASDK

/// The test bundle's principal class (`NSPrincipalClass`, set by the podspec's
/// `test_spec.info_plist`). XCTest instantiates it on the main thread BEFORE any test runs — so before
/// any test in this process configures the SDK — which is the only moment an explicit
/// `AppDNA.installNotificationProxy()` can be proven to be the install (the proxy installs once per
/// process; a `configure` first would make it `configure_fallback`). What the install reported is kept
/// for `AppdnaNotificationProxyHostedTests`.
@objc(AppdnaTestObservation)
final class AppdnaTestObservation: NSObject {
    static var diagnoseAtInstall = ""
    static var delegateClassAtInstall = ""

    /// The ONE StoreKit test session of this process, opened before anything touches
    /// StoreKit (the handler pass drives `purchase` / `restorePurchases`). `AppdnaStoreKitHostedTests`
    /// reuses it. Hygiene, not a fix: measured on the bridge, the late-purchase tests also pass with a
    /// fresh session per test — the losses once blamed on session binding were the core queue's stale
    /// in-memory reported set (fixed in `PurchaseDeliveryQueue.activate`).
    static var storeKitSession: SKTestSession?

    override init() {
        super.init()
        if let url = Bundle(for: Self.self).url(forResource: "AppDNATestProducts", withExtension: "storekit") {
            Self.storeKitSession = try? SKTestSession(contentsOf: url)
            Self.storeKitSession?.disableDialogs = true
        }
        AppDNA.installNotificationProxy()
        Self.diagnoseAtInstall = AppDNA.diagnose()
        Self.delegateClassAtInstall = UNUserNotificationCenter.current().delegate.map { String(describing: type(of: $0)) } ?? "nil"
    }
}
