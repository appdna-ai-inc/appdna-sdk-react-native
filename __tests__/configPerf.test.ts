/**
 * W16 (synchronous config snapshot) + W17 (fire-and-forget track).
 *
 * Both are perf contracts a typecheck cannot express: that `track()` returns no Promise, and that a
 * primed config snapshot answers synchronously and refreshes when native reports a change.
 */

let remoteConfigListener: (() => void) | undefined;

const mockModule = {
  track: jest.fn().mockReturnValue(undefined), // native track is a synchronous void method
  shutdown: jest.fn().mockResolvedValue(undefined),
  getAllRemoteConfig: jest.fn().mockResolvedValue(JSON.stringify({ flagA: true, count: 42 })),
  onRemoteConfigChanged: (listener: () => void) => {
    remoteConfigListener = listener;
    return { remove: () => (remoteConfigListener = undefined) };
  },
  // Sentinels/emitters the facade may touch at import or setup.
  onInitDegraded: () => ({ remove: () => undefined }),
  onHostCallback: () => ({ remove: () => undefined }),
};

jest.mock('react-native', () => ({
  TurboModuleRegistry: { get: () => mockModule, getEnforcing: () => mockModule },
  Platform: { OS: 'ios', select: (spec: Record<string, unknown>) => spec.ios ?? spec.default },
}));

import { AppDNA } from '../src/index';

const flush = () => new Promise((r) => setTimeout(r, 0));

beforeEach(async () => {
  jest.clearAllMocks();
  mockModule.getAllRemoteConfig.mockResolvedValue(JSON.stringify({ flagA: true, count: 42 }));
  await AppDNA.shutdown(); // reset the module-level snapshot between tests
});

describe('W17 — track() is fire-and-forget', () => {
  it('returns void, not a Promise, and still crosses to native', () => {
    const result = AppDNA.track('scrolled', { y: 10 });
    expect(result).toBeUndefined();
    expect(mockModule.track).toHaveBeenCalledWith('scrolled', { y: 10 });
  });

  it('a synchronous native throw (missing / torn-down module) does not crash the caller', () => {
    // track() is a SYNCHRONOUS void JSI method, and AppdnaModule is a Proxy whose get-trap runs
    // requireNativeModule() — so a missing/torn-down module throws SYNCHRONOUSLY, not as a promise
    // rejection. The old Promise.resolve(AppdnaModule.track(...)).catch() could never catch that (it
    // evaluated the throwing call before wrapping it) and would crash the host; only the try/catch
    // does. A rejected-promise mock was the wrong model — a void method returns no promise to reject.
    mockModule.track.mockImplementationOnce(() => {
      throw new Error('bridge torn down');
    });
    expect(() => AppDNA.track('e')).not.toThrow();
  });
});

describe('W16 — synchronous config snapshot', () => {
  it('getCached is undefined until primed', () => {
    expect(AppDNA.remoteConfig.hasSnapshot()).toBe(false);
    expect(AppDNA.remoteConfig.getCached('flagA')).toBeUndefined();
  });

  it('after primeSnapshot, reads are synchronous and correct', async () => {
    await AppDNA.remoteConfig.primeSnapshot();
    expect(AppDNA.remoteConfig.hasSnapshot()).toBe(true);
    expect(AppDNA.remoteConfig.getCached('flagA')).toBe(true);
    expect(AppDNA.remoteConfig.getCached('count')).toBe(42);
    expect(AppDNA.remoteConfig.getCached('absent')).toBeUndefined();
  });

  it('auto-refreshes when native fires onRemoteConfigChanged', async () => {
    await AppDNA.remoteConfig.primeSnapshot();
    expect(AppDNA.remoteConfig.getCached('count')).toBe(42);

    mockModule.getAllRemoteConfig.mockResolvedValueOnce(JSON.stringify({ flagA: false, count: 99 }));
    remoteConfigListener?.(); // native reports a change
    await flush();

    expect(AppDNA.remoteConfig.getCached('count')).toBe(99);
    expect(AppDNA.remoteConfig.getCached('flagA')).toBe(false);
  });

  it('shutdown clears the snapshot and its subscription', async () => {
    await AppDNA.remoteConfig.primeSnapshot();
    expect(AppDNA.remoteConfig.hasSnapshot()).toBe(true);
    await AppDNA.shutdown();
    expect(AppDNA.remoteConfig.hasSnapshot()).toBe(false);
    expect(AppDNA.remoteConfig.getCached('flagA')).toBeUndefined();
    expect(remoteConfigListener).toBeUndefined(); // unsubscribed
  });

  it('shutdown wins over an in-flight refetch — no pre-shutdown config resurrection', async () => {
    // The race: onRemoteConfigChanged fires and dispatches an async getAllRemoteConfig, THEN shutdown()
    // runs its synchronous teardown, THEN the in-flight read resolves. Without the liveness guard its
    // .then reassigns _configSnapshot AFTER shutdown, so getCached() serves pre-shutdown config and it
    // survives into the next session. Reverting the guard turns this red.
    await AppDNA.remoteConfig.primeSnapshot();
    mockModule.getAllRemoteConfig.mockResolvedValueOnce(JSON.stringify({ flagA: false, count: 99 }));
    remoteConfigListener?.();      // dispatches the refetch; its .then is a pending microtask
    await AppDNA.shutdown();       // synchronous teardown runs first (nulls the snapshot + sub)
    await flush();                 // now let the in-flight refetch's .then run — it must no-op
    expect(AppDNA.remoteConfig.hasSnapshot()).toBe(false);
    expect(AppDNA.remoteConfig.getCached('count')).toBeUndefined();
  });

  it('shutdown during an in-flight primeSnapshot wins — the initial fetch does not resurrect', async () => {
    // Symmetric race on the INITIAL fetch: shutdown lands while primeSnapshot is still awaiting its
    // first getAllRemoteConfig. The resolved read must not re-populate the snapshot post-shutdown.
    let resolveFetch!: (v: string) => void;
    mockModule.getAllRemoteConfig.mockReturnValueOnce(new Promise((r) => { resolveFetch = r; }));
    const priming = AppDNA.remoteConfig.primeSnapshot(); // in flight, awaiting the initial fetch
    await AppDNA.shutdown();                             // teardown while the fetch is pending
    resolveFetch(JSON.stringify({ flagA: true, count: 42 }));
    await priming;                                        // primeSnapshot's continuation runs (guarded)
    await flush();
    expect(AppDNA.remoteConfig.hasSnapshot()).toBe(false);
  });
});
