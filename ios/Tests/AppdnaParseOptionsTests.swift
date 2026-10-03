import XCTest
import AppDNASDK
@testable import appdna_sdk_react_native

/**
 The native `parseOptions` mapping, on iOS.

 ## Why this target exists at all

 The iOS half of this wrapper had **zero** unit tests. Everything AC-11 asks about — the `framework`
 tag, the `configTTL` default, the `billingProvider` mapping — was proven on Android only, and
 "Android is right" is not evidence about Swift. The two `parseOptions` implementations are separate
 hand-written functions; the entire bug class AC-11 exists for is *they drifted*. E7's `?? 300` was a
 wrapper literal that sat 12× below the native `configTTL` and made every wrapped app re-fetch its
 config twelve times as often as a native one. It was in the SWIFT file.

 A jest test cannot reach any of this: it mocks the native module away, so it observes neither a
 Swift `??` default nor the bridge's injected tag. Only a native test does.

 ## Why a podspec `test_spec` and not a target in the example `.xcodeproj`

 AC-11 rules this explicitly, and it is worth restating because the obvious thing does not work: the
 RN module compiles as a **Pod**. CocoaPods does not wire `ENABLE_TESTABILITY` for an ad-hoc test
 target bolted onto the example app's project, and RN's static linkage makes `@testable import` of a
 pod module fragile-to-infeasible there. A `test_spec` is CocoaPods' own mechanism: it wires
 testability, `@testable import` resolves, and `pod lib lint` runs test_specs by default.

 ⚠️ A `test_spec` launches a simulator, so this runs on a **macOS** runner only
 (`react-native-ios-compile`, Node 20 — see .github/workflows/sdk-ci.yml).

 ## What `AppDNAOptions()` gives us

 It is constructible with no arguments, so the ORACLE for every default is the native type itself.
 That matters: asserting `configTTL == 3600` against a literal would re-create the exact defect —
 a number in the wrapper that agrees with nothing. Every default below is compared against
 `AppDNAOptions()`'s own value, so if native moves, this test moves with it, and the wrapper is
 forced to move too.
 */
final class AppdnaParseOptionsTests: XCTestCase {

    private let module = AppdnaModuleImpl()

    /// The native defaults. The oracle — never a literal.
    private let defaults = AppDNAOptions()

    // MARK: - AC-11 leg 1: the `framework` tag

    func testFrameworkTagIsAlwaysReactNative() {
        // Omitted entirely.
        XCTAssertEqual(module.parseOptions(nil).framework, "react_native")
        XCTAssertEqual(module.parseOptions([:]).framework, "react_native")

        // A host must not be able to set, spoof or omit its own attribution. The tag is
        // INJECTED, never read from `values` — so a host claiming to be native stays react_native.
        // `event-envelope.schema.ts` is `.catch('native')`: a wrong tag does not error, is not logged
        // and is not metered. It just quietly lies in BigQuery, which is the worst possible failure.
        XCTAssertEqual(module.parseOptions(["framework": "native"]).framework, "react_native")
        XCTAssertEqual(module.parseOptions(["framework": "flutter"]).framework, "react_native")

        // Underscore, not hyphen. `'react-native'` was M1 and matched nothing downstream.
        XCTAssertFalse(module.parseOptions(nil).framework.contains("-"))
    }

    func testWrapperVersionIsSelfReported() {
        // The wrapper's OWN version, not the native core's. Flutter shipped this constant stuck at
        // 1.0.6 while publishing 1.0.8 — `diagnose()` and every event envelope reported a version
        // that had not been released, for two cycles, and nothing noticed.
        // `check:wrapper-version-selfreport` pins the value against package.json; this pins that the
        // field is populated at all and is not read from the host.
        let parsed = module.parseOptions(["frameworkVersion": "9.9.9"])
        XCTAssertNotNil(parsed.frameworkVersion)
        XCTAssertNotEqual(
            parsed.frameworkVersion, "9.9.9",
            "frameworkVersion must be injected by the wrapper, not accepted from the host"
        )
    }

    // MARK: - AC-11 leg 2: `configTTL` (E7 — the 12× drift)

    func testConfigTTLDefaultsToTheNativeValueNotAWrapperLiteral() {
        // 🔴 THE BUG. A `?? 300` here sat 12× below native's 3600, so every RN app re-fetched its
        // remote config twelve times as often as a native one — burning battery and quota — and no
        // test could see it, because the only place the number existed was a Swift `??`.
        XCTAssertEqual(module.parseOptions(nil).configTTL, defaults.configTTL)
        XCTAssertEqual(module.parseOptions([:]).configTTL, defaults.configTTL)

        // A host value is honored.
        XCTAssertEqual(module.parseOptions(["configTTL": 120.0]).configTTL, 120)

        // A JS number crosses the bridge as an **NSNumber**, and an integral JS number is an integral
        // NSNumber — `as? TimeInterval` must still accept it. Writing this as `["configTTL": 120]`
        // would be a Swift `Int`, which does NOT bridge to `TimeInterval` and would prove the
        // opposite of what it looks like it proves. The real dictionary comes from ObjC; model that.
        XCTAssertEqual(module.parseOptions(["configTTL": NSNumber(value: 120)]).configTTL, 120)
        XCTAssertEqual(module.parseOptions(["batchSize": NSNumber(value: 9)]).batchSize, 9)
    }

