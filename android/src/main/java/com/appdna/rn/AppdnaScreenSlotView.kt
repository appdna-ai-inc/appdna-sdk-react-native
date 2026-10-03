package com.appdna.rn

import ai.appdna.sdk.screens.AppDNAScreenSlot
import android.content.Context
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.PixelUtil
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import com.facebook.react.uimanager.events.EventDispatcher

/**
 * The Android host view for a server-driven AppDNA screen slot.
 *
 * A `FrameLayout` wrapping a `ComposeView` that renders the native `@Composable AppDNAScreenSlot`.
 * Height is not measured by Yoga (a Fabric host view has no intrinsic size without a C++ shadow
 * node — an ADR-001 violation), so this view measures its own content and reports it to JS through
 * `onContentSizeChange`; the `AppDNAScreenSlot` facade applies the height.
 *
 * ## The ViewTreeLifecycleOwner crash (reused from Flutter 070-C)
 *
 * `ComposeView.setContent {}` throws *"ViewTreeLifecycleOwner not found"* unless the view tree has a
 * `LifecycleOwner` + `SavedStateRegistryOwner` + `ViewModelStoreOwner`. A React Native host activity
 * is not guaranteed to provide them where this view is attached, so a plugin-owned owner is driven
 * to RESUMED and wired onto the ComposeView BEFORE `setContent`. Identical shim to
 * `AppDNAScreenSlotViewFactory.kt` in the Flutter plugin.
 */
