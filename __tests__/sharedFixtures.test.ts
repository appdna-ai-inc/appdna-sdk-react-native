/**
 * sharedFixtures.test.ts
 *
 * Cross-platform behavioral fixture runner for React Native — SPEC-070-0
 * §3.2 + §3.3 step 7.
 *
 * Per ADR-001 the React Native TS layer is a THIN WRAPPER. This runner is
 * leg ONE of two, and it verifies the **bridge contract**: for each
 * fixture's `action`, calling the SDK TS facade triggers the expected
 * `AppdnaModule.<x>` invocation with the correct args.
 *
 * IT CANNOT ASSERT `expect`, AND THAT IS STRUCTURAL — NOT A GAP
 * -------------------------------------------------------------
 * A fixture's `expect` block asserts NATIVE behaviour: an audience rule
 * evaluated, a DTO parsed, an envelope built, a step advanced. Here the
 * native module is MOCKED AWAY, so the wrapper has none of that to do —
 * ADR-001 forbids it from having any. The only honest assertion left on
 * this side of the boundary is the call itself. Making jest "assert
 * `expect`" would mean re-implementing the SDK inside the test and
 * asserting the mirror, which is exactly the fiction this branch exists
 * to kill.
 *
 * LEG TWO — `android/src/test/.../SharedFixtureBridgeTest.kt` — is what
 * discharges AC-24: it drives the SAME fixtures through the SAME bridged
 * `AppdnaModule` methods into a REAL, configured native `AppDNA`
 * singleton under Robolectric, and asserts the fixture's `expect` block
 * against the events native actually persisted, the payloads the wrapper
 * actually pushed across the bridge, and the state native actually holds.
 *
 * Which fixtures may claim `rn` — and the recorded reason for every one
 * that may not — lives in `scripts/check-fixture-coverage.ts`
 * (`RN_NATIVE_ONLY`). A wrapper's bridged surface IS the host's API
 * surface, so a fixture whose action is a UI tap, a raw push payload, or
 * a bare audience-rule evaluation has no host entry point on ANY platform.
 *
 *   - track_event   → AppDNA.track(...)                    — AppdnaModule.track
 *   - identify      → AppDNA.identify(...)                 — AppdnaModule.identify
 *   - show_paywall  → AppDNA.paywall.presentByPlacement()  — AppdnaModule.presentPaywallByPlacement
 *
 * FIXTURE PATH RESOLUTION
 * -----------------------
 *   1. APPDNA_SDK_FIXTURES_DIR env var (CI sets this absolute path)
 *   2. Walk up from __dirname until packages/sdk-shared-fixtures/ is found
 *   3. Codespace fallback: /workspaces/appdna-ai/packages/sdk-shared-fixtures
 *
 * react-native is mocked module-globally so importing `../src` works
 * without a real RN runtime. See `jest.mock('react-native', ...)` below.
 *
 * © 2026 AppDNA AI, Inc.
 */

import * as fs from 'node:fs';
import * as path from 'node:path';
import { resolveFixturesRoot } from './fixtureRoot';

// ---- Mock the entire react-native module surface used by ../src --------

interface CapturedCall {
  readonly method: string;
  readonly args: readonly unknown[];
}

const mockCapturedCalls: CapturedCall[] = [];
const mockEventListeners: Map<string, ((data: unknown) => void)[]> = new Map();

/**
 * A stand-in TurboModule. Under the New Architecture the module exposes methods AND, for each event,
 * an emitter PROPERTY that takes a listener and returns a subscription — `onReady` is the one `on*`
 * name that is a method, and this mock reproduces that quirk rather than papering over it.
 */
const mockAppdnaModule = new Proxy(
  {},
  {
    get(_target, methodName: string) {
      if (methodName === 'then') return undefined; // not a thenable

      if (methodName.startsWith('on') && methodName !== 'onReady') {
        return (callback: (data: unknown) => void) => {
          const list = mockEventListeners.get(methodName) ?? [];
          list.push(callback);
          mockEventListeners.set(methodName, list);
          return { remove: () => undefined };
        };
      }

      return (...args: unknown[]) => {
        mockCapturedCalls.push({ method: methodName, args });
        return Promise.resolve(null);
      };
    },
  },
) as Record<string, (...args: unknown[]) => Promise<unknown>>;

