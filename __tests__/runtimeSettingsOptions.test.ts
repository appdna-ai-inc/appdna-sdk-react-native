/**
 * The React Native half of the `runtime_settings_precedence` shared fixture.
 *
 * Native resolves `flushInterval` / `batchSize` / `configTTL` as host option > the bootstrap answer's
 * `settings` > its built-in default. A thin wrapper can break that one way: by sending a value the host
 * never set (a filled-in default reads, natively, as the host's own choice and beats the server's). So
 * the facade must hand native exactly what the host passed. The expectation is READ FROM THE FIXTURE the
 * iOS and Android runners drive; the native half of the bridge (`parseOptions`) is pinned by
 * `AppdnaParseOptionsTest` (Android) / `AppdnaParseOptionsTests` (iOS).
 */

import { readFileSync } from 'node:fs';
import { fixturePath } from './fixtureRoot';

const calls: Record<string, unknown[][]> = {};
const mockModule = new Proxy(
  {},
  {
    get: (_t, prop: string) => {
      if (prop.startsWith('on')) return () => ({ remove: () => undefined });
      return (...args: unknown[]) => {
        (calls[prop] ??= []).push(args);
        return Promise.resolve(undefined);
      };
    },
  },
);

jest.mock('react-native', () => ({
  TurboModuleRegistry: { get: () => mockModule, getEnforcing: () => mockModule },
  Platform: { OS: 'ios', select: (spec: Record<string, unknown>) => spec.ios ?? spec.default },
}));

import { AppDNA } from '../src/index';

const FIXTURE = fixturePath('resilience', 'runtime_settings_precedence.fixture.json');

describe('runtime settings reach native only when the host set them', () => {
  const w = (JSON.parse(readFileSync(FIXTURE, 'utf8')) as {
    resilience: {
      wrapper_options: {
        host_sets: Record<string, number>;
        native_receives: Record<string, number>;
        native_unset: string[];
      };
    };
  }).resilience.wrapper_options;

  beforeEach(async () => {
    for (const k of Object.keys(calls)) delete calls[k];
    await AppDNA.shutdown();
  });

  it('forwards the options the host set, and nothing in place of the ones it did not', async () => {
    await AppDNA.configure('adn_test_placeholder', 'sandbox', { ...w.host_sets });
    const sent = calls.configure?.[0]?.[2] as Record<string, unknown>;
    expect(sent).toBeDefined();
    for (const [k, v] of Object.entries(w.native_receives)) expect(sent[k]).toBe(v);
    for (const k of w.native_unset) expect(sent).not.toHaveProperty(k);
  });

  it('sends no runtime setting when the host passes no options', async () => {
    await AppDNA.configure('adn_test_placeholder', 'sandbox');
    const sent = (calls.configure?.[0]?.[2] ?? {}) as Record<string, unknown>;
    for (const k of ['flushInterval', 'batchSize', 'configTTL']) expect(sent).not.toHaveProperty(k);
  });
});
