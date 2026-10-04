import XCTest
import React
import AppDNASDK
@testable import appdna_sdk_react_native

/**
 The anti-"bridged-but-dead" evidence pass, iOS half.

 ## The bug class, restated because it is the one that keeps happening

 070-C's DOMINANT defect was not a wrong signature. It was **"the method is bridged and nothing ever
 calls it"** — a delegate that never fired, an event nobody emitted, a parameter silently dropped. Each
 of those compiles, satisfies `check:rn-facade-parity` (which proves the method EXISTS in the IR, in
 Kotlin, in Swift, in the ObjC++ adapter and in the TS spec), and passes a jest suite that mocks native
 away. **Existence is not reachability.** Only running the code tells the two apart, and this branch
 found three more of them.

 ## Why the 55 calls are written out, when the Android half reflects

 Because the safety is in the GATE, not in the list. `scripts/check-rn-handler-evidence.ts` extracts
 the manifest from the SOURCES — every `@objc(...)` selector in AppdnaModuleImpl.swift — and demands
 an evidence row for each. Forget a call here and the gate names the method that has no evidence. So an
 explicit list cannot silently drift: it can only fail loudly. (Swift cannot do what the Kotlin half
 does anyway — `NSInvocation` is unavailable in Swift, so a reflective pass over selectors of varying
 arity is not expressible.)

 ## What counts as REACHED

 The method's body ran. For the 50 methods that take a promise pair, that means the promise SETTLED —
 resolved or rejected, either one proves the body ran to completion. `returned normally` is deliberately
 NOT accepted for them, because an empty override returns normally too:

     @objc(getProducts:resolve:reject:)
     public func getProducts(_ ids: NSArray, resolve: ..., reject: ...) { }   // ← bridged, dead

 …and that is the corpse. A promise that never settles is also the E6 defect in its own right: a JS
 `await` on it hangs for the life of the process. The five void methods (`track`, `setLogLevel`,
 `notifyScreenAppeared`, `suppressMessages`, `respondToHostCallback`) have no promise to settle;
 returning is all they can do.

 ## Events are not here, and that is stated rather than hidden

 The ObjC++ `emitEventNamed:` table dispatches to the codegen'd `emitOnX:`, which calls a C++
 `EventEmitterCallback` that the React runtime installs. An XCTest has no React runtime, so that
 callback is an empty `std::function` and invoking it is a hard crash — not a test. The iOS event table
 is covered statically by `check:rn-facade-parity` (bidirectionally) plus the `RCTAssert(NO)`
 fall-through in AppdnaModule.mm, and at runtime by the AC-2 device pass. The gate prints this limit on
 every run, because an exclusion nobody can see is how a gate starts lying.
 */
final class AppdnaHandlerPassTests: XCTestCase {

    /// How each method settled, keyed by method name. Written from whichever thread native answers on.
    private let lock = NSLock()
    private var settled: [String: String] = [:]
    private var driven: [String: Bool] = [:]