class AppdnaScreenSlotView internal constructor(
    context: Context,
    /**
     * Test seam. `null` (the production path, and the only public constructor) builds the real
     * `ComposeView`. A unit test injects a `View` with a known intrinsic height instead, so the
     * measurement contract below can be asserted without standing up a Compose host.
     */
    injectedContent: View?,
) : FrameLayout(context) {

    constructor(context: Context) : this(context, null)

    private val lifecycleOwner = SlotViewTreeOwner()
    private var slotName: String = ""
    private var lastReportedWidth = -1
    private var lastReportedHeight = -1

    private val composeView: ComposeView? =
        if (injectedContent != null) {
            null
        } else {
            ComposeView(context).apply {
                lifecycleOwner.start()
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            }
        }

    /** The single child whose CONTENT height is measured and reported. */
    private val contentView: View = composeView ?: injectedContent!!

    /**
     * Test seam for [emitContentSize]. When set, the measured size (in raw PIXELS) is handed here
     * instead of being dispatched through the Fabric `EventDispatcher` — which needs a `ReactContext`
     * and an initialised `DisplayMetricsHolder` that a unit test does not have.
     */
    internal var contentSizeReporter: ((widthPx: Int, heightPx: Int) -> Unit)? = null

    init {
        addView(contentView)
        renderSlot()
    }

    /** Set (or change) the slot name and re-render. Called by the ViewManager on the `name` prop. */
    fun setSlotName(name: String) {
        if (name == slotName) return
        slotName = name
        renderSlot()
    }

    private fun renderSlot() {
        composeView?.setContent {
            AppDNAScreenSlot(name = slotName)
        }
    }

    /**
     * Report the CONTENT's height to JS. Fabric will not lay this view out to its content, so the
     * facade sizes the Yoga node from what we send here. Deduped so a stable content does not spam the
     * bridge every frame.
     *
     * ## Why this must MEASURE and not read `contentView.height`
     *
     * It used to report `composeView.width/height` — the child's LAID-OUT size. That made the slot a
     * height fixed-point and it was permanently blank:
     *
     *  - `contentView` is a MATCH_PARENT child of this FrameLayout;
     *  - Fabric lays THIS view out to exactly the height Yoga computed, and Yoga computes it from the
     *    JS `height` style — which `AppDNAScreenSlot.tsx` sets to `measuredHeight ?? minHeight`, i.e.
     *    to whatever we last reported;
     *  - so `contentView.height == this.height == the height we last emitted`.
     *
     * Steady state: emit 0 → JS sets 0 → next layout measures 0 → emit 0, forever. With
     * `minHeight={120}` it reported 120 regardless of the content. The content's own size never
     * entered the loop.
     *
     * So the content is measured UNCONSTRAINED in the vertical axis, at the width actually available —
     * the same contract as iOS, which fits its SwiftUI content with `systemLayoutSizeFitting` at
     * `.required` horizontal / `.fittingSizeLevel` vertical priority
     * (`ios/AppdnaScreenSlotHostView.swift`).
     */
    /**
     * 🔴 MEASURING IN `onLayout` ALONE IS NOT ENOUGH — NOTHING EVER CALLS IT AGAIN.
     *
     * This view's `onLayout` is invoked by its PARENT, and its parent is a React view:
     *
     *     // ReactViewGroup.java:217
     *     protected void onLayout(boolean changed, int l, int t, int r, int b) {
     *         // No-op since UIManagerModule handles actually laying out children.
     *     }
     *
     * So when Compose finishes composing the real content and calls `requestLayout()`, the traversal
     * runs, the React parent lays out NOTHING, and our `onLayout` never fires. It re-fires only when
     * Fabric mounts new layout metrics — i.e. only in response to a height WE emitted.
     *
     * The consequence, on the normal path: `AppDNAScreenSlot` loads its config asynchronously, so the
     * first (and only) measure sees the loading shimmer — a fixed 100dp. We emit 100, JS applies 100,
     * we dedupe, and we go quiet. The real 340dp content then composes and we never hear about it. The
     * slot displays a 100dp window onto the screen, forever. Same for every later content change: an
     * image finishing, a server-driven expand, content shrinking.
     *
     * `requestLayout()` is the re-entry point Compose actually calls, so that is where we hook. Posting
     * our own measure+layout pass is the standard RN escape hatch for a self-measuring child.
     */
    /**
     * The re-entry point, and it took three attempts to find one that actually fires.
     *
     * ① `onLayout` alone is not enough: this view's `onLayout` is called by its PARENT, and the parent
     *    is a React view — `ReactViewGroup.onLayout` is literally `// No-op since UIManagerModule
     *    handles actually laying out children.` So when Compose composes the real content, our
     *    `onLayout` never runs. The slot measures the loading shimmer once and freezes there.
     * ② Overriding `requestLayout()` does not fire either: `View.requestLayout()` propagates to the
     *    parent only `if (!mParent.isLayoutRequested())`, and during a pending traversal it already is
     *    — so the call is swallowed before it reaches us.
     * ③ A pre-draw listener always runs. Compose cannot change what is on screen without a draw, so
     *    this cannot be missed. The dedupe below makes it nearly free: an unchanged content is a
     *    measure-cache hit and reports nothing.
     */
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        onContentMayHaveResized()
        true
    }

    /**
     * Exactly what the pre-draw listener calls — not a parallel path. A test drives THIS, and asserts
     * separately that attaching registers the listener, because Robolectric will not run a real draw
     * pass. The composition of the two is what the device e2e proves.
     */
    internal fun onContentMayHaveResized() = measureAndReportContent(width)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        measureAndReportContent(availableWidth = right - left, laidOutHeight = bottom - top)
    }

    /** Measure the content unconstrained at [availableWidth] and report it if it changed. */
    private fun measureAndReportContent(availableWidth: Int, laidOutHeight: Int = height) {
        // Not laid out yet: an UNSPECIFIED-width probe would measure content against infinity and
        // report a height for a line length that will never exist on screen.
        if (availableWidth <= 0) return

        contentView.measure(
            MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        // `View.measure` is backed by a spec-keyed measure cache, so this probe costs a real measure
        // pass only when the content has actually invalidated itself (`requestLayout()` clears that
        // cache) — i.e. exactly when its height may have changed. An unchanged content is a cache hit.
        val contentHeight = contentView.measuredHeight

        // Restore the child's measured state to the bounds `super.onLayout` actually laid it out to.
        // The probe above is a query, not a resize; leaving it as the child's measured size would
        // publish the unconstrained height to anything that reads it before the next measure pass.
        contentView.measure(
            MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(laidOutHeight, MeasureSpec.EXACTLY),
        )

        // Compose measures asynchronously: the first layout after `setContent` can land before the
        // first composition and measure 0. Emitting that 0 would collapse the slot to zero height for
        // a frame — the exact layout shift `minHeight` (W19) exists to prevent — so the pre-content 0
        // is swallowed and JS keeps its reserved height. Once a real height HAS been reported, a later
        // 0 is a genuine collapse (the slot published nothing) and is forwarded.
        if (contentHeight <= 0 && lastReportedHeight <= 0) return

        // Dedupe. Also what breaks the measure→emit→relayout cycle: JS applies the height we send,
        // this view is laid out again, the content still measures the same, and we go quiet.
        if (availableWidth == lastReportedWidth && contentHeight == lastReportedHeight) return
        lastReportedWidth = availableWidth
        lastReportedHeight = contentHeight
        emitContentSize(availableWidth, contentHeight)
    }

    private fun emitContentSize(widthPx: Int, heightPx: Int) {
        contentSizeReporter?.let {
            it(widthPx, heightPx)
            return
        }
        val reactContext = context as? ReactContext ?: return
        val dispatcher: EventDispatcher =
            UIManagerHelper.getEventDispatcherForReactTag(reactContext, id) ?: return
        val surfaceId = UIManagerHelper.getSurfaceId(reactContext)
        dispatcher.dispatchEvent(
            OnContentSizeChangeEvent(
                surfaceId,
                id,
                // JS works in dp/points, not raw pixels — convert so the facade's `height` style is
                // in the same unit the layout system expects.
                PixelUtil.toDIPFromPixel(widthPx.toFloat()).toDouble(),
                PixelUtil.toDIPFromPixel(heightPx.toFloat()).toDouble(),
            ),
        )
    }

    /** Tear down Compose + the backing owner when the view is detached for good. */
    fun onDropView() {
        composeView?.disposeComposition()
        lifecycleOwner.destroy()
    }

    private class OnContentSizeChangeEvent(
        surfaceId: Int,
        viewId: Int,
        private val width: Double,
        private val height: Double,
    ) : Event<OnContentSizeChangeEvent>(surfaceId, viewId) {
        override fun getEventName() = "onContentSizeChange"
        override fun getEventData(): WritableMap =
            com.facebook.react.bridge.Arguments.createMap().apply {
                putDouble("width", width)
                putDouble("height", height)
            }
    }
}

/**
 * Minimal `LifecycleOwner` + `ViewModelStoreOwner` + `SavedStateRegistryOwner` backing a
 * `ComposeView` hosted outside a `ComponentActivity`. Driven to RESUMED so Compose composes and runs
 * effects; torn down to DESTROYED on dispose. Mirrors the Flutter plugin's `SlotViewTreeOwner`.
 */
private class SlotViewTreeOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        // `LifecycleRegistry` REFUSES an INITIALIZED → DESTROYED transition — it throws
        // "State must be at least CREATED to move to DESTROYED". Today the production path always
        // calls [start] first (it happens inside the ComposeView branch of the slot's `init`), so an
        // unguarded transition survives by coincidence rather than by design: any path that builds the
        // owner without starting it — the injected-content test seam does exactly that — crashes here,
        // and it crashes inside `onDropViewInstance`, a ViewManager callback on the UI thread.
        //
        // `destroy()` has no business caring whether anyone started it. Found by the earlier mount/dispose
        // memory test, which drives the real `onDropView()` the ViewManager drives.
        if (lifecycleRegistry.currentState != Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        store.clear()
    }
}
