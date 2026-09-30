import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  NativeModules,
  Platform,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import {
  AppDNA,
  AppDNAScreenSlot,
  AppDNABilling,
  AppDNAPush,
  type AppDNAOptions,
} from '@appdna-ai/react-native-sdk';

/**
 * The SDK key arrives as an initial prop, put there by the native host from a LAUNCH ARGUMENT
 * (see AppDelegate.mm / MainActivity.kt). It is never committed, bundled, or written to disk:
 * this example is force-pushed to a public mirror, so a key stored here is a key published.
 *
 * Without a key the app still renders — it just says so. A demo that crashes on a missing secret
 * teaches nothing about the SDK.
 *
 * ## Why this file is long
 *
 * It is the ONLY executable proof this SDK works on a device. The previous version drove less than
 * half the surface: four of the eight veto hooks were never registered (including `onPromoCodeSubmit`,
 * whose native default is REJECT and which has already shipped as a live production defect), billing
 * was never called ONCE, `shutdown()` was never called — so the shutdown→configure teardown path, which
 * four separate code comments claim to have fixed four separate bugs in, had never run on a device —
 * and screens, push, experiments, consent and remote-config priming were all untouched. A device pass
 * that greens on half the surface is a device pass that means half as much as it claims.
 *
 * So: every button below drives one API, every callback appends to the on-screen log, and the log is
 * what a human (or an agentic device driver) reads back. A hook that never fires is visible as an
 * ABSENCE of a line, not inferred from an await that resolved.
 *
 * `__tests__/exampleSurfaceCoverage.test.ts` enumerates the facade at runtime and fails if a public
 * method is not driven from here, so the next API added cannot quietly go unexercised.
 */
type Props = {
  apiKey?: string;
  /** Real content ids, injected as launch args — see AppDelegate.mm. Never committed. */
  onboardingId?: string;
  paywallId?: string;
  paywall2Id?: string;
  surveyId?: string;
  /** The event that triggers an in-app message in this app's console config. */
  messageEvent?: string;
  /** A store product id configured in App Store Connect / Play Console (billing section). */
  productId?: string;
  /** A server-driven screen id, and a multi-screen flow id. */
  screenId?: string;
  screenFlowId?: string;
  /** The console slot name the inline `<AppDNAScreenSlot>` renders. */
  slotName?: string;
  /** An experiment id and one of its variant ids. */
  experimentId?: string;
  experimentVariantId?: string;
  /** A paywall placement name, for `paywall.presentByPlacement`. */
  placement?: string;
  /**
   * SPEC-496 device pass — `items` makes `onBeforeStepRender` hand every step a sample
   * `dataContext` (`hook_data.recommendations`, three items); `empty` hands it an empty list, so the
   * empty-text / required-select path can be exercised. `showmore` is the SPEC-496 §5b paging host for
   * "Show more" (`refresh_step`): page 1 is a–d, and every `refresh` interaction answers — after 1.5 s, so
   * the spinner shows — with the list so far plus the next four (accumulate, at most 20). Unset → no
   * override, as before.
   */
  hostDataDemo?: string;
  /**
   * SPEC-497 §4.10 — the sign-in timeout floor device rows (all optional; unset = no delay):
   * `signInDelaySeconds` — `onBeforeStepAdvance` waits n s for the FIRST sign-in action of each
   * presentation, then answers `proceed` (later attempts in that presentation proceed at once);
   * `vetoTimeout` — passed to `AppDNAOptions.vetoTimeout`; `stepAdvanceDelaySeconds` +
   * `stepAdvanceReply` (`proceed` | `stay`) — for a NON-sign-in step, wait n s, then answer.
   */
  signInDelaySeconds?: string;
  vetoTimeout?: string;
  stepAdvanceDelaySeconds?: string;
  stepAdvanceReply?: string;
  /**
   * SPEC-497 §3.11 / §13h — the billing, local-server and location device rows:
   * `billingProvider` — `storeKit2` (default) | `revenueCat` | `none` | `adapty:<publicKey>`;
   * `appdnaEnv` — `sandbox`, emitted natively only when the build carries the base-URL override
   * (never a hand-passed argument); `hostProductId` — what "Host buy (no finish)" buys (iOS);
   * `locationFlowId` — the "Location flow" button's flow (`APPDNA_E2E_LOCATION_FLOW_ID`).
   */
  billingProvider?: string;
  appdnaEnv?: string;
  hostProductId?: string;
  locationFlowId?: string;
  /** A slow endpoint for `hostWait` (e.g. a local server's delay route); defaults to a 1 s delay URL. */
  waitUrl?: string;
};

/** `billingProvider` launch value → the option; unknown → the SDK default. */
const billingProviderOption = (raw?: string): AppDNAOptions['billingProvider'] | undefined => {
  if (raw === 'storeKit2' || raw === 'revenueCat' || raw === 'none') return raw;
  if (raw?.startsWith('adapty:')) return { type: 'adapty', apiKey: raw.slice('adapty:'.length) };
  return undefined;
};

/**
 * SPEC-497 §3.11 — the iOS host's OWN StoreKit calls (`AppdnaE2EHost.m`): a purchase the host never
 * finishes, and the `AppDNA-E2E unfinished=<ids>` log. Absent on Android (its ownership rows run on the
 * native sample host).
 */
const e2eHost: { hostBuy(productId: string): Promise<string>; logTransactions(): Promise<null> } | undefined =
  Platform.OS === 'ios' ? NativeModules.AppdnaE2EHost : undefined;

