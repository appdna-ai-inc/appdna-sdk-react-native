package com.appdna.rn

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * SPEC-496 §5b C9 — the React Native Android bridge half of "Show more":
 *
 *  1. `onElementInteraction` with `action == "refresh"` waits `max(vetoTimeout, core
 *     minimumBridgeTimeoutMs)` — a JS host answering at 6 s (above the 5 s default) IS delivered,
 *     while a non-refresh interaction still times out at the configured 5 s;
 *  2. `dataContext` is forwarded through the CORE decoder: the shared decode fixture, sent as the JSON a
 *     JS host's reply crosses the bridge as, decodes type-strictly — a null member is kept (it removes
 *     the key), 0/1 stay numbers, bools stay bools.
 *
 * Driven through the REAL invoker, the REAL pending-callback map and the REAL forwarder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // AppdnaBridge speaks org.json, which the stock android.jar stubs
@Config(sdk = [34])
class ElementInteractionBridgeTest {

    private val replyAt6s = """{"dataContext":{"recommendations":[{"id":"e"}]},"advance":false}"""

    private suspend fun kotlinx.coroutines.test.TestScope.interact(action: String): ai.appdna.sdk.onboarding.ElementInteractionResult? {
        lateinit var invoker: AppdnaVetoInvoker
        invoker = AppdnaVetoInvoker(5_000L) { payload ->
            // The JS host answers after 6 s of (virtual) time.
            backgroundScope.launch {
                delay(6_000)
                AppdnaHostCallbacks.respond(payload["callbackId"] as String, replyAt6s)
            }
        }
        val forwarder = OnboardingForwarder(AppdnaEventEmitter { _, _ -> }, invoker)
        return forwarder.onElementInteraction("f", "step_eir", "show_more", action, "more", emptyMap())
    }

    @Test
    fun `a refresh reply at 6 s is delivered although vetoTimeout is 5 s`() = runTest {
        val result = interact("refresh")
        assertNotNull("the bridge cut the refresh short of the SDK's 8 s deadline", result)
        val recs = result!!.dataContext!!["recommendations"] as List<*>
        assertEquals("e", (recs.single() as Map<*, *>)["id"])
    }

    @Test
    fun `a non-refresh interaction still times out at the configured 5 s`() = runTest {
        val start = testScheduler.currentTime
        assertNull(interact("otp_entered"))
        assertEquals(5_000L, testScheduler.currentTime - start)
    }

    @Test
    fun `the decode fixture round-trips through the bridge type-strictly`() {
        val fixture = JSONObject(File(fixturesRoot(), "config_overrides/element_interaction_data_context_decode.fixture.json").readText())
        val reply = fixture.getJSONObject("setup").getJSONObject("session_data").getJSONObject("host_interaction_reply")
        val expected = fixture.getJSONObject("expect").getJSONObject("state_after").get("decoded_data_context")
        // Exactly what a JS host's reply is on the wire, and exactly the function the forwarder calls.
        val result = AppdnaVetoDecoder.elementInteractionResult(AppdnaBridge.fromJson(reply.toString()))
        assertNotNull(result)
        assertFalse(result!!.advance)
        strict(expected, result.dataContext, "$")
        assertTrue("a null member is the removal marker", result.dataContext!!.containsKey("banner"))
    }

    private fun strict(expected: Any?, actual: Any?, path: String) {
        fun no(msg: String): Nothing = throw AssertionError("$path: $msg (expected=$expected actual=$actual)")
        when (expected) {
            null, JSONObject.NULL -> if (actual != null) no("expected null")
            is JSONObject -> {
                val m = actual as? Map<*, *> ?: no("expected an object")
                val ek = expected.keys().asSequence().toSet()
                if (ek != m.keys.map { it.toString() }.toSet()) no("keys differ")
                ek.forEach { strict(expected.opt(it), m[it], "$path.$it") }
            }
            is JSONArray -> {
                val l = actual as? List<*> ?: no("expected an array")
                if (l.size != expected.length()) no("length differs")
                for (i in 0 until expected.length()) strict(expected.opt(i), l[i], "$path[$i]")
            }
            is Boolean -> if (actual != expected) no("expected a bool")
            is Int, is Long -> if (actual !is Int && actual !is Long || (actual as Number).toLong() != (expected as Number).toLong()) no("expected an integer")
            is Number -> if (actual !is Double && actual !is Float || (actual as Number).toDouble() != expected.toDouble()) no("expected a fractional number")
            else -> if (actual != expected) no("differs")
        }
    }

    private fun fixturesRoot(): File {
        System.getenv("APPDNA_SDK_FIXTURES_DIR")?.let { if (File(it).isDirectory) return File(it) }
        var here: File? = File(".").canonicalFile
        repeat(12) {
            val candidate = File(here, "packages/sdk-shared-fixtures")
            if (candidate.isDirectory) return candidate
            here = here?.parentFile
        }
        val codespace = File("/workspaces/appdna-ai/packages/sdk-shared-fixtures")
        if (codespace.isDirectory) return codespace
        error("Could not locate packages/sdk-shared-fixtures. Set APPDNA_SDK_FIXTURES_DIR.")
    }
}
