import Foundation
import AppDNASDK

/**
 * SPEC-070-B §5 — ask the JS host for a veto, and give up after `timeout` seconds.
 *
 * The SDK awaits a host veto **forever**; the timer has always belonged to the wrapper. Flutter gets
 * one reply port per `invokeMethod`, so it needs no correlation. React Native's native→JS path is
 * one-way, so the request goes out as an `onHostCallback` event carrying a `callbackId` and the
 * answer comes back through `respondToHostCallback`.
 *
 * The timer must be here and not in JS: a JS `setTimeout` is throttled when the app is backgrounded
 * and destroyed outright by a Metro reload (E5). Native would then await forever, and an onboarding
 * step would hang mid-flow with no way out.
 *
 * On timeout the CALLER applies the hook's default, because the defaults differ: seven hooks allow,
 * `onPromoCodeSubmit` rejects. `nil` is this class's only failure value, and every caller reads it as
 * "apply my default".
 */
final class AppdnaVetoInvoker {

    /// The configured wait (`AppDNAOptions.vetoTimeout`). Internal so a caller can compute a per-call
    /// floor against it (SPEC-496 §5b C5.5).
    let timeout: TimeInterval
    private let emit: ([String: Any]) -> Void

    /// Schedules the give-up: `(seconds, fire)`. Production waits on the main queue;
    /// the XCTest injects a scheduler that records the requested interval and fires on demand, because
    /// there is no virtual clock under `DispatchQueue.main.asyncAfter`.
    typealias TimeoutScheduler = (TimeInterval, @escaping () -> Void) -> Void

    static let mainQueueScheduler: TimeoutScheduler = { wait, fire in
        DispatchQueue.main.asyncAfter(deadline: .now() + wait, execute: fire)
    }

    private let scheduleTimeout: TimeoutScheduler

    init(
        timeout: TimeInterval,
        scheduleTimeout: @escaping TimeoutScheduler = AppdnaVetoInvoker.mainQueueScheduler,
        emit: @escaping ([String: Any]) -> Void
    ) {
        self.timeout = timeout
        self.scheduleTimeout = scheduleTimeout
        self.emit = emit
    }

    /// Emit the veto request and await JS's reply. `nil` means "no opinion": a timeout, a saturated
    /// pending map, a hook JS never registered, or a host that answered `null`.
    ///
    /// `timeout` — SPEC-496 §5b C5.5: an optional PER-CALL wait, defaulting to the configured one. Only
    /// `onElementInteraction` passes it (a `refresh` has an 8 s SDK deadline the 5 s default would cut
    /// short); every other hook keeps the configured value.
    func invoke(_ hook: String, _ args: [String: Any], timeout: TimeInterval? = nil) async -> Any? {
        let wait = timeout ?? self.timeout
        return await withCheckedContinuation { (continuation: CheckedContinuation<Any?, Never>) in
            // `AppdnaHostCallbacks.register`'s dictionary removal is the double-resolve guard: only
            // one of `respond` and `evict` can ever take the resolver, so this resumes exactly once.
            guard let callbackId = AppdnaHostCallbacks.shared.register({ resultJson in
                continuation.resume(returning: Self.decode(resultJson))
            }) else {
                // Saturated: 256 vetoes in flight is a bug, and hanging is worse than defaulting.
                continuation.resume(returning: nil)
                return
            }

            emit([
                "callbackId": callbackId,
                "hook": hook,
                "argsJson": AppdnaJSON.encode(args),
            ])

            scheduleTimeout(wait) {
                // `evict` returns true only for the caller that actually removed the resolver. If JS
                // already answered, this is a no-op and the continuation has long since resumed.
                if AppdnaHostCallbacks.shared.evict(callbackId: callbackId) {
                    AppDNA.recordVetoTimeout()
                    continuation.resume(returning: nil)
                }
            }
        }
    }

    /// A malformed reply decodes to `nil`, which every caller reads as "apply the native default" —
    /// the same thing a timeout means. A host cannot make native throw by replying with garbage.
    private static func decode(_ resultJson: String?) -> Any? {
        guard let resultJson, let data = resultJson.data(using: .utf8) else { return nil }
        let value = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        return (value is NSNull) ? nil : value
    }
}