// NO `{ virtual: true }`: react-native is a REAL module (the `react-native` preset provides it).
// Marking a real module's mock virtual corrupts jest's per-worker mock registry — the NEXT test file
// in the same worker then finds react-native "already handled" and its own `jest.mock('react-native')`
// silently no-ops, so it resolves the REAL module whose `TurboModuleRegistry.get('AppdnaModule')`
// returns null → every suite after this one failed with "native module is not available", ordered by
// jest's sequencer. That was the whole flaky-suite bug.
jest.mock('react-native', () => ({
  TurboModuleRegistry: {
    get: () => mockAppdnaModule,
    getEnforcing: () => mockAppdnaModule,
  },
  Platform: { OS: 'ios', select: <T,>(spec: { default?: T; ios?: T; android?: T }) => spec.ios ?? spec.default },
}));

// Import AFTER mock is registered.
// eslint-disable-next-line @typescript-eslint/no-var-requires
const { AppDNA } = require('../src');

// ---- Fixture types + loader -------------------------------------------

interface FixtureAction {
  readonly kind: string;
  // arbitrary additional fields
  readonly [k: string]: unknown;
}

interface Fixture {
  readonly id: string;
  readonly category: string;
  readonly description: string;
  readonly platforms: readonly string[];
  readonly setup?: Record<string, unknown>;
  readonly action: FixtureAction;
  readonly expect: {
    readonly events?: ReadonlyArray<{ name: string; properties?: Record<string, unknown> }>;
    readonly delegate_calls?: ReadonlyArray<{ name: string; args?: Record<string, unknown> }>;
    readonly state_after?: Record<string, unknown>;
    readonly errors?: ReadonlyArray<{ type: string; message?: string }>;
  };
}

// Shared with `mapRouteBridge.test.ts`, which used to hardcode a relative path and died on a
// standalone checkout. One resolver so a third copy cannot diverge — see `./fixtureRoot`.

function walkFixtureFiles(root: string): string[] {
  const out: string[] = [];
  const stack: string[] = [root];
  while (stack.length > 0) {
    const dir = stack.pop()!;
    for (const name of fs.readdirSync(dir)) {
      const full = path.join(dir, name);
      const stat = fs.statSync(full);
      if (stat.isDirectory()) {
        stack.push(full);
      } else if (stat.isFile() && name.endsWith('.fixture.json')) {
        out.push(full);
      }
    }
  }
  return out.sort();
}

function loadRnFixtures(): Fixture[] {
  const root = resolveFixturesRoot();
  const fixtures: Fixture[] = [];
  for (const filePath of walkFixtureFiles(root)) {
    const raw = fs.readFileSync(filePath, 'utf8');
    const fixture = JSON.parse(raw) as Fixture;
    // The `platforms` list is the whole filter. It used to be `platforms.includes('rn') && category
    // !== 'render' && category !== 'events'` — a runner-side carve-out that silently ignored the four
    // `events` fixtures' own claim to support `rn`. A fixture that says it covers a platform and is
    // skipped by that platform's runner is coverage theater; the fixtures now say `["ios","android"]`.
    if (fixture.platforms.includes('rn')) {
      fixtures.push(fixture);
    }
  }
  return fixtures;
}

// ---- Drivers + assertions ---------------------------------------------

