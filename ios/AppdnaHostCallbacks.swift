import Foundation

/**
 * The pending-veto map for the host-veto wire protocol (iOS).
 *
 * Native awaits a host veto **forever** — the SDK has no timeout. Flutter gets correlation for free
 * (each `invokeMethod` carries its own private reply port) and robustness for free
 * (`SyncCallbackInvoker` guards `continuation.resume` with a `resumed` flag on the main queue).
 * React Native's native→JS path is **one-way**, so a veto is emitted as `{callbackId, hook,
 * argsJson}` and answered by an explicit `respondToHostCallback(callbackId, resultJson)`.
 *
 * That reintroduces by hand the four things Flutter never had to build:
 *
 *   1. **A double-resolve guard.** A late or duplicate reply for a timed-out id is DROPPED.
 *   2. **Eviction on timeout**, or the map grows unbounded — one leak per vetoed surface.
 *   3. **Reload-safe, namespaced ids.** The native module survives a JS reload: in-flight ids
 *      orphan, JS's pending map is gone while native still waits, and a recycled counter COLLIDES.
 *      Ids carry a per-process epoch, and [invalidateAll] clears the map on teardown (E11).
 *   4. **Per-hook defaults and per-hook wire shapes.** Seven hooks default to *allow*;
 *      `onPromoCodeSubmit` defaults to **reject**. A uniform default-on-timeout silently starts
 *      accepting unvalidated promo codes.
 *
 * W10: an unknown or foreign `callbackId` is **ignored, never raised** — a reply from a
 * previous JS epoch is expected, not exceptional — and the map is **count-bounded** as well as
 * time-bounded, because a burst can balloon it inside the five-second window.
 *
 * The timer is deliberately NOT here: it belongs to the caller, which knows the hook's default. It
 * is never a JS `setTimeout` — that is throttled when backgrounded and destroyed by a Metro reload,
 * and native would then await forever (E5).
 */
final class AppdnaHostCallbacks {

    static let shared = AppdnaHostCallbacks()

    /// Beyond this, a burst is a bug. Refusing is safer than an unbounded map.
    private static let maxPending = 256

    /// A per-process epoch, so an id minted before a JS reload can never collide with one after —
    /// even though this object (and its counter) survives the reload.
    private let epoch = String(UInt64(Date().timeIntervalSince1970 * 1_000_000), radix: 16)

    private let lock = NSLock()
    private var counter: UInt64 = 0
    private var pending: [String: (String?) -> Void] = [:]

    private init() {}

    /// Mint a namespaced id and register a resolver. Returns nil when the map is saturated.
    func register(_ resolver: @escaping (String?) -> Void) -> String? {
        lock.lock(); defer { lock.unlock() }
        guard pending.count < Self.maxPending else { return nil }
        counter += 1
        let id = "\(epoch):\(counter)"
        pending[id] = resolver
        return id
    }

    /// Deliver JS's answer. Idempotent: the first reply wins; later or duplicate ones are dropped.
    /// An id from a previous epoch is simply absent.
    func respond(callbackId: String, resultJson: String) {
        lock.lock()
        // `removeValue` IS the double-resolve guard: only one caller can take the resolver.
        let resolver = pending.removeValue(forKey: callbackId)
        lock.unlock()
        resolver?(resultJson)
    }

    /// Evict a timed-out id. Returns true when this call is the one that evicted it.
    @discardableResult
    func evict(callbackId: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return pending.removeValue(forKey: callbackId) != nil
    }

    /// On teardown, resolve every pending veto with nil so the caller applies the hook's
    /// default. A JS side that no longer exists will never answer, and native awaits forever.
    func invalidateAll() {
        lock.lock()
        let resolvers = Array(pending.values)
        pending.removeAll()
        lock.unlock()
        for resolve in resolvers { resolve(nil) }
    }

    /// Test seam.
    var pendingCount: Int {
        lock.lock(); defer { lock.unlock() }
        return pending.count
    }
}
