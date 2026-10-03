package com.appdna.rn

import android.content.Context
import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The height contract of [AppdnaScreenSlotView].
 *
 * There was no Android test for the slot, and that is precisely why it shipped as a HEIGHT FIXED
 * POINT. `AppDNAScreenSlot.tsx` drives the host's height from JS
 * (`style={{ height: measuredHeight ?? minHeight }}`) using what native reports, so native reporting
 * its own laid-out height reported the number JS had just given it — a closed loop that the content's
 * real size never entered. The slot rendered at `minHeight` (default 0: permanently blank) forever.
 *
 * These tests assert what native must report is the CONTENT's height, measured unconstrained at the
 * available width — the same contract iOS gets from `systemLayoutSizeFitting(.fittingSizeLevel)`.
 *
 * ## Falsification
 *
 * Restore the old body of `onLayout` —
 *
 * ```kotlin
 * val contentHeight = composeView.height   // the LAID-OUT height, i.e. the host's
 * ```
 *
 * — and [reportsContentHeightNotHostHeight] and [reportsContentHeightNotMinHeightWhenContentIsTaller]
 * both fail with `expected:<300> but was:<40>` / `<120>`: the old code echoes the host height back.
 */
@RunWith(RobolectricTestRunner::class)
class AppdnaScreenSlotViewTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /**
     * Stands in for the ComposeView. Behaves exactly as any real content view does:
     *
     *  - under an EXACTLY height spec it takes the height it is GIVEN — this is what a MATCH_PARENT
     *    child of the Fabric host does, and it is what made the old implementation echo the host's
     *    height straight back to JS;
     *  - under UNSPECIFIED it reports its own INTRINSIC height;
     *  - and changing that intrinsic height invalidates it. `View.measure` is backed by a measure
     *    cache keyed on the spec pair, and `requestLayout()` is what clears it — so content that
     *    resized without calling it would be measured from the stale cache. Every real view
     *    (ComposeView included) requests layout when its content changes; a fake that did not would
     *    be testing a situation Android cannot produce.
     */
    private class FakeContentView(
        context: Context,
        intrinsicHeightPx: Int,
    ) : View(context) {
        var intrinsicHeightPx: Int = intrinsicHeightPx
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> intrinsicHeightPx
                MeasureSpec.AT_MOST -> minOf(intrinsicHeightPx, MeasureSpec.getSize(heightMeasureSpec))
                else -> MeasureSpec.getSize(heightMeasureSpec) // EXACTLY — content ignored
            }
            setMeasuredDimension(width, height)
        }
    }

    /** Drives one Fabric layout pass: the host is sized to exactly what JS asked for. */
    private fun layoutHost(view: AppdnaScreenSlotView, widthPx: Int, hostHeightPx: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hostHeightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, hostHeightPx)
    }

    private fun slot(
        contentHeightPx: Int,
        reported: MutableList<Pair<Int, Int>>,
    ): Pair<AppdnaScreenSlotView, FakeContentView> {
        val content = FakeContentView(context, contentHeightPx)
        val view = AppdnaScreenSlotView(context, content)
        content.layoutParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        view.contentSizeReporter = { w, h -> reported.add(w to h) }
        return view to content
    }

    @Test
    fun reportsContentHeightNotHostHeight() {
        val reported = mutableListOf<Pair<Int, Int>>()
        val (view, _) = slot(contentHeightPx = 300, reported = reported)

        // JS has not measured yet, so the host is laid out to `minHeight` — here 40px.
        layoutHost(view, widthPx = 1080, hostHeightPx = 40)

        assertEquals(listOf(1080 to 300), reported)
    }

    @Test
    fun reportsContentHeightNotMinHeightWhenContentIsTaller() {
        val reported = mutableListOf<Pair<Int, Int>>()
        // `minHeight={120}` — the old code reported 120 forever, whatever the content was.
        val (view, _) = slot(contentHeightPx = 640, reported = reported)

        layoutHost(view, widthPx = 1080, hostHeightPx = 120)

        val height = reported.single().second
        assertEquals(640, height)
        assertTrue("must not echo minHeight back to JS", height != 120)
    }

    /**
     * The fixed point in reverse: JS applies the height we reported, which re-lays this view out. The
     * content still measures the same, so we must go QUIET — otherwise every emit triggers a layout
     * that triggers an emit, forever.
     */
    @Test
    fun doesNotReEmitOnceJsAppliesTheReportedHeight() {
        val reported = mutableListOf<Pair<Int, Int>>()
        val (view, _) = slot(contentHeightPx = 300, reported = reported)

        layoutHost(view, widthPx = 1080, hostHeightPx = 0) // first frame: minHeight default 0
        assertEquals(listOf(1080 to 300), reported)

        // JS sets height: 300 → Fabric lays the host out to 300 → onLayout again.
        layoutHost(view, widthPx = 1080, hostHeightPx = 300)
        layoutHost(view, widthPx = 1080, hostHeightPx = 300)

        assertEquals("a stable content must emit exactly once", 1, reported.size)
    }

    /** Content that grows (an image loads, a step advances) must be re-reported. */
    @Test
    fun reEmitsWhenContentHeightChanges() {
        val reported = mutableListOf<Pair<Int, Int>>()
        val (view, content) = slot(contentHeightPx = 300, reported = reported)

        layoutHost(view, widthPx = 1080, hostHeightPx = 0)
        content.intrinsicHeightPx = 500
        layoutHost(view, widthPx = 1080, hostHeightPx = 300)

        assertEquals(listOf(1080 to 300, 1080 to 500), reported)
    }

    /**
     * Compose measures asynchronously: the layout right after `setContent` can land before the first
     * composition and measure 0. Emitting it would collapse the slot to zero height for a frame —
     * the layout shift `minHeight` (W19) exists to prevent.
     */
    @Test
    fun swallowsThePreCompositionZeroSoTheReservedHeightSurvives() {
        val reported = mutableListOf<Pair<Int, Int>>()
        val (view, content) = slot(contentHeightPx = 0, reported = reported)

        layoutHost(view, widthPx = 1080, hostHeightPx = 120) // minHeight reserved
        assertTrue("must not emit the pre-content 0", reported.isEmpty())

        // Composition lands.
        content.intrinsicHeightPx = 300
        layoutHost(view, widthPx = 1080, hostHeightPx = 120)
        assertEquals(listOf(1080 to 300), reported)

        // A LATER 0 is a genuine collapse (nothing published for this slot) and must be forwarded.
        content.intrinsicHeightPx = 0
        layoutHost(view, widthPx = 1080, hostHeightPx = 300)
        assertEquals(listOf(1080 to 300, 1080 to 0), reported)
    }

    /** A zero-width host is not laid out yet; probing content against it reports a meaningless height. */
    @Test
    fun doesNotMeasureBeforeTheHostHasAWidth() {
        val reported = mutableListOf<Pair<Int, Int>>()
        val (view, _) = slot(contentHeightPx = 300, reported = reported)

        layoutHost(view, widthPx = 0, hostHeightPx = 0)

        assertTrue(reported.isEmpty())
    }

    /**
     * 🔴 THE ONE THE OTHER TESTS COULD NOT CATCH.
     *
     * Every test above calls `layoutHost(...)` — i.e. it invokes `onLayout` by hand. In a REAL app
     * nothing does that. This view's `onLayout` is called by its parent, and its parent is a React
     * view whose `onLayout` is `// No-op since UIManagerModule handles actually laying out children.`
     * (ReactViewGroup.java:217). So when Compose finishes composing the real content and calls
     * `requestLayout()`, our `onLayout` NEVER RUNS.
     *
     * The normal path: AppDNAScreenSlot loads its config asynchronously, so the first measure sees the
     * loading shimmer (a fixed 100dp). We emit 100, JS applies 100, we dedupe, we go quiet — and the
     * real 340dp content composes into a 100dp window, forever.
     *
     * So this test never calls `onLayout` after the first pass. It changes the content's height and
     * calls `requestLayout()`, exactly as Compose does, and requires a fresh report.
     */
    @Test
    fun reMeasuresWhenComposeInvalidatesItself_withNoFurtherLayoutFromTheParent() {
        val reported = mutableListOf<Pair<Int, Int>>()
        val (view, content) = slot(contentHeightPx = 100, reported = reported)

        // First and ONLY parent-driven layout: the async config has not arrived, so this is the shimmer.
        layoutHost(view, widthPx = 1080, hostHeightPx = 0)
        assertEquals(listOf(1080 to 100), reported)

        // The config arrives; Compose composes the real content and invalidates itself. The React
        // parent will not lay us out again — the view must re-enter on its own.
        // The setter calls requestLayout(), exactly as Compose does. Compose cannot change what is on
        // screen without a draw, so the pre-draw pass is what must notice.
        content.intrinsicHeightPx = 340

        // What the registered pre-draw listener calls.
        view.onContentMayHaveResized()

        assertEquals(
            "the slot froze at the shimmer height — a 100px window onto 340px of content",
            listOf(1080 to 100, 1080 to 340),
            reported,
        )
    }
}
