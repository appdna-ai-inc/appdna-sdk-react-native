import { AppdnaModule, addNativeListener, parseNativeJson } from './nativeModule';

/**
 * The JS half of the host-veto wire protocol.
 *
 * Flutter's `invokeMethod` carries its own private reply port, so a veto correlates for free. React
 * Native's native→JS path is **one-way**: native emits `onHostCallback` with `{callbackId, hook,
 * argsJson}` and waits for an explicit `respondToHostCallback(callbackId, resultJson)`.
 *
 * ## Where the timeout is, and is not
 *
 * The 5s timer lives in **native** (`AppdnaHostCallbacks` on both platforms), never here. A JS
 * `setTimeout` is throttled when the app is backgrounded and destroyed outright by a Metro reload
 * (E5); native would then await forever, and onboarding would hang on a blank step. Native also owns
 * the per-hook default — seven allow, `onPromoCodeSubmit` rejects — so a hook that throws or is
 * unregistered replies with a JSON `null`, meaning "no opinion", and native applies that default.
 *
 * ## The eight hooks
 *
 * Four onboarding hooks return a **map**; the promo hook and the three vetoes return a bare
 * **boolean**. The single `resultJson` envelope preserves the distinction because it is JSON: `null`,
 * `true` and `{"action":"block"}` are all legal top-level values.
 */

/** The eight hooks native can ask about. Adding one here without a native emitter is inert, not a bug. */
export type HostCallbackHook =
  | 'onBeforeStepAdvance'
  | 'onBeforeStepRender'
  | 'onElementInteraction'
  | 'onPermissionRequest'
  | 'onPromoCodeSubmit'
  | 'shouldShowMessage'
  | 'shouldOpen'
  | 'onScreenAction';

/**
 * A hook receives the native argument map and returns the value to send back.
 *
 * `undefined` and `null` both encode as JSON `null` — "no opinion", native applies its default.
 */
export type HostCallbackHandler = (
  args: Record<string, unknown>,
) => unknown | Promise<unknown>;

type HostCallbackEvent = { callbackId: string; hook: string; argsJson: string };

const handlers = new Map<HostCallbackHook, HostCallbackHandler>();

let dispatcherInstalled = false;

/** `"null"` is the wire form of "no opinion". Never a stringified error — native must not parse prose. */
const NO_OPINION = 'null';

function encodeResult(value: unknown): string {
  if (value === undefined || value === null) return NO_OPINION;
  try {
    const json = JSON.stringify(value);
    // `JSON.stringify(() => {})` is `undefined`, not a throw. Treat it as no opinion rather than
    // sending the literal string "undefined", which native's JSON parser would reject.
    return json ?? NO_OPINION;
  } catch {
    return NO_OPINION;
  }
}

/**
 * The reply that means "this host registered NO handler for this hook" — as opposed to "the host
 * looked at it and had no opinion". Native treats both as "apply your default" everywhere except one
 * place, and that place is why the distinction has to exist:
 *
 * Native gates its AUTH actions (`email_login`, `login`, `request_otp`, …) on delegate presence — no
 * delegate means "nobody can sign this user in", so it stays on the step and shows an error. But a
 * WRAPPER always attaches a delegate at `configure()` (native starts emitting during configure, so it
 * must), and that delegate answers `.proceed` when JS has nothing to say. The result: React Native
 * ADVANCED PAST THE CREDENTIAL STEP WITHOUT AUTHENTICATING ANYONE, while native stayed put. The
 * delegate-presence check is a proxy for "will someone handle this", and for wrappers the proxy lies.
 *
 * This sentinel gives the wrapper's forwarder the fact it actually needs.
 */
const UNHANDLED = JSON.stringify({ __appdna_unhandled: true });

async function dispatch(event: HostCallbackEvent): Promise<void> {
  const handler = handlers.get(event.hook as HostCallbackHook);

  let resultJson = handler ? NO_OPINION : UNHANDLED;
  if (handler) {
    try {
      const args = parseNativeJson<Record<string, unknown>>(event.argsJson);
      resultJson = encodeResult(await handler(args));
    } catch (err) {
      // A host hook that throws must not hang the native surface, so it answers NO_OPINION — the
      // wire form of "apply your default".
      //
      // 🔴 This comment used to say native's default is "the conservative answer for each hook —
      // reject for a promo code, allow for the rest". That was WRONG for one hook, and it was the
      // hook that mattered: on an AUTH action (`email_login`, `social_login`, …) native's default is
      // to ADVANCE. So a host whose sign-in call rejected — backend 500, no network, or simply slower
      // than `vetoTimeout` — had the user walked straight past the credential step, unauthenticated.
      // A failing auth backend was free entry.
      //
      // Native now blocks an auth action on ANY non-explicit reply (`AppdnaVetoDecoder.isNoOpinion`),
      // so this line is safe again. It is still the right thing to send: the decision about what a
      // silence MEANS belongs next to the surface that acts on it, not here.
      console.error(`[AppDNA] host callback '${event.hook}' threw; applying the native default.`, err);
      resultJson = NO_OPINION;
    }
  }

  // A reply for an id native already timed out and evicted is dropped there, not here. Guard the Proxy
  // get-trap's synchronous throw: `respondToHostCallback` is a sync void native method, and if the
  // bridge was torn down between subscribing and this reply, that throw would reject this async
  // `dispatch` as an unhandled rejection. A dropped reply is harmless — native applies its own default.
  try {
    AppdnaModule.respondToHostCallback(event.callbackId, resultJson);
  } catch {
    /* bridge gone — native falls back to its default for this hook. */
  }
}

