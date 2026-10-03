package com.appdna.rn

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.onboarding.StepAdvanceResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The React Native Android bridge half of the sign-in timeout floor, on
 * coroutine virtual time, through the REAL invoker, the REAL pending-callback map and the REAL forwarder:
 *
 *  - a `social_login` reply at 60 s is delivered (the bridge waits `max(vetoTimeout, 120 s)`);
 *  - a reply at 121 s is too late: `Block(AUTH_UNAVAILABLE_MESSAGE)` at exactly 120 000 ms;
 *  - a non-auth reply at 6 s times out at the configured 5 s;
 *  - a configured 10 s lets that 6 s reply through;
 *  - every timeout moves `diagnose()`'s `veto.timeouts_observed` by exactly 1;
 *  - the shared fixture's `bridge_waits` rows hold as measured waits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BeforeStepAdvanceBridgeTest {

    private val authBlock = "Sign-in isn't available right now. Please try again later."

    /** A forwarder whose JS host answers [reply] after [afterMs] of virtual time (never, when null). */
    private fun TestScope.forwarder(configuredMs: Long, afterMs: Long?, reply: String = """{"type":"proceed"}"""): OnboardingForwarder {
        val invoker = AppdnaVetoInvoker(configuredMs) { payload ->
            if (afterMs != null) {
                backgroundScope.launch {
                    delay(afterMs)
                    AppdnaHostCallbacks.respond(payload["callbackId"] as String, reply)
                }
            }
        }
        return OnboardingForwarder(AppdnaEventEmitter { _, _ -> }, invoker)
    }

    private suspend fun OnboardingForwarder.advance(action: String?) =
        onBeforeStepAdvance("flow", "step_a", 0, "question", emptyMap(), action?.let { mapOf("action" to it) })

    private fun timeoutsObserved(): Int =
        Regex("veto\\.timeouts_observed: (\\d+)").find(AppDNA.diagnose())?.groupValues?.get(1)?.toInt()
            ?: throw AssertionError("diagnose() has no veto.timeouts_observed line")

    @Test
    fun `a sign-in reply at 60 s is delivered, not blocked at the 5 s default`() = runTest {
        assertEquals(StepAdvanceResult.Proceed, forwarder(5_000L, 60_000L).advance("social_login"))
    }

    @Test
    fun `a sign-in reply at 121 s is too late - the bridge blocks at exactly 120 s and counts it`() = runTest {
        val before = timeoutsObserved()
        val start = testScheduler.currentTime
        val result = forwarder(5_000L, 121_000L).advance("social_login")
        assertEquals(120_000L, testScheduler.currentTime - start)
        assertEquals(StepAdvanceResult.Block(authBlock), result)
        assertEquals(before + 1, timeoutsObserved())
    }

    @Test
    fun `a non-auth reply at 6 s times out at the configured 5 s`() = runTest {
        val before = timeoutsObserved()
        val start = testScheduler.currentTime
        forwarder(5_000L, 6_000L, """{"type":"stay"}""").advance("next")
        assertEquals(5_000L, testScheduler.currentTime - start)
        assertEquals(before + 1, timeoutsObserved())
    }

    @Test
    fun `a configured 10 s lets a non-auth reply at 6 s through`() = runTest {
        assertEquals(StepAdvanceResult.Stay(null), forwarder(10_000L, 6_000L, """{"type":"stay"}""").advance("next"))
    }

    @Test
    fun `the shared fixture's bridge_waits hold as measured waits`() = runTest {
        val action = JSONObject(File(fixturesRoot(), "delegate_contracts/sign_in_bridge_timeout_floor.fixture.json").readText())
            .getJSONObject("action")
        val waits = action.getJSONArray("bridge_waits")
        assertTrue("the fixture has no bridge_waits", waits.length() > 0)
        for (i in 0 until waits.length()) {
            val row = waits.getJSONObject(i)
            val stepAction = row.optJSONObject("step_data")?.optString("action")
            val start = testScheduler.currentTime
            forwarder(row.getLong("configured_ms"), null).advance(stepAction)
            assertEquals("bridge_waits[$i]", row.getLong("expect_wait_ms"), testScheduler.currentTime - start)
        }
    }

    /**
     * The bridge half of `delegate_contracts/skip_to_without_step_id_is_not_a_decision`: each JSON
     * reply goes through the REAL invoker, the REAL auth gate and the REAL decoder. NEGATIVE CONTROL:
     * with the old decoder a `{"type":"skipTo"}` on a sign-in step returned `SkipTo("")` (which
     * advances) instead of `Block`.
     */
    @Test
    fun `the shared fixture's skipTo replies decode as the fixture says`() = runTest {
        val cases = JSONObject(File(fixturesRoot(), "delegate_contracts/skip_to_without_step_id_is_not_a_decision.fixture.json").readText())
            .getJSONObject("action").getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i)
            val reply = row.opt("reply").let { if (it == null || it == JSONObject.NULL) "null" else it.toString() }
            val result = forwarder(5_000L, 0L, reply).advance(if (row.getBoolean("auth_action")) "email_login" else "next")
            val expected = row.getJSONObject("expect_result")
            val (type, stepId) = when (result) {
                is StepAdvanceResult.Proceed -> "proceed" to null
                is StepAdvanceResult.ProceedWithData -> "proceedWithData" to null
                is StepAdvanceResult.Block -> "block" to null
                is StepAdvanceResult.SkipTo -> "skipTo" to result.stepId
                is StepAdvanceResult.Stay -> "stay" to null
            }
            assertEquals("cases[$i] $reply type", expected.getString("type"), type)
            assertEquals("cases[$i] step_id", if (expected.has("step_id")) expected.getString("step_id") else null, stepId)
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