    func testEveryBridgedMethodIsActuallyReached() throws {
        let impl = AppdnaModuleImpl()

        /// Hand a method its promise pair, run it, and record whether it ever answered.
        func drive(_ name: String, settles: Bool, _ call: (@escaping RCTPromiseResolveBlock, @escaping RCTPromiseRejectBlock) -> Void) {
            let res: RCTPromiseResolveBlock = { [weak self] _ in self?.record(name, "resolved") }
            let rej: RCTPromiseRejectBlock = { [weak self] code, _, _ in self?.record(name, "rejected: \(code ?? "?")") }
            self.lock.lock(); self.driven[name] = settles; self.lock.unlock()
            call(res, rej)
        }

        // CONFIGURE FIRST, and wait for the SDK to actually reach READY.
        //
        // Not a nicety. `onReady`'s whole contract is "settle when the SDK becomes ready", so against an
        // unconfigured singleton it correctly never settles — and the strict rule below would then call
        // it dead, which is the gate lying about the code rather than the code lying about itself. The
        // Android half does exactly this, for exactly this reason.
        let ready = expectation(description: "the SDK reached ready")
        let bootRes: RCTPromiseResolveBlock = { _ in }
        let bootRej: RCTPromiseRejectBlock = { _, _, _ in }
        impl.configure("adn_test_placeholder", env: "sandbox", options: [:] as NSDictionary, resolve: bootRes, reject: bootRej)
        AppDNA.onReady { ready.fulfill() }
        // 120s, not 30s. Reaching ready runs a REAL bootstrap attempt (this key is a placeholder, so
        // it is the failure path — network + retry/backoff before the SDK settles into degraded-ready).
        // That is wall-clock work on a shared macOS runner, and 30s went red here on a loaded one while
        // passing on a quiet one — a flake that says nothing about the wrapper. The generous ceiling
        // costs nothing on a healthy run (the expectation fulfils as soon as ready fires) and only
        // changes how long a genuine never-ready hang takes to report.
        wait(for: [ready], timeout: 120)

        // ── Every bridged method, driven against a live singleton ────────────────
        //
        // Generated from the real `@objc(...)` signatures, so no argument is guessed. The gate is what
        // guarantees the list is complete.
        drive("configure", settles: true) { res, rej in impl.configure("handler_pass", env: "sandbox", options: [:] as NSDictionary, resolve: res, reject: rej) }
        drive("identify", settles: true) { res, rej in impl.identify("handler_pass", traits: [:] as NSDictionary, resolve: res, reject: rej) }
        drive("reset", settles: true) { res, rej in impl.reset(resolve: res, reject: rej) }
        drive("track", settles: false) { _, _ in impl.track("handler_pass", properties: [:] as NSDictionary) }
        // Bridged, so it is driven: a handler that is declared and never reached is the
        // "bridged but dead" case this pass exists to make impossible.
        drive("reportPayingUser", settles: true) { res, rej in impl.reportPayingUser("handler_pass", priceCents: 999, currency: "USD", resolve: res, reject: rej) }
        drive("flush", settles: true) { res, rej in impl.flush(resolve: res, reject: rej) }
        drive("setConsent", settles: true) { res, rej in impl.setConsent(true, resolve: res, reject: rej) }
        drive("isConsentGranted", settles: true) { res, rej in impl.isConsentGranted(resolve: res, reject: rej) }
        drive("setLogLevel", settles: false) { _, _ in impl.setLogLevel("handler_pass") }
        drive("getSdkVersion", settles: true) { res, rej in impl.getSdkVersion(resolve: res, reject: rej) }
        drive("diagnose", settles: true) { res, rej in impl.diagnose(resolve: res, reject: rej) }
        drive("getLastInitError", settles: true) { res, rej in impl.getLastInitError(resolve: res, reject: rej) }
        drive("notifyScreenAppeared", settles: false) { _, _ in impl.notifyScreenAppeared("handler_pass") }
        drive("onReady", settles: true) { res, rej in impl.onReady(resolve: res, reject: rej) }
        drive("getRemoteConfig", settles: true) { res, rej in impl.getRemoteConfig("handler_pass", resolve: res, reject: rej) }
        drive("getAllRemoteConfig", settles: true) { res, rej in impl.getAllRemoteConfig(resolve: res, reject: rej) }
        drive("refreshConfig", settles: true) { res, rej in impl.refreshConfig(resolve: res, reject: rej) }
        drive("isFeatureEnabled", settles: true) { res, rej in impl.isFeatureEnabled("handler_pass", resolve: res, reject: rej) }
        drive("getFeatureVariant", settles: true) { res, rej in impl.getFeatureVariant("handler_pass", resolve: res, reject: rej) }
        drive("getExperimentVariant", settles: true) { res, rej in impl.getExperimentVariant("handler_pass", resolve: res, reject: rej) }
        drive("isInVariant", settles: true) { res, rej in impl.isInVariant("handler_pass", variantId: "handler_pass", resolve: res, reject: rej) }
        drive("getExperimentConfig", settles: true) { res, rej in impl.getExperimentConfig("handler_pass", key: "handler_pass", resolve: res, reject: rej) }
        drive("getExperimentExposures", settles: true) { res, rej in impl.getExperimentExposures(resolve: res, reject: rej) }
        drive("presentOnboarding", settles: true) { res, rej in impl.presentOnboarding("handler_pass", resolve: res, reject: rej) }
        drive("presentPaywall", settles: true) { res, rej in impl.presentPaywall("handler_pass", context: [:] as NSDictionary, resolve: res, reject: rej) }
        drive("presentPaywallByPlacement", settles: true) { res, rej in impl.presentPaywallByPlacement("handler_pass", context: [:] as NSDictionary, resolve: res, reject: rej) }
        drive("presentSurvey", settles: true) { res, rej in impl.presentSurvey("handler_pass", resolve: res, reject: rej) }
        drive("setSessionData", settles: true) { res, rej in impl.setSessionData("handler_pass", valueJson: "handler_pass", resolve: res, reject: rej) }
        drive("getSessionData", settles: true) { res, rej in impl.getSessionData("handler_pass", resolve: res, reject: rej) }
        drive("clearSessionData", settles: true) { res, rej in impl.clearSessionData(resolve: res, reject: rej) }
        drive("getUserTraits", settles: true) { res, rej in impl.getUserTraits(resolve: res, reject: rej) }
        drive("getLocationData", settles: true) { res, rej in impl.getLocationData("handler_pass", resolve: res, reject: rej) }
        drive("showScreen", settles: true) { res, rej in impl.showScreen("handler_pass", resolve: res, reject: rej) }
        drive("showFlow", settles: true) { res, rej in impl.showFlow("handler_pass", resolve: res, reject: rej) }
        drive("dismissScreen", settles: true) { res, rej in impl.dismissScreen(resolve: res, reject: rej) }
        drive("previewScreen", settles: true) { res, rej in impl.previewScreen("handler_pass", resolve: res, reject: rej) }
        drive("enableNavigationInterception", settles: true) { res, rej in impl.enableNavigationInterception([] as NSArray, resolve: res, reject: rej) }
        drive("disableNavigationInterception", settles: true) { res, rej in impl.disableNavigationInterception(resolve: res, reject: rej) }
        drive("suppressMessages", settles: false) { _, _ in impl.suppressMessages(true) }
        drive("purchase", settles: true) { res, rej in impl.purchase("handler_pass", offerToken: "handler_pass" as NSString, resolve: res, reject: rej) }
        drive("restorePurchases", settles: true) { res, rej in impl.restorePurchases(resolve: res, reject: rej) }
        drive("getProducts", settles: true) { res, rej in impl.getProducts([] as NSArray, resolve: res, reject: rej) }
        drive("hasActiveSubscription", settles: true) { res, rej in impl.hasActiveSubscription(resolve: res, reject: rej) }
        drive("getEntitlements", settles: true) { res, rej in impl.getEntitlements(resolve: res, reject: rej) }
        drive("startEntitlementObserver", settles: true) { res, rej in impl.startEntitlementObserver(resolve: res, reject: rej) }
        drive("billingDelegateReady", settles: false) { _, _ in impl.billingDelegateReady(false) }
        drive("requestPushPermission", settles: true) { res, rej in impl.requestPushPermission(resolve: res, reject: rej) }
        drive("getPushToken", settles: true) { res, rej in impl.getPushToken(resolve: res, reject: rej) }
        drive("setPushToken", settles: true) { res, rej in impl.setPushToken("handler_pass", resolve: res, reject: rej) }
        drive("setPushPermission", settles: true) { res, rej in impl.setPushPermission(true, resolve: res, reject: rej) }
        drive("trackPushDelivered", settles: true) { res, rej in impl.trackPushDelivered("handler_pass", resolve: res, reject: rej) }
        drive("trackPushTapped", settles: true) { res, rej in impl.trackPushTapped("handler_pass", action: "handler_pass" as NSString, resolve: res, reject: rej) }
        drive("isAppDNAMessage", settles: true) { res, rej in impl.isAppDNAMessage(["push_id": "handler_pass"] as NSDictionary, resolve: res, reject: rej) }
        drive("handlePushMessage", settles: true) { res, rej in impl.handlePushMessage(["push_id": "handler_pass"] as NSDictionary, resolve: res, reject: rej) }
        drive("handlePushTap", settles: true) { res, rej in impl.handlePushTap(["push_id": "handler_pass"] as NSDictionary, actionId: "handler_pass" as NSString, resolve: res, reject: rej) }
        drive("handleDeepLink", settles: true) { res, rej in impl.handleDeepLink("handler_pass", resolve: res, reject: rej) }
        drive("checkDeferredDeepLink", settles: true) { res, rej in impl.checkDeferredDeepLink(resolve: res, reject: rej) }
        drive("getWebEntitlement", settles: true) { res, rej in impl.getWebEntitlement(resolve: res, reject: rej) }
        drive("setForcedTheme", settles: true) { res, rej in impl.setForcedTheme("handler_pass" as NSString, resolve: res, reject: rej) }
        drive("getForcedTheme", settles: true) { res, rej in impl.getForcedTheme(resolve: res, reject: rej) }
        drive("respondToHostCallback", settles: false) { _, _ in impl.respondToHostCallback("handler_pass", resultJson: "handler_pass") }

        // LAST, deliberately. `shutdown()` tears the SDK down; driven earlier, every method after it
        // would be talking to a corpse — and `onReady` in particular could never settle, so the pass
        // would report a dead handler that is perfectly alive. Order is part of the harness, not a detail.
        drive("shutdown", settles: true) { res, rej in impl.shutdown(resolve: res, reject: rej) }

        // Native answers on its own queues (`configure` hops to a utility queue; `purchase` awaits
        // StoreKit). Give every promise a bounded chance to settle — then judge.
        let allSettled = expectation(description: "every promise-taking method settled")
        DispatchQueue.global().async {
            let deadline = Date().addingTimeInterval(30)
            while Date() < deadline {
                self.lock.lock()
                let outstanding = self.driven.filter { $0.value && self.settled[$0.key] == nil }.count
                self.lock.unlock()
                if outstanding == 0 { break }
                Thread.sleep(forTimeInterval: 0.05)
            }
            allSettled.fulfill()
        }
        wait(for: [allSettled], timeout: 40)

        lock.lock()
        let settledNow = settled
        let drivenNow = driven
        lock.unlock()

        var evidence: [String: String] = [:]
        var gaps: [String: String] = [:]

        for (name, expectsPromise) in drivenNow {
            if let how = settledNow[name] {
                evidence[name] = "promise settled (\(how))"
            } else if Self.blocksOnUserInteraction.contains(name) {
                // Reached, invoked, and waiting on a human. See `blocksOnUserInteraction`.
                evidence[name] = "invoked; settlement requires a user tap (see blocksOnUserInteraction)"
            } else if expectsPromise {
                gaps[name] = "took a promise pair, returned, and NEVER SETTLED it — an empty body looks "
                    + "exactly like this, and a JS `await` on it hangs forever"
            } else {
                evidence[name] = "returned normally"
            }
        }

        try writeEvidence(evidence)

        XCTAssertTrue(
            gaps.isEmpty,
            "AC-3 — \(gaps.count) bridged method(s) were NOT reached at runtime:\n"
                + gaps.map { "    \($0.key): \($0.value)" }.sorted().joined(separator: "\n")
        )
        // A pass that exercised nothing is green over an empty set — the way a gate like this dies.
        XCTAssertGreaterThanOrEqual(
            evidence.count, 50,
            "only \(evidence.count) methods were driven; this pass would have been green over an empty module"
        )
    }

