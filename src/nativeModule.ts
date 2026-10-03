import { TurboModuleRegistry, type EventSubscription } from 'react-native';
import type { Spec } from './specs/NativeAppdnaModule';
import { clearHostCallbacksForSlot } from './hostCallbacks';

/**
 * The single place the native module is resolved.
 *
 * ## Why `TurboModuleRegistry`, and why no `NativeEventEmitter`
 *
 * D-c makes this package New Architecture only. Under the New Architecture the native side emits
 * through the codegen'd `emitOnX:` methods on `NativeAppdnaModuleSpecBase` (iOS) /
 * `NativeAppdnaModuleSpec` (Android), and JS receives through the `EventEmitter<T>` **properties**
 * on the spec — `AppdnaModule.onPaywallDismissed(listener)`. That path never touches
 * `RCTEventEmitter`, so `new NativeEventEmitter(module).addListener(name, cb)` — what every
 * published version of this package did — subscribes to a channel nothing ever writes to. It does
 * not throw. It simply never fires, which is the worst possible failure mode for an analytics SDK.
 *
 * ## Why lazily
 *
 * `index.ts`, `billing.ts` and `push.ts` each used to resolve the module at MODULE SCOPE. On a
 * runtime without it — Expo Go, RN Web, or an app whose `pod install` never ran — that threw at
 * import, before any host code ran and before any error of ours could be raised. So the module is
 * resolved on first use. A host that imports this package on a platform where it never calls it
 * gets silence, which is the correct behavior.
 */

/** The bare, useless error five published versions of this package produced. */
const DIRECTED_ERROR =
  '[AppDNA] The native module is not available.\n' +
  '  • Expo Go cannot host it — use a development build (npx expo prebuild && npx expo run:ios).\n' +
  '  • Bare RN: run `pod install` (iOS) / rebuild the app (Android) after installing the package.\n' +
  '  • RN Web and Yarn PnP are not supported.\n' +
  '  See https://docs.appdna.ai/sdks/react-native/installation';

/**
 * The module resolves under the legacy bridge too — codegen still exports an `RCT_EXPORT_MODULE`
 * class — but its event emitters are installed only by the TurboModule runtime, so every listener
 * would go dead. Naming that here is the difference between a five-minute fix and a week of "the
 * SDK works but no callback ever fires".
 */
const NEW_ARCH_ERROR =
  '[AppDNA] The AppDNA React Native SDK requires the New Architecture (react-native >= 0.76.9 with\n' +
  '  `newArchEnabled=true`). The native module was found, but its event emitters are missing, which\n' +
  '  means the app is running on the legacy bridge and no SDK callback would ever fire.\n' +
  '  See https://docs.appdna.ai/sdks/react-native/installation';

/** Any emitter property proves the TurboModule runtime installed them. */
const SENTINEL_EMITTER = 'onInitDegraded';

let cached: Spec | undefined;

// 🔴 `hasNativeModule()` LIVED HERE AND HAD ZERO CALLERS — not in the SDK, not in the example, not in
// a test. It was never exported from `index.ts` either, so no host could reach it. A public-looking
// function that nothing calls and nobody can call is not an API; it is a promise the package does not
// keep. `requireNativeModule()` below is the one everything actually uses, and its error message is the
// thing a host needs when the module is missing. Deleted.

/** The native module, or a directed error explaining exactly which runtime you are on. */
export function requireNativeModule(): Spec {
  if (cached) return cached;

  const mod = TurboModuleRegistry.get<Spec>('AppdnaModule');
  if (!mod) throw new Error(DIRECTED_ERROR);

  if (typeof (mod as unknown as Record<string, unknown>)[SENTINEL_EMITTER] !== 'function') {
    throw new Error(NEW_ARCH_ERROR);
  }

  cached = mod;
  return mod;
}

/**
 * A stand-in that raises the directed error on the first METHOD CALL rather than at import.
 * Property reads are what the facade does; each resolves through `requireNativeModule`.
 */
export const AppdnaModule: Spec = new Proxy({} as Spec, {
  get(_target, prop: string) {
    return (requireNativeModule() as unknown as Record<string, unknown>)[prop];
  },
}) as Spec;

/**
 * The events the native side can emit, read off the generated spec so a renamed event is a type
 * error at every call site rather than a listener that never fires.
 *
 * Every event name begins with `on`; so does exactly one method, `onReady`, which is excluded by
 * name. `check:rn-facade-parity` (P6) asserts this set equals `SDK_EVENTS`.
 */
