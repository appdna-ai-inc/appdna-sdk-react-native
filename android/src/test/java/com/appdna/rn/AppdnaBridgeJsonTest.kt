package com.appdna.rn

import ai.appdna.sdk.onboarding.ElementInteractionResult
import ai.appdna.sdk.onboarding.PermissionHandling
import ai.appdna.sdk.onboarding.StepAdvanceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two pieces no jest test can see.
 *
 * `AppdnaBridge.toJson` is the only encoding whose meaning must be identical on both platforms, and
 * `AppdnaVetoDecoder` turns a host's JSON reply into a native return type. Both live below the
 * bridge, so the TypeScript suite cannot reach them and the fixture runner cannot either.
 *
 * Robolectric, not a plain JVM test: `org.json` on the stock `android.jar` is a stub whose every
 * method throws.
 */
@RunWith(RobolectricTestRunner::class)
class AppdnaBridgeJsonTest {

    // ── toJson ───────────────────────────────────────────────────────────────

    @Test
    fun `encodes scalars at top level`() {
        assertEquals("null", AppdnaBridge.toJson(null))
        assertEquals("true", AppdnaBridge.toJson(true))
        assertEquals("3", AppdnaBridge.toJson(3))
        assertEquals("\"a\"", AppdnaBridge.toJson("a"))
    }

    /**
     * The regression this test exists for: `JSONObject(map)` does NOT wrap recursively on Android.
     * `JSONStringer` knows `JSONObject`, `JSONArray`, `String`, `Number`, `Boolean` and nothing else,
     * so a nested Kotlin `Map` was written as its `toString()` — `{"a":"{b=1}"}` instead of
     * `{"a":{"b":1}}`. An object-valued remote-config flag reached the facade as a string.
     */
    @Test
    fun `encodes a nested map as JSON, not as its toString`() {
        val json = AppdnaBridge.toJson(mapOf("a" to mapOf("b" to 1)))
        assertEquals("""{"a":{"b":1}}""", json)
    }

    @Test
    fun `encodes a nested list`() {
        assertEquals("""{"a":[1,2]}""", AppdnaBridge.toJson(mapOf("a" to listOf(1, 2))))
    }

    /** A value neither side can represent encodes as `null`, never as its `toString()`. */
    @Test
    fun `refuses to stringify a type it cannot represent`() {
        assertEquals("null", AppdnaBridge.toJson(Any()))
        assertEquals("""{"a":null}""", AppdnaBridge.toJson(mapOf("a" to Any())))
    }

    // ── fromJson ─────────────────────────────────────────────────────────────

    @Test
    fun `decodes every legal top-level value`() {
        assertNull(AppdnaBridge.fromJson("null"))
        assertNull(AppdnaBridge.fromJson(null))
        assertNull(AppdnaBridge.fromJson(""))
        assertEquals(true, AppdnaBridge.fromJson("true"))
        assertEquals("a", AppdnaBridge.fromJson("\"a\""))
        assertEquals(mapOf("a" to 1), AppdnaBridge.fromJson("""{"a":1}"""))
        assertEquals(listOf(1, 2), AppdnaBridge.fromJson("[1,2]"))
    }

    /** A host cannot make native throw by replying with garbage; a malformed reply means "no opinion". */
    @Test
    fun `a malformed reply decodes to null rather than throwing`() {
        assertNull(AppdnaBridge.fromJson("not json"))
        assertNull(AppdnaBridge.fromJson("{"))
    }

    /**
     * `JSONTokener` is lenient where `JSONSerialization` (iOS) is not: it reads `not json` as the
     * bare word "not", and it ignores trailing garbage after a complete value. Garbage therefore
     * came back as a *value* — an opinion — which for a veto hook is the difference between "the
     * host said nothing, apply the default" and "the host said yes".
     */
    @Test
    fun `leniency does not turn garbage into an opinion`() {
        assertNull(AppdnaBridge.fromJson("not"))          // a bare word is not a JSON string
        assertNull(AppdnaBridge.fromJson("true false"))   // trailing garbage after a legal value
        assertNull(AppdnaBridge.fromJson("""{"a":1} junk"""))
        // …while the legal forms still decode.
        assertEquals("not", AppdnaBridge.fromJson("\"not\""))
        assertEquals(true, AppdnaBridge.fromJson("  true  "))
    }

    @Test
    fun `a JSON null inside an object decodes to a Kotlin null`() {
        assertEquals(mapOf("a" to null), AppdnaBridge.fromJson("""{"a":null}"""))
    }

