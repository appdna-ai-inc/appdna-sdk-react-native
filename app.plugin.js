/**
 * Expo config plugin for @appdna-ai/react-native-sdk.
 *
 * Floor: **Expo SDK 52** (pins React Native 0.76 exactly). RN 0.77+ / Kotlin 2.0 IS supported — the
 * Android module's Compose config is version-gated (see android/build.gradle). ⚠ On Expo SDK 53+
 * (a Swift AppDelegate) this plugin cannot auto-register the Fabric screen-slot under dynamic
 * frameworks: it WARNS and skips (the SDK still installs; the slot needs a manual override or static
 * frameworks). A native Swift Fabric-registration path is a follow-up.
 *
 * ## What this plugin must do, and what it must not
 *
 * **Both platforms: the New Architecture.** `src/nativeModule.ts` refuses to run on the legacy
 * bridge — the TurboModule resolves there, but its event emitters do not exist, so every SDK
 * callback would be silently dead. Expo SDK 52 leaves the New Architecture OPT-IN, so a plugin that
 * did not switch it on handed every Expo host a `NEW_ARCH_ERROR` on the first facade call. That is
 * the majority install path, and it was broken.
 *
 * **Android: New-Arch gradle property, and nothing else.** The SDK's library `AndroidManifest.xml`
 * auto-merges into the host. A plugin that also wrote application-level attributes would break
 * manifest-merge for any app declaring its own — which is why the wrapper's manifest is empty.
 *
 * **iOS: four things**, none of which Expo's defaults give you:
 *   1. `deploymentTarget → 16.0`. The core pod requires it (`AppDNASDK.podspec:15`); Expo 52
 *      defaults to 15.1, and CocoaPods resolves the floor, so the build fails at `pod install`.
 *   2. Frameworks, not static libraries. The SDK depends on FirebaseFirestore, and pods built as
 *      static libraries (Expo's default, no `use_frameworks!`) cannot build it: `pod install` refuses
 *      a Swift Firebase pod whose dependency defines no modules, and the modular-header workarounds
 *      fail later (see the `none` branch of `ci/ios-extension-host/Podfile`). Dynamic and static
 *      frameworks both work. The default is `ios.useFrameworks: 'dynamic'`, the linkage
 *      `example/ios/Podfile` builds and tests; `'static'` is the `pods-static` CI leg's.
 *   3. **The Fabric screen-slot registration that dynamic frameworks compiles out.** See below —
 *      this is why the default in (2) cannot be set and forgotten.
 *   4. Push entitlements — **only if the app uses push**. `AppDNA.swift:642` calls
 *      `registerForRemoteNotifications()`, so an app without `aps-environment` gets a runtime
 *      registration failure rather than a build error.
 *
 * ## Why (3) exists, and why it is an AppDelegate mod
 *
 * React Native's codegen'd `RCTThirdPartyFabricComponentsProvider` wraps its whole component map in
 * `#ifndef RCT_DYNAMIC_FRAMEWORKS`. Under dynamic frameworks — (2)'s default — nothing registers
 * `AppdnaScreenSlotView`, and `<AppDNAScreenSlot>` renders React's placeholder,
 * `Unimplemented component: <AppdnaScreenSlotView>`. No throw. No warning. The bare example carries
 * the override by hand (`example/ios/AppdnaExample/AppDelegate.mm`); an Expo app cannot, because
 * `expo prebuild` REGENERATES the AppDelegate and destroys any hand edit. So the plugin has to write
 * it on every prebuild, which is exactly what a mod is for.
 *
 * A **Swift** AppDelegate (Expo SDK 53+) is not patched: the override's Swift signature is not the
 * ObjC one, and shipping an unverified patch that silently fails to compile — or silently fails to
 * register — would rebuild the defect this mod exists to close. The mod leaves it untouched and the
 * prebuild prints `SWIFT_APPDELEGATE_WARNING`, naming the two real ways out (static frameworks, or
 * `screenSlot: "skip"`); the prebuild still succeeds.
 *
 * @param {import('@expo/config-types').ExpoConfig} config
 * @param {{
 *   enablePush?: boolean,
 *   deploymentTarget?: string,
 *   useFrameworks?: 'dynamic' | 'static',
 *   screenSlot?: 'auto' | 'skip',
 * }} [props]
 */
const {
  withPodfileProperties,
  withGradleProperties,
  withEntitlementsPlist,
  withInfoPlist,
  withAppDelegate,
  createRunOncePlugin,
} = require('@expo/config-plugins');

const pkg = require('./package.json');

const DEFAULT_DEPLOYMENT_TARGET = '16.0';

const FABRIC_IMPORT = '#import <appdna_sdk_react_native/AppdnaFabricComponents.h>';