    /// Methods whose promise CANNOT settle without a human, and are therefore judged on invocation.
    ///
    /// `requestPushPermission` calls `UNUserNotificationCenter.requestAuthorization`, which raises the
    /// system permission alert. Its continuation fires when the user taps Allow or Don't Allow. In an
    /// unattended test nobody taps, so the promise never settles — and the pass reported it as
    /// "BRIDGED but never REACHED". That verdict was wrong: the method ran, entered the core, and put
    /// up the dialog. The core's `requestPermission()` even catches its own throw and returns `false`,
    /// so an empty body is not the explanation.
    ///
    /// This is the one exemption in the pass, and it is deliberately shaped to be hard to abuse:
    ///
    ///  - it does NOT skip the method — the method is still invoked, so a genuinely empty body still
    ///    fails to reach the core and still shows up in the log;
    ///  - it is PINNED by `testTheUserInteractionExemptionHasNotGrown` below. Adding a second entry
    ///    fails that test. "Exempt whatever is red" is precisely how a gate rots into decoration, and
    ///    the only defence is making each exemption a visible, argued edit rather than a quiet one.
    ///
    /// The honest cost: for THIS one method, the pass proves it is wired and reached, not that it
    /// settles. Settlement on the permission path is covered by the device e2e, where a human taps.
    static let blocksOnUserInteraction: Set<String> = ["requestPushPermission"]

