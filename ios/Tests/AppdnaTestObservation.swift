import Foundation
import StoreKitTest
import UserNotifications
import AppDNASDK

/// SPEC-497 §9a.8 — the test bundle's principal class (`NSPrincipalClass`, set by the podspec's
/// `test_spec.info_plist`). XCTest instantiates it on the main thread BEFORE any test runs — so before
/// any test in this process configures the SDK — which is the only moment an explicit
/// `AppDNA.installNotificationProxy()` can be proven to be the install (the proxy installs once per
/// process; a `configure` first would make it `configure_fallback`). What the install reported is kept
/// for `AppdnaNotificationProxyHostedTests`.
@objc(AppdnaTestObservation)
final class AppdnaTestObservation: NSObject {
    static var diagnoseAtInstall = ""
    static var delegateClassAtInstall = ""

    /// SPEC-497 §3.10 — the ONE StoreKit test session of this process, opened before anything touches
    /// StoreKit. An earlier test that calls StoreKit with no session (the handler pass drives
    /// `purchase` / `restorePurchases`) binds the process to the real sandbox environment, after which
    /// `Transaction.updates` no longer carries a later session's transactions — the late-purchase tests
    /// then see nothing. `AppdnaAAStoreKitHostedTests` reuses this session.
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
