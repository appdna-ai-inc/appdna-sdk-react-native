package com.appdna.rn

import ai.appdna.sdk.onboarding.InputValidationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Live validation — the React Native Android bridge half of `onValidateInput`, through the REAL invoker, the REAL pending-
 * callback map and the REAL forwarder:
 *
 *  1. every reply in the shared parse fixture, sent as the JSON a JS host's reply crosses the bridge as, decodes to the
 *     fixture's expected result through core's `InputValidationResult.decodeBridgeReply`;
 *  2. JS's "no handler registered" sentinel becomes `not_implemented`, so the field behaves as if there were no delegate;
 *  3. outside a check the bridge waits `bridgeTimeoutMs()` (the field's 5 s default plus a second), not `vetoTimeout`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ValidateInputBridgeTest {

    private suspend fun kotlinx.coroutines.test.TestScope.validate(replyJson: String, afterMs: Long = 0): InputValidationResult? {
        val invoker = AppdnaVetoInvoker(1_000L) { payload ->
            backgroundScope.launch {
                delay(afterMs)
                AppdnaHostCallbacks.respond(payload["callbackId"] as String, replyJson)
            }
        }
        val forwarder = OnboardingForwarder(AppdnaEventEmitter { _, _ -> }, invoker)
        return forwarder.onValidateInput("f", "s", "username", "abc")
    }

    @Test
    fun `every fixture reply decodes as the fixture says`() = runTest {
        val fixture = JSONObject(File(fixturesRoot(), "delegate_contracts/validate_input_result_parse.fixture.json").readText())
        val cases = fixture.getJSONObject("action").getJSONArray("cases")
        val expected = fixture.getJSONObject("expect").getJSONObject("state_after").getJSONArray("results")
        for (i in 0 until cases.length()) {
            val reply = cases.getJSONObject(i).opt("reply")
            val wire = if (reply == null || reply == JSONObject.NULL) "null" else JSONObject.wrap(reply).toString()
            val got = validate(wire)
            val want = expected.opt(i)
            val name = cases.getJSONObject(i).getString("name")
            if (want == null || want == JSONObject.NULL) {
                assertNull("[$name] expected no answer, got $got", got)
            } else {
                val w = want as JSONObject
                val message = if (w.isNull("message")) null else w.getString("message")
                when (w.getString("status")) {
                    "valid" -> assertEquals(name, InputValidationResult.Valid(message), got)
                    "invalid" -> assertEquals(name, InputValidationResult.Invalid(message), got)
                    "not_implemented" -> assertEquals(name, "NotImplemented", got?.javaClass?.simpleName)
                }
            }
        }
    }

    @Test
    fun `no JS handler is not_implemented`() = runTest {
        val got = validate("""{"__appdna_unhandled":true}""")
        assertEquals("NotImplemented", got?.javaClass?.simpleName)
    }

    @Test
    fun `the bridge waits the field timeout, not vetoTimeout`() = runTest {
        // vetoTimeout is 1 s; outside a check the field default (5 s) plus a second applies.
        assertEquals(InputValidationResult.Valid(), validate("""{"status":"valid"}""", afterMs = 5_500))
        val start = testScheduler.currentTime
        assertNull(validate("""{"status":"valid"}""", afterMs = 7_000))
        assertEquals(6_000L, testScheduler.currentTime - start)
    }

    private fun fixturesRoot(): File {
        System.getenv("APPDNA_SDK_FIXTURES_DIR")?.let { if (File(it).isDirectory) return File(it) }
        var here: File? = File(".").canonicalFile
        repeat(12) {
            val candidate = File(here, "packages/sdk-shared-fixtures")
            if (candidate.isDirectory) return candidate
            here = here?.parentFile
        }
        error("Could not locate packages/sdk-shared-fixtures. Set APPDNA_SDK_FIXTURES_DIR.")
    }
}
