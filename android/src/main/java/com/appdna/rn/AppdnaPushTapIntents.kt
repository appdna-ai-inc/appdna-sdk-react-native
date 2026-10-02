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
 *    neither adds its own nor is kept alive by one, and a `shutdown()` followed by `configure` drains what is
 *    waiting then — nothing stale.
 */
internal object PendingPushTaps {
    internal const val MAX_PENDING = 64

    /** Native's `AppDNA.handlePushTap`. `internal var` — a test seam. */
    internal var handle: (Intent) -> Boolean = { AppDNA.handlePushTap(it) }

    /** (the activity's intent, the copy native gets), oldest first. */
    private val pending = ArrayDeque<Pair<Intent, Intent>>()
    private var drainRegistered = false

    fun add(original: Intent, copy: Intent) {
        var dropped: Intent? = null
        val register: Boolean
        synchronized(this) {
            pending.addLast(original to copy)
            if (pending.size > MAX_PENDING) dropped = pending.removeFirst().first
            register = !drainRegistered
            drainRegistered = true
        }
        dropped?.let {
            PushTapIntentLedger.forget(it)
            Log.w("AppDNA", "More than $MAX_PENDING push taps wait for configure(); the oldest was dropped")
        }
        if (register) AppDNA.onReady { drain() }
    }

    private fun drain() {
        val batch = synchronized(this) {
            drainRegistered = false
            ArrayList(pending).also { pending.clear() }
        }
        for ((_, copy) in batch) {
            // A throw here ran on the main thread, unguarded, and crashed the app.
            try {
                handle(copy)
            } catch (t: Throwable) {
                Log.w("AppDNA", "handlePushTap threw: ${t.message}")
            }
        }
    }

    internal fun pendingCountForTest(): Int = synchronized(this) { pending.size }

    internal fun resetForTest() {
        synchronized(this) { pending.clear() }
        handle = { AppDNA.handlePushTap(it) }
    }
}
