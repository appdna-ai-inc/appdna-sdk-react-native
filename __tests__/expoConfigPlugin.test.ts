/**
 * SPEC-070-B — the Expo config plugin, driven through fake Expo mod runners.
 *
 * These fakes stand in for `@expo/config-plugins`, which is an OPTIONAL peer (an Expo app always has
 * it; a bare-RN app never loads `app.plugin.js` at all). They behave the way the real runners do —
 * each `withX` appends a mod that is handed a `modResults` and returns it — so the plugin under test
 * is the real one, unmodified, and its output is the real file content Expo would write.
 *
 * What each test locks down was, before this suite, false:
 *   • the docs promised the plugin enables the New Architecture. It did not touch it, on either
 *     platform, so every Expo host hit `NEW_ARCH_ERROR` on the first facade call.
 *   • the plugin forced dynamic frameworks — which compiles RN's third-party component registry out
 *     — and did NOT add the AppDelegate override that puts `AppdnaScreenSlotView` back. Expo
 *     regenerates the AppDelegate on every prebuild, so a hand edit could not survive either.
 *   • `createRunOncePlugin` wrapped the IDENTITY function and ran AFTER the mods, so the run-once
 *     contract was inverted: listing the plugin twice applied every mod twice.
 */

type Mod = (cfg: any) => any;

/** The mods each `withX` queued, in order, so a test can run them against a starting file state. */
const queued: Array<{ platform: string; mod: Mod }> = [];

const queue = (platform: string) => (config: any, mod: Mod) => {
  queued.push({ platform, mod });
  return config;
};

jest.mock(
  '@expo/config-plugins',
  () => ({
    withPodfileProperties: queue('podfile'),
    withGradleProperties: queue('gradle'),
    withEntitlementsPlist: queue('entitlements'),
    withInfoPlist: queue('infoPlist'),
    withAppDelegate: queue('appDelegate'),
    // The real guard, reimplemented exactly as Expo's: a name+version keyed record on the config,
    // checked BEFORE the plugin runs. This is the contract the old composition inverted.
    createRunOncePlugin:
      (plugin: any, name: string, version?: string) => (config: any, props?: any) => {
        const history = (config._internal ??= {}).pluginHistory ?? {};
        if (history[name]) return config;
        config._internal.pluginHistory = { ...history, [name]: { name, version } };
        return plugin(config, props);
      },
  }),
  { virtual: true },
);

// eslint-disable-next-line @typescript-eslint/no-var-requires
const withAppDNA = require('../app.plugin.js');

/** Run every mod a `withX` queued for one platform against a starting `modResults`. */
const runMods = <T>(platform: string, initial: T): T => {
  let modResults: any = initial;
  for (const entry of queued) {
    if (entry.platform !== platform) continue;
    modResults = entry.mod({ modResults }).modResults;
  }
  return modResults as T;
};

const OBJC_APPDELEGATE = `#import "AppDelegate.h"

#import <React/RCTBundleURLProvider.h>

@implementation AppDelegate

- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions
{
  self.moduleName = @"main";
  return [super application:application didFinishLaunchingWithOptions:launchOptions];
}

@end
`;

const SWIFT_APPDELEGATE = `import ExpoModulesCore

@UIApplicationMain
public class AppDelegate: ExpoAppDelegate {
}
`;

beforeEach(() => {
  queued.length = 0;
});

describe('the New Architecture', () => {
  it('is enabled on iOS — the pod install derives RCT_NEW_ARCH_ENABLED from this', () => {
    withAppDNA({});
    const podfile = runMods<Record<string, string>>('podfile', {});

    // 🔴 Was absent. `src/nativeModule.ts` then threw NEW_ARCH_ERROR on the first facade call,
    // because the TurboModule resolves on the legacy bridge but its event emitters do not exist.
    expect(podfile.newArchEnabled).toBe('true');
    expect(podfile['ios.deploymentTarget']).toBe('16.0');
  });

  it('is enabled on Android — the plugin used to write no gradle property at all', () => {
    withAppDNA({});
    const gradle = runMods<any[]>('gradle', []);

    expect(gradle).toContainEqual({ type: 'property', key: 'newArchEnabled', value: 'true' });
  });

  it('overwrites an existing newArchEnabled=false rather than appending a dead second entry', () => {
    withAppDNA({});
    const gradle = runMods<any[]>('gradle', [
      { type: 'property', key: 'newArchEnabled', value: 'false' },
    ]);

    expect(gradle.filter((p) => p.key === 'newArchEnabled')).toEqual([
      { type: 'property', key: 'newArchEnabled', value: 'true' },
    ]);
  });

  it('is reported on the resolved config, so `expo config` agrees with the property files', () => {
    const config: any = {};
    withAppDNA(config);
    expect(config.newArchEnabled).toBe(true);
  });
});