    func testTheOtherScalarsAlsoDefaultToNative() {
        let d = module.parseOptions(nil)
        XCTAssertEqual(d.flushInterval, defaults.flushInterval)
        XCTAssertEqual(d.batchSize, defaults.batchSize)
        XCTAssertEqual(d.vetoTimeout, defaults.vetoTimeout)
        XCTAssertEqual(d.requireConsent, defaults.requireConsent)

        let set = module.parseOptions([
            "flushInterval": 5.0,
            "batchSize": 7,
            "vetoTimeout": 11.0,
            "requireConsent": true,
        ])
        XCTAssertEqual(set.flushInterval, 5)
        XCTAssertEqual(set.batchSize, 7)
        XCTAssertEqual(set.vetoTimeout, 11)
        XCTAssertTrue(set.requireConsent)
    }

    /// The three runtime settings reach native only when the host set them: native resolves host option >
    /// bootstrap `settings` > default, so a value the wrapper filled in would beat the server's.
    func testRuntimeSettingsAreUnsetUnlessTheHostSetThem() {
        let unset = module.parseOptions(nil)
        XCTAssertNil(unset.requestedFlushInterval)
        XCTAssertNil(unset.requestedBatchSize)
        XCTAssertNil(unset.requestedConfigTTL)

        let some = module.parseOptions(["batchSize": NSNumber(value: 7)])
        XCTAssertEqual(some.requestedBatchSize, 7)
        XCTAssertNil(some.requestedFlushInterval)
        XCTAssertNil(some.requestedConfigTTL)

        let all = module.parseOptions(["flushInterval": NSNumber(value: 5), "batchSize": NSNumber(value: 0),
                                       "configTTL": NSNumber(value: 120)])
        XCTAssertEqual(all.requestedFlushInterval, 5)
        XCTAssertEqual(all.requestedBatchSize, 0)
        XCTAssertEqual(all.requestedConfigTTL, 120)
    }

    // MARK: - AC-11 leg 3 / AC-21: `billingProvider`

    func testBillingProviderBareStrings() {
        XCTAssertEqual(module.parseOptions(["billingProvider": "revenueCat"]).billingProvider, BillingProvider.revenueCat)
        XCTAssertEqual(module.parseOptions(["billingProvider": "storeKit2"]).billingProvider, BillingProvider.storeKit2)
        // `BillingProvider.none`, spelled out: a bare `.none` inside XCTAssertEqual's generic
        // overloads binds to `Optional.none` and the assertion silently changes meaning.
        XCTAssertEqual(module.parseOptions(["billingProvider": "none"]).billingProvider, BillingProvider.none)
    }

    func testBillingProviderAdaptyNeedsItsKey() {
        // The tagged-map form carries the associated value.
        XCTAssertEqual(
            module.parseOptions(["billingProvider": ["type": "adapty", "apiKey": "pk_live_1"]]).billingProvider,
            BillingProvider.adapty(apiKey: "pk_live_1")
        )

        // A BARE "adapty" carries no key. Constructing `.adapty(apiKey: "")` would hand the Adapty SDK
        // an empty key and fail at runtime, far from the cause — so it is REFUSED and falls back to
        // the native default. Same for a tagged map with an empty key.
        XCTAssertEqual(module.parseOptions(["billingProvider": "adapty"]).billingProvider, defaults.billingProvider)
        XCTAssertEqual(
            module.parseOptions(["billingProvider": ["type": "adapty", "apiKey": ""]]).billingProvider,
            defaults.billingProvider
        )
    }

    func testBillingProviderUnknownFallsBackToNativeDefault() {
        XCTAssertEqual(module.parseOptions(["billingProvider": "paddle"]).billingProvider, defaults.billingProvider)
        XCTAssertEqual(module.parseOptions(nil).billingProvider, defaults.billingProvider)
    }

    // MARK: - `logLevel`

    func testLogLevelMapsEveryWireValueAndDefaultsToNative() {
        XCTAssertEqual(module.parseOptions(["logLevel": "none"]).logLevel, LogLevel.none)
        XCTAssertEqual(module.parseOptions(["logLevel": "error"]).logLevel, LogLevel.error)
        XCTAssertEqual(module.parseOptions(["logLevel": "warning"]).logLevel, LogLevel.warning)
        XCTAssertEqual(module.parseOptions(["logLevel": "info"]).logLevel, LogLevel.info)
        XCTAssertEqual(module.parseOptions(["logLevel": "debug"]).logLevel, LogLevel.debug)
        XCTAssertEqual(module.parseOptions(["logLevel": "verbose"]).logLevel, defaults.logLevel)
        XCTAssertEqual(module.parseOptions(nil).logLevel, defaults.logLevel)
    }
}
