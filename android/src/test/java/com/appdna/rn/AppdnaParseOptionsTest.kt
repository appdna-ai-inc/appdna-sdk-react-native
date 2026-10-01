package com.appdna.rn

import ai.appdna.sdk.BillingProvider
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.bridge.ReactApplicationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

/**
 * SPEC-070-B AC-11 — the native `parseOptions` mapping, on Android.
 *
 * A jest test mocks the native module, so it can observe neither the `?? 3600` config-TTL default
 * (E7 — the drift that made the wrappers fetch config 12× too often) nor the unconditional
 * `framework = "react_native"` tag (§7 rule 1 — the reason RN events land in BigQuery as `react_native`
 * and not `native`). Only a native unit test reaches them, which is why `parseOptions` is `internal`.
 *
 * Robolectric because `parseOptions` builds an `AppDNAOptions` from a `ReadableMap`, and `JavaOnlyMap`
 * plus the SDK's `BillingProvider.fromWire` want a real (not stubbed) runtime.
 */
@RunWith(RobolectricTestRunner::class)
class AppdnaParseOptionsTest {

    // ReactApplicationContext is abstract since RN 0.76; AppdnaModule never touches it at
    // construction (its init only allocates a CoroutineScope), so a mock reaches parseOptions.
    private val module = AppdnaModule(mock(ReactApplicationContext::class.java))

    @Test
    fun `framework is react_native regardless of input`() {
        // Omitted entirely.
        assertEquals("react_native", module.parseOptions(JavaOnlyMap()).framework)
        // A host trying to spoof it as "native" cannot: the tag is injected, never read from input.
        val spoof = JavaOnlyMap().apply { putString("framework", "native") }
        assertEquals("react_native", module.parseOptions(spoof).framework)
    }

    @Test
    fun `configTTL defaults to the native 3600, not a wrapper literal`() {
        // The bug this guards: a `?? 300` in the wrapper drifted 12× off the native default. When the
        // host says nothing, the value MUST come from AppDNAOptions() — 3600 — not a hardcoded number.
        assertEquals(3600L, module.parseOptions(JavaOnlyMap()).configTTL)
    }

    @Test
    fun `configTTL is honored when the host provides one`() {
        val opts = JavaOnlyMap().apply { putDouble("configTTL", 900.0) }
        assertEquals(900L, module.parseOptions(opts).configTTL)
    }

    @Test
    fun `billingProvider decodes the adapty tagged map with its apiKey (AC-21)`() {
        val opts = JavaOnlyMap().apply {
            putMap("billingProvider", JavaOnlyMap().apply {
                putString("type", "adapty")
                putString("apiKey", "public_live_abc")
            })
        }
        assertEquals(BillingProvider.Adapty("public_live_abc"), module.parseOptions(opts).billingProvider)
    }

    @Test
    fun `billingProvider decodes a bare revenueCat string`() {
        val opts = JavaOnlyMap().apply { putString("billingProvider", "revenueCat") }
        assertEquals(BillingProvider.RevenueCat, module.parseOptions(opts).billingProvider)
    }

    @Test
    fun `frameworkVersion is the wrapper's own version, not the host's`() {
        // Same rule as the framework tag: a wrapper ASSERTS what it is, it does not ask. This used
        // to pass the host's value through, so the field was empty unless a host thought to set it
        // (none did), and a host that did set it could claim any version it liked.
        //
        // The assertion is that the host cannot influence the value — deliberately NOT that it
        // equals a literal "1.0.8", which would just restate the constant and need editing on every
        // release. `check:wrapper-version-selfreport` owns the value, pinning it to package.json.
        val absent = module.parseOptions(JavaOnlyMap()).frameworkVersion
        val spoofed = module.parseOptions(
            JavaOnlyMap().apply { putString("frameworkVersion", "0.76.5") },
        ).frameworkVersion

        assertEquals(absent, spoofed)
        assertNotEquals("0.76.5", spoofed)
        assertTrue("reports no version at all", !absent.isNullOrBlank())
    }

    /**
     * `vetoTimeout` is seconds and may be fractional. It went through `toLong()` before becoming
     * milliseconds: 0.5 became 0 (→ the 5 s default) and 2.7 became 2. NEGATIVE CONTROL: the old
     * conversion gives 5000 and 2000 here.
     */
    @Test
    fun `vetoTimeout converts fractional seconds to milliseconds without truncating`() {
        fun ms(v: Double) = module.parseVetoTimeoutMs(JavaOnlyMap().apply { putDouble("vetoTimeout", v) })
        assertEquals(500L, ms(0.5))
        assertEquals(2700L, ms(2.7))
        assertEquals(8000L, ms(8.0))
        val defaultMs = ai.appdna.sdk.AppDNAOptions().vetoTimeout * 1000L
        assertEquals(defaultMs, ms(0.0))
        assertEquals(defaultMs, ms(-1.0))
        assertEquals(defaultMs, module.parseVetoTimeoutMs(JavaOnlyMap()))
        // The core's whole-second field (diagnose only) rounds UP, so a sub-second value is not the default.
        assertEquals(1L, module.parseOptions(JavaOnlyMap().apply { putDouble("vetoTimeout", 0.5) }).vetoTimeout)
        assertEquals(3L, module.parseOptions(JavaOnlyMap().apply { putDouble("vetoTimeout", 2.7) }).vetoTimeout)
    }

    /**
     * The exact value reaches the core, which diagnose() reports as given (core `DiagnoseVetoTimeoutTest`).
     * NEGATIVE CONTROL: only the rounded-up whole seconds reached the core, so diagnose() said 1 for 0.5.
     */
    @Test
    fun `the exact vetoTimeout reaches the core for diagnose`() {
        assertEquals(0.5, module.parseOptions(JavaOnlyMap().apply { putDouble("vetoTimeout", 0.5) }).vetoTimeoutSeconds!!, 0.0)
        assertEquals(8.0, module.parseOptions(JavaOnlyMap().apply { putDouble("vetoTimeout", 8.0) }).vetoTimeoutSeconds!!, 0.0)
        assertEquals(null, module.parseOptions(JavaOnlyMap().apply { putDouble("vetoTimeout", -1.0) }).vetoTimeoutSeconds)
        assertEquals(null, module.parseOptions(JavaOnlyMap()).vetoTimeoutSeconds)
    }
}