export type AppdnaEventName = Exclude<Extract<keyof Spec, `on${string}`>, 'onReady'>;

/**
 * Subscribe to a native event through the TurboModule's generated emitter property.
 *
 * The emitter is a plain closure on the JSI host object, so calling it unbound is safe. A missing
 * emitter is a codegen/spec drift bug, never a runtime condition — hence the throw.
 */
export function addNativeListener<T>(
  event: AppdnaEventName,
  listener: (payload: T) => void,
): EventSubscription {
  const emitter = (requireNativeModule() as unknown as Record<string, unknown>)[event];
  if (typeof emitter !== 'function') {
    throw new Error(`[AppDNA] the native module has no emitter for '${String(event)}'.`);
  }
  return (emitter as (l: (payload: T) => void) => EventSubscription)(listener);
}

/**
 * Listeners owned by one delegate slot, so a later `setDelegate` REPLACES the earlier one.
 *
 * 🔴 Every `setDelegate` used to call `addNativeListener` and throw the subscription away. There was
 * no unsubscribe path at all. A screen that registers a delegate in a `useEffect` and remounts — a
 * tab switch, a navigation-back, a Fast Refresh — stacked another full set of listeners, and one
 * `onPaywallPurchaseCompleted` then invoked N delegates: N entitlement grants, N analytics events,
 * and the delegates of unmounted screens still live, holding their closures.
 *
 * The keys are delegate slots (one per namespace), not event names: replacing a delegate must drop
 * exactly the listeners that delegate installed, and nothing else.
 */
const delegateSubscriptions = new Map<string, EventSubscription[]>();

/**
 * Install a delegate's listeners, removing whatever the previous delegate in that slot installed.
 * Idempotent by construction: calling it twice leaves one set of listeners, not two.
 */
export function setDelegateListeners(
  slot: string,
  install: () => EventSubscription[],
): void {
  for (const sub of delegateSubscriptions.get(slot) ?? []) {
    sub.remove();
  }
  // The VETO hooks are the other half of the delegate, and they are registered conditionally — so a
  // new delegate that omits an optional hook would never overwrite the old delegate's, and the old one
  // would keep answering vetoes from an unmounted screen.
  clearHostCallbacksForSlot(slot);
  delegateSubscriptions.set(slot, []);

  // If `install` throws part-way — a drifted event name on the 3rd of 11 subscriptions, say — the
  // subscriptions it already made would otherwise be unreachable and unremovable. Keep them as we go.
  const created: EventSubscription[] = [];
  try {
    for (const sub of install()) created.push(sub);
  } catch (error) {
    for (const sub of created) sub.remove();
    delegateSubscriptions.set(slot, []);
    throw error;
  }
  delegateSubscriptions.set(slot, created);
}

/** Drop every delegate's listeners — `shutdown()`, and the test seam below. */
export function removeAllDelegateListeners(): void {
  for (const subs of delegateSubscriptions.values()) {
    for (const sub of subs) sub.remove();
  }
  delegateSubscriptions.clear();
}

/** Test seam: drop the memoised module so a suite can re-resolve against a fresh mock. */
export function __resetNativeModuleForTesting(): void {
  removeAllDelegateListeners();
  cached = undefined;
}

/**
 * Parse a value that crossed the bridge as a JSON string.
 *
 * `getRemoteConfig`, `getFeatureVariant`, `getExperimentConfig`, `getPushToken`,
 * `checkDeferredDeepLink`, `getWebEntitlement` and `getLastInitError` can each return a bool, a
 * number, a string, an array, an object, or null. There is no codegen type for "any JSON value", so
 * native encodes and the facade decodes here — the one place, so a malformed payload has one
 * failure mode instead of seven.
 *
 * A native mapper that cannot encode a value returns the literal `"null"`, never a stringified
 * `description`. So a parse failure means the bridge is broken, not that the value was exotic, and
 * throwing is the correct response.
 */
export function parseNativeJson<T>(json: string): T {
  try {
    return JSON.parse(json) as T;
  } catch {
    // `ES2020` target: `new Error(msg, { cause })` needs ES2022 lib. The received value is the
    // diagnostic that matters; the parse error itself adds nothing.
    throw new Error(
      `[AppDNA] the native module returned a value that is not JSON. This is a bridge bug, not a ` +
        `configuration problem. Received: ${JSON.stringify(json).slice(0, 120)}`,
    );
  }
}