    func testTheUserInteractionExemptionHasNotGrown() {
        XCTAssertEqual(
            Self.blocksOnUserInteraction, ["requestPushPermission"],
            "The user-interaction exemption in the AC-3 handler pass grew. Every entry here is a method "
                + "the pass can no longer prove SETTLES — only that it was invoked. That is a real "
                + "weakening of the gate, so it must be an argued edit: justify the new entry in the "
                + "doc comment above and update this assertion deliberately."
        )
    }

    /// Cross-platform parity guard for `handleDeepLink`. Android never validates the URL and
    /// always resolves; iOS used to REJECT with `BAD_URL` when `URL(string:)` returned nil. Called
    /// fire-and-forget, that reject surfaced as an unhandled rejection on iOS ONLY. iOS now resolves
    /// (drops the unparseable string) to match Android and the wrapper's resolve-don't-fork convention.
    /// The premise makes this non-vacuous: if the input parsed, the guard-else would be unreachable
    /// and the test would pass trivially on the resolve path.
    ///
    /// 🔴 The premise was a hard-coded `"bad\nurl"` on the claim that a newline "is a control character
    /// `URL(string:)` refuses in every iOS version". That stopped being true: swift-foundation's modern
    /// parser PERCENT-ENCODES it (`bad%0Aurl`) and returns a URL, so the premise assert started failing
    /// on newer toolchains — a red test for a toolchain change, not a regression. Probe a candidate set
    /// and use the first string THIS Foundation actually refuses, so the guard-else stays reachable on
    /// every version; fail loudly only if none of them are refused (which would make the guard vacuous).
    func testHandleDeepLinkResolvesForUnparseableURLRatherThanRejecting() {
        let candidates = ["http://[", "http://[v1.x", "bad\nurl", ""]
        guard let unparseable = candidates.first(where: { URL(string: $0) == nil }) else {
            return XCTFail(
                "test premise: no candidate was unparseable on this Foundation — the guard-else in "
                    + "handleDeepLink is unreachable, so this parity guard would be vacuous. Add an "
                    + "input this version refuses."
            )
        }
        let impl = AppdnaModuleImpl()
        var outcome = "neither"
        let res: RCTPromiseResolveBlock = { _ in outcome = "resolved" }
        let rej: RCTPromiseRejectBlock = { code, _, _ in outcome = "rejected: \(code ?? "?")" }
        impl.handleDeepLink(unparseable, resolve: res, reject: rej)
        XCTAssertEqual(outcome, "resolved",
                       "handleDeepLink must resolve (like Android), not reject, on an unparseable URL")
    }

