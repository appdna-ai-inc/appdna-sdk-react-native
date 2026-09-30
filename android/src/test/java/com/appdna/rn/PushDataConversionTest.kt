package com.appdna.rn

import com.facebook.react.bridge.JavaOnlyArray
import com.facebook.react.bridge.JavaOnlyMap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC-497 §9.2 / §9.8 — how a JS push payload crosses into the SDK's `Map<String, String>` on Android.
 *
 * JS numbers arrive as `Double`; an integral one must cross as `"5"`, not `"5.0"` (R82) — what the host
 * wrote, and what Flutter produces for the same payload. Nested objects and arrays must cross as VALID
 * JSON that the SDK's `PushPayloadParser` reads (`action: {type, value}` routes the deep link) — never
 * Kotlin's `toString()`, which yields `{type=deep_link, …}`.
 */
@RunWith(RobolectricTestRunner::class) // org.json and JavaOnlyMap need a real Android runtime
@Config(sdk = [34])
class PushDataConversionTest {

    private fun payload(): JavaOnlyMap = JavaOnlyMap().apply {
        putString("appdna", "1")
        putString("push_id", "p_nested")
        putDouble("badge", 5.0)
        putDouble("ratio", 2.5)
        putBoolean("silent", false)
        putNull("nothing")
        putMap("action", JavaOnlyMap().apply {
            putString("type", "deep_link")
            putString("value", "x://y")
        })
        putArray("tags", JavaOnlyArray().apply {
            pushString("a")
            pushDouble(3.0)
        })
    }

    @Test
    fun `an integral double crosses without dot zero`() {
        val out = AppdnaPushData.toStringMap(payload())
        assertEquals("5", out["badge"])
        assertEquals("2.5", out["ratio"])
        assertEquals("false", out["silent"])
    }

    @Test
    fun `a nested map and a list cross as valid JSON the parser reads`() {
        val out = AppdnaPushData.toStringMap(payload())
        val action = JSONObject(out.getValue("action"))
        assertEquals("deep_link", action.getString("type"))
        assertEquals("x://y", action.getString("value"))
        val tags = JSONArray(out.getValue("tags"))
        assertEquals("a", tags.getString(0))
        assertEquals(3, tags.getInt(1))
        assertFalse("never Kotlin toString() output", out.values.any { it.startsWith("{type=") })
    }

    @Test
    fun `a null value is dropped and the marker survives`() {
        val out = AppdnaPushData.toStringMap(payload())
        assertFalse(out.containsKey("nothing"))
        assertEquals("1", out["appdna"])
        assertTrue(ai.appdna.sdk.AppDNA.push.isAppDNAMessage(out))
    }
}
