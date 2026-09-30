import XCTest
import UserNotifications
import AppDNASDK

/// SPEC-497 §9a.8 — the APP-HOSTED, public-API variant of the notification-proxy tests.
///
/// The core's `AppDNANotificationCenterProxyTests` / `AppDNANotificationBootstrapTests` run hostless
/// against an injected in-memory slot, because `UNUserNotificationCenter.current()` raises
/// "bundleProxyForCurrentProcess is nil" without an app. This pod's `test_spec` is app-hosted, so here
/// the REAL centre is used — through public API only (`AppDNA.installNotificationProxy()`, the class of
/// `UNUserNotificationCenter.current().delegate`, the `diagnose()` report lines).
///
/// The run sets `XCTestConfigurationFilePath`, so the SDK's `+load` launch observer is inert: the proxy
/// is installed EXPLICITLY here (or by the `configure` fallback, when an earlier test in this bundle
/// configured the SDK first — install is once per process), and never by `launch_observer`.
final class AppdnaNotificationProxyHostedTests: XCTestCase {

    override func tearDown() {
        AppDNA.shutdown()
        super.tearDown()
    }

    func testExplicitInstallPutsTheProxyOnTheRealCentre() {
        AppDNA.shutdown()
        let ready = expectation(description: "ready")
        AppDNA.configure(apiKey: "adn_test_placeholder", environment: .sandbox, options: AppDNAOptions(logLevel: .none))
        AppDNA.onReady { ready.fulfill() }
        wait(for: [ready], timeout: 120)

        AppDNA.installNotificationProxy()
        AppDNA.installNotificationProxy()   // idempotent

        let delegate = UNUserNotificationCenter.current().delegate
        XCTAssertNotNil(delegate, "the proxy is the centre's delegate")
        XCTAssertTrue(String(describing: type(of: delegate!)).contains("AppDNANotificationCenterProxy"),
                      "the delegate is AppDNA's proxy, got \(String(describing: delegate))")

        let report = AppDNA.diagnose()
        XCTAssertTrue(report.contains("notificationDelegate: installed"), report)
        XCTAssertFalse(report.contains("notificationProxyInstallSource: launch_observer"),
                       "the +load observer is inert under XCTest")
        XCTAssertTrue(report.contains("notificationProxyInstallSource: explicit")
                      || report.contains("notificationProxyInstallSource: configure_fallback"), report)
    }
}
