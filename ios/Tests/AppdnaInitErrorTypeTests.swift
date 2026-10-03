import XCTest
import AppDNASDK
@testable import appdna_sdk_react_native

/**
 The `type` of an init error reaching JS (`onInitDegraded`, `getLastInitError`) is the name Android sends
 (`throwable::class.java.simpleName`: `BootstrapFailed`, `SubsystemFailed`, `FirebaseConfigMissing`), so one JS
 branch on `type` works on both platforms. iOS sent "AppDNAInitError" for every case.

 NEGATIVE CONTROL: `InitForwarder` emitting `String(describing: type(of: reason))` again → "AppDNAInitError", and
 the first assertion fails.
 */
final class AppdnaInitErrorTypeTests: XCTestCase {
    func testInitDegradedCarriesTheAndroidTypeName() {
        var captured: [String: Any]?
        let forwarder = InitForwarder(emit: { name, body in
            if name == "onInitDegraded" { captured = body }
        })
        forwarder.onInitDegraded(reason: AppDNAInitError.bootstrapFailed("offline"))
        XCTAssertEqual(captured?["type"] as? String, "BootstrapFailed")

        XCTAssertEqual(appdnaInitErrorTypeName(AppDNAInitError.subsystemFailed(name: "a", message: "b")), "SubsystemFailed")
        XCTAssertEqual(appdnaInitErrorTypeName(AppDNAInitError.firebaseConfigMissing("x")), "FirebaseConfigMissing")
        XCTAssertEqual(appdnaInitErrorTypeName(AppDNAInitError.unsupportedBlockType("x")), "UnsupportedBlockType")
        struct Other: Error {}
        XCTAssertEqual(appdnaInitErrorTypeName(Other()), "Other")
    }
}
