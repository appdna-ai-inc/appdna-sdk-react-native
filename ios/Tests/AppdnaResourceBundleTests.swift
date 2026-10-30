import XCTest
@testable import AppDNASDK

/// The SDK's resources reach a CocoaPods host.
///
/// The Google sign-in mark was read through `Bundle.module`, which only SwiftPM generates: as a pod the SDK did not
/// compile at all, and every React Native and Flutter iOS host is a pod. The podspec's `resource_bundles` also left
/// the PNG out, so even a compiling lookup would have found nothing and fallen back to the letterform.
///
/// The SwiftPM package's own tests cannot see either half — `Bundle.module` exists there. This test runs where the
/// defect lived: inside a CocoaPods build of the SDK, resolving the mark through the same `AppDNAResources.bundle`
/// the renderer calls.
///
/// Red controls: remove the PNG from the podspec's `resource_bundles` and the first test fails; point the renderer
/// back at `Bundle.module` and this target does not compile.
final class AppdnaResourceBundleTests: XCTestCase {
    func testThePodResolvesTheGoogleMarkFromItsOwnResourceBundle() throws {
        let bundle = try XCTUnwrap(AppDNAResources.bundle, "AppDNASDK.bundle was not found next to the SDK's code")
        let url = try XCTUnwrap(bundle.url(forResource: "appdna_google_g", withExtension: "png"),
                                "appdna_google_g.png is not in \(bundle.bundlePath)")
        // It must be a real, decodable image — an empty or mangled file would pass a bare URL check and still
        // draw nothing.
        let data = try Data(contentsOf: url)
        XCTAssertNotNil(UIImage(data: data), "appdna_google_g.png in the pod bundle does not decode as an image")
    }

    /// Negative control for the harness: the bundle the test resolved is the SDK's resource bundle — it also carries
    /// the privacy manifest the podspec always shipped — and an absent resource is reported absent, so the lookup
    /// above cannot pass by resolving against some unrelated bundle that answers every query.
    func testTheResolvedBundleIsTheSDKResourceBundle() throws {
        let bundle = try XCTUnwrap(AppDNAResources.bundle)
        XCTAssertEqual(bundle.bundleURL.lastPathComponent, "AppDNASDK.bundle")
        XCTAssertNotNil(bundle.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"))
        XCTAssertNil(bundle.url(forResource: "appdna_no_such_resource", withExtension: "png"))
    }
}
