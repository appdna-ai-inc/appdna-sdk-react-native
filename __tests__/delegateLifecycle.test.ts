/**
 * Two fixes that a round-2 audit broke on purpose — and every existing test still passed.
 *
 *   1. `setDelegate` REPLACES the previous delegate's listeners. It used to throw the subscription
 *      away, so a delegate registered in a `useEffect` that remounts (tab switch, navigation-back,
 *      Fast Refresh) stacked another full set, and one `onPaywallPurchaseCompleted` invoked N
 *      delegates: N entitlement grants for one purchase.
 *
 *   2. The veto dispatcher is installed by `configure()`, not lazily by the first `setDelegate`.
 *      Native registers its veto forwarders unconditionally during configure and the onboarding
 *      renderer awaits a veto on EVERY step render — so with no delegate, nobody answered on
 *      `onHostCallback` and native waited out the full timeout (5 s by default) before applying its
 *      default. A five-second freeze before every step, for the commonest integration of all.
 *
 * Both were "fixed" with no test. A fix with no test is a fix with a countdown on it.
 */

const listenersByEvent = new Map<string, Array<(payload: unknown) => void>>();
const replies: Array<{ callbackId: string; resultJson: string }> = [];

/** Emitter properties are plain closures on the JSI host object; the mock mirrors that shape. */
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
  respondToHostCallback: (callbackId: string, resultJson: string) => {
    replies.push({ callbackId, resultJson });
  },
};
for (const event of EVENTS) mockModule[event] = emitterFor(event);

jest.mock('react-native', () => ({
  TurboModuleRegistry: { get: () => mockModule, getEnforcing: () => mockModule },
  Platform: { OS: 'ios', select: (spec: Record<string, unknown>) => spec.ios ?? spec.default },
}));

import { AppDNA } from '../src/index';
import { __resetHostCallbacksForTesting } from '../src/hostCallbacks';
import { removeAllDelegateListeners } from '../src/nativeModule';

function countListeners(event: string): number {
  return (listenersByEvent.get(event) ?? []).length;
}

const noopSurveyDelegate = (tag: string, seen: string[]) => ({
  onSurveyPresented: () => seen.push(tag),
  onSurveyCompleted: () => undefined,
  onSurveyDismissed: () => undefined,
});

beforeEach(() => {
  removeAllDelegateListeners();
  __resetHostCallbacksForTesting();
  listenersByEvent.clear();
  replies.length = 0;
});

describe('setDelegate replaces, it does not stack', () => {
  it('a second setDelegate leaves ONE listener per event, and only the new delegate fires', () => {
    const seen: string[] = [];
    AppDNA.surveys.setDelegate(noopSurveyDelegate('first', seen));
    AppDNA.surveys.setDelegate(noopSurveyDelegate('second', seen));

    expect(countListeners('onSurveyPresented')).toBe(1);

    for (const listener of listenersByEvent.get('onSurveyPresented') ?? []) {
      listener({ surveyId: 's1' });
    }
    // If the old subscription survived, BOTH delegates run — which on the paywall means N
    // entitlement grants for one purchase.
    expect(seen).toEqual(['second']);
  });

  it('100 remounts leave 100 listeners if replacement is broken; they must leave 1', () => {
    const seen: string[] = [];
    for (let i = 0; i < 100; i++) AppDNA.surveys.setDelegate(noopSurveyDelegate(`d${i}`, seen));
    expect(countListeners('onSurveyPresented')).toBe(1);
  });

  /**
   * 🔴 AN RN HOST COULD NOT UNSET A DELEGATE AT ALL.
   *
   * Every facade `setDelegate` was typed non-nullable (`delegate: AppDNAPushDelegate`) while iOS and
   * Android both take one that can be nil/null. So a screen that registered a delegate on mount had no
   * way to withdraw it on unmount: its callbacks — and its VETOES — kept answering from an unmounted
   * component for the rest of the session.
   *
   * That is also why `unregisterHostCallback` looked like dead code. It was the removal half of a pair
   * whose other half nothing could reach.
   *
   * `null` now clears the slot: `setDelegateListeners` already removes the previous subscriptions AND
   * the slot's host callbacks, so installing nothing is exactly "no delegate".
   */
  it('setDelegate(null) CLEARS the delegate — a host can withdraw one on unmount', () => {
    const seen: string[] = [];
    AppDNA.surveys.setDelegate(noopSurveyDelegate('mounted', seen));
    expect(countListeners('onSurveyPresented')).toBe(1);

    AppDNA.surveys.setDelegate(null);

    expect(countListeners('onSurveyPresented')).toBe(0);
  });

  /**
   * ...and a PARTIAL delegate is accepted. The facade used to demand every callback of the protocol, so
   * a host that cared about one of twelve had to stub the other eleven or cast — while
   * `AppDNABilling.setDelegate` next door already took a `Partial<>`. Same SDK, two contracts.
   *
   * Asserts the behaviour a HOST would notice: the callback it implemented fires, and the event for a
   * callback it did NOT implement is delivered harmlessly instead of throwing
   * `delegate.onSurveyCompleted is not a function` into its app.
   */
  it('a PARTIAL delegate works — implemented callbacks fire, omitted ones do not throw', () => {
    const seen: string[] = [];
    AppDNA.surveys.setDelegate({ onSurveyPresented: () => seen.push('presented') });

    for (const l of listenersByEvent.get('onSurveyPresented') ?? []) l({ surveyId: 's1' });
    expect(seen).toEqual(['presented']);

    // The host never implemented this one. Delivering it must not blow up their app.
    expect(() => {
      for (const l of listenersByEvent.get('onSurveyCompleted') ?? []) l({ surveyId: 's1' });
    }).not.toThrow();
  });

  it('shutdown() drops every delegate slot, not just the config snapshot', async () => {
    const seen: string[] = [];
    AppDNA.surveys.setDelegate(noopSurveyDelegate('x', seen));
    AppDNA.screens.setDelegate({
      onScreenPresented: () => undefined,
      onScreenDismissed: () => undefined,
      onFlowCompleted: () => undefined,
      onScreenAction: () => true,
    });
    expect(countListeners('onSurveyPresented')).toBe(1);

    await AppDNA.shutdown();

    expect(countListeners('onSurveyPresented')).toBe(0);
    expect(countListeners('onScreenPresented')).toBe(0);
  });
});

