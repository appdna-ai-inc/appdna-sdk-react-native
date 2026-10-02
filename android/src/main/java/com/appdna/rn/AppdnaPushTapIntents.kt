package com.appdna.rn

import android.content.Intent
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
}
