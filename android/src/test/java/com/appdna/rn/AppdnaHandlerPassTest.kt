package com.appdna.rn

import com.facebook.react.bridge.JavaOnlyArray
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * The anti-"bridged-but-dead" evidence pass, Android half.
 *
 * ## The bug class
 *
 * 070-C's DOMINANT defect was not "the method is wrong". It was **"the method is bridged and nothing
 * ever calls it"** — a delegate that never fired, an event nobody emitted, a parameter silently
 * dropped. Every one of those passes a compile, passes `check:rn-facade-parity` (which proves the
 * method EXISTS in the IR, in Kotlin, in Swift, in the ObjC++ adapter and in the TS spec), and passes
 * a jest suite that mocks native away. Existence is not reachability, and until now nothing in this
 * repo asserted the difference.
 *
 * AC-3 asks for the assertion: an EVIDENCE set, recorded by actually running the code, that must
 * equal the MANIFEST set, extracted from the sources. Any gap is named.
 *
 * ## Why this is reflective, and why that is the point
 *
 * The method list is not typed out here. It is read off the CLASS — every `override fun` the module
 * declares — and each one is invoked on a real `AppdnaModule` instance with synthesised arguments. A
 * hand-written list of 55 calls would be a second manifest, drifting from the first, and the fifty-
 * sixth method would simply never be added to it. That is the same failure one level up.
 *
 * ## What counts as REACHED
 *
 * A method is reached when its BODY RAN. Two signals, and both are proof:
 *
 *   - it settled its promise (resolve or reject) — the body ran to completion; or
 *   - it threw, and the stack trace has a frame inside `com.appdna.rn.AppdnaModule` or, better,
 *     inside `ai.appdna.sdk` — i.e. it got as far as the SDK singleton. The SDK is unconfigured here,
 *     so plenty of calls throw; a throw FROM INSIDE is evidence, not absence. What is NOT evidence is
 *     an `AbstractMethodError` or a `NoSuchMethodError` — the signature of a method that is declared
 *     and not implemented, which is precisely the corpse this pass hunts.
 *
 * Void methods (`track`, `setLogLevel`, `notifyScreenAppeared`, `suppressMessages`,
 * `respondToHostCallback`) have no promise; returning normally is the body running to completion.
 *
 * ## Events
 *
 * All 41 are driven through the REAL `emitEventNamed` dispatch. Its `else` branch throws
 * `IllegalStateException("no TurboModule emitter for event …")` — so an event whose `when` branch is
 * missing is *distinguishable* from one whose branch exists but whose emitter needs a live JS bridge
 * (that one throws from inside the codegen'd base class, which is evidence the branch was taken).
 *
 * The evidence is written to `handler-pass-android.json` at the repo root and checked by
 * `scripts/check-rn-handler-evidence.ts`, which fails on any manifest entry with no evidence.
 */
@RunWith(RobolectricTestRunner::class)
class AppdnaHandlerPassTest {

    /** RN lifecycle hooks, not SDK surface. They have no IR entry by design. */
    private val notSdkMethods = setOf("invalidate", "getName", "getConstants", "initialize")

    @Test
    fun everyBridgedMethodAndEventIsActuallyReached() {
        val ctx = mock(ReactApplicationContext::class.java)
        org.mockito.Mockito.`when`(ctx.applicationContext)
            .thenReturn(RuntimeEnvironment.getApplication())
        val module = AppdnaModule(ctx)

        // CONFIGURE FIRST, and wait for the SDK to actually reach READY.
        //
        // Not a nicety. `onReady`'s whole contract is "settle when the SDK becomes ready", so against
        // an unconfigured singleton it correctly does not settle — and the strict rule below would
        // then call it dead, which would be the gate lying about the code rather than the code lying
        // about itself. Configure, wait for readiness through the SDK's OWN `onReady` (the only
        // observable: `isConfigured` is private), and every method is then exercised against a live
        // singleton, which is also a far more honest wiring pass than one against a dead one.
        val ready = java.util.concurrent.CountDownLatch(1)
        module.configure("adn_test_placeholder", "sandbox", JavaOnlyMap(), mock(Promise::class.java, Answer<Any?> { null }))
        ai.appdna.sdk.AppDNA.onReady { ready.countDown() }
        val readyDeadline = System.currentTimeMillis() + 20_000
        while (ready.count > 0L && System.currentTimeMillis() < readyDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue(
            "the SDK never reached READY in 20s — every method below would be exercised against a " +
                "dead singleton, and `onReady` could not settle no matter how correct it is",
            ready.count == 0L,
        )

        val methodEvidence = ConcurrentHashMap<String, String>()
        val methodGaps = LinkedHashMap<String, String>()

        // Every `override fun` the module DECLARES. Read off the class, never typed out — a
        // hand-written list is a second manifest, and the 56th method would never join it.
        val declared = AppdnaModule::class.java.declaredMethods
            .filter { it.name !in notSdkMethods && !it.name.contains('$') && !it.isSynthetic }
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .sortedBy { it.name }

        for (m in declared) {
            when (val outcome = invokeAndObserve(module, m)) {
                is Outcome.Reached -> methodEvidence[m.name] = outcome.how
                is Outcome.Dead -> methodGaps[m.name] = outcome.why
            }
        }

        // Drain the paused Robolectric looper and give the off-thread coroutines (Dispatchers.Default:
        // configure, purchase, getProducts, restorePurchases) a bounded chance to settle. Without this,
        // every `launchSettling` method looks unsettled and the test would be measuring the scheduler.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (declared.filter { it.takesPromise() }.all { settled.containsKey(it.name) }) break
            Thread.sleep(20)
        }

        // 🔴 The strengthening that matters. `returned normally` is sufficient evidence for a VOID
        // method — running to completion is all it can do. For a method that takes a Promise it proves
        // almost nothing, because an EMPTY override returns normally too:
        //
        //     override fun getProducts(ids: ReadableArray, promise: Promise) { }   // ← bridged, dead
        //
        // …and that is precisely the corpse AC-3 hunts. So a promise-taking method must SETTLE (or
        // throw from inside its own body). A promise that never settles is not merely unproven, it is
        // the E6 defect in its own right: a JS `await` on it hangs for the life of the process.
        for (m in declared) {
            if (!m.takesPromise()) continue
            val how = settled[m.name]
            if (how != null) {
                methodEvidence[m.name] = "promise settled ($how)"
                methodGaps.remove(m.name)
            } else if (methodEvidence[m.name] == "returned normally") {
                methodEvidence.remove(m.name)
                methodGaps[m.name] = "took a Promise, returned, and NEVER SETTLED it — an empty body " +
                    "looks exactly like this, and a JS `await` on it hangs forever"
            }
        }

        // ── Events ──────────────────────────────────────────────────────────────

        val emitEventNamed: Method = AppdnaModule::class.java
            .getDeclaredMethod("emitEventNamed", String::class.java, com.facebook.react.bridge.WritableMap::class.java)
            .apply { isAccessible = true }

        val eventEvidence = LinkedHashMap<String, String>()
        val eventGaps = LinkedHashMap<String, String>()

        for (event in EVENT_NAMES) {
            try {
                emitEventNamed.invoke(module, event, JavaOnlyMap())
                eventEvidence[event] = "dispatched to its emitter"
            } catch (e: InvocationTargetException) {
                val cause = e.targetException
                // THE gap. The `else` branch: this event has no `when` case, so nothing native could
                // ever emit it, and every JS listener on it would wait forever.
                if (cause is IllegalStateException &&
                    cause.message?.contains("no TurboModule emitter") == true
                ) {
                    eventGaps[event] = "no `when` branch in emitEventNamed — nothing can ever emit it"
                } else {
                    // The branch WAS taken; the codegen'd emitter then needed a live JS event-emitter
                    // callback, which a unit test has no way to provide. Reaching the emitter is the
                    // thing under test — delivering to JS is the device pass's job.
                    eventEvidence[event] = "dispatched to its emitter (${cause::class.java.simpleName} beyond the bridge)"
                }
            }
        }

        writeEvidence(methodEvidence, eventEvidence)

        // The gate (`check-rn-handler-evidence.ts`) compares this evidence to the 3-way-extracted
        // manifest and is the authority on completeness. This assertion is the FAST failure: a dead
        // handler should redden the test that found it, not only a script three steps later.
        assertTrue(
            "AC-3 — ${methodGaps.size} bridged method(s) were NOT reached at runtime:\n" +
                methodGaps.entries.joinToString("\n") { "    ${it.key}: ${it.value}" },
            methodGaps.isEmpty(),
        )
        assertTrue(
            "AC-3 — ${eventGaps.size} event(s) have no emitter — a JS listener on them waits forever:\n" +
                eventGaps.entries.joinToString("\n") { "    ${it.key}: ${it.value}" },
            eventGaps.isEmpty(),
        )
        assertTrue(
            "AC-3 — no methods were exercised at all; the reflection found nothing and this pass " +
                "would have been green over an empty module",
            methodEvidence.size >= 50,
        )
    }

    // ── Invocation ──────────────────────────────────────────────────────────────

    private sealed class Outcome {
        data class Reached(val how: String) : Outcome()
        data class Dead(val why: String) : Outcome()
    }

    /** Records, per method, how its promise settled — read after the looper is idled. */
    private val settled = ConcurrentHashMap<String, String>()

    /** Does this method owe JS an answer? Then it must be seen to give one. */
    private fun Method.takesPromise(): Boolean =
        parameterTypes.any { Promise::class.java.isAssignableFrom(it) }

    private fun invokeAndObserve(module: AppdnaModule, m: Method): Outcome {
        val args = m.parameterTypes.map { type -> synthesize(type, m.name) }.toTypedArray()
        return try {
            m.invoke(module, *args)
            settled[m.name]?.let { return Outcome.Reached("promise settled ($it)") }
            // Provisional. A VOID method is done — returning normally IS its body running to
            // completion. A PROMISE method is re-judged after the settle-wait above, because at this
            // instant its coroutine has not been given a chance to run.
            Outcome.Reached("returned normally")
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            // 🔴 The corpse. A declared-but-unimplemented method — the exact thing AC-3 hunts.
            if (cause is AbstractMethodError || cause is NoSuchMethodError) {
                return Outcome.Dead("${cause::class.java.simpleName} — declared but not implemented")
            }
            val frames = cause.stackTrace.map { it.className }
            val insideWrapper = frames.any { it.startsWith("com.appdna.rn.AppdnaModule") }
            val insideSdk = frames.any { it.startsWith("ai.appdna.sdk") }
            when {
                insideSdk -> Outcome.Reached("threw from inside the SDK singleton (${cause::class.java.simpleName})")
                insideWrapper -> Outcome.Reached("threw from inside the wrapper body (${cause::class.java.simpleName})")
                // It threw, but from nowhere in our code. That is not evidence the body ran.
                else -> Outcome.Dead(
                    "threw ${cause::class.java.simpleName} with no frame in the wrapper or the SDK — " +
                        "the body may never have run",
                )
            }
        } catch (e: IllegalArgumentException) {
            Outcome.Dead("could not synthesise arguments: ${e.message}")
        }
    }

    /**
     * Plausible arguments per parameter type. A `Promise` is a recording mock — every settlement is
     * captured, which is what turns "it returned" into "its body ran to completion".
     */
    private fun synthesize(type: Class<*>, methodName: String): Any? = when {
        type == String::class.java -> "handler_pass"
        type == Boolean::class.javaPrimitiveType -> true
        type == Int::class.javaPrimitiveType -> 0
        type == Double::class.javaPrimitiveType -> 0.0
        type == ReadableMap::class.java -> JavaOnlyMap()
        type == ReadableArray::class.java -> JavaOnlyArray()
        Promise::class.java.isAssignableFrom(type) -> recordingPromise(methodName)
        else -> throw IllegalArgumentException("no synthesiser for ${type.name}")
    }

    private fun recordingPromise(methodName: String): Promise {
        val answer = Answer<Any?> { invocation ->
            val how = when (invocation.method.name) {
                "resolve" -> "resolved"
                else -> "rejected: ${invocation.arguments.firstOrNull() as? String ?: "?"}"
            }
            settled.putIfAbsent(methodName, how)
            null
        }
        return mock(Promise::class.java, answer)
    }

    // ── Evidence file ───────────────────────────────────────────────────────────

    private fun writeEvidence(methods: Map<String, String>, events: Map<String, String>) {
        val out = repoRoot()?.let { File(it, "packages/appdna-sdk-react-native/handler-pass-android.json") }
            ?: throw AssertionError(
                "Could not locate the repo root — the evidence file has nowhere to go, and " +
                    "check-rn-handler-evidence.ts will (correctly) fail for want of it",
            )
        val json = JSONObject().apply {
            put("_comment",
                "SPEC-070-B AC-3 — GENERATED by AppdnaHandlerPassTest. Runtime evidence that every " +
                    "bridged native method and event was actually REACHED, not merely declared. " +
                    "Do not hand-edit: `check-rn-handler-evidence.ts` compares it to the 3-way-" +
                    "extracted manifest, and a hand-written entry is a lie with a straight face.")
            put("platform", "android")
            put("methods", JSONObject(methods.toSortedMap() as Map<*, *>))
            put("events", JSONObject(events.toSortedMap() as Map<*, *>))
        }
        out.writeText(json.toString(2) + "\n", Charsets.UTF_8)
        println("AC-3: wrote ${methods.size} method + ${events.size} event evidence rows to ${out.path}")
    }

    private fun repoRoot(): File? {
        System.getenv("APPDNA_HANDLER_EVIDENCE_DIR")?.let { val f = File(it); if (f.isDirectory) return f }
        var here: File? = File(".").canonicalFile
        repeat(12) {
            if (File(here, "packages/sdk-shared-fixtures").isDirectory) return here
            here = here?.parentFile
        }
        val codespace = File("/workspaces/appdna-ai")
        return if (codespace.isDirectory) codespace else null
    }

    private companion object {
        /**
         * The event names are read from the SAME source the gate extracts its manifest from — the
         * `when` in `emitEventNamed` — by parsing the Kotlin. Typing them out here would make this a
         * mirror of the code it is testing, which is the fiction the whole fixture-suite post-mortem
         * was about: a test that restates the implementation asserts nothing.
         *
         * Reading the source is not a purity flourish. It means a `when` branch DELETED from the
         * module disappears from this list too — and then the gate's manifest (extracted from the TS
         * spec and the IR, not from Kotlin) still demands it, and the gap is named. The two lists have
         * different origins on purpose.
         */
        val EVENT_NAMES: List<String> by lazy {
            val src = moduleSource()
            Regex("\"(\\w+)\" -> emitOn\\w+\\(payload\\)")
                .findAll(src)
                .map { it.groupValues[1] }
                .toList()
                .also {
                    require(it.size >= 40) {
                        "only ${it.size} event branches found in AppdnaModule.kt — the parse is wrong, " +
                            "and a pass over 3 events would be a green that proves nothing"
                    }
                }
        }

        private fun moduleSource(): String {
            var here: File? = File(".").canonicalFile
            repeat(12) {
                val f = File(
                    here,
                    "packages/appdna-sdk-react-native/android/src/main/java/com/appdna/rn/AppdnaModule.kt",
                )
                if (f.isFile) return f.readText(Charsets.UTF_8)
                // Also try the module-local path, for a run whose cwd IS the android module.
                val local = File(here, "src/main/java/com/appdna/rn/AppdnaModule.kt")
                if (local.isFile) return local.readText(Charsets.UTF_8)
                here = here?.parentFile
            }
            throw AssertionError("Could not locate AppdnaModule.kt to read its event dispatch table")
        }
    }
}