describe('the flag bucket survives the bridge into a host delegate', () => {
  /**
   * A flag CTA writes its key into the step's answers AND into a flat `flags` bucket, and the host
   * reads that bucket at `onOnboardingCompleted` to route once onboarding has finished.
   *
   * React Native needs no wrapper code for this — `responses` crosses as a plain object and the
   * facade forwards it untouched — which is exactly why it deserves a test: "no code needed" is a
   * claim, and an untested claim about a nested value crossing a bridge is how a wrapper quietly
   * flattens one. Flutter's `cast<String, dynamic>()` is shallow for precisely this reason.
   */
  it('a nested flags map arrives intact, alongside the step answers', async () => {
    await AppDNA.configure('adn_test_key');

    const seen: Array<{ flowId: string; responses: Record<string, unknown> }> = [];
    AppDNA.onboarding.setDelegate({
      onOnboardingCompleted: (flowId, responses) => {
        seen.push({ flowId, responses });
      },
    });

    for (const listener of listenersByEvent.get('onOnboardingCompleted') ?? []) {
      listener({
        flowId: 'flow_tour_booking',
        responses: {
          ask_goal: ['relax'],
          summary: { upsell_choice: 'booking' },
          flags: { upsell_choice: 'booking', wants_callback: 'true' },
        },
      });
    }

    expect(seen).toHaveLength(1);
    const flags = seen[0]!.responses.flags as Record<string, unknown>;
    expect(flags).toEqual({ upsell_choice: 'booking', wants_callback: 'true' });
    // The step-scoped copy is still there — the bucket is an addition, nothing is moved.
    expect(seen[0]!.responses.summary).toEqual({ upsell_choice: 'booking' });
  });
});

