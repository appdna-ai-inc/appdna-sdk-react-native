package com.appdna.rn

import android.view.View
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.lang.ref.WeakReference

/**
 * The performance budgets, as numbers in CI.
 *
 * AC-37 asks for budgets that are "**numbers in CI, not vibes**", and the distinction it is drawing is
 * between a measurement and a constant. Two of the six existed (app size, ScreenSlot layout shift).
 * The other four — cold-start, veto latency, `configure()` JS-thread time, memory — had **zero hits
 * repo-wide**. This file is the four, minus the one that genuinely cannot be measured here.
 *
 * ## The rules this file holds itself to
 *
 * 1. **Every number is measured.** Not one assertion compares a constant to itself. Where a default
 *    is involved the oracle is the production code, never a literal copied beside it.
 * 2. **The budget lives in `.ai/sdk-perf-budgets.json`, not in this file.** A budget in the test is a
 *    number that moves whenever the test is edited, which is to say it is not a budget. Raising one
 *    is a reviewed edit to that file.
 * 3. **What cannot be measured is SAID, not faked.** The end-to-end cold-start TTI delta needs an app
 *    launch on a device; a JVM harness has no app, no frame, and no first frame to time. It is
 *    declared in the JSON's `_deferred` block with its reason. [bridgeModuleInit] measures the
 *    component of cold start the WRAPPER actually owns and can regress — the honest subset.
 *
 * ## Why these run under Robolectric on the JS-thread analogue
 *
 * A TurboModule method executes on the JS thread (E10). The JUnit thread is that thread's stand-in:
 * what a method does *before returning* is exactly what it would do to JS. That is a real measurement
 * of the property W15 is about, on the real code, with no device required.
 */
@RunWith(RobolectricTestRunner::class)
class AppdnaPerfBudgetTest {

    // ── The budgets, loaded from the file that owns them ────────────────────────

