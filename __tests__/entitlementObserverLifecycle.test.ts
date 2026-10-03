/**
 * 🔴 `configure → shutdown → configure` left `onEntitlementsChanged` PERMANENTLY DEAD.
 *
 * Native `shutdown()` drops every entitlement handler it holds (iOS `AppDNA.shutdown()` calls
 * `webEntitlementChangeHandlers.removeAll()` and the billing observer with it; Android's nulls the
 * billing manager). A JS subscriber is untouched by that: its closure is still in the emitter, still
 * expecting events, with nobody left on the native side to send any.
 *
 * `shutdown()` called `resetEntitlementObserver()`, which cleared the latch so the NEXT subscriber
 * would re-send `startEntitlementObserver()` to native. In the normal integration there is no next
 * subscriber — a host subscribes ONCE at startup and keeps that subscription for the life of the
 * process. So the re-arm never happened, and a renewal that should unlock the app silently stopped
 * arriving. No error, no log, no failing test: the observer is a thing that goes quiet.
 *
 * The mock counts `startEntitlementObserver()` crossings, because the crossing IS the fix.
 */

const listenersByEvent = new Map<string, Array<(payload: unknown) => void>>();

function emitterFor(event: string) {
  return (listener: (payload: unknown) => void) => {
    const list = listenersByEvent.get(event) ?? [];
    list.push(listener);
    listenersByEvent.set(event, list);
    return {
      remove: () => {
        const current = listenersByEvent.get(event) ?? [];
        const at = current.indexOf(listener);
        if (at >= 0) current.splice(at, 1);
      },
    };
  };
}

const mockModule: Record<string, unknown> = {
  configure: jest.fn().mockResolvedValue(undefined),
  shutdown: jest.fn().mockResolvedValue(undefined),
  startEntitlementObserver: jest.fn().mockResolvedValue(undefined),
  respondToHostCallback: jest.fn(),
};
for (const event of ['onInitDegraded', 'onHostCallback', 'onEntitlementsChanged', 'onRemoteConfigChanged', 'onFeatureFlagsChanged']) {
  mockModule[event] = emitterFor(event);
}

jest.mock('react-native', () => ({
  TurboModuleRegistry: { get: () => mockModule, getEnforcing: () => mockModule },
  Platform: { OS: 'ios', select: (spec: Record<string, unknown>) => spec.ios ?? spec.default },
}));

import { AppDNA } from '../src/index';
import { __resetEntitlementObserverForTesting } from '../src/billing';
import { __resetHostCallbacksForTesting } from '../src/hostCallbacks';
import { removeAllDelegateListeners } from '../src/nativeModule';

const startObserver = mockModule.startEntitlementObserver as jest.Mock;

/** Deliver one entitlement change from native to whatever is still listening. */
function emitEntitlementChange(): void {
  for (const listener of listenersByEvent.get('onEntitlementsChanged') ?? []) {
    listener({ entitlements: [{ identifier: 'premium', productId: 'premium_monthly', isActive: true }] });
  }
}

beforeEach(() => {
  removeAllDelegateListeners();
  __resetHostCallbacksForTesting();
  __resetEntitlementObserverForTesting();
  listenersByEvent.clear();
  startObserver.mockClear();
});

describe('a subscriber that outlives a shutdown keeps receiving entitlements', () => {
  it('configure() re-arms native for a listener registered before the shutdown', async () => {
    await AppDNA.configure('adn_test', 'production');

    const seen: unknown[][] = [];
    AppDNA.billing.onEntitlementsChanged((entitlements) => seen.push(entitlements));
    expect(startObserver).toHaveBeenCalledTimes(1);

    // The host tears the SDK down (sign-out, tenant switch, test harness) and brings it back up.
    await AppDNA.shutdown();
    await AppDNA.configure('adn_test', 'production');

    // Native was told to observe again. WITHOUT this, the count stays at 1: the latch was cleared for a
    // "next subscriber" that never comes, and the still-live listener below never fires again.
    expect(startObserver).toHaveBeenCalledTimes(2);

    emitEntitlementChange();
    expect(seen).toHaveLength(1);
    expect((seen[0]![0] as { isActive: boolean }).isActive).toBe(true);
  });

  it('does NOT re-arm when nobody is subscribed — a shutdown with no listeners stays quiet', async () => {
    await AppDNA.configure('adn_test', 'production');
    expect(startObserver).not.toHaveBeenCalled();

    await AppDNA.shutdown();
    await AppDNA.configure('adn_test', 'production');

    // The observer is a native subscription with a cost. Re-arming it for zero listeners would be a
    // re-subscribe storm dressed up as a fix.
    expect(startObserver).not.toHaveBeenCalled();
  });

  it('an unsubscribed listener is not re-armed for, and unsubscribing twice does not corrupt the count', async () => {
    await AppDNA.configure('adn_test', 'production');

    const unsubscribe = AppDNA.billing.onEntitlementsChanged(() => undefined);
    unsubscribe();
    // A double-unsubscribe (a cleanup that runs on both unmount and a manual call) must not push the
    // count NEGATIVE — that would leave a genuinely live subscriber uncounted and dead after the next
    // shutdown, which is the exact bug this file exists for.
    unsubscribe();
    startObserver.mockClear();

    await AppDNA.shutdown();
    await AppDNA.configure('adn_test', 'production');

    expect(startObserver).not.toHaveBeenCalled();

    // And a listener that subscribes AFTER all that still arms it.
    AppDNA.billing.onEntitlementsChanged(() => undefined);
    expect(startObserver).toHaveBeenCalledTimes(1);
  });

  it('the latch still holds within one session — N subscribers, one native crossing', async () => {
    await AppDNA.configure('adn_test', 'production');

    for (let i = 0; i < 25; i++) AppDNA.billing.onEntitlementsChanged(() => undefined);

    expect(startObserver).toHaveBeenCalledTimes(1);
  });

  it('a shutdown completing DURING configure()\'s await does not leave the observer dead', async () => {
    // The same teardown-race class as the config snapshot, on the entitlement observer. configure()
    // resumes the observer in its post-await continuation; if a shutdown() completes while that await is
    // pending, the resume must be SKIPPED — otherwise it re-latches the observer as "started" on the
    // torn-down SDK, and the next real configure() short-circuits and leaves onEntitlementsChanged dead.
    await AppDNA.configure('adn_test', 'production');
    const seen: unknown[][] = [];
    AppDNA.billing.onEntitlementsChanged((e) => seen.push(e as unknown[]));
    expect(startObserver).toHaveBeenCalledTimes(1);
    startObserver.mockClear();

    // The next configure's native call is slow; shutdown() lands and completes before it resolves.
    let resolveConfigure!: () => void;
    (mockModule.configure as jest.Mock).mockReturnValueOnce(
      new Promise<void>((r) => { resolveConfigure = () => r(); }),
    );
    const configuring = AppDNA.configure('adn_test', 'production'); // in flight
    await AppDNA.shutdown(); // completes: resets the observer latch AND bumps the configure generation
    resolveConfigure();
    await configuring; // configure's post-await resume MUST be skipped (a shutdown intervened)

    // If the racy resume had run, this would be 1 (wasted on the torn-down SDK) and the latch would be
    // stuck "started", so the proper restart below would never re-arm. The generation guard prevents it.
    expect(startObserver).toHaveBeenCalledTimes(0);

    // The proper restart re-arms native for the still-live subscriber, and events flow again.
    await AppDNA.configure('adn_test', 'production');
    expect(startObserver).toHaveBeenCalledTimes(1);
    emitEntitlementChange();
    expect(seen).toHaveLength(1);
  });
});
