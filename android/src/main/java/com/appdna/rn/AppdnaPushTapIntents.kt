package com.appdna.rn

import ai.appdna.sdk.AppDNA
import android.content.Intent
import android.util.Log
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.LifecycleEventListener
import java.util.Collections
import java.util.WeakHashMap

/**
 * Hands every intent the React Native activity receives (`onNewIntent`) to [route], and the current
 * activity's own intent each time the host resumes. Kept out of `AppdnaModule` so the module's own methods
 * stay exactly the bridged surface.
 *
 * The resume hand-over is for a React instance that outlives its activity (the activity finished with
 * Back while the process lived): a tap then starts a NEW activity whose launch intent carries it — not
 * through `onNewIntent`, and not at a `configure` that already ran. [route] hands each intent object over
 * once ([PushTapIntentLedger]), so resuming the same activity again hands nothing over twice.
 */
internal class AppdnaPushTapIntents(
    private val currentIntent: () -> Intent?,
    private val route: (Intent) -> Unit,
) : BaseActivityEventListener(), LifecycleEventListener {
    override fun onNewIntent(intent: Intent) = route(intent)

    override fun onHostResume() {
        currentIntent()?.let(route)
    }

    override fun onHostPause() {}

    override fun onHostDestroy() {}
}

/**
 * The intents the module has handed to the native `AppDNA.handlePushTap`.
 *
 * Native gets a COPY of every intent and makes only that copy inert, so the activity's own intent keeps
 * its extras and host JS still recognises the tap. That left the activity's intent a live tap: every
 * `configure` re-ran the launch intent, and only the persisted tap claim — the last 32 tap keys — kept it
 * from being tracked and routed again. After 32 later taps the claim was gone and a re-`configure` fired
 * the launch tap again; a tap on a notification the previous SDK version posted has no key at all and was
 * routed again on every re-run.
 *
 * Each intent OBJECT is handed over once, for as long as it lives (the activity's lifetime): `Intent` does
 * not override `equals` / `hashCode`, so this weak set compares by identity. Process-wide, so a module
 * re-created by a JS reload shares it.
 */
internal object PushTapIntentLedger {
    private val seen: MutableSet<Intent> = Collections.newSetFromMap(WeakHashMap())

    /** True the first time [intent] is seen; false when it was handed over before. */
    @Synchronized
    fun claim(intent: Intent): Boolean = seen.add(intent)

    /** Forget [intent] ([PendingPushTaps] dropped it before the SDK was ready): it can be handed over again. */
    @Synchronized
    fun forget(intent: Intent) {
        seen.remove(intent)
    }
}

/**
 * The push taps the module has handed over and that wait for the SDK to become ready, behind ONE native
 * `AppDNA.onReady` callback for the whole process.
 *
 * The module used to register one `AppDNA.onReady` closure per intent it handed over. Native keeps those
 * closures until the SDK is ready — across `shutdown()` too — so before `configure` (or in an app that
 * never configures) every intent it saw (`onHostResume` on every resume of a new activity, every
 * `onNewIntent`) added a closure holding a copy of that intent and the module, and the list only grew. Now:
 *  - an intent that is not an AppDNA tap never waits: `AppdnaModule.routePushTap` drops it at once
 *    (native's `handlePushTap` answers `false` for it, and does nothing);
 *  - at most [MAX_PENDING] taps wait; past that the OLDEST is dropped and forgotten by
 *    [PushTapIntentLedger], so a later hand-over of the same intent queues it again;
 *  - one `onReady` closure drains them all; a tap that arrives after the drain started registers the next
 *    one. The closure reads this process-wide queue, never a module, so a module re-created by a JS reload
 *    neither adds its own nor is kept alive by one;
 *  - JS `shutdown()` empties the queue ([clearOnShutdown]) before the native `shutdown()`. A tap that waited
 *    for the session that ended is not delivered to the next `configure` (possibly another user, after a
 *    sign-out) — as the iOS SDK clears its own launch buffer at `shutdown()`. It used to be: the closure
 *    survives `shutdown()` and drained the old taps into the new session. A cleared intent stays claimed in
 *    [PushTapIntentLedger], so it is never handed over again. Nothing records it as handled (no tap claim is
 *    persisted, `onPushTapped` does not fire): host JS that reads the intent and calls
 *    `AppDNA.push.isAppDNAMessage` / `handleTap` sees an unhandled AppDNA tap, and `handleTap` handles it then
 *    — tracked and routed once, in the session that is running.
 *  - JS `shutdown()` runs on the native-modules thread, a tap arrives on the main thread, and the drain runs
 *    on the main thread — it is posted there by `AppDNA.onReady` the moment the SDK is ready, so a drain
 *    posted before a `shutdown()` runs after it. Two rules close that window:
 *    (1) the clear and the native `shutdown()` run under [handoverLock] ([shutdownNative]), and every
 *        hand-over to native takes the same lock, so no tap is handed over while the SDK is being shut down;
 *    (2) [shutdownGeneration] counts shutdowns. A drain registered before a shutdown hands nothing over: it
 *        may have been posted while the SDK was ready, and now runs on a shut-down SDK, which would drop the
 *        tap. It registers a fresh drain instead, which runs when the SDK is ready again (at once if it is).
 */
