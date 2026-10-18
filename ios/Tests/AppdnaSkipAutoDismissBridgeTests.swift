import XCTest
import AppDNASDK
@testable import appdna_sdk_react_native

/**
 #970 — the paywall auto-dismiss opt-out reaches the native flag from React Native.

 ## What is under test, and what is NOT

 That the FLAG suppresses the dismiss is the native SDK's own property, held by `PaywallManager`'s
 own suite: the restore handler re-reads `skipNextAutoDismissOnRestore` on the line after it
 forwards `onPaywallRestoreCompleted`, and clears it on every terminal event. Repeating that here
 would be testing the SDK through a wrapper.

 What was missing is the WRAPPER half, and it is the half that was actually broken: React Native had
 no way to set the flag at all, while iOS, Android and Flutter all did. So the assertion is that the
 module method writes THE SAME property the restore handler reads — not a private copy, not a no-op
 body.

 ## Why the baseline read is the control, not ceremony

 The defect shape for a bridged method is a body that runs and does nothing (SPEC-070-B's
 "bridged-but-dead"), and `AppdnaHandlerPassTests` can only see that the body ran. A test that
 asserted `set(true) ⇒ true` alone would also pass against a flag already `true`, or a setter stuck
 at `true`. Reading the baseline, writing both values and reading back each time separates a real
 setter from both.

 The Android twin is `SkipAutoDismissBridgeTest.kt`; the two platforms are different code, so one
 green says nothing about the other.
 */
final class AppdnaSkipAutoDismissBridgeTests: XCTestCase {

    private let module = AppdnaModuleImpl()

    /// The flag is global SDK state — leaving it set would arm an unrelated test's restore.
    override func tearDown() {
        AppDNA.paywall.skipNextAutoDismissOnRestore = false
        super.tearDown()
    }

    func testModuleSetterWritesTheFlagTheRestoreHandlerReads() {
        XCTAssertFalse(
            AppDNA.paywall.skipNextAutoDismissOnRestore,
            "the SDK's default is to auto-dismiss after a restore that found products"
        )

        module.skipNextAutoDismissOnRestore(true)
        XCTAssertTrue(
            AppDNA.paywall.skipNextAutoDismissOnRestore,
            "skipNextAutoDismissOnRestore(true) did not reach AppDNA.paywall — a bridged-but-dead body"
        )

        module.skipNextAutoDismissOnRestore(false)
        XCTAssertFalse(
            AppDNA.paywall.skipNextAutoDismissOnRestore,
            "the setter could not take the flag back down — a host cannot cancel its own opt-out"
        )
    }

    /**
     The flag is settable BEFORE any paywall is presented, which is what makes the documented usage
     possible at all: a React Native host must arm it before the Restore tap, because its
     `onPaywallRestoreCompleted` crosses the bridge asynchronously and runs after native has already
     decided. Were `AppDNA.paywall` to require a live presentation, that advice would be impossible
     to follow and the API would be useless on this platform.
     */
    func testFlagIsSettableWithNoPaywallOnScreen() {
        module.skipNextAutoDismissOnRestore(true)
        XCTAssertTrue(AppDNA.paywall.skipNextAutoDismissOnRestore)
    }
}
