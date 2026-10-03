import type { HostComponent, ViewProps } from 'react-native';
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';
import type { DirectEventHandler, Double } from 'react-native/Libraries/Types/CodegenTypes';

/**
 * The Fabric host view for a server-driven AppDNA screen slot.
 *
 * ## Why a native component, and why height is a problem
 *
 * The native SDKs render a slot with SwiftUI (`AppDNAScreenSlot`) / Compose (`@Composable
 * AppDNAScreenSlot`). React Native cannot re-implement that (ADR-001), so it embeds the native view
 * through Fabric. But a Fabric host view **does not measure its native content**: Yoga sizes it, and
 * without a C++ `YGMeasureFunc` shadow node the view has no intrinsic height. Writing that shadow
 * node would be exactly the kind of C++/business logic ADR-001 forbids in a wrapper.
 *
 * So height is driven from JS instead: the native view measures its own content and fires
 * `onContentSizeChange`, and the `AppDNAScreenSlot` facade applies the reported height. Two costs
 * are inherent to this and are stated here so they are not rediscovered on a device:
 *
 *   1. **Zero height on the first frame.** The event has not fired yet, so the slot occupies no space
 *      until the first measure. The facade reserves a caller-supplied `minHeight` to avoid a
 *      collapse-then-expand flash (W19).
 *   2. **A one-frame-late re-layout on every size change.** The native content changes size, then the
 *      event crosses to JS, then Yoga re-lays-out — always one frame behind the native change.
 */
export interface NativeProps extends ViewProps {
  /** The console slot name to render. Matches the Flutter `AppDNAScreenSlot(name:)` argument. */
  name: string;

  /**
   * Fired when the hosted native content measures a new size. `width`/`height` are in points
   * (iOS) / dp (Android). The facade reads `height` to size the Yoga node.
   */
  onContentSizeChange?: DirectEventHandler<Readonly<{ width: Double; height: Double }>>;
}

/**
 * The Fabric component. The name here — `AppdnaScreenSlotView` — is the identifier the native
 * `RCTViewComponentView` (iOS) and `ViewManager` (Android) must register under; codegen generates the
 * C++ `ComponentDescriptor` and the props struct from this file, and `check:rn-facade-parity` has no
 * say over it because it is codegen, not hand-written glue.
 */
export default codegenNativeComponent<NativeProps>('AppdnaScreenSlotView') as HostComponent<NativeProps>;
