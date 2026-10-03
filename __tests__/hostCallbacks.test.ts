/**
 * The JS half of the host-veto protocol.
 *
 * Native awaits a veto and applies its own default when the answer is `null`. So every assertion
 * here is about what lands in `respondToHostCallback`: `"null"` means "apply your default", and a
 * hook that throws must produce exactly that rather than hanging the native surface.
 *
 * The eighth hook is why the defaults live natively: `onPromoCodeSubmit` defaults to REJECT while
 * the other seven allow. If JS invented a default, that asymmetry would have to be duplicated in
 * three places instead of one.
 */

const mockReplies: Array<{ callbackId: string; resultJson: string }> = [];
let mockHostCallbackListener: ((event: unknown) => void) | undefined;

const mockModule = {
  respondToHostCallback: (callbackId: string, resultJson: string) => {
    mockReplies.push({ callbackId, resultJson });
  },
  onHostCallback: (listener: (event: unknown) => void) => {
    mockHostCallbackListener = listener;
    return { remove: () => undefined };
  },
  // The sentinel `requireNativeModule` checks to detect the legacy bridge.
  onInitDegraded: () => ({ remove: () => undefined }),
};

jest.mock('react-native', () => ({
  TurboModuleRegistry: { get: () => mockModule, getEnforcing: () => mockModule },
  Platform: { OS: 'ios', select: (spec: Record<string, unknown>) => spec.ios ?? spec.default },
}));

import {
  registerHostCallback,
  unregisterHostCallback,
  __resetHostCallbacksForTesting,
} from '../src/hostCallbacks';
import { readFileSync } from 'node:fs';
import { fixturePath } from './fixtureRoot';

/** Deliver a native veto request and wait for the dispatcher's async reply. */
async function fireHostCallback(hook: string, args: Record<string, unknown>, callbackId = 'e1:1') {
  mockHostCallbackListener?.({ callbackId, hook, argsJson: JSON.stringify(args) });
  // The dispatcher awaits the handler, so the reply lands a microtask later.
  await new Promise((resolve) => setImmediate(resolve));
}

describe('host-callback dispatcher', () => {
  beforeEach(() => {
    mockReplies.length = 0;
    mockHostCallbackListener = undefined;
    __resetHostCallbacksForTesting();
  });

  it('routes a request to its registered hook and replies with the encoded result', async () => {
    registerHostCallback('shouldShowMessage', (args) => args.messageId !== 'blocked');

    await fireHostCallback('shouldShowMessage', { messageId: 'welcome' });
    await fireHostCallback('shouldShowMessage', { messageId: 'blocked' }, 'e1:2');

    expect(mockReplies).toEqual([
      { callbackId: 'e1:1', resultJson: 'true' },
      { callbackId: 'e1:2', resultJson: 'false' },
    ]);
  });

  it('awaits an async hook before replying', async () => {
    registerHostCallback('onPromoCodeSubmit', async (args) => {
      await new Promise((resolve) => setTimeout(resolve, 0));
      return args.code === 'VALID';
    });

    mockHostCallbackListener?.({ callbackId: 'e1:9', hook: 'onPromoCodeSubmit', argsJson: '{"code":"VALID"}' });
    expect(mockReplies).toHaveLength(0); // not yet — the hook has not resolved

    await new Promise((resolve) => setTimeout(resolve, 5));
    expect(mockReplies).toEqual([{ callbackId: 'e1:9', resultJson: 'true' }]);
  });

  it('encodes a map result verbatim — the onboarding hooks answer with objects, not booleans', async () => {
    registerHostCallback('onBeforeStepAdvance', () => ({ type: 'block', message: 'Pick a plan' }));

    await fireHostCallback('onBeforeStepAdvance', { flowId: 'f1' });

    expect(JSON.parse(mockReplies[0].resultJson)).toEqual({ type: 'block', message: 'Pick a plan' });
  });

  it('replies "null" for a hook nobody registered, so native applies its per-hook default', async () => {
    // The dispatcher only exists once SOME hook is registered; register a different one.
    registerHostCallback('shouldOpen', () => true);

    await fireHostCallback('onPermissionRequest', { permissionType: 'notifications' });

    // An unregistered hook now says so explicitly — see UNHANDLED in hostCallbacks.ts. Native
    // maps it to the same per-hook default as `null` everywhere except an AUTH action, where
    // "nobody is here" must block the advance rather than proceed past unauthenticated credentials.
    expect(mockReplies).toEqual([{ callbackId: 'e1:1', resultJson: '{"__appdna_unhandled":true}' }]);
  });

  it('replies "null" when the hook throws, rather than hanging the native surface', async () => {
    const consoleError = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    registerHostCallback('onElementInteraction', () => {
      throw new Error('host bug');
    });

    await fireHostCallback('onElementInteraction', { blockId: 'b1' });

    expect(mockReplies).toEqual([{ callbackId: 'e1:1', resultJson: 'null' }]);
    expect(consoleError).toHaveBeenCalled();
    consoleError.mockRestore();
  });

  it('replies "null" when the hook returns undefined — "no opinion" has one wire form', async () => {
    registerHostCallback('onBeforeStepRender', () => undefined);

    await fireHostCallback('onBeforeStepRender', { stepId: 's1' });

    expect(mockReplies).toEqual([{ callbackId: 'e1:1', resultJson: 'null' }]);
  });

  it('replies "null" when argsJson is malformed — a broken bridge is not the host\'s problem', async () => {
    const consoleError = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    registerHostCallback('shouldShowMessage', () => true);

    mockHostCallbackListener?.({ callbackId: 'e1:1', hook: 'shouldShowMessage', argsJson: 'not json' });
    await new Promise((resolve) => setImmediate(resolve));

    expect(mockReplies).toEqual([{ callbackId: 'e1:1', resultJson: 'null' }]);
    consoleError.mockRestore();
  });

  it('unregistering a hook returns it to the native default', async () => {
    registerHostCallback('shouldShowMessage', () => false);
    unregisterHostCallback('shouldShowMessage');

    await fireHostCallback('shouldShowMessage', { messageId: 'welcome' });

    // An unregistered hook now says so explicitly — see UNHANDLED in hostCallbacks.ts. Native
    // maps it to the same per-hook default as `null` everywhere except an AUTH action, where
    // "nobody is here" must block the advance rather than proceed past unauthenticated credentials.
    expect(mockReplies).toEqual([{ callbackId: 'e1:1', resultJson: '{"__appdna_unhandled":true}' }]);
  });

  it('installs exactly one native listener no matter how many hooks register', () => {
    const spy = jest.spyOn(mockModule, 'onHostCallback');
    registerHostCallback('shouldOpen', () => true);
    registerHostCallback('shouldShowMessage', () => true);
    registerHostCallback('onScreenAction', () => true);
    expect(spy).toHaveBeenCalledTimes(1);
    spy.mockRestore();
  });
});

