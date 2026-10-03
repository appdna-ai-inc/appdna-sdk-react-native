package com.appdna.rn

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The pending-veto map for the host-veto wire protocol.
 *
 * ## Why this exists at all
 *
 * Native awaits a host veto **forever** — there is no timeout in the SDK. Flutter's invoker gets
 * correlation for free (every `invokeMethod` carries its own private reply port) and robustness for
 * free (`suspendCancellableCoroutine` inside `withTimeout`). React Native's native→JS path is
 * **one-way**, so a veto must be emitted as `{callbackId, hook, argsJson}` and answered by an
 * explicit `respondToHostCallback(callbackId, resultJson)` call back into native.
 *
 * That reintroduces by hand four things Flutter never had to build, and this class is where each
 * lives. They are requirements, not niceties:
 *
 *   1. **A double-resolve guard.** A late or duplicate reply for a timed-out id is DROPPED.
 *   2. **Eviction on timeout**, or the map grows without bound — one leak per vetoed surface.
 *   3. **Reload-safe, namespaced ids.** The native module survives a JS reload: in-flight ids
 *      orphan, JS's pending map is gone while native still waits, and a recycled counter COLLIDES.
 *      Ids carry a per-process epoch, and [invalidateAll] clears the map on teardown (E11).
 *   4. **Per-hook defaults and per-hook wire shapes.** Seven hooks default to *allow*;
 *      `onPromoCodeSubmit` defaults to **reject**. A uniform default-on-timeout silently starts
 *      accepting unvalidated promo codes. The onboarding hooks answer with a map; the three vetoes
 *      answer with a bare bool — one `resultJson` envelope must preserve the distinction.
 *
 * ## W10 hardening
 *   - An unknown or foreign `callbackId` is **ignored, never thrown** — a reply from a previous JS
 *     epoch is expected, not exceptional.
 *   - The map is **count-bounded** as well as time-bounded: a burst can balloon it inside the
 *     five-second window.
 *
 * The timer itself is deliberately NOT here — it belongs to the caller, which knows the hook's
 * default. It is never a JS `setTimeout`: that is throttled when backgrounded and destroyed by a
 * Metro reload, and native would then await forever (E5).
 */
internal object AppdnaHostCallbacks {

    /** Beyond this, a burst is a bug. Rejecting is safer than an unbounded map. */
    private const val MAX_PENDING = 256

    /**
     * A per-process epoch. A JS reload creates a new epoch, so an id minted before the reload can
     * never collide with one minted after — even though the native module (and this counter)
     * survives.
     */
    private val epoch: String = java.lang.Long.toHexString(System.nanoTime())
    private val counter = AtomicLong(0)

    /** callbackId → the continuation waiting for JS. */
    private val pending = ConcurrentHashMap<String, (String?) -> Unit>()

    /** Mint a namespaced id and register a resolver. Returns null when the map is saturated. */
    fun register(resolver: (String?) -> Unit): String? {
        if (pending.size >= MAX_PENDING) return null
        val id = "$epoch:${counter.incrementAndGet()}"
        pending[id] = resolver
        return id
    }

    /**
     * Deliver JS's answer. Idempotent: the first reply wins, and every later or duplicate one is
     * dropped. An id from a previous epoch simply is not present.
     */
    fun respond(callbackId: String, resultJson: String) {
        // `remove` is the double-resolve guard: only one caller can ever take the resolver.
        val resolver = pending.remove(callbackId) ?: return
        resolver(resultJson)
    }

    /** Evict a timed-out id. Returns true when this call is the one that evicted it. */
    fun evict(callbackId: String): Boolean = pending.remove(callbackId) != null

    /**
     * On `invalidate()`, resolve every pending veto with `null` so the caller applies the
     * hook's default. A JS side that no longer exists will never answer, and native awaits forever.
     */
    fun invalidateAll() {
        val snapshot = pending.keys.toList()
        for (id in snapshot) {
            pending.remove(id)?.invoke(null)
        }
    }

    /** Test seam. */
    internal fun pendingCount(): Int = pending.size
}
