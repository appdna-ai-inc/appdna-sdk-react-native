#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * The seam that lets Swift emit a TurboModule event.
 *
 * Codegen puts the `emitOnX:` methods on `NativeAppdnaModuleSpecBase`, an ObjC class with a C++
 * `facebook::react::EventEmitterCallback` ivar. Swift cannot subclass it — the header pulls in
 * `<vector>` and ReactCommon. So the Swift implementation never touches the emitters directly: it
 * calls this protocol, and the ObjC++ adapter (`AppdnaModule.mm`) maps a name to the right
 * `emitOnX:`.
 *
 * A name with no matching emitter is a programming error, not a runtime condition — the adapter
 * asserts rather than dropping the event silently, because a silently dropped event is exactly the
 * bug class this whole phase exists to kill.
 */
@protocol AppdnaEventSink <NSObject>
- (void)emitEventNamed:(NSString *)name payload:(NSDictionary *)payload;
@end

NS_ASSUME_NONNULL_END