describe('the Fabric screen slot under dynamic frameworks', () => {
  it('registers AppdnaScreenSlotView in the AppDelegate the plugin itself forces the need for', () => {
    withAppDNA({});
    expect(runMods<Record<string, string>>('podfile', {})['ios.useFrameworks']).toBe('dynamic');

    const appDelegate = runMods('appDelegate', {
      contents: OBJC_APPDELEGATE,
      language: 'objcpp',
    }) as any;

    // 🔴 Without this, <AppDNAScreenSlot> renders "Unimplemented component:
    // <AppdnaScreenSlotView>" — no throw, no warning — for EVERY Expo customer.
    expect(appDelegate.contents).toContain(
      '#import <appdna_sdk_react_native/AppdnaFabricComponents.h>',
    );
    expect(appDelegate.contents).toContain('- (NSDictionary<NSString *, Class<RCTComponentViewProtocol>> *)thirdPartyFabricComponents');
    expect(appDelegate.contents).toContain('addEntriesFromDictionary:AppdnaFabricComponents()');

    // The override must land INSIDE @implementation, not before it.
    expect(appDelegate.contents.indexOf('thirdPartyFabricComponents')).toBeGreaterThan(
      appDelegate.contents.indexOf('@implementation AppDelegate'),
    );
    // ...and the import OUTSIDE it.
    expect(appDelegate.contents.indexOf('AppdnaFabricComponents.h')).toBeLessThan(
      appDelegate.contents.indexOf('@implementation AppDelegate'),
    );
  });

  it('is idempotent — prebuild regenerates the AppDelegate and re-runs every mod', () => {
    withAppDNA({});
    const once = runMods('appDelegate', { contents: OBJC_APPDELEGATE, language: 'objcpp' }) as any;

    queued.length = 0;
    withAppDNA({});
    const twice = runMods('appDelegate', { contents: once.contents, language: 'objcpp' }) as any;

    // A second override in the same @implementation would not compile. (The method body names the
    // selector twice — declaration and `super` call — so count the DECLARATION.)
    expect(twice.contents).toBe(once.contents);
    expect(twice.contents.match(/^- \(NSDictionary.*thirdPartyFabricComponents$/gm)).toHaveLength(1);
    expect(twice.contents.match(/AppdnaFabricComponents\.h/g)).toHaveLength(1);
  });

  it('degrades gracefully on a Swift AppDelegate (Expo 53+): warns + skips, does NOT hard-block', () => {
    // Expo 53+ ships a Swift AppDelegate. Throwing here hard-blocked every new Expo-53 app on the
    // default dynamic frameworks (static frameworks are an escape, but not one to force). Instead the plugin warns
    // and skips the ScreenSlot Fabric registration — the SDK still installs; the slot needs a manual
    // override on Expo 53 (a native Swift path is a follow-up).
    withAppDNA({});
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    let appDelegate: any;
    expect(() => {
      appDelegate = runMods('appDelegate', { contents: SWIFT_APPDELEGATE, language: 'swift' });
    }).not.toThrow();
    expect(appDelegate.contents).toBe(SWIFT_APPDELEGATE); // untouched — registration skipped, not blind-patched
    expect(warn).toHaveBeenCalledWith(expect.stringMatching(/SWIFT AppDelegate/));
    // The message said FirebaseFirestore REQUIRES dynamic frameworks and called the static option
    // "link statically". Static frameworks work (sdk-ci's pods-static leg); only static LIBRARIES fail.
    const message = String(warn.mock.calls[0]?.[0]);
    expect(message).toMatch(/Use static frameworks/);
    expect(message).toMatch(/not static libraries/);
    expect(message).toContain('{ "useFrameworks": "static" }');
    expect(message).not.toMatch(/requires it|forces it|Link statically/);
    warn.mockRestore();
  });

  it('touches no AppDelegate under static linkage — codegen registers the component there', () => {
    withAppDNA({}, { useFrameworks: 'static' });

    expect(runMods<Record<string, string>>('podfile', {})['ios.useFrameworks']).toBe('static');
    expect(queued.some((q) => q.platform === 'appDelegate')).toBe(false);
  });

  it('honours the documented escape hatch', () => {
    withAppDNA({}, { screenSlot: 'skip' });
    expect(queued.some((q) => q.platform === 'appDelegate')).toBe(false);
  });
});

describe('the run-once guard', () => {
  it('applies the mods once when the plugin is listed twice', () => {
    let config: any = {};
    config = withAppDNA(config);
    config = withAppDNA(config);

    // 🔴 `createRunOncePlugin` used to wrap `(c) => c` and run AFTER the mods, so it guarded
    // nothing: a config listing the plugin twice queued — and applied — every mod twice.
    expect(queued.filter((q) => q.platform === 'podfile')).toHaveLength(1);
    expect(queued.filter((q) => q.platform === 'gradle')).toHaveLength(1);
    expect(queued.filter((q) => q.platform === 'appDelegate')).toHaveLength(1);
    expect(config._internal.pluginHistory['@appdna-ai/react-native-sdk']).toBeDefined();
  });
});

describe('push', () => {
  it('stays off unless the host asks for it', () => {
    withAppDNA({});
    expect(queued.some((q) => q.platform === 'entitlements')).toBe(false);
  });

  it('adds the APNs entitlement and the background mode when it is asked for', () => {
    withAppDNA({}, { enablePush: true });

    expect(runMods<Record<string, string>>('entitlements', {})['aps-environment']).toBe(
      'development',
    );
    const infoPlist = runMods<Record<string, string[]>>('infoPlist', {});
    expect(infoPlist.UIBackgroundModes).toContain('remote-notification');
    expect(infoPlist.BGTaskSchedulerPermittedIdentifiers).toContain('ai.appdna.sdk.eventUpload');
  });
});