    // ── AppdnaVetoDecoder ────────────────────────────────────────────────────

    @Test
    fun `stepAdvanceResult defaults to Proceed for null, garbage, and unknown types`() {
        assertTrue(AppdnaVetoDecoder.stepAdvanceResult(null) is StepAdvanceResult.Proceed)
        assertTrue(AppdnaVetoDecoder.stepAdvanceResult("nonsense") is StepAdvanceResult.Proceed)
        assertTrue(AppdnaVetoDecoder.stepAdvanceResult(mapOf("type" to "??")) is StepAdvanceResult.Proceed)
    }

    @Test
    fun `stepAdvanceResult decodes each tagged shape`() {
        assertEquals(
            StepAdvanceResult.Block("Pick a plan"),
            AppdnaVetoDecoder.stepAdvanceResult(mapOf("type" to "block", "message" to "Pick a plan")),
        )
        assertEquals(
            StepAdvanceResult.ProceedWithData(mapOf("k" to 1)),
            AppdnaVetoDecoder.stepAdvanceResult(mapOf("type" to "proceedWithData", "data" to mapOf("k" to 1))),
        )
        assertEquals(
            StepAdvanceResult.SkipTo("s2"),
            AppdnaVetoDecoder.stepAdvanceResult(mapOf("type" to "skipTo", "stepId" to "s2")),
        )
        assertEquals(
            StepAdvanceResult.Stay("check your email"),
            AppdnaVetoDecoder.stepAdvanceResult(mapOf("type" to "stay", "message" to "check your email")),
        )
    }

    /** `skipTo` with data is a DIFFERENT constructor. An empty `data` must not silently become one. */
    @Test
    fun `skipTo carries data only when the host sent some`() {
        val withData = AppdnaVetoDecoder.stepAdvanceResult(
            mapOf("type" to "skipTo", "stepId" to "s2", "data" to mapOf("k" to 1)),
        )
        assertEquals(StepAdvanceResult.SkipTo("s2", mapOf("k" to 1)), withData)

        val empty = AppdnaVetoDecoder.stepAdvanceResult(
            mapOf("type" to "skipTo", "stepId" to "s2", "data" to emptyMap<String, Any>()),
        )
        assertEquals(StepAdvanceResult.SkipTo("s2"), empty)
    }

    @Test
    fun `permissionHandling short-circuits only on handledByHost`() {
        assertNull(AppdnaVetoDecoder.permissionHandling(null))
        assertTrue(AppdnaVetoDecoder.permissionHandling(mapOf("type" to "proceed")) is PermissionHandling.Proceed)
        assertEquals(
            PermissionHandling.HandledByHost(true),
            AppdnaVetoDecoder.permissionHandling(mapOf("type" to "handledByHost", "granted" to true)),
        )
        // A `handledByHost` with no `granted` key means the host claimed to handle it and told us
        // nothing. Denying is the only safe reading.
        assertEquals(
            PermissionHandling.HandledByHost(false),
            AppdnaVetoDecoder.permissionHandling(mapOf("type" to "handledByHost")),
        )
    }

    @Test
    fun `elementInteractionResult decodes nested field-config patches`() {
        val result = AppdnaVetoDecoder.elementInteractionResult(
            mapOf(
                "fieldConfigPatches" to mapOf("b1" to mapOf("disabled" to true)),
                "inputValuePatches" to mapOf("f1" to "v"),
                "advance" to true,
            ),
        )
        assertEquals(
            ElementInteractionResult(
                fieldConfigPatches = mapOf("b1" to mapOf("disabled" to true)),
                inputValuePatches = mapOf("f1" to "v"),
                advance = true,
            ),
            result,
        )
    }

    @Test
    fun `elementInteractionResult advance defaults to false`() {
        val result = AppdnaVetoDecoder.elementInteractionResult(emptyMap<String, Any>())
        assertFalse(result!!.advance)
    }

    /**
     * A JSON `null` inside a veto reply is DROPPED rather than crashing the step: the native DTOs
     * take `Map<String, Any>`, which cannot hold a Kotlin null, and an absent key is exactly what the
     * SDK's own no-delegate path produces.
     */
    @Test
    fun `a null value inside a reply map is dropped, not crashed on`() {
        val result = AppdnaVetoDecoder.stepAdvanceResult(
            mapOf("type" to "proceedWithData", "data" to mapOf("k" to null, "j" to 1)),
        )
        assertEquals(StepAdvanceResult.ProceedWithData(mapOf("j" to 1)), result)
    }
}
