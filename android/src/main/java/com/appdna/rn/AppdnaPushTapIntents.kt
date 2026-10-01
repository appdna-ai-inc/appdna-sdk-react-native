package com.appdna.rn

import android.content.Intent
import com.facebook.react.bridge.BaseActivityEventListener

/**
 * Hands every intent the React Native activity receives (`onNewIntent`) to [route]. Kept out of
 * `AppdnaModule` so the module's own methods stay exactly the bridged surface.
 */
internal class AppdnaPushTapIntents(private val route: (Intent) -> Unit) : BaseActivityEventListener() {
    override fun onNewIntent(intent: Intent) = route(intent)
}
