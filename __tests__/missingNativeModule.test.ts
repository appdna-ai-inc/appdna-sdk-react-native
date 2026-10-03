/**
 * A runtime that cannot host the native module must fail
 * comprehensibly.
 *
 * `AppdnaModule is undefined` is the error every published version of this package produced, and it
 * tells a host nothing about Expo Go, an unrun `pod install`, or RN Web. This suite asserts the
 * directed error, and — just as importantly — that merely IMPORTING the package on such a runtime
 * does not throw. A host that imports on web and never calls must not crash at module scope.
 *
 * The second half covers the failure mode that replaced it: a module that resolves under the legacy
 * bridge, where every method works and no event ever fires. Silence is worse than a crash.
 */

let mockModule: Record<string, unknown> | null = null;

// `TurboModuleRegistry.get` returns null on a runtime without the module — Expo Go, RN Web, or an app
// whose `pod install` never ran. `getEnforcing` is what throws; the facade must never call it.
jest.mock('react-native', () => ({
  TurboModuleRegistry: {
    get: () => mockModule,
    getEnforcing: () => {
      if (!mockModule) throw new Error('AppdnaModule is undefined');
      return mockModule;
    },
  },
  Platform: { OS: 'ios', select: (spec: Record<string, unknown>) => spec.ios ?? spec.default },
}));

describe('missing native module', () => {
  beforeEach(() => {
    jest.resetModules();
    mockModule = null;
  });

  it('importing the package does not throw', () => {
    // Regression: index.ts, billing.ts and push.ts each resolved the module at import scope, so
    // merely importing on Expo Go raised before any AppDNA error could.
    expect(() => require('../src')).not.toThrow();
  });

  it('subscribing raises the directed error, not RN Invariant Violation', () => {
    const { AppDNA } = require('../src');
    expect(() =>
      AppDNA.onboarding.setDelegate({
        onOnboardingStarted: () => undefined,
        onOnboardingStepChanged: () => undefined,
        onOnboardingCompleted: () => undefined,
        onOnboardingDismissed: () => undefined,
      }),
    ).toThrow(/native module is not available/i);
  });

  it('calling a method throws a directed error, not "AppdnaModule is undefined"', async () => {
    const { AppDNA } = require('../src');
    await expect(AppDNA.configure('adn_test_placeholder', 'sandbox')).rejects.toThrow(
      /native module is not available/i,
    );
  });

  it('sync void methods do NOT crash the JS thread on a missing/torn-down bridge', () => {
    // track/setLogLevel/notifyScreenAppeared/suppressDisplay are SYNCHRONOUS void JSI methods a
    // host calls fire-and-forget — a void return gives no promise to `.catch()`, and the Proxy
    // get-trap throws SYNCHRONOUSLY when the module is absent. notifyScreenAppeared is wired into the
    // navigation container (fires on EVERY route change), so an unguarded throw crashes navigation on a
    // not-yet-linked app. All four must be no-ops here, not throws. (Async methods reject instead — see
    // the configure() case above.) Reverting any `safeSyncInvoke` guard turns its line red.
    const { AppDNA } = require('../src');
    expect(() => AppDNA.track('evt', { a: 1 })).not.toThrow();
    expect(() => AppDNA.setLogLevel('debug')).not.toThrow();
    expect(() => AppDNA.notifyScreenAppeared('Home')).not.toThrow();
    expect(() => AppDNA.inAppMessages.suppressDisplay(true)).not.toThrow();
  });

  it('the error names Expo Go, pod install, and the unsupported runtimes', async () => {
    const { AppDNA } = require('../src');
    const err = await AppDNA.configure('adn_test_placeholder', 'sandbox').catch((e: Error) => e);

    expect(err.message).toMatch(/Expo Go/);
    expect(err.message).toMatch(/pod install/);
    expect(err.message).toMatch(/RN Web and Yarn PnP/);
    // The bare, useless message must NOT be what a host sees.
    expect(err.message).not.toMatch(/^AppdnaModule is undefined/);
  });
});

describe('legacy bridge (New Architecture disabled)', () => {
  beforeEach(() => {
    jest.resetModules();
    // Codegen still exports an `RCT_EXPORT_MODULE` class, so the module RESOLVES. What the legacy
    // runtime never installs is the `EventEmitter<T>` properties — so every listener goes dead and
    // the SDK looks like it works. This mock is that runtime exactly.
    mockModule = { configure: jest.fn().mockResolvedValue(undefined) };
  });

  it('is rejected by name rather than silently dropping every callback', async () => {
    const { AppDNA } = require('../src');
    const err = await AppDNA.configure('adn_test_placeholder', 'sandbox').catch((e: Error) => e);

    expect(err.message).toMatch(/New Architecture/);
    expect(err.message).toMatch(/newArchEnabled/);
    // It must NOT be mistaken for the missing-module case: `pod install` would not fix this.
    expect(err.message).not.toMatch(/Expo Go/);
  });
});