/** The sign-in actions a host must answer (the SDK's own list, SPEC-497 §4.2) — picks which delay applies. */
const SIGN_IN_ACTIONS = new Set([
  'social_login', 'login', 'register', 'reset_password', 'magic_link', 'verify_email',
  'resend_verification', 'enable_biometric', 'email_login', 'request_otp', 'verify_otp',
  'logout', 'change_password', 'set_new_password', 'delete_account', 'update_profile',
]);

/**
 * Wait `seconds` WITHOUT `setTimeout`: RN Android pauses JS timers while the SDK's onboarding Activity
 * is in front, so a timer-based wait never fires there. A clock loop over awaited `fetch` calls keeps
 * the JS thread yielding (each `fetch` settles through native networking, which is not paused), and a
 * failed fetch (offline) just loops again.
 */
const hostWait = async (seconds: number, waitUrl = 'https://httpbin.org/delay/1') => {
  const end = Date.now() + seconds * 1000;
  while (Date.now() < end) {
    try {
      // A SLOW endpoint (≈1 s per request), so this is a handful of requests, not a hammer.
      await fetch(waitUrl, { method: 'GET' });
    } catch {
      // Offline or refused: back off with a short clock spin before the next attempt, so a fast
      // failure cannot turn into a tight request loop.
      const until = Date.now() + 250;
      while (Date.now() < until) {
        // spin
      }
    }
  }
};

/** A synthetic AppDNA-marked push (SPEC-497 §9.2) for the forwarding API buttons. */
const SAMPLE_PUSH = {
  appdna: '1',
  push_id: 'rn_e2e_push',
  action: { type: 'deep_link', value: 'appdnaexample://push' },
};

/** SPEC-496 — sample host data for the `hostDataDemo` launch arg. Public placeholder images only. */
const HOST_DATA_DEMO_ITEMS = [
  { id: 'w1', name: 'Maple Farm', subtitle: 'Toronto', imageUrl: 'https://picsum.photos/seed/w1/400/300' },
  { id: 'w2', name: 'Oak Hall', subtitle: 'Nashville', imageUrl: 'https://picsum.photos/seed/w2/400/300' },
  { id: 'w3', name: 'Birch Mill', subtitle: 'Dublin', imageUrl: 'https://picsum.photos/seed/w3/400/300' },
];

/** SPEC-496 §5b — the `showmore` host's list: `a`, `b`, … with the page each arrived on as its subtitle. */
const showMoreItems = (count: number) =>
  Array.from({ length: count }, (_, i) => {
    const id = String.fromCharCode(97 + i);
    return {
      id,
      name: `Venue ${id.toUpperCase()}`,
      subtitle: `page ${Math.floor(i / 4) + 1}`,
      imageUrl: `https://picsum.photos/seed/p1b-${id}/400/300`,
    };
  });

/** SPEC-496 §5b — the `showmore` host's stand-in for backend latency (see the call site). */
const demoLatency = (ms: number) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    // spin — see the call site for why this is not setTimeout
  }
};

/** Every option native parses, set explicitly — the defaults path was the only one ever exercised. */
const OPTIONS: AppDNAOptions = {
  flushInterval: 15,
  batchSize: 10,
  configTTL: 600,
  logLevel: 'debug',
  vetoTimeout: 3,
  billingProvider: 'storeKit2',
  // Deliberately FALSE. `requireConsent: true` holds every event — including `sdk_initialized` — until
  // `setConsent(true)`, which would make an unattended device pass look like a dead SDK. The consent
  // APIs themselves are driven by the Consent buttons below.
  requireConsent: false,
};

