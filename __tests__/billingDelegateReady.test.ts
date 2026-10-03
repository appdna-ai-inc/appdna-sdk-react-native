/**
 * When the native billing forwarder may take queued purchases.
 *
 * Native registers the RN billing forwarder NOT delivering: the SDK's late-purchase queue (a purchase
 * reported at app start or after `identify`, an interrupted or Ask-to-Buy purchase…) drains only into a
 * delegate that will really hand the purchase to the host. A queued purchase emitted to JS while no
 * `onPurchaseCompleted` listens is gone. So the facade tells native, through the INTERNAL
 * `billingDelegateReady(ready)`:
 *
 *   - on every `billing.setDelegate` — `true` only when a JS `onPurchaseCompleted` is registered;
 *   - again after `configure()` resolves (native builds a fresh forwarder at every configure);
 *   - and `shutdown()` resets the latch, as it drops the listeners — so `setDelegate({onPurchaseCompleted})
 *     → shutdown() → configure()` must NOT claim `true`.
 *
 * The flip to `true` comes AFTER the listener exists, so a delivery it drains finds its handler.
 */

const listenersByEvent = new Map<string, Array<(payload: unknown) => void>>();
const calls: Array<{ method: string; args: unknown[] }> = [];

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

const EVENTS = [
  'onInitDegraded', 'onHostCallback', 'onRemoteConfigChanged', 'onFeatureFlagsChanged', 'onEntitlementsChanged',
  'onPurchaseCompleted', 'onPurchaseFailed', 'onRestoreCompleted', 'onBillingUnavailable',
];

const mockModule: Record<string, unknown> = {
  configure: jest.fn(async () => {
    calls.push({ method: 'configure', args: [] });
  }),
  shutdown: jest.fn(async () => {
    calls.push({ method: 'shutdown', args: [] });
  }),
  startEntitlementObserver: jest.fn().mockResolvedValue(undefined),
  billingDelegateReady: (ready: boolean) => {
    // Recorded with the number of live `onPurchaseCompleted` listeners AT THE MOMENT of the call.
    calls.push({ method: 'billingDelegateReady', args: [ready, (listenersByEvent.get('onPurchaseCompleted') ?? []).length] });
  },
};
for (const event of EVENTS) mockModule[event] = emitterFor(event);

jest.mock('react-native', () => ({
  TurboModuleRegistry: { get: () => mockModule, getEnforcing: () => mockModule },
  Platform: { OS: 'android', select: (spec: Record<string, unknown>) => spec.android ?? spec.default },
}));

import { AppDNA } from '../src/index';
import { __resetEntitlementObserverForTesting } from '../src/billing';
import { removeAllDelegateListeners } from '../src/nativeModule';

const readyCalls = () => calls.filter((c) => c.method === 'billingDelegateReady').map((c) => c.args[0]);

beforeEach(() => {
  removeAllDelegateListeners();
  __resetEntitlementObserverForTesting();
  listenersByEvent.clear();
  calls.length = 0;
});

describe('billingDelegateReady — the delivery latch', () => {
  it('a delegate with onPurchaseCompleted says ready, after its listener exists', () => {
    AppDNA.billing.setDelegate({ onPurchaseCompleted: () => undefined });
    const last = calls.filter((c) => c.method === 'billingDelegateReady').pop();
    expect(last?.args[0]).toBe(true);
    expect(last?.args[1]).toBe(1); // the listener was already registered when native was told
  });

  it('a delegate WITHOUT onPurchaseCompleted says not ready — a delivery would be lost', () => {
    AppDNA.billing.setDelegate({ onRestoreCompleted: () => undefined });
    expect(readyCalls()).toEqual([false]);
  });

  it('setDelegate(null) says not ready', () => {
    AppDNA.billing.setDelegate({ onPurchaseCompleted: () => undefined });
    AppDNA.billing.setDelegate(null);
    expect(readyCalls()).toEqual([true, false]);
  });

  it('configure() re-sends the latest value once native is configured', async () => {
    AppDNA.billing.setDelegate({ onPurchaseCompleted: () => undefined });
    calls.length = 0;
    await AppDNA.configure('adn_test_placeholder');
    const order = calls.map((c) => c.method);
    expect(order.indexOf('configure')).toBeLessThan(order.indexOf('billingDelegateReady'));
    expect(readyCalls()).toEqual([true]);
  });

  it('configure() with no delegate re-sends false', async () => {
    await AppDNA.configure('adn_test_placeholder');
    expect(readyCalls()).toEqual([false]);
  });

  it('setDelegate({onPurchaseCompleted}) → shutdown() → configure() does NOT claim ready (R60 S7)', async () => {
    AppDNA.billing.setDelegate({ onPurchaseCompleted: () => undefined });
    await AppDNA.shutdown();
    calls.length = 0;
    await AppDNA.configure('adn_test_placeholder');
    expect(readyCalls()).not.toContain(true);
    expect(readyCalls()).toEqual([false]);
  });

  it('is internal — not on the public billing facade', () => {
    expect((AppDNA.billing as unknown as Record<string, unknown>).billingDelegateReady).toBeUndefined();
    expect((AppDNA as unknown as Record<string, unknown>).billingDelegateReady).toBeUndefined();
  });
});
