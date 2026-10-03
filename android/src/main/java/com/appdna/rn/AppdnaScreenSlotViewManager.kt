package com.appdna.rn

import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.viewmanagers.AppdnaScreenSlotViewManagerDelegate
import com.facebook.react.viewmanagers.AppdnaScreenSlotViewManagerInterface

/**
 * The Fabric ViewManager for [AppdnaScreenSlotView].
 *
 * Implements the CODEGEN'D `AppdnaScreenSlotViewManagerInterface` (generated from
 * `src/specs/AppdnaScreenSlotNativeComponent.ts`) and forwards prop setters through the codegen'd
 * delegate, so a prop that exists in the TS spec but not here — or vice versa — is a COMPILE error,
 * exactly as the TurboModule half is kept honest by `NativeAppdnaModuleSpec`.
 *
 * `onContentSizeChange` is a `DirectEventHandler`, so it is registered here as a direct event; the
 * view dispatches it through the Fabric `EventDispatcher`.
 */
@ReactModule(name = AppdnaScreenSlotViewManager.NAME)
class AppdnaScreenSlotViewManager :
    SimpleViewManager<AppdnaScreenSlotView>(),
    AppdnaScreenSlotViewManagerInterface<AppdnaScreenSlotView> {

    private val delegate = AppdnaScreenSlotViewManagerDelegate(this)

    override fun getDelegate(): ViewManagerDelegate<AppdnaScreenSlotView> = delegate

    override fun getName(): String = NAME

    override fun createViewInstance(context: ThemedReactContext): AppdnaScreenSlotView =
        AppdnaScreenSlotView(context)

    override fun setName(view: AppdnaScreenSlotView, value: String?) {
        view.setSlotName(value ?: "")
    }

    override fun onDropViewInstance(view: AppdnaScreenSlotView) {
        super.onDropViewInstance(view)
        // Tear down Compose + the backing lifecycle owner, or every slot mount/unmount leaks one
        // owner and its ViewModelStore for the app session.
        view.onDropView()
    }

    /** `onContentSizeChange` is a direct event; register its JS handler name. */
    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> =
        mutableMapOf(
            "onContentSizeChange" to mapOf("registrationName" to "onContentSizeChange"),
        )

    companion object {
        // Must equal the name passed to `codegenNativeComponent(...)` in the TS spec.
        const val NAME = "AppdnaScreenSlotView"
    }
}
