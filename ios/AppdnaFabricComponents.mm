// See AppdnaFabricComponents.h for why a host on dynamic frameworks needs this.

#import "AppdnaFabricComponents.h"

#import <React/RCTComponentViewProtocol.h>

// Defined in AppdnaScreenSlotView.mm. Codegen's provider calls the same symbol; the only difference
// is that this call site is not compiled out under RCT_DYNAMIC_FRAMEWORKS.
Class<RCTComponentViewProtocol> AppdnaScreenSlotViewCls(void);

NSDictionary<NSString *, Class> *AppdnaFabricComponents(void)
{
  // The key MUST be the codegen component name — `codegenNativeComponent<NativeProps>(…)` in
  // src/specs/AppdnaScreenSlotNativeComponent.ts — because that is the string the factory looks up.
  return @{@"AppdnaScreenSlotView" : AppdnaScreenSlotViewCls()};
}