internal object PendingPushTaps {
    internal const val MAX_PENDING = 64

    /** Native's `AppDNA.handlePushTap`. `internal var` — a test seam. */
    internal var handle: (Intent) -> Boolean = { AppDNA.handlePushTap(it) }

    /** (the activity's intent, the copy native gets), oldest first. */
    private val pending = ArrayDeque<Pair<Intent, Intent>>()
    private var drainRegistered = false
    /** Bumped by every [shutdownNative]; a drain registered under an older value hands nothing over. */
    private var shutdownGeneration = 0
    /** Held while native is shut down and while a tap is handed to native. */
    private val handoverLock = Any()

    fun add(original: Intent, copy: Intent) {
        var dropped: Intent? = null
        val register: Int?
        synchronized(this) {
            pending.addLast(original to copy)
            if (pending.size > MAX_PENDING) dropped = pending.removeFirst().first
            register = if (drainRegistered) null else shutdownGeneration
            drainRegistered = true
        }
        dropped?.let {
            PushTapIntentLedger.forget(it)
            Log.w("AppDNA", "More than $MAX_PENDING push taps wait for configure(); the oldest was dropped")
        }
        if (register != null) registerDrain(register)
    }

    private fun registerDrain(generation: Int) {
        onReady { drain(generation) }
    }

    /** Native's `AppDNA.onReady`. `internal var` — a test seam (hold the posted drain). */
    internal var onReady: (() -> Unit) -> Unit = { AppDNA.onReady(it) }

    private fun drain(registeredAt: Int) {
        val batch = synchronized(this) {
            if (registeredAt != shutdownGeneration) {
                // Registered before a shutdown: this may be a drain posted while the old session was ready,
                // running now on a shut-down SDK. Hand nothing over; wait for the SDK to be ready again.
                if (pending.isEmpty()) { drainRegistered = false; return }
                null
            } else {
                drainRegistered = false
                ArrayList(pending).also { pending.clear() }
            }
        }
        if (batch == null) {
            registerDrain(synchronized(this) { shutdownGeneration })
            return
        }
        for ((index, entry) in batch.withIndex()) {
            val handedOver = synchronized(handoverLock) {
                // A shutdown between taking the batch and this tap: give the rest back to the queue (unless the
                // shutdown cleared it — then they were cleared too, as waiting taps of the ended session).
                if (registeredAt != synchronized(this) { shutdownGeneration }) false else {
                    // A throw here ran on the main thread, unguarded, and crashed the app.
                    try {
                        handle(entry.second)
                    } catch (t: Throwable) {
                        Log.w("AppDNA", "handlePushTap threw: ${t.message}")
                    }
                    true
                }
            }
            if (!handedOver) {
                val rest = batch.subList(index, batch.size).size
                Log.d("AppDNA", "shutdown(): $rest push tap(s) taken for hand-over were dropped")
                return
            }
        }
    }

    /**
     * The wrapper's `shutdown()`: forget every waiting tap ([clearOnShutdown]) and run [nativeShutdown] — both
     * under [handoverLock], so no tap is handed to native while it shuts down, and with [shutdownGeneration]
     * bumped, so a drain registered before this hands nothing to the shut-down SDK.
     */
    fun shutdownNative(nativeShutdown: () -> Unit) {
        synchronized(handoverLock) {
            synchronized(this) { shutdownGeneration += 1 }
            clearOnShutdown()
            nativeShutdown()
        }
    }

    /**
     * The wrapper's `shutdown()`: forget every waiting tap. `drainRegistered` is left as it is — the native
     * `onReady` closure that drains this queue outlives `shutdown()` and still fires at the next ready, so a
     * tap queued after this one registers nothing new and is drained by it.
     */
    fun clearOnShutdown() {
        val dropped = synchronized(this) { pending.size.also { pending.clear() } }
        if (dropped > 0) Log.d("AppDNA", "shutdown(): $dropped push tap(s) waiting for configure() were dropped")
    }

    internal fun pendingCountForTest(): Int = synchronized(this) { pending.size }

    internal fun resetForTest() {
        synchronized(this) {
            pending.clear()
            drainRegistered = false
            shutdownGeneration = 0
        }
        handle = { AppDNA.handlePushTap(it) }
        onReady = { AppDNA.onReady(it) }
    }
}