/**
 * The JS half of the `element_interaction_data_context_decode` shared fixture.
 *
 * The decode of `dataContext` (null members kept as removal markers, 0/1 kept as numbers) happens in
 * the native bridge, into the core `ElementInteractionResult.decodeDataContext`. The wrapper's job is to
 * FORWARD the host's map unchanged — a `null` member must cross, or "null removes a key" is impossible
 * for every RN host. An `undefined` member cannot cross JSON, so it is a no-op, not a removal: that is
 * the documented TS contract, pinned here. The reply is READ FROM THE FIXTURE the natives decode.
 */
describe('onElementInteraction reply forwarding', () => {
  const fixture = JSON.parse(
    readFileSync(fixturePath('config_overrides', 'element_interaction_data_context_decode.fixture.json'), 'utf8'),
  ) as { setup: { session_data: { host_interaction_reply: Record<string, unknown> } } };
  const reply = fixture.setup.session_data.host_interaction_reply;

  beforeEach(() => {
    mockReplies.length = 0;
    mockHostCallbackListener = undefined;
    __resetHostCallbacksForTesting();
  });

  it('forwards the host map unchanged, null members included', async () => {
    registerHostCallback('onElementInteraction', () => reply);

    await fireHostCallback('onElementInteraction', { stepId: 'step_eir', blockId: 'show_more', action: 'refresh', value: 'more' });

    const sent = JSON.parse(mockReplies[0].resultJson) as { dataContext: Record<string, unknown> };
    expect(sent).toEqual(reply);
    expect('banner' in sent.dataContext).toBe(true);
    expect(sent.dataContext.banner).toBeNull();
    expect(sent.dataContext.count).toBe(0);
    expect(sent.dataContext.flag).toBe(1);
    expect(sent.dataContext.enabled).toBe(true);
  });

  it('does not forward an undefined member — it is a no-op, not a removal', async () => {
    registerHostCallback('onElementInteraction', () => ({ dataContext: { banner: undefined, label: 'x' } }));

    await fireHostCallback('onElementInteraction', { action: 'refresh' });

    const sent = JSON.parse(mockReplies[0].resultJson) as { dataContext: Record<string, unknown> };
    expect(sent.dataContext).toEqual({ label: 'x' });
    expect('banner' in sent.dataContext).toBe(false);
  });
});

