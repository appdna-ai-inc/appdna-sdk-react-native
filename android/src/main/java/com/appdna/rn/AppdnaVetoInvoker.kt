package com.appdna.rn

import ai.appdna.sdk.AppDNA
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Ask the JS host for a veto, and give up after [timeoutMs].
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
 * On timeout the caller — not this class — applies the hook's default, because the defaults differ:
 * seven hooks allow, `onPromoCodeSubmit` rejects. `null` is this class's only failure value, and
 * every caller reads it as "apply my default".
 */
internal class AppdnaVetoInvoker(
    /** The configured wait (`AppDNAOptions.vetoTimeout`), the default for every [invoke]. */
    internal val timeoutMs: Long,
    private val emit: (Map<String, Any?>) -> Unit,
) {

    /**
     * `withTimeoutOrNull` returns `null` both when it times out and when the block itself resolved to
     * `null`. Those are different events — a host that never answered, versus a host that declined to
     * have an opinion — and only the first belongs in `diagnose()`'s veto-timeout counter. Boxing the
     * block's result is what tells them apart.
     */
    private class Reply(val value: Any?)

    /**
     * Emit the veto request and await JS's reply.
     *
     * @param timeoutMs this call's wait; defaults to the configured one. Only the
     *   `onElementInteraction` bridge passes its own (`max(configured, core minimumBridgeTimeoutMs)`),
     *   so a `refresh` is never cut short of the SDK's 8 s deadline.
     * @return the decoded reply (a `Map`, a `Boolean`, …), or `null` on timeout, on a saturated
     *   pending map, or when JS answered `null` — all of which mean "no opinion".
     */
    suspend fun invoke(hook: String, args: Map<String, Any?>, timeoutMs: Long = this.timeoutMs): Any? {
        val reply: Reply? = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val callbackId = AppdnaHostCallbacks.register { resultJson ->
                    // The pending map's `remove` is the double-resolve guard; `isActive` covers the
                    // race where the timeout cancelled us between that removal and this line.
                    if (continuation.isActive) continuation.resume(Reply(AppdnaBridge.fromJson(resultJson)))
                }

                if (callbackId == null) {
                    // Saturated: 256 vetoes in flight is a bug, and hanging is worse than defaulting.
                    continuation.resume(Reply(null))
                    return@suspendCancellableCoroutine
                }

                // Cancellation is how `withTimeoutOrNull` ends us. Evicting here is what keeps the
                // pending map from growing by one entry per timed-out veto.
                continuation.invokeOnCancellation { AppdnaHostCallbacks.evict(callbackId) }

                emit(
                    mapOf(
                        "callbackId" to callbackId,
                        "hook" to hook,
                        "argsJson" to AppdnaBridge.toJson(args),
                    ),
                )
            }
        }

        if (reply == null) {
            AppDNA.recordVetoTimeout()
            return null
        }
        return reply.value
    }
}