const FABRIC_OVERRIDE = `
// Added by @appdna-ai/react-native-sdk's config plugin. Required because this app links pods as
// DYNAMIC frameworks (the plugin's default useFrameworks): codegen's
// RCTThirdPartyFabricComponentsProvider is wrapped in \`#ifndef RCT_DYNAMIC_FRAMEWORKS\`, so nothing
// registers AppdnaScreenSlotView and <AppDNAScreenSlot> silently renders React's "Unimplemented
// component" placeholder.
- (NSDictionary<NSString *, Class<RCTComponentViewProtocol>> *)thirdPartyFabricComponents
{
  NSMutableDictionary *components = [[super thirdPartyFabricComponents] mutableCopy];
  [components addEntriesFromDictionary:AppdnaFabricComponents()];
  return components;
}
`;

const SWIFT_APPDELEGATE_WARNING =
  '[@appdna-ai/react-native-sdk] This project has a SWIFT AppDelegate (Expo SDK 53+), so the\n' +
  'AppDNA plugin is SKIPPING the Fabric screen-slot registration (the rest of the SDK installs normally).\n' +
  '\n' +
  'Why it matters: this app links pods as dynamic frameworks (the plugin\'s default; the AppDNA iOS\n' +
  'SDK needs use_frameworks!, dynamic or static), and under dynamic frameworks React Native compiles\n' +
  'its third-party component registry OUT. Without a registration, <AppDNAScreenSlot> renders\n' +
  '"Unimplemented component: <AppdnaScreenSlotView>" — with no error and no warning. Everything\n' +
  'else in the SDK works.\n' +
  '\n' +
  'Pick one:\n' +
  '  1. Use static frameworks — the codegen registry is then compiled IN and nothing else is needed:\n' +
  '       ["@appdna-ai/react-native-sdk", { "useFrameworks": "static" }]\n' +
  '     (Static frameworks, not static libraries: without use_frameworks! the iOS SDK does not build.)\n' +
  '  2. Acknowledge that <AppDNAScreenSlot> will not render, and keep everything else:\n' +
  '       ["@appdna-ai/react-native-sdk", { "screenSlot": "skip" }]\n' +
  '\n' +
  'https://docs.appdna.ai/sdks/react-native/installation';

/** Replace-or-append a key in a `gradle.properties` mod result. */
const setGradleProperty = (properties, key, value) => {
  const existing = properties.find(
    (item) => item.type === 'property' && item.key === key,
  );
  if (existing) {
    existing.value = value;
    return properties;
  }
  properties.push({ type: 'property', key, value });
  return properties;
};

/** @type {(cfg: any, props?: any) => any} */
const withAppDNA = (config, props = {}) => {
  const deploymentTarget = props.deploymentTarget ?? DEFAULT_DEPLOYMENT_TARGET;
  const enablePush = props.enablePush ?? false;
  const useFrameworks = props.useFrameworks ?? 'dynamic';
  const screenSlot = props.screenSlot ?? 'auto';

  // Expo reads this when generating the native projects, so `expo config` and `expo-doctor` agree
  // with the two property files written below rather than contradicting them.
  config.newArchEnabled = true;

  // (1) + (2) + New Arch on iOS. All three land in Podfile.properties.json, which `expo prebuild`
  // reads. Expo's template Podfile derives `ENV['RCT_NEW_ARCH_ENABLED']` from `newArchEnabled`, so
  // this — not a hand-set env var — is how the pod install gets it.
  config = withPodfileProperties(config, (cfg) => {
    cfg.modResults['ios.deploymentTarget'] = deploymentTarget;
    // Frameworks either way: pods built as static libraries cannot build AppDNASDK's Firebase graph.
    // `dynamic` by default because example/ios/Podfile uses it, so the default is the linkage the
    // example builds and tests. `static` frameworks also install and build AppDNASDK with CocoaPods
    // 1.17 (sdk-ci's `pods-static` leg); an older CocoaPods wedged in BoringSSL-GRPC with them
    // (recorded in example/ios/Podfile).
    cfg.modResults['ios.useFrameworks'] = useFrameworks;
    cfg.modResults.newArchEnabled = 'true';
    return cfg;
  });

  // New Arch on Android. Expo SDK 52 defaults this to false; the TurboModule's event emitters only
  // exist under the New Architecture, so without this every SDK callback is dead.
  config = withGradleProperties(config, (cfg) => {
    setGradleProperty(cfg.modResults, 'newArchEnabled', 'true');
    return cfg;
  });

  // (3) The screen-slot registration dynamic frameworks compiles out. Static linkage does not need
  // it — codegen's provider already covers that case, and adding the override there is merely
  // redundant, so skip it and keep the AppDelegate untouched.
  if (useFrameworks === 'dynamic' && screenSlot !== 'skip') {
    config = withAppDelegate(config, (cfg) => {
      cfg.modResults.contents = addFabricRegistration(
        cfg.modResults.contents,
        cfg.modResults.language,
      );
      return cfg;
    });
  }

  if (enablePush) {
    // (4a) APNs environment. `expo prebuild` writes this into the .entitlements file.
    config = withEntitlementsPlist(config, (cfg) => {
      cfg.modResults['aps-environment'] =
        cfg.modResults['aps-environment'] ?? 'development';
      return cfg;
    });

    // (4b) Background delivery of silent pushes.
    config = withInfoPlist(config, (cfg) => {
      const modes = new Set(cfg.modResults.UIBackgroundModes ?? []);
      modes.add('remote-notification');
      cfg.modResults.UIBackgroundModes = [...modes];
      return cfg;
    });
  }

  // The SDK's BGTaskScheduler identifier. Registering a background task whose id is absent here is
  // an immediate NSInternalInconsistencyException at launch — not a degraded feature.
  config = withInfoPlist(config, (cfg) => {
    const ids = new Set(cfg.modResults.BGTaskSchedulerPermittedIdentifiers ?? []);
    ids.add('ai.appdna.sdk.eventUpload');
    cfg.modResults.BGTaskSchedulerPermittedIdentifiers = [...ids];
    return cfg;
  });

  return config;
};

