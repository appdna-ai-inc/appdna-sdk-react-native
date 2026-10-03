// The iOS Fabric host view for a server-driven AppDNA screen slot.
//
// A Fabric component view MUST be an ObjC++ `RCTViewComponentView`: that is where the C++
// ComponentDescriptor, the generated props struct, and the event emitter live. The thing embedded is
// `AppDNAScreenSlot`, a SwiftUI view ObjC++ cannot name, so this shell owns an
// `AppdnaScreenSlotHostView` (Swift) and forwards the `name` prop into it and its size callback out
// through the codegen'd `onContentSizeChange` event. Same ObjC++/Swift split, and for the same
// reason, as the TurboModule adapter.

#import <React/RCTViewComponentView.h>
#import <React/RCTConversions.h>

#import <react/renderer/components/RNAppdnaSpec/ComponentDescriptors.h>
#import <react/renderer/components/RNAppdnaSpec/EventEmitters.h>
#import <react/renderer/components/RNAppdnaSpec/Props.h>
#import <react/renderer/components/RNAppdnaSpec/RCTComponentViewHelpers.h>

// RCTBridgeModule.h MUST precede the Swift umbrella header. That header re-declares EVERY `@objc`
// method in the pod — including `AppdnaModuleImpl`'s `configure:…resolve:reject:`, which name
// `RCTPromiseResolveBlock`/`RCTPromiseRejectBlock`. Those typedefs live in RCTBridgeModule.h; without
// it first, this translation unit hits "expected a type" on 20 method signatures it never calls. The
// TurboModule adapter (AppdnaModule.mm) gets them transitively via the generated spec header, which
// is why only this Fabric TU needed the explicit import.
#import <React/RCTBridgeModule.h>

// The generated Swift header — exposes `AppdnaScreenSlotHostView`. Same module-name rewrite
// (`-` → `_`) + `DEFINES_MODULE = YES` as the TurboModule adapter relies on.
#import <appdna_sdk_react_native/appdna_sdk_react_native-Swift.h>

using namespace facebook::react;

@interface AppdnaScreenSlotView : RCTViewComponentView <RCTAppdnaScreenSlotViewViewProtocol>
@end

@implementation AppdnaScreenSlotView {
  AppdnaScreenSlotHostView *_host;
}

+ (ComponentDescriptorProvider)componentDescriptorProvider
{
  return concreteComponentDescriptorProvider<AppdnaScreenSlotViewComponentDescriptor>();
}

- (instancetype)initWithFrame:(CGRect)frame
{
  if (self = [super initWithFrame:frame]) {
    static const auto defaultProps = std::make_shared<const AppdnaScreenSlotViewProps>();
    _props = defaultProps;

    _host = [AppdnaScreenSlotHostView new];
    __weak __typeof(self) weakSelf = self;
    _host.onContentSize = ^(CGFloat width, CGFloat height) {
      [weakSelf emitContentSizeWidth:width height:height];
    };
    self.contentView = _host;
  }
  return self;
}

- (void)updateProps:(const Props::Shared &)props oldProps:(const Props::Shared &)oldProps
{
  const auto &newProps = *std::static_pointer_cast<const AppdnaScreenSlotViewProps>(props);
  [_host setSlotName:RCTNSStringFromString(newProps.name)];
  [super updateProps:props oldProps:oldProps];
}

- (void)emitContentSizeWidth:(CGFloat)width height:(CGFloat)height
{
  if (!_eventEmitter) {
    return;
  }
  auto emitter = std::static_pointer_cast<const AppdnaScreenSlotViewEventEmitter>(_eventEmitter);
  emitter->onContentSizeChange(
      AppdnaScreenSlotViewEventEmitter::OnContentSizeChange{
          .width = static_cast<double>(width),
          .height = static_cast<double>(height),
      });
}

// Fabric recycles component views. Tear down the SwiftUI host so a recycled slot does not leak its
// UIHostingController (E6/E11 on the view side).
- (void)prepareForRecycle
{
  [_host prepareForReuse];
  [super prepareForRecycle];
}

@end

// The New-Architecture component registry looks this symbol up by the component name to obtain the
// backing view class.
Class<RCTComponentViewProtocol> AppdnaScreenSlotViewCls(void)
{
  return AppdnaScreenSlotView.class;
}