async function runFixture(fixture: Fixture): Promise<void> {
  switch (fixture.action.kind) {
    case 'track_event': {
      // SPEC-070-B §18: the fixture key is `event_name`, not `event`. The driver read the wrong key
      // and papered over it with `?? 'unknown'` — so it drove a track('unknown') and asserted
      // against undefined. The suite never ran, so nobody saw it. A missing key is a broken
      // fixture; say so instead of inventing an event name.
      const event = fixture.action.event_name as string | undefined;
      if (!event) throw new Error(`[${fixture.id}] track_event fixture has no 'event_name'`);
      const properties = fixture.action.properties as
        | Record<string, unknown>
        | undefined;
      await AppDNA.track(event, properties);
      return;
    }
    case 'identify': {
      const userId = fixture.action.userId as string | undefined;
      if (!userId) throw new Error(`[${fixture.id}] identify fixture has no 'userId'`);
      const traits = fixture.action.traits as
        | Record<string, unknown>
        | undefined;
      await AppDNA.identify(userId, traits);
      return;
    }
    case 'show_paywall': {
      // Only the PLACEMENT form has a host API. A `trigger_node_id` paywall is fired from inside a
      // native onboarding flow graph; no SDK on any platform exposes that to a host, so a fixture
      // that names one must not claim `rn`.
      const placement = fixture.action.placement as string | undefined;
      if (!placement) {
        throw new Error(
          `[${fixture.id}] show_paywall without a 'placement' — there is no host API for an ` +
            `onboarding paywall-trigger node. Remove "rn" from this fixture's platforms.`,
        );
      }
      await AppDNA.paywall.presentByPlacement(placement);
      return;
    }
    default:
      // 🔴 There used to be a soft-skip here: `{skipped: true, reason: 'not yet implemented'}`, and the
      // harness `console.warn`ed it and RETURNED — inside the `it()`. Jest printed a tick. 35 of the 37
      // rn fixtures were green no-ops, including every paywall, purchase and push fixture. Emptying
      // `AppDNA.presentPaywall` left all five paywall fixtures passing.
      //
      // A fixture the RN runner cannot drive is not a fixture the RN runner may pass. Either the
      // wrapper has a code path for the action — in which case drive it — or the fixture is asserting
      // native behaviour the wrapper does not implement, in which case its `platforms` list must not
      // claim `rn`. Both are honest; a skip that prints ✓ is not.
      throw new Error(
        `[${fixture.id}] no RN driver for action.kind=${fixture.action.kind}. Either add one, or ` +
          `remove "rn" from this fixture's platforms — it asserts behaviour the wrapper does not have.`,
      );
  }
}

function assertBridgeContract(fixture: Fixture): void {
  const id = fixture.id;
  switch (fixture.action.kind) {
    case 'track_event': {
      expect(mockCapturedCalls).toHaveLength(1);
      const c = mockCapturedCalls[0]!;
      expect(c.method).toBe('track');
      expect(c.args[0]).toBe(fixture.action.event_name);
      // The facade passes `properties` AS-IS (undefined when the fixture omits it — the optional-param
      // contract), not `?? null`. Asserting `?? null` was a latent trap: a property-less fixture would
      // fail on `expect(undefined).toEqual(null)`.
      expect(c.args[1]).toEqual(fixture.action.properties);
      break;
    }
    case 'identify': {
      expect(mockCapturedCalls).toHaveLength(1);
      const c = mockCapturedCalls[0]!;
      expect(c.method).toBe('identify');
      expect(c.args[0]).toBe(fixture.action.userId);
      expect(c.args[1]).toEqual(fixture.action.traits);
      break;
    }
    case 'show_paywall': {
      expect(mockCapturedCalls).toHaveLength(1);
      const c = mockCapturedCalls[0]!;
      expect(c.method).toBe('presentPaywallByPlacement');
      expect(c.args[0]).toBe(fixture.action.placement);
      expect(c.args[1]).toBeUndefined();
      break;
    }
    default:
      throw new Error(`[${id}] no bridge-contract assertion registered`);
  }
}

// ---- Test harness -----------------------------------------------------

const fixtures = loadRnFixtures();

describe('SharedFixtures (React Native bridge contract)', () => {
  beforeEach(() => {
    mockCapturedCalls.length = 0;
    mockEventListeners.clear();
  });

  it('fixtures directory contains at least one rn-applicable fixture', () => {
    expect(fixtures.length).toBeGreaterThan(0);
  });

  describe.each(fixtures.map((f) => [f.id, f] as const))('%s', (_id, fixture) => {
    it(fixture.description, async () => {
      await runFixture(fixture);
      assertBridgeContract(fixture);
    });
  });
});