/**
 * Insert the `thirdPartyFabricComponents` override into an AppDelegate.
 *
 * Idempotent: `expo prebuild` regenerates the AppDelegate and re-runs every mod, and a host may also
 * list the plugin under two names. Re-adding the override would not compile.
 *
 * @param {string} contents
 * @param {string} language — 'objc' | 'objcpp' | 'swift', as `withAppDelegate` reports it.
 * @returns {string}
 */
const addFabricRegistration = (contents, language) => {
  if (language === 'swift') {
    // Expo SDK 53+ ships a Swift AppDelegate. Injecting the ObjC thirdPartyFabricComponents override
    // into Swift is not something we can do safely without validating it against a real Expo-53
    // project, and a blind patch is worse than none. Rather than THROW (which hard-blocks a new
    // Expo-53 app that wants to keep dynamic frameworks), WARN and skip: the whole SDK still
    // installs and works on Expo 53; only the
    // dynamic-frameworks <AppDNAScreenSlot> auto-registration is skipped. The warning explains how to
    // restore it (static frameworks, a hand override, or screenSlot:"skip" to silence this). A native
    // Swift Fabric-registration path is a follow-up gated on an Expo-53 validation env.
    console.warn(SWIFT_APPDELEGATE_WARNING);
    return contents;
  }
  if (contents.includes('AppdnaFabricComponents')) {
    return contents; // already registered — a re-run, not a second app
  }

  const importAnchor = contents.lastIndexOf('#import');
  if (importAnchor === -1) {
    throw new Error(
      '[@appdna-ai/react-native-sdk] Could not find an #import in AppDelegate to anchor the ' +
        'Fabric screen-slot registration. Add it by hand, or pass { "screenSlot": "skip" }: ' +
        'https://docs.appdna.ai/sdks/react-native/installation',
    );
  }
  const importLineEnd = contents.indexOf('\n', importAnchor);
  const withImport =
    contents.slice(0, importLineEnd + 1) +
    FABRIC_IMPORT +
    '\n' +
    contents.slice(importLineEnd + 1);

  const implMatch = /@implementation\s+AppDelegate\b.*\n/.exec(withImport);
  if (!implMatch) {
    throw new Error(
      '[@appdna-ai/react-native-sdk] Could not find `@implementation AppDelegate` to anchor the ' +
        'Fabric screen-slot registration. Add it by hand, or pass { "screenSlot": "skip" }: ' +
        'https://docs.appdna.ai/sdks/react-native/installation',
    );
  }
  const insertAt = implMatch.index + implMatch[0].length;
  return withImport.slice(0, insertAt) + FABRIC_OVERRIDE + withImport.slice(insertAt);
};

// 🔴 This used to wrap the IDENTITY function `(c) => c` and run AFTER every mod had already been
// applied — so the run-once guard guarded nothing, and a config listing the plugin twice applied
// every mod twice. The guard has to wrap the PLUGIN.
module.exports = createRunOncePlugin(withAppDNA, pkg.name, pkg.version);

// Exported for the plugin's own test suite, which drives the real mods through fake Expo runners.
module.exports.withAppDNA = withAppDNA;
module.exports.addFabricRegistration = addFabricRegistration;
