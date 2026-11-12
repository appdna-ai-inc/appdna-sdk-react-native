package com.appdna.rn

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.AppDNAOptions
import ai.appdna.sdk.Environment
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * RN COPY of the native SDK's `ConsentFixtureRunner` (another Gradle module cannot reach its test classes); the
 * SDK calls go through the bridged `AppdnaModule`, and the internal hooks are reached by reflection.
 *
 * Drives the shared consent fixtures (`event_emission/consent_*`, `action.kind == "script"`) through the REAL
 * `AppDNA` singleton: install the clock seam, reset, seed the stores, then run each script op and settle.
 *
 * Settling: `runCurrent()` on the injected scheduler (never `advanceUntilIdle`, which would run a
 * retry), then the sender's idle hook (nothing on the wire), repeated until nothing is due, nothing is in flight and
 * no completion is pending. `advance_clock_ms` advances deadline by deadline, settling at each, so a chained retry
 * exists. The stub server records each consent POST with `at_ms` on the injected clock and answers it from the
 * case's `request_replies` (the last repeats; none means 202 `{}`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal open class ConsentFixtureRunner(private val ctx: Context) {

    // ── The SDK under test. The native runner calls AppDNA directly; the wrapper runners (RN, Flutter plugin)
    // override these to go through their bridged module and reach the internal hooks by reflection. ──────────
    // The hooks are `internal` to the native SDK; `@JvmName` keeps their JVM names un-mangled for exactly this.
    private fun hook(name: String, vararg types: Class<*>) =
        AppDNA::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
    open fun installClock(dispatcher: kotlinx.coroutines.CoroutineDispatcher?, now: (() -> Long)?) {
        hook("installConsentClockForTesting", kotlinx.coroutines.CoroutineDispatcher::class.java, Function0::class.java)
            .invoke(AppDNA, dispatcher, now)
    }
    open fun resetSdk() { hook("resetForTesting").invoke(AppDNA) }
    open fun seed(setupJson: String) {
        hook("seedConsentFixtureForTesting", Context::class.java, String::class.java).invoke(AppDNA, ctx, setupJson)
    }
    open fun awaitIdle(timeoutMs: Long): Int =
        hook("awaitConsentSenderIdleForTesting", Long::class.javaPrimitiveType!!).invoke(AppDNA, timeoutMs) as Int
    open fun nextDeadline(): Long? = hook("nextConsentDeadlineForTesting").invoke(AppDNA) as Long?
    open fun configure(requireConsent: Boolean) = AppDNA.configure(
        ctx, "adn_test_placeholder", Environment.SANDBOX, AppDNAOptions(requireConsent = requireConsent),
    )
    open fun setConsent(analytics: Boolean) = AppDNA.setConsent(analytics)
    open fun identify(userId: String) = AppDNA.identify(userId)
    open fun reset() = AppDNA.reset()
    open fun shutdown() = AppDNA.shutdown()

    private val settleTimeoutMs = 10_000L

    fun runAll(fixtureName: String, action: JSONObject) {
        val cases = action.getJSONArray("cases")
        val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            try {
                runCase(c)
            } catch (e: AssertionError) {
                failures += "[$fixtureName / ${c.getString("name")}] ${e.message}"
            }
        }
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n"))
    }

    private fun runCase(case: JSONObject) {
        val setup = case.getJSONObject("setup")
        val epoch = setup.getLong("clock_epoch_ms")
        val script = case.getJSONArray("script")
        validateScript(script)

        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val server = ConsentStubServer(setup.optJSONArray("request_replies")) { scheduler.currentTime }
        try {
            // install, reset, seed, configure
            installClock(dispatcher) { epoch + scheduler.currentTime }
            resetSdk()
            pointAt(server.baseUrl)
            wipeStores()
            seed(setup.toString())

            for (i in 0 until script.length()) {
                val op = script.getJSONObject(i)
                when (val kind = op.getString("kind")) {
                    "configure" -> configure(op.optBoolean("require_consent", false))
                    "set_consent" -> setConsent(op.getBoolean("analytics"))
                    "identify" -> identify(op.getString("userId"))
                    "reset" -> reset()
                    "shutdown" -> shutdown()
                    "relaunch" -> resetSdk()
                    "advance_clock_ms" -> advance(scheduler, epoch, op.getLong("ms"))
                    else -> throw AssertionError("NO DRIVER for script op '$kind' on android")
                }
                settle(scheduler)
            }
            assertRequests(case.getJSONObject("expect").getJSONArray("requests"), server.recorded(), epoch)
        } finally {
            resetSdk()
            // Leave a fresh device behind: the fixtures after this one configure the same singleton, and a
            // declined decision left in the consent store would keep every later event on the device.
            wipeStores()
            installClock(null, null)
            unpoint()
            server.close()
        }
    }

    /** A configure needs a relaunch, reinstall or shutdown since the previous one; only set_consent may run unconfigured. */
    private fun validateScript(script: JSONArray) {
        var configured = false
        for (i in 0 until script.length()) {
            when (val k = script.getJSONObject(i).getString("kind")) {
                "configure" -> { assertTrue("configure while configured at op $i", !configured); configured = true }
                "relaunch", "reinstall", "shutdown" -> {
                    if (k == "shutdown") assertTrue("shutdown while unconfigured at op $i", configured)
                    configured = false
                }
                "set_consent", "advance_clock_ms" -> Unit
                else -> assertTrue("SDK call '$k' while unconfigured at op $i", configured)
            }
        }
    }

    private fun settle(scheduler: TestCoroutineScheduler) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settleTimeoutMs)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            scheduler.runCurrent()
            val pending = awaitIdle(settleTimeoutMs)
            if (pending < 0) throw AssertionError("settle timed out: a consent request never completed")
            if (pending == 0) {
                scheduler.runCurrent()
                if (awaitIdle(settleTimeoutMs) == 0) return
            }
            if (System.nanoTime() > end) throw AssertionError("settle timed out with $pending completions pending")
            Thread.sleep(2)
        }
    }

    private fun advance(scheduler: TestCoroutineScheduler, epoch: Long, ms: Long) {
        val target = scheduler.currentTime + ms
        while (true) {
            val d = nextDeadline() ?: break
            val due = d - epoch
            if (due > target) break
            if (due > scheduler.currentTime) scheduler.advanceTimeBy(due - scheduler.currentTime)
            scheduler.runCurrent()
            settle(scheduler)
        }
        if (target > scheduler.currentTime) scheduler.advanceTimeBy(target - scheduler.currentTime)
        scheduler.runCurrent()
        settle(scheduler)
    }

    private fun pointAt(baseUrl: String) {
        val pkg = shadowOf(ctx.packageManager).getInternalMutablePackageInfo(ctx.packageName)
        pkg.applicationInfo!!.flags = pkg.applicationInfo!!.flags or ApplicationInfo.FLAG_DEBUGGABLE
        pkg.applicationInfo!!.metaData = Bundle().apply { putString("ai.appdna.sdk.BASE_URL_OVERRIDE", baseUrl) }
    }

    private fun unpoint() {
        val pkg = shadowOf(ctx.packageManager).getInternalMutablePackageInfo(ctx.packageName)
        pkg.applicationInfo!!.metaData = null
    }

    /** Each case is a fresh device: no decision, no identity, no consent state. */
    private fun wipeStores() {
        for (name in listOf("ai.appdna.sdk", "ai.appdna.sdk.secure", "ai.appdna.sdk.consent")) {
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun assertRequests(expected: JSONArray, actual: List<Recorded>, epoch: Long) {
        val got = actual.joinToString("\n    ") { "at ${it.atMs}: ${it.body}" }
        assertEquals("request count; recorded:\n    $got", expected.length(), actual.size)
        for (i in 0 until expected.length()) {
            val e = expected.getJSONObject(i)
            val a = actual[i]
            val where = "requests[$i] (recorded:\n    $got)"
            assertEquals("$where method", e.getString("method"), "POST")
            assertEquals("$where path", e.getString("path"), "/api/v1/ingest/consent")
            val eb = e.getJSONObject("body")
            val ab = JSONObject(a.body)
            assertEquals("$where keys", eb.keys().asSequence().toSortedSet(), ab.keys().asSequence().toSortedSet())
            for (k in listOf("anon_id", "user_id", "analytics")) {
                assertEquals("$where body.$k", eb.opt(k), ab.opt(k))
            }
            inRange("$where body.ts_ms", ab.getLong("ts_ms"), eb.get("ts_ms"))
            e.optJSONObject("at_ms")?.let { inRange("$where at_ms", a.atMs, it) }
            e.optJSONObject("since_prev_ms")?.let { inRange("$where since_prev_ms", a.atMs - actual[i - 1].atMs, it) }
        }
    }

    private fun inRange(what: String, v: Long, m: Any) {
        if (m is Number) { assertEquals(what, m.toLong(), v); return }
        val o = m as JSONObject
        if (o.has("gte")) assertTrue("$what = $v < ${o.getLong("gte")}", v >= o.getLong("gte"))
        if (o.has("lte")) assertTrue("$what = $v > ${o.getLong("lte")}", v <= o.getLong("lte"))
    }

    data class Recorded(val atMs: Long, val body: String)

    /**
     * Loopback stub. Consent POSTs are recorded and answered from the case's replies; bootstrap gets a valid answer
     * (so recovery never starts); everything else 202 `{}`. A `network_failure` reply reads the request, then closes.
     */
    private class ConsentStubServer(private val replies: JSONArray?, private val clock: () -> Long) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        private val recorded = Collections.synchronizedList(mutableListOf<Recorded>())
        @Volatile private var closed = false

        init {
            thread(isDaemon = true, name = "consent-stub-accept") {
                while (!closed) {
                    val s = try { server.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true, name = "consent-stub-conn") { serve(s) }
                }
            }
        }

        fun recorded(): List<Recorded> = synchronized(recorded) { recorded.toList() }

        private fun reply(index: Int): JSONObject? {
            val r = replies ?: return null
            if (r.length() == 0) return null
            return r.getJSONObject(minOf(index, r.length() - 1))
        }

        private fun serve(s: Socket) {
            try {
                val input = BufferedInputStream(s.getInputStream())
                while (!closed) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) continue
                    var length = 0
                    while (true) {
                        val h = readLine(input) ?: return
                        if (h.isEmpty()) break
                        val i = h.indexOf(':')
                        if (i > 0 && h.substring(0, i).trim().equals("content-length", true)) length = h.substring(i + 1).trim().toInt()
                    }
                    val bytes = ByteArray(length)
                    var read = 0
                    while (read < length) { val n = input.read(bytes, read, length - read); if (n < 0) return; read += n }
                    val parts = line.split(" ")
                    val path = parts.getOrElse(1) { "" }
                    var status = 202
                    var body = "{}"
                    val headers = mutableMapOf<String, String>()
                    if (parts[0] == "POST" && path == "/api/v1/ingest/consent") {
                        val idx: Int
                        synchronized(recorded) { idx = recorded.size; recorded += Recorded(clock(), String(bytes, Charsets.UTF_8)) }
                        val r = reply(idx)
                        if (r != null && r.optBoolean("network_failure", false)) return
                        if (r != null) {
                            status = r.getInt("status")
                            r.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.getString(k) } }
                        }
                    } else if (path.startsWith("/api/v1/sdk/bootstrap")) {
                        status = 200
                        body = JSONObject().put("orgId", "org_consent").put("appId", "app_consent")
                            .put("firestorePath", "orgs/org_consent/apps/app_consent").put("settings", JSONObject()).toString()
                    }
                    val payload = body.toByteArray(Charsets.UTF_8)
                    val out = s.getOutputStream()
                    out.write(
                        ("HTTP/1.1 $status X\r\nContent-Type: application/json\r\n" +
                            headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } +
                            "Content-Length: ${payload.size}\r\n\r\n").toByteArray(Charsets.UTF_8),
                    )
                    out.write(payload)
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { s.close() }
            }
        }

        private fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (c == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
                buf.write(c)
            }
        }

        override fun close() {
            closed = true
            runCatching { server.close() }
        }
    }
}