export default function App({
  apiKey,
  onboardingId,
  paywallId,
  paywall2Id,
  surveyId,
  messageEvent,
  productId,
  screenId,
  screenFlowId,
  slotName,
  experimentId,
  experimentVariantId,
  placement,
  hostDataDemo,
  signInDelaySeconds,
  vetoTimeout,
  stepAdvanceDelaySeconds,
  stepAdvanceReply,
  billingProvider,
  appdnaEnv,
  hostProductId,
  locationFlowId,
  waitUrl,
}: Props) {
  const [status, setStatus] = useState(apiKey ? 'Configuring…' : 'No API key — pass -appdnaApiKey');
  const [log, setLog] = useState<string[]>([]);
  const [diagnostics, setDiagnostics] = useState('');
  const [slot, setSlot] = useState(slotName ?? 'home_banner');
  const [suppressed, setSuppressed] = useState(false);
  // Unsubscribers from the standalone listener helpers, so the shutdown button can prove they detach.
  const subscriptions = useRef<Array<() => void>>([]);
  // `showmore` host: pages shown so far, per step. A revisit answers with the list the user last saw.
  const showMorePages = useRef<Record<string, number>>({});
  // SPEC-497 §4.10 — whether this presentation's first sign-in action has been delayed yet.
  const signInDelayed = useRef(false);

  // The log is what the device pass reads back: every delegate callback and every veto appends a
  // line, so a hook that never fires is visible as an ABSENCE, not inferred from a passing await.
  const append = useCallback((line: string) => {
    setLog((prev) => [...prev.slice(-120), line]);
  }, []);

  /** Run an API call, log what it returned, and log a failure rather than swallowing it. */
  const run = useCallback(
    async (label: string, fn: () => Promise<unknown> | unknown) => {
      try {
        const result = await fn();
        append(`${label} → ${result === undefined ? 'ok' : JSON.stringify(result)}`);
      } catch (e) {
        append(`${label} ✗ ${String(e)}`);
      }
    },
    [append],
  );

  const registerDelegates = useCallback(() => {
    // Delegates BEFORE configure: native starts emitting during configure, and a delegate
    // registered after it silently misses the opening events.
    AppDNA.onboarding.setDelegate({
      onOnboardingStarted: (flowId) => {
        signInDelayed.current = false;
        append(`onboarding started: ${flowId}`);
      },
      onOnboardingStepChanged: (_f, stepId, i, total) =>
        append(`step ${i + 1}/${total}: ${stepId}`),
      onOnboardingCompleted: (flowId, responses) =>
        append(`onboarding completed: ${flowId} — ${Object.keys(responses).length} responses`),
      onOnboardingDismissed: (flowId, atStep) =>
        append(`onboarding dismissed: ${flowId} @ ${atStep}`),
      onPermissionResult: (_f, _s, type, granted) =>
        append(`permission ${type}: ${granted ? 'granted' : 'denied'}`),

      // ── The four onboarding VETOES. Native BLOCKS the step until each resolves (or the timeout
      // fires and the hook's own default applies), which is why they cannot ride the event channel.
      // Three of them were never registered by this example, so their native round trip had never
      // once run on a device.
      onBeforeStepAdvance: async (_flowId, fromStepId, _index, _type, _responses, stepData) => {
        const action = typeof stepData?.action === 'string' ? stepData.action : undefined;
        if (action && SIGN_IN_ACTIONS.has(action)) {
          const delay = Number(signInDelaySeconds);
          if (delay > 0 && !signInDelayed.current) {
            signInDelayed.current = true;
            append(`veto onBeforeStepAdvance(${fromStepId}, ${action}) — signing in for ${delay}s`);
            await hostWait(delay, waitUrl);
          }
          append(`veto onBeforeStepAdvance(${fromStepId}, ${action}) → proceed`);
          return { type: 'proceed' };
        }
        const delay = Number(stepAdvanceDelaySeconds);
        if (delay > 0) {
          const reply = stepAdvanceReply === 'stay' ? 'stay' : 'proceed';
          append(`veto onBeforeStepAdvance(${fromStepId}) — waiting ${delay}s`);
          await hostWait(delay, waitUrl);
          append(`veto onBeforeStepAdvance(${fromStepId}) → ${reply}`);
          return { type: reply };
        }
        append(`veto onBeforeStepAdvance(${fromStepId}) → proceed`);
        return { type: 'proceed' };
      },
      // `null` = "no config override" — the step renders as the console published it. The LOG LINE is
      // the proof the hook ran; returning an override here would mutate every step of every flow this
      // example is pointed at, which is a worse default for a demo than a visible log.
      onBeforeStepRender: async (_flowId, stepId) => {
        // SPEC-497 §13h D2-1 — the location device row reads the stored answer on the step after it.
        if (locationFlowId && stepId === 'step_after') {
          const loc = await AppDNA.getLocationData('e2e_location');
          const line = `AppDNA-E2E location ${loc === null ? 'null' : JSON.stringify(loc)}`;
          console.log(line);
          append(line);
        }
        // SPEC-496 — opt-in via the `appdnaHostDataDemo` launch arg only, so the default stays "no
        // override". The payload is what a host's `{{hook_data.recommendations}}` repeat reads.
        if (hostDataDemo === 'showmore') {
          const recommendations = showMoreItems(4 * (showMorePages.current[stepId] ?? 1));
          append(`veto onBeforeStepRender(${stepId}) → dataContext: ${recommendations.length} recommendation(s)`);
          return { dataContext: { recommendations } };
        }
        if (hostDataDemo === 'items' || hostDataDemo === 'empty') {
          const recommendations = hostDataDemo === 'items' ? HOST_DATA_DEMO_ITEMS : [];
          append(`veto onBeforeStepRender(${stepId}) → dataContext: ${recommendations.length} recommendation(s)`);
          return { dataContext: { recommendations } };
        }
        append(`veto onBeforeStepRender(${stepId}) → no override`);
        return null;
      },
      // `null` = "no field patches, do not advance" — the interaction proceeds natively.
      onElementInteraction: async (_flowId, stepId, blockId, action, value) => {
        if (hostDataDemo === 'showmore' && action === 'refresh') {
          append(`veto onElementInteraction(${stepId}/${blockId}, ${action}, value=${value ?? '<none>'}) → loading`);
          // Slow enough that the tapped button's spinner is visible. Deliberately NOT `setTimeout`: React
          // Native on Android pauses JS timers while its Activity is paused, and the SDK's onboarding
          // Activity is in front during a flow — a timer-based delay would not resolve until the flow
          // closes, so the SDK's 8 s deadline would drop the reply. A real host awaits its network call,
          // which is not paused; this demo stands in for that with a clock spin.
          demoLatency(1500);
          const pages = Math.min((showMorePages.current[stepId] ?? 1) + 1, 5);
          showMorePages.current[stepId] = pages;
          const recommendations = showMoreItems(4 * pages);
          append(`onElementInteraction(${stepId}/${blockId}) → dataContext: ${recommendations.length} recommendation(s)`);
          // The WHOLE list so far (accumulate), under the key the Select repeats over.
          return { dataContext: { recommendations }, advance: false };
        }
        append(`veto onElementInteraction(${stepId}/${blockId}, ${action}, value=${value ?? '<none>'}) → no patch`);
        return null;
      },
      // `{type:'proceed'}` = run the native OS prompt. (`{type:'handledByHost', granted}` would
      // short-circuit it — the branch a host takes when it owns its own permission UI.)
      onPermissionRequest: async (permissionType) => {
        append(`veto onPermissionRequest(${permissionType}) → proceed (native prompt)`);
        return { type: 'proceed' };
      },
    });

    AppDNA.screens.setDelegate({
      onScreenPresented: (id) => append(`screen presented: ${id}`),
      onScreenDismissed: (id) => append(`screen dismissed: ${id}`),
      onFlowCompleted: (id) => append(`flow completed: ${id}`),
      onScreenAction: async (screenIdArg, action) => {
        append(`veto onScreenAction(${screenIdArg}, ${String(action.type)}) → allow`);
        return true;
      },
    });

    // ALL NINE delegates. Each callback appends a line, so a delegate that native never invokes is
    // visible as a missing line rather than inferred from a passing await. Three of these were
    // silently dead on Android until this pass: lifecycle, web-entitlement, and config-changed.
    AppDNA.paywall.setDelegate({
      onPaywallPresented: (id) => append(`paywall presented: ${id}`),
      onPaywallDismissed: (id) => append(`paywall dismissed: ${id}`),
      onPaywallAction: (id, action) => append(`paywall action: ${id} / ${action}`),
      onPaywallPurchaseCompleted: (id, product) => append(`paywall purchase: ${id} / ${product}`),
      onPaywallPurchaseFailed: (id, error, errorType, productId) => {
        // SPEC-497 §3.11 — the exact line the device rows grep (host log / logcat).
        console.log(`AppDNA-E2E onPaywallPurchaseFailed ${errorType} ${productId}`);
        append(`paywall purchase failed: ${id} / ${errorType} / ${productId} / ${String(error)}`);
      },
      // 🔴 This read the FIRST parameter and called it `products`. The signature is
      // `(paywallId: string, restoredProductIds: string[])`, so `products` was the paywall ID and
      // `.length` was its CHARACTER COUNT: restoring zero products logged "paywall restore: 24
      // product(s)". TypeScript cannot catch it — `.length` is valid on a string — and the example is
      // the code customers copy. Worse, it is the log an agentic or human device pass READS, so the
      // pass would have recorded a successful restore of N products that never happened.
      onPaywallRestoreCompleted: (paywallId, restoredProductIds) =>
        append(`paywall restore (${paywallId}): ${restoredProductIds.length} product(s)`),
      onPaywallPurchaseStarted: (id, product) => {
        console.log(`AppDNA-E2E onPaywallPurchaseStarted ${product}`);
        append(`paywall purchase started: ${id} / ${product}`);
      },
      onPaywallRestoreStarted: (id) => append(`paywall restore started: ${id}`),
      onPaywallRestoreFailed: (id, error) => append(`paywall restore failed: ${id} / ${String(error)}`),
      onPostPurchaseDeepLink: (url) => append(`post-purchase deep link: ${url}`),
      onPostPurchaseNextStep: (step) => append(`post-purchase next step: ${step}`),
      // 🔴 The one veto whose native default is REJECT — and the most recent live production defect
      // (the renderer's no-delegate fallback accepted ANY non-blank code and folded it into the
      // purchase metadata as "validated"). Unregistered, this example could never have caught it.
      // Accepting only the literal code below, so a device pass sees BOTH branches.
      onPromoCodeSubmit: async (id, code) => {
        const accepted = code.trim().toUpperCase() === 'APPDNA';
        append(`veto onPromoCodeSubmit(${id}, "${code}") → ${accepted ? 'accept' : 'REJECT'}`);
        return accepted;
      },
    });

    AppDNA.surveys.setDelegate({
      onSurveyPresented: (id) => append(`survey presented: ${id}`),
      onSurveyCompleted: (id, responses) => append(`survey completed: ${id} — ${responses.length} response(s)`),
      onSurveyDismissed: (id) => append(`survey dismissed: ${id}`),
    });

    AppDNA.inAppMessages.setDelegate({
      onMessageShown: (id, trigger) => append(`message shown: ${id} (trigger=${trigger})`),
      onMessageAction: (id, action) => append(`message action: ${id} / ${action}`),
      onMessageDismissed: (id) => append(`message dismissed: ${id}`),
      // A VETO — native awaits this before showing. Allowing, and logging that we were asked.
      shouldShowMessage: (id) => { append(`veto shouldShowMessage(${id}) → allow`); return true; },
    });

    AppDNA.push.setDelegate({
      onPushTokenRegistered: (token) => append(`push token: ${token.slice(0, 12)}…`),
      onPushReceived: (_payload, inForeground) => append(`push received (foreground=${inForeground})`),
      onPushTapped: () => append('push tapped'),
    });

    AppDNA.deepLinks.setDelegate({
      onDeepLinkReceived: (url) => append(`deep link: ${url}`),
      shouldOpen: (url) => { append(`veto shouldOpen(${url}) → allow`); return true; },
    });

    AppDNA.lifecycle.setDelegate({
      onSdkRuntimeLocked: (reason) => append(`SDK LOCKED: ${reason}`),
      onSdkRuntimeUnlocked: () => append('SDK unlocked'),
    });

    AppDNABilling.setDelegate({
      onPurchaseCompleted: (product) => append(`purchase completed: ${product}`),
      onPurchaseFailed: (product, error) => append(`purchase failed: ${product} / ${error}`),
      onEntitlementsChanged: (ents) => append(`entitlements changed: ${ents.length}`),
      onRestoreCompleted: (products) => append(`restore completed: ${products.length}`),
      onBillingUnavailable: () => append('billing unavailable (Android)'),
    });

    // Standalone listener helpers — a separate code path from the delegates above, and the one that
    // starts the native entitlement observer on first subscribe. Their unsubscribers are kept so the
    // shutdown button can detach them.
    subscriptions.current.forEach((off) => off());
    subscriptions.current = [
      AppDNA.onWebEntitlementChanged((e) => append(`web entitlement: ${e ? e.status : 'none'}`)),
      AppDNA.remoteConfig.onChanged(() => append('remote config changed')),
      AppDNA.features.onChanged(() => append('feature flags changed')),
      AppDNA.onInitDegraded((e) => append(`init degraded: ${e.type}: ${e.message}`)),
      AppDNA.billing.onEntitlementsChanged((ents) => append(`billing.onEntitlementsChanged: ${ents.length}`)),
      AppDNABilling.onEntitlementsChanged((ents) => append(`AppDNABilling.onEntitlementsChanged: ${ents.length}`)),
      AppDNAPush.onPushReceived((_p, inForeground) => append(`AppDNAPush.onPushReceived (fg=${inForeground})`)),
      AppDNAPush.onPushTapped((_p, actionId) => append(`AppDNAPush.onPushTapped (${actionId ?? 'default'})`)),
    ];
  }, [append, hostDataDemo, signInDelaySeconds, stepAdvanceDelaySeconds, stepAdvanceReply, locationFlowId, waitUrl]);

  const boot = useCallback(async () => {
    if (!apiKey) return;
    registerDelegates();

    const veto = Number(vetoTimeout);
    const provider = billingProviderOption(billingProvider);
    // `appdnaEnv` is `sandbox` only when the native host found the base-URL override (local-server runs).
    await AppDNA.configure(apiKey, appdnaEnv === 'sandbox' ? 'sandbox' : 'production', {
      ...OPTIONS,
      ...(veto > 0 ? { vetoTimeout: veto } : {}),
      ...(provider ? { billingProvider: provider } : {}),
    });
    append(`configured env=${appdnaEnv === 'sandbox' ? 'sandbox' : 'production'} billingProvider=${billingProvider ?? 'default'}`);
    setStatus('Configured');

    await AppDNA.onReady();
    setStatus('Ready');

    await AppDNA.identify('rn_e2e_user', { plan: 'demo' });
    append('identified rn_e2e_user');

    // W16 — the synchronous config cache. Priming it is what makes `getCached()` a real read rather
    // than `undefined`, and it is also what `shutdown()` has to tear down.
    await AppDNA.remoteConfig.primeSnapshot();
    append(`remoteConfig.hasSnapshot() → ${AppDNA.remoteConfig.hasSnapshot()}`);

    // Announce the visible screen: every subsequent event carries it as `context.screen`.
    AppDNA.notifyScreenAppeared('rn_example_home');

    // If init degraded, the SDK knows why — ask it rather than guessing from the outside.
    const initError = await AppDNA.getLastInitError();
    append(`getLastInitError → ${initError ? `${initError.type}: ${initError.message}` : 'none'}`);
    append(`getSdkVersion → ${await AppDNA.getSdkVersion()}`);

    // The framework tag and the wrapper version are injected by native, never by this host —
    // diagnose() is where you see what it actually reported.
    setDiagnostics(await AppDNA.diagnose());
  }, [apiKey, append, registerDelegates]);

  useEffect(() => {
    boot().catch((e) => setStatus(`Failed: ${String(e)}`));
    // Deliberately runs once per `boot` identity. The Reconfigure button drives the second cycle.
  }, [boot]);

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.title}>AppDNA SDK Example</Text>

        <InfoCard title="SDK Status" value={status} />

        {/* An inline server-driven screen. It reserves its last-known height, so mounting it must
            not shift the layout below — that is the visible half of the W19 fix. The name can be
            SWITCHED at runtime: a live `name` change is its own code path (the height cache must not
            carry the previous slot's height into the new one). */}
        <Text style={styles.sectionTitle}>Inline screen slot — {slot}</Text>
        <AppDNAScreenSlot
          name={slot}
          minHeight={80}
          style={styles.slot}
          onContentSizeChange={({ width, height }) =>
            append(`slot ${slot} measured: ${Math.round(width)}×${Math.round(height)}`)
          }
        />
        <Section title="Screen slot">
          <Button
            label="Switch slot name"
            onPress={() => {
              const next = slot === (slotName ?? 'home_banner') ? 'rn_e2e_other_slot' : (slotName ?? 'home_banner');
              setSlot(next);
              append(`slot name → ${next}`);
            }}
          />
        </Section>

        <Section title="Core">
          <Button label="Track event" onPress={() => { AppDNA.track('rn_e2e_button', { source: 'example' }); append('track(rn_e2e_button) queued'); }} />
          <Button label="Flush now" onPress={() => run('flush()', () => AppDNA.flush())} />
          <Button label="Identify" onPress={() => run('identify()', () => AppDNA.identify('rn_e2e_user', { plan: 'demo', tier: 2 }))} />
          <Button label="Get user traits" onPress={() => run('getUserTraits()', () => AppDNA.getUserTraits())} />
          <Button label="Notify screen appeared" onPress={() => { AppDNA.notifyScreenAppeared('rn_example_manual'); append('notifyScreenAppeared(rn_example_manual)'); }} />
          <Button label="Set log level (info)" onPress={() => { AppDNA.setLogLevel('info'); append('setLogLevel(info)'); }} />
          <Button label="Deferred deep link" onPress={() => run('checkDeferredDeepLink()', () => AppDNA.checkDeferredDeepLink())} />
          <Button label="Handle deep link" onPress={() => run('deepLinks.handleURL()', () => AppDNA.deepLinks.handleURL('appdna://rn-example/home?src=e2e'))} />
          <Button label="Web entitlement" onPress={() => run('getWebEntitlement()', () => AppDNA.getWebEntitlement())} />
          <Button label="Refresh diagnose()" onPress={async () => setDiagnostics(await AppDNA.diagnose())} />
          {/* `reset()` clears the identity — run it late, or every event after it is anonymous. */}
          <Button label="Reset identity" onPress={() => run('reset()', () => AppDNA.reset())} />
        </Section>

        <Section title="Onboarding">
          <Button
            label="Location flow"
            onPress={() => run('onboarding.present(location)', () => AppDNA.onboarding.present(locationFlowId ?? 'default'))}
          />
          <Button
            label="Host buy (no finish, iOS)"
            onPress={() => run('hostBuy()', () => e2eHost?.hostBuy(hostProductId ?? 'ai.appdna.test.monthly') ?? 'iOS-only')}
          />
          <Button
            label="Log unfinished transactions (iOS)"
            onPress={() => run('logTransactions()', () => e2eHost?.logTransactions() ?? 'iOS-only')}
          />
          <Button
            label="Present onboarding"
            onPress={() => run('onboarding.present()', () => AppDNA.onboarding.present(onboardingId ?? 'default'))}
          />
          <Button
            label="Get location data"
            onPress={() => run('getLocationData()', () => AppDNA.getLocationData('location'))}
          />
        </Section>

        <Section title="Paywalls">
          <Button label="Present paywall" onPress={() => run('paywall.present()', () => AppDNA.paywall.present(paywallId ?? 'default', { placement: 'rn_example', experiment: experimentId, variant: experimentVariantId }))} />
          <Button label="Present paywall 2" onPress={() => run('paywall.present(2)', () => AppDNA.paywall.present(paywall2Id ?? 'default'))} />
          <Button label="Present by placement" onPress={() => run('paywall.presentByPlacement()', () => AppDNA.paywall.presentByPlacement(placement ?? 'rn_example', { customData: { from: 'example' } }))} />
          <Text style={styles.hint}>
            Promo code: the host veto accepts only “APPDNA”. Type anything else and it must be REJECTED —
            that branch is a live production defect elsewhere.
          </Text>
        </Section>

        <Section title="Survey & in-app message">
          <Button label="Show survey" onPress={() => run('surveys.present()', () => AppDNA.surveys.present(surveyId ?? 'default'))} />
          <Button
            label="Trigger in-app message"
            onPress={() => {
              // In-app messages are TRIGGERED by an event, not presented by id — the console binds a
              // message to an event name. Tracking it is the only way a host asks for one.
              AppDNA.track(messageEvent ?? 'rn_e2e_message_trigger');
              append(`track(${messageEvent ?? 'rn_e2e_message_trigger'}) — awaiting message`);
            }}
          />
          <Button
            label={suppressed ? 'Un-suppress messages' : 'Suppress messages'}
            onPress={() => {
              const next = !suppressed;
              setSuppressed(next);
              AppDNA.inAppMessages.suppressDisplay(next);
              append(`inAppMessages.suppressDisplay(${next})`);
            }}
          />
          {/*
            N5 (ADR-002) — forced theme. ANDROID-ONLY: iOS has no ForcedTheme and answers as a
            documented no-op, so on iOS this button will log `null` back. That asymmetry is the point
            of showing it: a host should see what the API actually does on each platform rather than
            discover the no-op in production. Reading the value back is what makes it a demonstration
            and not a claim.
          */}
          <Button
            label="Force dark theme (Android)"
            onPress={async () => {
              await AppDNA.setForcedTheme('dark');
              const readBack = await AppDNA.getForcedTheme();
              append(`setForcedTheme('dark') → getForcedTheme() = ${String(readBack)}`);
            }}
          />
          <Button
            label="Clear forced theme"
            onPress={async () => {
              await AppDNA.setForcedTheme(null);
              const readBack = await AppDNA.getForcedTheme();
              append(`setForcedTheme(null) → getForcedTheme() = ${String(readBack)}`);
            }}
          />
        </Section>

        <Section title="Screens">
          <Button label="Show screen" onPress={() => run('screens.show()', () => AppDNA.screens.show(screenId ?? 'default'))} />
          <Button label="Show flow" onPress={() => run('screens.showFlow()', () => AppDNA.screens.showFlow(screenFlowId ?? 'default'))} />
          <Button label="Dismiss screen" onPress={() => run('screens.dismiss()', () => AppDNA.screens.dismiss())} />
          <Button
            label="Preview screen from JSON"
            onPress={() =>
              // 🔴 This sent `blocks`. `ScreenConfig` has `sections` — there is no `blocks` key on
              // either platform. Every ScreenConfig field is optional, so decoding SUCCEEDED,
              // `previewScreen` returned TRUE, and then the presenter found `sections = []` and bailed
              // silently. The log said `screens.preview() → true` and nothing appeared: the only
              // executable proof of `previewScreen` was a false positive, and the example taught a
              // payload shape that renders a blank screen.
              run('screens.preview()', () =>
                AppDNA.screens.preview(
                  JSON.stringify({
                    id: 'rn_preview',
                    sections: [
                      {
                        type: 'text',
                        data: { text: 'Previewed from JSON — no console round trip.' },
                      },
                    ],
                  }),
                ),
              )
            }
          />
          <Button label="Enable nav interception" onPress={() => run('screens.enableNavigationInterception()', () => AppDNA.screens.enableNavigationInterception([screenId ?? 'default']))} />
          <Button label="Disable nav interception" onPress={() => run('screens.disableNavigationInterception()', () => AppDNA.screens.disableNavigationInterception())} />
        </Section>

        <Section title="Billing">
          <Button label="Get products" onPress={() => run('billing.getProducts()', () => AppDNA.billing.getProducts([productId ?? 'rn_e2e_product']))} />
          <Button label="Purchase" onPress={() => run('billing.purchase()', () => AppDNA.billing.purchase(productId ?? 'rn_e2e_product'))} />
          <Button label="Restore purchases" onPress={() => run('billing.restorePurchases()', () => AppDNA.billing.restorePurchases())} />
          <Button label="Get entitlements" onPress={() => run('billing.getEntitlements()', () => AppDNA.billing.getEntitlements())} />
          <Button label="Has active subscription" onPress={() => run('billing.hasActiveSubscription()', () => AppDNA.billing.hasActiveSubscription())} />
          {/* The static facade, exercised separately: `AppDNABilling.*` and `AppDNA.billing.*` are the
              same implementation now, but they are two documented import paths and both must work. */}
          <Button label="AppDNABilling.getProducts" onPress={() => run('AppDNABilling.getProducts()', () => AppDNABilling.getProducts([productId ?? 'rn_e2e_product']))} />
          <Button label="AppDNABilling.purchase" onPress={() => run('AppDNABilling.purchase()', () => AppDNABilling.purchase(productId ?? 'rn_e2e_product'))} />
          <Button label="AppDNABilling.restore" onPress={() => run('AppDNABilling.restorePurchases()', () => AppDNABilling.restorePurchases())} />
          <Button label="AppDNABilling.entitlements" onPress={() => run('AppDNABilling.getEntitlements()', () => AppDNABilling.getEntitlements())} />
          <Button label="AppDNABilling.hasActiveSub" onPress={() => run('AppDNABilling.hasActiveSubscription()', () => AppDNABilling.hasActiveSubscription())} />
          {/* Re-register through the namespace. It must REPLACE the delegate, not stack a second one:
              stacking is a shipped bug (one `onPurchaseCompleted` invoking every delegate ever
              registered = N entitlement grants for one buy). Press it, then purchase: exactly ONE
              `purchase completed` line must appear. */}
          <Button
            label="Re-register billing delegate"
            onPress={() => {
              AppDNA.billing.setDelegate({
                onPurchaseCompleted: (product) => append(`purchase completed: ${product}`),
                onPurchaseFailed: (product, error) => append(`purchase failed: ${product} / ${error}`),
                onEntitlementsChanged: (ents) => append(`entitlements changed: ${ents.length}`),
                onRestoreCompleted: (products) => append(`restore completed: ${products.length}`),
                onBillingUnavailable: () => append('billing unavailable (Android)'),
              });
              append('billing.setDelegate() re-registered (must replace, not stack)');
            }}
          />
        </Section>

        <Section title="Push">
          <Button label="Request permission" onPress={() => run('push.requestPermission()', () => AppDNA.push.requestPermission())} />
          <Button label="AppDNAPush.requestPermission" onPress={() => run('AppDNAPush.requestPermission()', () => AppDNAPush.requestPermission())} />
          <Button label="Get token" onPress={() => run('push.getToken()', () => AppDNA.push.getToken())} />
          <Button label="Set token" onPress={() => run('push.setToken()', () => AppDNA.push.setToken('rn_e2e_fake_token'))} />
          <Button label="Set permission (true)" onPress={() => run('push.setPermission()', () => AppDNA.push.setPermission(true))} />
          <Button label="Track delivered" onPress={() => run('push.trackDelivered()', () => AppDNA.push.trackDelivered('rn_e2e_push'))} />
          <Button label="Track tapped" onPress={() => run('push.trackTapped()', () => AppDNA.push.trackTapped('rn_e2e_push', 'open'))} />
          <Button label="Is AppDNA message" onPress={() => run('push.isAppDNAMessage()', () => AppDNA.push.isAppDNAMessage(SAMPLE_PUSH))} />
          <Button label="Forward message" onPress={() => run('push.handleMessage()', () => AppDNA.push.handleMessage(SAMPLE_PUSH))} />
          <Button label="Forward tap" onPress={() => run('push.handleTap()', () => AppDNA.push.handleTap(SAMPLE_PUSH))} />
        </Section>

        <Section title="Experiments & flags">
          <Button label="Experiment variant" onPress={() => run('experiments.getVariant()', () => AppDNA.experiments.getVariant(experimentId ?? 'rn_e2e_experiment'))} />
          <Button label="Is in variant" onPress={() => run('experiments.isInVariant()', () => AppDNA.experiments.isInVariant(experimentId ?? 'rn_e2e_experiment', experimentVariantId ?? 'control'))} />
          <Button label="Experiment exposures" onPress={() => run('experiments.getExposures()', () => AppDNA.experiments.getExposures())} />
          <Button label="Experiment config" onPress={() => run('getExperimentConfig()', () => AppDNA.getExperimentConfig(experimentId ?? 'rn_e2e_experiment', 'headline'))} />
          <Button label="Feature flag" onPress={() => run('features.isEnabled()', () => AppDNA.features.isEnabled('dark_mode'))} />
          <Button label="Feature variant" onPress={() => run('features.getVariant()', () => AppDNA.features.getVariant('dark_mode'))} />
        </Section>

        <Section title="Remote config">
          <Button label="Get value" onPress={() => run('remoteConfig.get()', () => AppDNA.remoteConfig.get('welcome_message'))} />
          <Button label="Get all" onPress={() => run('remoteConfig.getAll()', () => AppDNA.remoteConfig.getAll())} />
          <Button label="Refresh" onPress={() => run('remoteConfig.refresh()', () => AppDNA.remoteConfig.refresh())} />
          <Button label="Prime snapshot" onPress={() => run('remoteConfig.primeSnapshot()', () => AppDNA.remoteConfig.primeSnapshot())} />
          <Button
            label="Read cached (sync)"
            onPress={() => {
              append(`remoteConfig.hasSnapshot() → ${AppDNA.remoteConfig.hasSnapshot()}`);
              append(`remoteConfig.getCached(welcome_message) → ${JSON.stringify(AppDNA.remoteConfig.getCached('welcome_message'))}`);
            }}
          />
        </Section>

        <Section title="Consent & session">
          <Button label="Grant consent" onPress={() => run('setConsent(true)', () => AppDNA.setConsent(true))} />
          <Button label="Revoke consent" onPress={() => run('setConsent(false)', () => AppDNA.setConsent(false))} />
          <Button label="Is consent granted" onPress={() => run('isConsentGranted()', () => AppDNA.isConsentGranted())} />
          <Button
            label="Session round-trip"
            onPress={async () => {
              await AppDNA.session.set('rn_e2e', { n: 1, nested: { ok: true } });
              append(`session.get → ${JSON.stringify(await AppDNA.session.get('rn_e2e'))}`);
            }}
          />
          <Button label="Clear session" onPress={() => run('session.clear()', () => AppDNA.session.clear())} />
        </Section>

        <Section title="Lifecycle">
          {/* 🔴 `shutdown()` had NEVER run on a device. Four bugs are documented as fixed in its
              teardown path — the config snapshot, the delegate listeners, the veto handlers, and the
              entitlement-observer latch — and none of those fixes had ever executed outside jest.
              After Reconfigure, every delegate must fire again exactly ONCE per event: a duplicated
              line in the log means the teardown leaked, and a missing line means it over-reaped. */}
          <Button
            label="Shutdown"
            onPress={async () => {
              subscriptions.current.forEach((off) => off());
              subscriptions.current = [];
              await run('shutdown()', () => AppDNA.shutdown());
              setStatus('Shut down');
              append(`remoteConfig.hasSnapshot() after shutdown → ${AppDNA.remoteConfig.hasSnapshot()}`);
            }}
          />
          <Button
            label="Reconfigure (after shutdown)"
            onPress={async () => {
              setStatus('Reconfiguring…');
              await run('reconfigure', boot);
            }}
          />
        </Section>

        <Text style={styles.sectionTitle}>diagnose()</Text>
        <Text style={styles.mono} testID="diagnostics">{diagnostics || '—'}</Text>

        <Text style={styles.sectionTitle}>Callback log</Text>
        <Text style={styles.mono} testID="callback-log">{log.length ? log.join('\n') : '—'}</Text>
      </ScrollView>
    </SafeAreaView>
  );
}

