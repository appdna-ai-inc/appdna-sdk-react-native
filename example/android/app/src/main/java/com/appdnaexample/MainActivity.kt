package com.appdnaexample

import android.os.Bundle
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "AppdnaExample"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   *
   * The delegate also carries the SDK key from the launch intent into JS as an initial prop. The key
   * is never committed, bundled, or written to disk — the example is force-pushed to a public mirror,
   * so a key stored here is a key published. Launch it with:
   *
   *     adb shell am start -n com.appdnaexample/.MainActivity \
   *       -e appdnaApiKey adn_test_… -e appdnaOnboardingId … -e appdnaPaywallId …
   *
   * Mirrors the iOS AppDelegate, which reads the same names out of the launch arguments.
   *
   * 🔴 It used to forward ONLY `apiKey` while the iOS host forwarded six props — so every content id
   * arrived `undefined` on Android and the example fell back to the id `"default"`, which exists in no
   * console. An Android device pass therefore could not present a real onboarding flow, paywall or
   * survey, and nothing said so: `present("default")` just resolves false. The two hosts now carry the
   * same set.
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      object : DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled) {
        override fun getLaunchOptions(): Bundle? {
          val props =
              Bundle().apply {
                intent?.let { launchIntent ->
                  for ((arg, prop) in LAUNCH_PROPS) {
                    launchIntent.getStringExtra(arg)?.takeIf { it.isNotBlank() }?.let { putString(prop, it) }
                  }
                }
                // `appdnaEnv=sandbox` exactly when this build carries the test-only
                // base-URL override (the `ai.appdna.sdk.BASE_URL_OVERRIDE` meta-data, fed from the
                // gitignored local.properties). Emitted even with no intent extras.
                if (!baseUrlOverride().isNullOrBlank()) putString("appdnaEnv", "sandbox")
              }
          return if (props.isEmpty) null else props
        }
      }

  private fun baseUrlOverride(): String? =
      try {
        packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
            .metaData
            ?.getString("ai.appdna.sdk.BASE_URL_OVERRIDE")
      } catch (e: Exception) {
        null
      }

  companion object {
    /** Launch-argument name → JS initial-prop name. Kept identical to AppDelegate.mm's `launchKeys`. */
    private val LAUNCH_PROPS =
        listOf(
            "appdnaApiKey" to "apiKey",
            "appdnaOnboardingId" to "onboardingId",
            "appdnaPaywallId" to "paywallId",
            "appdnaPaywall2Id" to "paywall2Id",
            "appdnaSurveyId" to "surveyId",
            "appdnaMessageEvent" to "messageEvent",
            "appdnaProductId" to "productId",
            "appdnaScreenId" to "screenId",
            "appdnaScreenFlowId" to "screenFlowId",
            "appdnaSlotName" to "slotName",
            "appdnaExperimentId" to "experimentId",
            "appdnaExperimentVariantId" to "experimentVariantId",
            "appdnaPlacement" to "placement",
            // Device pass: `items` | `empty` — sample host data for onBeforeStepRender.
            "appdnaHostDataDemo" to "hostDataDemo",
            // The sign-in timeout floor device rows.
            "appdnaSignInDelaySeconds" to "signInDelaySeconds",
            "appdnaVetoTimeout" to "vetoTimeout",
            "appdnaStepAdvanceDelaySeconds" to "stepAdvanceDelaySeconds",
            "appdnaStepAdvanceReply" to "stepAdvanceReply",
            // Billing provider, host-buy product, location flow, hostWait's URL.
            "appdnaBillingProvider" to "billingProvider",
            "appdnaHostProductId" to "hostProductId",
            "appdnaLocationFlowId" to "locationFlowId",
            "APPDNA_E2E_LOCATION_FLOW_ID" to "locationFlowId",
            "appdnaPermissionsFlowId" to "permissionsFlowId",
            "APPDNA_E2E_PERMISSIONS_FLOW_ID" to "permissionsFlowId",
            "appdnaWaitUrl" to "waitUrl",
        )
  }
}