/** The RN leg: every SDK call through the REAL bridged `AppdnaModule`, settled on its promise. */
internal class RnConsentFixtureRunner(
    ctx: Context,
    private val module: AppdnaModule,
) : ConsentFixtureRunner(ctx) {
    private fun call(block: (com.facebook.react.bridge.Promise) -> Unit) {
        val done = java.util.concurrent.CountDownLatch(1)
        val p = org.mockito.Mockito.mock(com.facebook.react.bridge.Promise::class.java, org.mockito.stubbing.Answer { inv ->
            if (inv.method.name == "resolve" || inv.method.name == "reject") done.countDown(); null
        })
        block(p)
        val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (done.count > 0 && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        if (done.count > 0) throw AssertionError("a bridged call never settled its promise")
    }
    override fun configure(requireConsent: Boolean) = call { p ->
        module.configure("adn_test_placeholder", "sandbox", com.facebook.react.bridge.JavaOnlyMap().apply {
            putBoolean("requireConsent", requireConsent)
            putString("logLevel", "none")
        }, p)
    }
    override fun setConsent(analytics: Boolean) = call { p -> module.setConsent(analytics, p) }
    override fun identify(userId: String) = call { p -> module.identify(userId, null, p) }
    override fun reset() = call { p -> module.reset(p) }
    override fun shutdown() = call { p -> module.shutdown(p) }
}
