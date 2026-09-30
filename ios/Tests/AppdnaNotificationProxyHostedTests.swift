import XCTest
import UserNotifications
import AppDNASDK
@testable import appdna_sdk_react_native

/// SPEC-497 §9a.8 — the APP-HOSTED, public-API variant of the notification-proxy tests.
///
/// The core's proxy/bootstrap tests run hostless against an injected in-memory slot, because
/// `UNUserNotificationCenter.current()` raises without an app. This pod's `test_spec` is app-hosted, so
/// the REAL centre is used — through public API only: `AppDNA.installNotificationProxy()`, the class of
/// `UNUserNotificationCenter.current().delegate`, and the `diagnose()` report lines.
///
/// The run sets `XCTestConfigurationFilePath`, so the SDK's `+load` launch observer is inert, and the
/// install happens EXPLICITLY in the bundle's principal class (`AppdnaTestObservation`) before any test
/// configures the SDK — so the install source is `explicit`, and only `explicit`.
final class AppdnaNotificationProxyHostedTests: XCTestCase {

    override func tearDown() {
        AppDNA.shutdown()
        super.tearDown()
    }

    func testTheExplicitInstallIsTheInstall() {
        let atInstall = AppdnaTestObservation.diagnoseAtInstall
        XCTAssertFalse(atInstall.isEmpty, "the principal class did not run — NSPrincipalClass missing from the test bundle")
        XCTAssertTrue(AppdnaTestObservation.delegateClassAtInstall.contains("AppDNANotificationCenterProxy"),
                      "the delegate after the explicit install is \(AppdnaTestObservation.delegateClassAtInstall)")
        XCTAssertTrue(atInstall.contains("notificationProxyInstallSource: explicit"), atInstall)
    }

    func testTheProxyStaysExplicitAndInstalledAfterConfigure() {
        let ready = expectation(description: "ready")
        AppDNA.configure(apiKey: "adn_test_placeholder", environment: .sandbox, options: AppDNAOptions(logLevel: .none))
        AppDNA.onReady { ready.fulfill() }
        wait(for: [ready], timeout: 120)

        AppDNA.installNotificationProxy()   // idempotent
        let delegate = UNUserNotificationCenter.current().delegate
        XCTAssertTrue(delegate.map { String(describing: type(of: $0)).contains("AppDNANotificationCenterProxy") } ?? false,
                      "the delegate is AppDNA's proxy, got \(String(describing: delegate))")
        let report = AppDNA.diagnose()
        XCTAssertTrue(report.contains("notificationDelegate: installed"), report)
        XCTAssertTrue(report.contains("notificationProxyInstallSource: explicit"),
                      "the install is once per process; `configure` must not re-attribute it: \(report)")
    }
}
