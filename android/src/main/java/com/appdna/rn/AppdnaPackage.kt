package com.appdna.rn

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager

/**
 * A New-Architecture package.
 *
 * `ReactPackage.createNativeModules` eagerly constructs every module at startup and cannot expose a
 * TurboModule. `BaseReactPackage` resolves modules LAZILY by name, and `isTurboModule = true` is
 * what makes the runtime look for the codegen'd JSI binding rather than silently falling back to the
 * legacy bridge — which is where "it builds, but `AppdnaModule` is undefined" comes from.
 *
 * P4 adds the `AppdnaScreenSlotView` Fabric component, registered as a ViewManager.
 */
class AppdnaPackage : BaseReactPackage() {

    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        if (name == AppdnaModule.NAME) AppdnaModule(reactContext) else null

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider = ReactModuleInfoProvider {
        mapOf(
            AppdnaModule.NAME to ReactModuleInfo(
                AppdnaModule.NAME, // name
                AppdnaModule.NAME, // className
                false, // canOverrideExistingModule
                false, // needsEagerInit
                false, // isCxxModule
                true, // isTurboModule
            ),
        )
    }

    /**
     * Fabric resolves view managers through this list. The ScreenSlot is a component, not a
     * TurboModule, so it rides here rather than in `getModule`.
     */
    override fun createViewManagers(
        reactContext: ReactApplicationContext,
    ): List<ViewManager<*, *>> = listOf(AppdnaScreenSlotViewManager())
}
