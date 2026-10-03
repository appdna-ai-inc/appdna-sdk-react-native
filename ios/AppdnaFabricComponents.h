// Manual Fabric registration, for hosts that link pods as DYNAMIC frameworks.
//
// React Native's codegen emits an `RCTThirdPartyFabricComponentsProvider` that maps every
// third-party component name to its view class — and wraps the whole map in `#ifndef
// RCT_DYNAMIC_FRAMEWORKS`. So under `use_frameworks!` the registration is compiled OUT, the factory
// cannot resolve `AppdnaScreenSlotView`, and React silently substitutes its placeholder:
//
//     Unimplemented component: <AppdnaScreenSlotView>
//
// Nothing throws. Nothing logs. The slot just never renders — which is how this survived a green
// build, a green pod install, and a passing test suite, and was caught only by looking at a screen.
//
// This is not a niche configuration: any app whose pod graph contains a Swift pod that needs modules
// — Firebase, for one, which the AppDNA core SDK itself depends on — ends up on dynamic frameworks.
//
// Hosts on dynamic frameworks must therefore hand the class to React themselves. `RCTAppDelegate`
// already exposes the hook; override it and merge this map in:
//
//     #import <appdna_sdk_react_native/AppdnaFabricComponents.h>
//
//     - (NSDictionary<NSString *, Class<RCTComponentViewProtocol>> *)thirdPartyFabricComponents
//     {
//       NSMutableDictionary *components = [[super thirdPartyFabricComponents] mutableCopy];
//       [components addEntriesFromDictionary:AppdnaFabricComponents()];
//       return components;
//     }
//
// Hosts on static libraries do not need this — codegen's provider already covers them — and calling
// it anyway is harmless: registering the same class twice is idempotent.

// Deliberately imports Foundation and NOTHING else. This header is public, so it lands in the pod's
// umbrella header and its module map. Importing <React/RCTComponentViewProtocol.h> here would pull
// Fabric's C++ headers into a module that Clang also builds in plain ObjC mode, and the whole module
// then fails to build with `'tuple' file not found` — an error that names none of this. The value
// type is plain `Class`; `addEntriesFromDictionary:` does not care, and the .mm still returns the
// properly-typed classes.
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Every Fabric component this SDK ships, keyed by the name codegen registered it under.
NSDictionary<NSString *, Class> *AppdnaFabricComponents(void);

NS_ASSUME_NONNULL_END