function InfoCard({ title, value }: { title: string; value: string }) {
  return (
    <View style={styles.card}>
      <Text style={styles.cardTitle}>{title}</Text>
      <Text style={styles.cardValue} testID={`card-${title}`}>{value}</Text>
    </View>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View>
      <Text style={styles.sectionTitle}>{title}</Text>
      <View style={styles.buttons}>{children}</View>
    </View>
  );
}

function Button({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <TouchableOpacity style={styles.button} onPress={onPress} testID={label}>
      <Text style={styles.buttonText}>{label}</Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  content: { padding: 16 },
  title: { fontSize: 24, fontWeight: 'bold', marginBottom: 16, color: '#1a1a1a' },
  sectionTitle: { fontSize: 14, fontWeight: '600', color: '#666', marginTop: 16, marginBottom: 6 },
  hint: { fontSize: 12, color: '#888', marginBottom: 8 },
  slot: { backgroundColor: '#fff', borderRadius: 12 },
  card: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
    shadowColor: '#000',
    shadowOpacity: 0.05,
    shadowRadius: 4,
    elevation: 2,
  },
  cardTitle: { fontSize: 14, fontWeight: '600', color: '#666', marginBottom: 4 },
  cardValue: { fontSize: 16, color: '#1a1a1a' },
  buttons: { marginTop: 4 },
  button: {
    backgroundColor: '#6366f1',
    borderRadius: 12,
    padding: 14,
    marginBottom: 10,
    alignItems: 'center',
  },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  mono: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 12,
    fontFamily: 'Courier',
    fontSize: 12,
    color: '#1a1a1a',
  },
});