    private val budgets: Map<String, JSONObject> by lazy {
        val file = budgetsFile()
        assertNotNull(
            "Could not locate .ai/sdk-perf-budgets.json — the budgets must not silently vanish, " +
                "leaving a green suite that measures nothing",
            file,
        )
        val arr = JSONObject(file!!.readText(Charsets.UTF_8)).getJSONArray("budgets")
        (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .associateBy { it.getString("id") }
    }

    private fun budget(id: String): Double {
        val b = budgets[id] ?: throw AssertionError(
            "No budget '$id' in .ai/sdk-perf-budgets.json. A test measuring an unbudgeted number " +
                "asserts nothing; add it there or delete the test.",
        )
        return b.getDouble("budget")
    }

    /** Assert a measurement against its budget, and always PRINT it — a number nobody sees is a vibe. */
    private fun assertWithinBudget(id: String, measured: Double, unit: String) {
        val limit = budget(id)
        println("AC-37 [$id] measured %.3f %s (budget %.3f %s)".format(measured, unit, limit, unit))
        assertTrue(
            "[$id] measured $measured $unit, over the ${limit} $unit budget in .ai/sdk-perf-budgets.json. " +
                "If this growth is intentional, raise the budget THERE, in this PR, with a reason.",
            measured <= limit,
        )
    }

    // ── W15: configure() must barely touch the JS thread ────────────────────────

    @Test
    fun configureBarelyTouchesTheJsThread() {
        val ctx = mock(ReactApplicationContext::class.java)
        org.mockito.Mockito.`when`(ctx.applicationContext)
            .thenReturn(RuntimeEnvironment.getApplication())
        val module = AppdnaModule(ctx)

        // Warm up the classes `configure` touches on the caller's thread — AppDNAOptions, the enums,
        // the ReadableMap reader. Otherwise the first call measures the JVM's CLASS LOADER, not the
        // wrapper, and the budget would be enforcing something it does not name. (parseOptions is
        // `internal` precisely so the parse-options tests can reach it; that seam is reused here.)
        repeat(50) { module.parseOptions(JavaOnlyMap()) }

        // A no-op promise: the settling happens on Dispatchers.Default and is not what is being timed.
        val promise = mock(Promise::class.java, Answer<Any?> { null })

        // The JS thread's exposure is what `configure()` does BEFORE IT RETURNS. `AppDNA.configure`
        // itself is launched onto Dispatchers.Default, so what is timed here is the option parse plus
        // the coroutine launch — and if somebody ever moves the SDK call back inline, this is the
        // number that moves with it.
        //
        // The MEDIAN of many, not one sample. A single measurement of this swung 0.2ms → 5.6ms
        // between two runs of this very file — JIT and a GC that happened to land inside the window.
        // A budget enforced against one noisy sample is a flaky test, and a flaky perf test gets its
        // budget raised until it stops failing, which is how a budget becomes a vibe.
        val runs = 21
        val samplesMs = ArrayList<Double>(runs)
        repeat(runs) {
            val startNs = System.nanoTime()
            module.configure("adn_test_placeholder", "sandbox", JavaOnlyMap(), promise)
            samplesMs.add((System.nanoTime() - startNs) / 1_000_000.0)
        }
        samplesMs.sort()
        val medianMs = samplesMs[runs / 2]

        assertWithinBudget("rn-configure-js-thread-ms", medianMs, "ms")
    }

    // ── W12: the veto round trip (the wrapper's half of it) ─────────────────────

    @Test
    fun vetoRoundTripP95() {
        // The REAL invoker and the REAL host-callback registry — the objects `registerDelegates`
        // installs. `emit` plays JS: it answers immediately through the same `respond` path
        // `respondToHostCallback` calls, so the whole wrapper-side round trip is exercised.
        val invoker = AppdnaVetoInvoker(timeoutMs = 5_000L) { payload ->
            val callbackId = payload["callbackId"] as String
            AppdnaHostCallbacks.respond(callbackId, "true")
        }

        val warmup = 50
        val samples = 500
        val timingsMs = ArrayList<Double>(samples)

        runBlocking {
            repeat(warmup) { invoker.invoke("onPromoCodeSubmit", mapOf("code" to "SAVE20")) }
            repeat(samples) {
                val startNs = System.nanoTime()
                val reply = invoker.invoke("onPromoCodeSubmit", mapOf("code" to "SAVE20"))
                timingsMs.add((System.nanoTime() - startNs) / 1_000_000.0)
                // If the veto stopped round-tripping, every sample would be fast AND wrong. Timing a
                // broken path is the classic way a perf test goes green while the feature is dead.
                assertEquals("the veto did not round-trip — this timed nothing", true, reply)
            }
        }

        timingsMs.sort()
        // P95, the tail. A veto blocks a paywall while the host decides; the mean is not what a user
        // feels.
        val p95 = timingsMs[(samples * 0.95).toInt().coerceAtMost(samples - 1)]
        assertWithinBudget("rn-veto-roundtrip-p95-ms", p95, "ms")
    }

    // ── W21: the memory sample across ScreenSlot mount/dispose ──────────────────

    @Test
    fun screenSlotIsFullyReleasedAfterDispose() {
        val context = RuntimeEnvironment.getApplication()
        val cycles = 30

        val refs = ArrayList<WeakReference<AppdnaScreenSlotView>>(cycles)

        val heapBefore = usedHeapBytesAfterGc()
        repeat(cycles) { i ->
            // A React Native screen mounts and unmounts the slot on every navigation. The slot holds a
            // ComposeView, drives its own LifecycleOwner to RESUMED, and hangs ViewTree owners off it —
            // three strong edges. One retained edge is not one leaked view, it is one PER NAVIGATION.
            //
            // The injected-content constructor is used deliberately: it exercises the slot's OWN
            // lifecycle/measure/report machinery — the part this wrapper wrote and can leak — without
            // standing up a Compose runtime that Robolectric cannot host. What it cannot see, the heap
            // budget below can.
            val view = AppdnaScreenSlotView(context, View(context))
            view.setSlotName("slot_$i")
            view.contentSizeReporter = { _, _ -> }
            // Dispose EXACTLY as the ViewManager does — `onDropViewInstance` calls `onDropView()`,
            // and nothing else. If teardown ever stops being reachable from there, the leak is real
            // and this test must see it; driving `View.onDetachedFromWindow` instead would be testing
            // a path production does not take.
            view.onDropView()
            refs.add(WeakReference(view))
        }

        // GC is advisory, so ask repeatedly and stop as soon as everything is gone. Ten rounds is far
        // more than a JVM needs to collect 30 unreachable views; if they are still there, they are
        // REACHABLE, and something is holding them.
        var stillReachable = refs.size
        repeat(10) {
            if (stillReachable == 0) return@repeat
            forceGc()
            stillReachable = refs.count { it.get() != null }
        }

        assertWithinBudget("rn-screenslot-retained-after-dispose", stillReachable.toDouble(), "views")

        val heapGrowthKb = ((usedHeapBytesAfterGc() - heapBefore).coerceAtLeast(0L)) / 1024.0
        assertWithinBudget("rn-screenslot-heap-growth-kb", heapGrowthKb, "KB")
    }

    // ── Cold start: the component the wrapper actually owns ─────────────────────

    @Test
    fun bridgeModuleInit() {
        val ctx = mock(ReactApplicationContext::class.java)

        // The RN bridge constructs this TurboModule on its init path, before the host's first line of
        // JS runs — so whatever the constructor does is added to every cold start. This is not the
        // end-to-end TTI delta (see `_deferred` in .ai/sdk-perf-budgets.json: that needs a real app
        // launch and cannot be produced here). It is the slice of TTI the wrapper can regress.
        repeat(50) { AppdnaModule(ctx) }

        val runs = 200
        val startNs = System.nanoTime()
        repeat(runs) { AppdnaModule(ctx) }
        val perConstructionMs = (System.nanoTime() - startNs) / 1_000_000.0 / runs

        assertWithinBudget("rn-bridge-module-init-ms", perConstructionMs, "ms")
    }

    // ── Plumbing ───────────────────────────────────────────────────────────────

    private fun forceGc() {
        // A `System.gc()` alone is a hint the JVM may ignore. Allocating pressure first makes it one
        // the JVM acts on, which is the difference between a deterministic test and a flaky one.
        val ballast = ArrayList<ByteArray>()
        repeat(16) { ballast.add(ByteArray(1 shl 20)) }
        ballast.clear()
        System.gc()
        System.runFinalization()
        System.gc()
    }

    private fun usedHeapBytesAfterGc(): Long {
        forceGc()
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    /**
     * Walk up for the repo's `.ai/sdk-perf-budgets.json`. The Gradle test working directory is the
     * module dir, which — because the example installs this package as a real directory under
     * `node_modules` — may be several levels below the repo root. Same shape as the shared fixtures'
     * loader, and for the same reason.
     */
    private fun budgetsFile(): File? {
        System.getenv("APPDNA_PERF_BUDGETS")?.let { val f = File(it); if (f.isFile) return f }
        var here: File? = File(".").canonicalFile
        repeat(12) {
            val candidate = File(here, ".ai/sdk-perf-budgets.json")
            if (candidate.isFile) return candidate
            here = here?.parentFile
        }
        val codespace = File("/workspaces/appdna-ai/.ai/sdk-perf-budgets.json")
        return if (codespace.isFile) codespace else null
    }
}