/**
 * Install the dispatcher for native's veto channel. Called from `configure()`, and again (harmlessly)
 * by every `registerHostCallback`.
 *
 * It used to be installed ONLY by the first `registerHostCallback`, i.e. only if the host set a
 * delegate. But native registers its veto forwarders unconditionally during configure, and the
 * onboarding renderer awaits a veto on every step render — so with no delegate, nobody was listening
 * on `onHostCallback` and native waited out the whole timeout before falling back to its default.
 * With the dispatcher always present, an unhandled hook replies immediately with "no opinion", which
 * is what a host that never registered anything means.
 */
export function installHostCallbackDispatcher(): void {
  ensureDispatcher();
}

/** Installed once. Never removed — the module owns its own teardown. */
function ensureDispatcher(): void {
  if (dispatcherInstalled) return;
  // Set the flag only AFTER the subscription succeeds. `addNativeListener` throws on a missing module
  // (the legacy bridge), and flipping the flag first would leave the module believing a dispatcher
  // exists that does not — so every later `registerHostCallback` short-circuits and every native veto
  // waits out the full 5 s timeout. That is precisely the stall this function was added to prevent.
  addNativeListener<HostCallbackEvent>('onHostCallback', (event) => {
    void dispatch(event);
  });
  dispatcherInstalled = true;
}

/** Register (or replace) the handler for one hook. A later `setDelegate` overwrites the earlier one. */
export function registerHostCallback(hook: HostCallbackHook, handler: HostCallbackHandler): void {
  handlers.set(hook, handler);
  ensureDispatcher();
}

/**
 * The veto hooks each delegate slot owns. `setDelegate` must clear its slot's hooks before installing
 * the new delegate's, because the hooks are registered CONDITIONALLY — `if (delegate.onBeforeStepAdvance)`.
 *
 * 🔴 Without this: `onboarding.setDelegate(A)` where A implements `onBeforeStepAdvance`, then a remount
 * calls `setDelegate(B)` where B does NOT — the `if` never fires, B never overwrites the entry, and
 * **A keeps vetoing every step advance from an unmounted screen, forever**. The same for
 * `onPromoCodeSubmit`, `shouldShowMessage`, `shouldOpen` and `onScreenAction`. Replacing a delegate has
 * to mean replacing ALL of it; the event listeners were only half the delegate.
 */
export const HOOKS_BY_DELEGATE_SLOT: Readonly<Record<string, readonly HostCallbackHook[]>> = {
  onboarding: ['onBeforeStepAdvance', 'onBeforeStepRender', 'onElementInteraction', 'onPermissionRequest'],
  paywall: ['onPromoCodeSubmit'],
  inAppMessages: ['shouldShowMessage'],
  screens: ['onScreenAction'],
  deepLinks: ['shouldOpen'],
};

/** Drop every veto hook owned by one delegate slot. Called by `setDelegate` before it installs. */
export function clearHostCallbacksForSlot(slot: string): void {
  for (const hook of HOOKS_BY_DELEGATE_SLOT[slot] ?? []) handlers.delete(hook);
}

/** Drop a hook. Native then applies its per-hook default, exactly as if no host had ever registered. */
export function unregisterHostCallback(hook: HostCallbackHook): void {
  handlers.delete(hook);
}

/**
 * Drop every hook. Called from `shutdown()`.
 *
 * 🔴 `shutdown()` used to drop the delegates' EVENT listeners and leave the veto handlers in place, so
 * a paywall delegate whose `onPromoCodeSubmit` returned `true` kept accepting promo codes after the
 * SDK had been shut down — a dead delegate still approving revenue decisions.
 */
export function clearHostCallbacks(): void {
  handlers.clear();
}

/** Test seam: forget every hook and the dispatcher, so a suite starts from a known state. */
export function __resetHostCallbacksForTesting(): void {
  handlers.clear();
  dispatcherInstalled = false;
}