    private func record(_ name: String, _ how: String) {
        lock.lock()
        if settled[name] == nil { settled[name] = how }
        lock.unlock()
    }

    // MARK: - Evidence file

    private func writeEvidence(_ methods: [String: String]) throws {
        let out = Self.packageRoot.appendingPathComponent("handler-pass-ios.json")
        let payload: [String: Any] = [
            "_comment":
                "SPEC-070-B AC-3 — GENERATED by AppdnaHandlerPassTests. Runtime evidence that every "
                + "bridged native method was actually REACHED, not merely declared. Do not hand-edit: "
                + "check-rn-handler-evidence.ts compares it to the 3-way-extracted manifest, and a "
                + "hand-written entry is a lie with a straight face.",
            "platform": "ios",
            "methods": methods,
        ]
        let data = try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: out)
        print("AC-3: wrote \(methods.count) method evidence rows to \(out.path)")
    }

    /// The PACKAGE root — the directory `check-rn-handler-evidence.ts` reads the evidence file from.
    ///
    /// The test bundle runs from DerivedData, so `#filePath` (this file, wherever the sources actually
    /// live) is the only reliable anchor. This file is always at `<package>/ios/Tests/`, so three hops
    /// up is the package, in EVERY layout.
    ///
    /// 🔴 It used to search UPWARD for a directory containing `packages/sdk-shared-fixtures` and call
    /// that the repo root, then append `packages/appdna-sdk-react-native/`. That assumes the monorepo
    /// layout, and it is wrong the moment the package is checked out on its own — which is exactly what
    /// the Mac build bridge does (the sync lands the package AT `~/Projects/appdna-sdk-react-native`,
    /// fixtures and all). The upward walk then "found" a root that was already the package, appended the
    /// package path a second time, and tried to write to
    /// `…/appdna-sdk-react-native/packages/appdna-sdk-react-native/handler-pass-ios.json` — a directory
    /// that does not exist:
    ///
    ///     Error Domain=NSCocoaErrorDomain Code=4 "The file “handler-pass-ios.json” doesn’t exist."
    ///
    /// A path derived by guessing at the layout is a path that works until the layout changes. This one
    /// is derived from a fact that cannot change without moving the file that states it.
    static let packageRoot: URL = URL(fileURLWithPath: #filePath)  // …/ios/Tests/AppdnaHandlerPassTests.swift
        .deletingLastPathComponent()  // …/ios/Tests
        .deletingLastPathComponent()  // …/ios
        .deletingLastPathComponent()  // …/<package>
}
