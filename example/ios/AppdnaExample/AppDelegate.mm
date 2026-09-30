#import "AppDelegate.h"

#import <React/RCTBundleURLProvider.h>
#import <appdna_sdk_react_native/AppdnaFabricComponents.h>

@implementation AppDelegate

// Required because this app links pods as DYNAMIC frameworks (see the Podfile — Firebase forces it).
// Codegen's `RCTThirdPartyFabricComponentsProvider` wraps its whole component map in
// `#ifndef RCT_DYNAMIC_FRAMEWORKS`, so under `use_frameworks!` nothing registers AppdnaScreenSlotView
// and React silently renders "Unimplemented component: <AppdnaScreenSlotView>" — no throw, no log.
- (NSDictionary<NSString *, Class<RCTComponentViewProtocol>> *)thirdPartyFabricComponents
{
  NSMutableDictionary *components = [[super thirdPartyFabricComponents] mutableCopy];
  [components addEntriesFromDictionary:AppdnaFabricComponents()];
  return components;
}

- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions
{
  self.moduleName = @"AppdnaExample";

  // The SDK key arrives as a LAUNCH ARGUMENT and is handed to JS as an initial prop. It is never
  // written to this repo, to the bundle, or to disk on the device — the example is force-pushed to a
  // public mirror, so a key committed here is a key published. Launch it with:
  //
  //     xcrun simctl launch <device> <bundle-id> -appdnaApiKey adn_test_…
  //
  // `-key value` pairs land in NSUserDefaults' argument domain, which lives only in this process.
  // The content ids travel the same way, for the same reason: they identify a real customer app, and
  // this example is public. A device pass that only proves "the SDK configured" is not a device pass
  // — you have to render an actual onboarding flow, paywall and survey — so the ids have to come from
  // somewhere, and that somewhere is not this repository.
  NSMutableDictionary *props = [NSMutableDictionary new];
  NSDictionary<NSString *, NSString *> *launchKeys = @{
    @"appdnaApiKey" : @"apiKey",
    @"appdnaOnboardingId" : @"onboardingId",
    @"appdnaPaywallId" : @"paywallId",
    @"appdnaPaywall2Id" : @"paywall2Id",
    @"appdnaSurveyId" : @"surveyId",
    @"appdnaMessageEvent" : @"messageEvent",
    // The rest of the surface the example now drives: billing, screens, the inline slot, experiments
    // and paywall placements. Same reason as above — these name real console content, and this
    // repository is public.
    @"appdnaProductId" : @"productId",
    @"appdnaScreenId" : @"screenId",
    @"appdnaScreenFlowId" : @"screenFlowId",
    @"appdnaSlotName" : @"slotName",
    @"appdnaExperimentId" : @"experimentId",
    @"appdnaExperimentVariantId" : @"experimentVariantId",
    @"appdnaPlacement" : @"placement",
    // SPEC-496 device pass: `items` | `empty` — sample host data for onBeforeStepRender (App.tsx).
    @"appdnaHostDataDemo" : @"hostDataDemo",
    // SPEC-497 §4.10 — the sign-in timeout floor device rows (App.tsx).
    @"appdnaSignInDelaySeconds" : @"signInDelaySeconds",
    @"appdnaVetoTimeout" : @"vetoTimeout",
    @"appdnaStepAdvanceDelaySeconds" : @"stepAdvanceDelaySeconds",
    @"appdnaStepAdvanceReply" : @"stepAdvanceReply",
    // SPEC-497 §3.11 / §13h — billing provider, the host-buy product, the location flow, hostWait's URL.
    @"appdnaBillingProvider" : @"billingProvider",
    @"appdnaHostProductId" : @"hostProductId",
    @"appdnaLocationFlowId" : @"locationFlowId",
    @"APPDNA_E2E_LOCATION_FLOW_ID" : @"locationFlowId",
    @"appdnaPermissionsFlowId" : @"permissionsFlowId",
    @"APPDNA_E2E_PERMISSIONS_FLOW_ID" : @"permissionsFlowId",
    @"appdnaWaitUrl" : @"waitUrl",
  };
  for (NSString *arg in launchKeys) {
    NSString *value = [[NSUserDefaults standardUserDefaults] stringForKey:arg];
    if (value.length > 0) {
      props[launchKeys[arg]] = value;
    }
  }
  // SPEC-497 §3.11 — `appdnaEnv=sandbox` exactly when this build carries the test-only base-URL
  // override (Info.plist `AppDNABaseURLOverride` ← `$(APPDNA_BASE_URL_OVERRIDE)` from the uncommitted
  // Local.xcconfig). Emitted even with no launch arguments.
  NSString *override = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"AppDNABaseURLOverride"];
  if ([override isKindOfClass:[NSString class]] &&
      [override stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]].length > 0) {
    props[@"appdnaEnv"] = @"sandbox";
  }
  self.initialProps = props;

  return [super application:application didFinishLaunchingWithOptions:launchOptions];
}

- (NSURL *)sourceURLForBridge:(RCTBridge *)bridge
{
  return [self bundleURL];
}

- (NSURL *)bundleURL
{
#if DEBUG
  // A Debug build normally streams JS from Metro. An automated device pass has no Metro — and on a
  // machine whose default Node is too new for the RN CLI, it cannot have one. So if a JS bundle was
  // dropped into the app, prefer it: the run is then hermetic, which is what you want from a test
  // anyway. A normal `npm start` workflow never has this file, so Metro stays the default.
  NSURL *packaged = [[NSBundle mainBundle] URLForResource:@"main" withExtension:@"jsbundle"];
  if (packaged) {
    return packaged;
  }
  return [[RCTBundleURLProvider sharedSettings] jsBundleURLForBundleRoot:@"index"];
#else
  return [[NSBundle mainBundle] URLForResource:@"main" withExtension:@"jsbundle"];
#endif
}

@end
