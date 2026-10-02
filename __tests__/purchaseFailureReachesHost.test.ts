/**
 * A failed purchase must reach the JS host with a reason and a product.
 *
 * 🔴 The native SDKs gained an `errorType` discriminator and a `productId` on
 * `onPaywallPurchaseFailed`, because `error` crosses the bridge as an OPAQUE platform object: without
 * them a JS host cannot tell a user cancel from a declined card from a dead network, and a paywall
 * selling two plans cannot say which one failed. So it cannot decide whether retrying is even sensible.
 *
 * The wrapper then threw both away. Its native forwarders overrode the OLD 2-arg overload, and the
 * SDK's 4-arg call funnelled down through the defaults to reach it — so the callback still FIRED, and
 * every test still passed, while `errorType` and `productId` were erased in transit. The whole point of
 * the discriminator, defeated one layer above where it was added.
 *
 * That is why this test asserts the PAYLOAD, not merely that the callback fired. "It fired" was true
 * the entire time the bug existed.
 */

const listenersByEvent = new Map<string, Array<(payload: unknown) => void>>();

function emitterFor(event: string) {
  return (listener: (payload: unknown) => void) => {
    const list = listenersByEvent.get(event) ?? [];
    list.push(listener);
    listenersByEvent.set(event, list);
    return { remove: () => listenersByEvent.set(event, (listenersByEvent.get(event) ?? []).filter((l) => l !== listener)) };
  };
}

const EVENTS = [
  'onInitDegraded', 'onHostCallback', 'onRemoteConfigChanged', 'onFeatureFlagsChanged',
  'onOnboardingStarted', 'onOnboardingStepChanged', 'onOnboardingCompleted', 'onOnboardingDismissed',
  'onPermissionResult', 'onPaywallPresented', 'onPaywallDismissed', 'onPaywallAction',
  'onPaywallPurchaseStarted', 'onPaywallPurchaseCompleted', 'onPaywallPurchaseFailed',
  'onPaywallRestoreStarted', 'onPaywallRestoreCompleted', 'onPaywallRestoreFailed',
  'onPostPurchaseDeepLink', 'onPostPurchaseNextStep', 'onSurveyPresented', 'onSurveyCompleted',
  'onSurveyDismissed', 'onMessageShown', 'onMessageAction', 'onMessageDismissed',
  'onPushTokenRegistered', 'onPushReceived', 'onPushTapped', 'onDeepLinkReceived',
  'onSdkRuntimeLocked', 'onSdkRuntimeUnlocked', 'onScreenPresented', 'onScreenDismissed',
  'onFlowCompleted', 'onWebEntitlementChanged', 'onEntitlementsChanged', 'onPurchaseCompleted',
  'onPurchaseFailed', 'onRestoreCompleted', 'onBillingUnavailable',
];

const mockModule: Record<string, unknown> = {
  configure: jest.fn().mockResolvedValue(undefined),
  shutdown: jest.fn().mockResolvedValue(undefined),
  respondToHostCallback: jest.fn(),
};
for (const event of EVENTS) mockModule[event] = emitterFor(event);

jest.mock('react-native', () => ({
  TurboModuleRegistry: { get: () => mockModule, getEnforcing: () => mockModule },
  Platform: { OS: 'ios', select: (spec: Record<string, unknown>) => spec.ios ?? spec.default },
}));

import { AppDNA } from '../src/index';
import { removeAllDelegateListeners } from '../src/nativeModule';

beforeEach(() => {
  removeAllDelegateListeners();
  listenersByEvent.clear();
});

/** The exact payload the native forwarders emit — see AppdnaDelegates.kt / AppdnaDelegates.swift. */
function emitNativeFailure(payload: Record<string, unknown>) {
  for (const listener of listenersByEvent.get('onPaywallPurchaseFailed') ?? []) listener(payload);
}

const paywallDelegate = (seen: unknown[][]) => ({
  onPaywallPresented: () => undefined,
  onPaywallDismissed: () => undefined,
  onPaywallAction: () => undefined,
  onPaywallPurchaseStarted: () => undefined,
  onPaywallPurchaseCompleted: () => undefined,
  onPaywallPurchaseFailed: (...args: unknown[]) => seen.push(args),
  onPaywallRestoreStarted: () => undefined,
  onPaywallRestoreCompleted: () => undefined,
  onPaywallRestoreFailed: () => undefined,
  onPostPurchaseDeepLink: () => undefined,
  onPostPurchaseNextStep: () => undefined,
});

describe('a failed purchase reaches the JS host with its reason and its product', () => {
  it('forwards errorType and productId, not just the opaque error', () => {
    const seen: unknown[][] = [];
    AppDNA.paywall.setDelegate(paywallDelegate(seen));

    emitNativeFailure({
      paywallId: 'pw_1',
      error: 'The payment method was declined.',
      errorType: 'verificationFailed',
      productId: 'pro_yearly',
    });

    // Asserting the callback merely FIRED would have passed for the entire life of the bug — the
    // narrower overload fires too. The payload is the contract.
    expect(seen).toEqual([[
      'pw_1',
      'The payment method was declined.',
      'verificationFailed',
      'pro_yearly',
    ]]);
  });

  it('a cancel is distinguishable from a decline — the reason a host can act on', () => {
    const seen: unknown[][] = [];
    AppDNA.paywall.setDelegate(paywallDelegate(seen));

    emitNativeFailure({ paywallId: 'pw_1', error: 'cancelled', errorType: 'userCancelled', productId: 'pro_monthly' });
    emitNativeFailure({ paywallId: 'pw_1', error: 'network down', errorType: 'networkError', productId: 'pro_monthly' });

    // A host retries a networkError and does NOT nag a user who deliberately cancelled. Without the
    // discriminator both arrive as an opaque string and the host cannot tell them apart.
    expect(seen.map((a) => a[2])).toEqual(['userCancelled', 'networkError']);
  });

  it('a null productId means "no product was selected" — never a fabricated one', () => {
    const seen: unknown[][] = [];
    AppDNA.paywall.setDelegate(paywallDelegate(seen));

    emitNativeFailure({ paywallId: 'pw_1', error: 'no config', errorType: 'unknown', productId: null });

    expect(seen[0]![3]).toBeNull();
  });

  it('an undefined productId (iOS nil-optional boxing) is coalesced to null, never passed through', () => {
    // iOS boxed a nil `productId` via `as Any` → the RCTTurboModule bridge could not represent it,
    // so the host saw `undefined` on iOS while Android sent `null`. Native now emits NSNull, and the
    // facade also coalesces `?? null`, so the delegate NEVER sees `undefined` (contract: `string | null`).
    const seen: unknown[][] = [];
    AppDNA.paywall.setDelegate(paywallDelegate(seen));

    emitNativeFailure({ paywallId: 'pw_1', error: 'no config', errorType: 'unknown', productId: undefined });

    expect(seen[0]![3]).toBeNull();
  });
});