describe('a delegate swap replaces the VETO HOOKS too, not just the listeners', () => {
  /** The hooks are registered conditionally, so a new delegate that omits one never overwrites it. */
  const onboardingDelegate = (opts: { withVeto: boolean }) => ({
    onOnboardingStarted: () => undefined,
    onOnboardingStepChanged: () => undefined,
    onOnboardingCompleted: () => undefined,
    onOnboardingDismissed: () => undefined,
    onPermissionResult: () => undefined,
    ...(opts.withVeto
      ? { onBeforeStepAdvance: async () => ({ type: 'block', message: 'A still here' }) }
      : {}),
  });

  it('the OLD delegate stops vetoing when the new one does not implement the hook', async () => {
    await AppDNA.configure('adn_test_key');

    AppDNA.onboarding.setDelegate(onboardingDelegate({ withVeto: true }));
    AppDNA.onboarding.setDelegate(onboardingDelegate({ withVeto: false }));

    const listener = (listenersByEvent.get('onHostCallback') ?? [])[0]!;
    listener({ callbackId: 'e1:9', hook: 'onBeforeStepAdvance', argsJson: JSON.stringify({ flowId: 'f' }) });
    await new Promise((r) => setImmediate(r));

    // If the swap left the first delegate's hook in place, it answers `{"type":"block",...}` — from a
    // screen that has been unmounted — and every step advance is vetoed forever.
    expect(replies).toEqual([{ callbackId: 'e1:9', resultJson: '{"__appdna_unhandled":true}' }]);
  });

  it('screens.setDelegate(null) clears the onScreenAction VETO — the one that was left unguarded', async () => {
    await AppDNA.configure('adn_test_key');
    AppDNA.screens.setDelegate({
      onScreenPresented: () => undefined,
      onScreenDismissed: () => undefined,
      onFlowCompleted: () => undefined,
      onScreenAction: () => true,
    });

    AppDNA.screens.setDelegate(null);

    const listener = (listenersByEvent.get('onHostCallback') ?? [])[0]!;
    listener({ callbackId: 'e1:9', hook: 'onScreenAction', argsJson: JSON.stringify({ screenId: 's' }) });
    await new Promise((r) => setImmediate(r));

    // The withdrawn delegate answering `true` here would mean an unmounted screen still vetoing. After
    // `null`, the seam must report UNHANDLED — no delegate is registered.
    expect(replies).toEqual([{ callbackId: 'e1:9', resultJson: '{"__appdna_unhandled":true}' }]);
  });

  it('shutdown() drops the veto hooks — a dead delegate must not approve a promo code', async () => {
    await AppDNA.configure('adn_test_key');
    AppDNA.paywall.setDelegate({
      onPaywallPresented: () => undefined,
      onPaywallDismissed: () => undefined,
      onPaywallAction: () => undefined,
      onPaywallPurchaseStarted: () => undefined,
      onPaywallPurchaseCompleted: () => undefined,
      onPaywallPurchaseFailed: () => undefined,
      onPaywallRestoreStarted: () => undefined,
      onPaywallRestoreCompleted: () => undefined,
      onPaywallRestoreFailed: () => undefined,
      onPostPurchaseDeepLink: () => undefined,
      onPostPurchaseNextStep: () => undefined,
      onPromoCodeSubmit: async () => true,
    });

    await AppDNA.shutdown();

    const listener = (listenersByEvent.get('onHostCallback') ?? [])[0]!;
    listener({ callbackId: 'e1:11', hook: 'onPromoCodeSubmit', argsJson: JSON.stringify({ code: 'FREE' }) });
    await new Promise((r) => setImmediate(r));

    // `true` here would mean a shut-down SDK's delegate accepted an unvalidated promo code.
    expect(replies).toEqual([{ callbackId: 'e1:11', resultJson: '{"__appdna_unhandled":true}' }]);
  });
});

describe('the veto dispatcher exists as soon as configure() has run', () => {
  it('configure() installs the onHostCallback listener even with NO delegate registered', async () => {
    expect(countListeners('onHostCallback')).toBe(0);

    await AppDNA.configure('adn_test_key');

    // Native registers its veto forwarders during configure and awaits an answer on every step
    // render. Nobody listening here means native waits out the whole timeout, every time.
    expect(countListeners('onHostCallback')).toBe(1);
  });

  it('an UNREGISTERED hook is answered immediately, and says it is unregistered', async () => {
    await AppDNA.configure('adn_test_key');

    const listener = (listenersByEvent.get('onHostCallback') ?? [])[0]!;
    listener({ callbackId: 'e1:7', hook: 'onBeforeStepAdvance', argsJson: JSON.stringify({ flowId: 'f' }) });
    await new Promise((r) => setImmediate(r));

    // NOT `"null"`. "I have no handler" and "I looked and have no opinion" are the same for seven of
    // the eight hooks — but for an AUTH action they are opposites: no handler means nobody can sign
    // this user in, and the wrapper must block the advance rather than let native's `.proceed` default
    // walk the user past the credential step unauthenticated. The reply is answered at once either
    // way, so the 5 s stall stays fixed.
    expect(replies).toEqual([{ callbackId: 'e1:7', resultJson: '{"__appdna_unhandled":true}' }]);
  });

  it('a REGISTERED hook that returns nothing still means "no opinion", not "unhandled"', async () => {
    await AppDNA.configure('adn_test_key');
    AppDNA.onboarding.setDelegate({
      onOnboardingStarted: () => undefined,
      onOnboardingStepChanged: () => undefined,
      onOnboardingCompleted: () => undefined,
      onOnboardingDismissed: () => undefined,
      onPermissionResult: () => undefined,
      onBeforeStepAdvance: async () => undefined as never,
    });

    const listener = (listenersByEvent.get('onHostCallback') ?? [])[0]!;
    listener({ callbackId: 'e1:8', hook: 'onBeforeStepAdvance', argsJson: JSON.stringify({ flowId: 'f' }) });
    await new Promise((r) => setImmediate(r));

    // The host looked at it and shrugged. That IS an answer, and it must not be mistaken for "there is
    // nobody here" — otherwise a host that deliberately proceeds on an auth step would be blocked.
    expect(replies).toEqual([{ callbackId: 'e1:8', resultJson: 'null' }]);
  });
});
