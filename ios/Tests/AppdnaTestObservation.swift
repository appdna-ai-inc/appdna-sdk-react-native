import Foundation
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

    override init() {
        super.init()
        AppDNA.installNotificationProxy()
        Self.diagnoseAtInstall = AppDNA.diagnose()
        Self.delegateClassAtInstall = UNUserNotificationCenter.current().delegate.map { String(describing: type(of: $0)) } ?? "nil"
    }
}
