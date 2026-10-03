/**
 * The TurboModule spec must be codegen-LEGAL.
 *
 * RN's codegen is what turns `src/specs/NativeAppdnaModule.ts` into a C++ JSI spec and an abstract
 * Kotlin class. If a type in it is illegal, the failure lands at a HOST's build — `pod install` or
 * `assembleDebug` in someone else's app — with a stack trace that names our file and nothing else.
 *
 * So we run RN's own parser here. This is not a re-implementation of the rules: it is the exact
 * `TypeScriptParser` a host would run.
 *
 * ## 🔴 Two claims are REFUTED for RN 0.76.4, measured here, not reasoned
 *
 *   - **"`UnsafeObject[]` throws `UnsupportedArrayElementTypeAnnotationParserError`."** It does not.
 *     `UnsafeObject` and `Object` both lower to `GenericObjectTypeAnnotation`, so `UnsafeObject[]`
 *     parses AND generates identically to `Object[]`.
 *   - **"General/discriminated TS unions are codegen-illegal."** They are not: a union parses to a
 *     `UnionTypeAnnotation` and both the Android and iOS generators emit for it.
 *
 * The rule that DOES fire is the one E1 names: a generic like `Record<string, unknown>` — the
 * delegate emitter's TS mapping — throws `UnsupportedGenericParserError`. That is the real reason a
 * delegate type must never be reused in a TurboModule spec.
 *
 * We still cross unknown-shape values as a JSON `string` (E2). Not because codegen refuses a union,
 * but because what a union *bridges to at runtime* is unspecified per platform, and a JSON string is
 * the only encoding whose meaning is identical on both. That is a design choice, stated as one.
 *
 * `EventEmitter<T>` as a spec property requires a NON-NULLABLE `T` and lands in RN 0.76 — which is
 * why CODEGEN works from 0.76.4 up, and why this test measures 0.76.4. The CONSUMER peer floor is
 * `>=0.76.9` (D-t, corrected): 0.76.4–0.76.8 are DOA for third-party TurboModules via the OnLoad.cpp
 * autolinking bug (a native app-build issue jest never hits), fixed in 0.76.9. Codegen floor and
 * consumer floor are deliberately distinct — this suite pins the former.
 */
import fs from 'node:fs';
import path from 'node:path';

// eslint-disable-next-line @typescript-eslint/no-require-imports
const { TypeScriptParser } = require('@react-native/codegen/lib/parsers/typescript/parser');

const SPEC_PATH = path.join(__dirname, '..', 'src', 'specs', 'NativeAppdnaModule.ts');

function parseSpec(source: string) {
  return new TypeScriptParser().parseString(source, SPEC_PATH);
}

describe('NativeAppdnaModule TurboModule spec', () => {
  const source = fs.readFileSync(SPEC_PATH, 'utf8');

  it('parses with RN’s own codegen parser', () => {
    const schema = parseSpec(source);
    expect(Object.keys(schema.modules)).toEqual(['NativeAppdnaModule']);
  });

  it('declares every facade method and every native event', () => {
    const spec = parseSpec(source).modules.NativeAppdnaModule.spec;

    // Guards against a method silently disappearing from the IR. `check:rn-facade-parity` (P6)
    // asserts the stronger property — that these are exactly the methods the facade calls.
    expect(spec.methods.length).toBeGreaterThanOrEqual(40);
    expect(spec.eventEmitters.length).toBeGreaterThanOrEqual(30);

    const names = spec.methods.map((m: { name: string }) => m.name);
    // The one method that makes native→JS vetoes answerable at all.
    expect(names).toContain('respondToHostCallback');
    // D-h / AC-22: reachable from JS, or the runner's injection would launder a dead surface.
    expect(names).toContain('notifyScreenAppeared');
    // D-k: the init-degraded seam ships with a consumer, not as dead native API.
    expect(names).toContain('getLastInitError');

    const events = spec.eventEmitters.map((e: { name: string }) => e.name);
    expect(events).toContain('onHostCallback');
    expect(events).toContain('onInitDegraded');
  });

  it('rejects a generic like Record<string, unknown> — the delegate mapping E1 forbids', () => {
    const bad = source.replace(
      'identify(userId: string, traits?: Object): Promise<void>;',
      'identify(userId: string, traits?: Record<string, unknown>): Promise<void>;',
    );
    expect(bad).not.toEqual(source); // the substitution actually applied
    expect(() => parseSpec(bad)).toThrow(/Unrecognized generic type 'Record'/);
  });

  it('REFUTES the spec: UnsafeObject[] is accepted, identically to Object[]', () => {
    const variant = source.replace(
      'getExperimentExposures(): Promise<Object[]>;',
      'getExperimentExposures(): Promise<UnsafeObject[]>;',
    );
    expect(variant).not.toEqual(source);

    const annotationOf = (src: string) =>
      parseSpec(src).modules.NativeAppdnaModule.spec.methods.find(
        (m: { name: string }) => m.name === 'getExperimentExposures',
      ).typeAnnotation.returnTypeAnnotation;

    // Both lower to the same thing. The "UnsupportedArrayElementTypeAnnotationParserError" does
    // not occur in RN 0.76.4. We keep `Object[]` because it says what we mean, not because the
    // alternative fails.
    expect(annotationOf(variant)).toEqual(annotationOf(source));
  });

  it('REFUTES the spec: a general union is accepted by the parser', () => {
    const variant = source.replace(
      'isFeatureEnabled(flag: string): Promise<boolean>;',
      'isFeatureEnabled(flag: string): Promise<boolean | number>;',
    );
    expect(variant).not.toEqual(source);

    const ret = parseSpec(variant).modules.NativeAppdnaModule.spec.methods.find(
      (m: { name: string }) => m.name === 'isFeatureEnabled',
    ).typeAnnotation.returnTypeAnnotation;

    // Parses to a UnionTypeAnnotation rather than throwing. Unknown-shape values still cross as a
    // JSON string (E2) — because a union's runtime bridging is platform-specific, not because
    // codegen refuses it.
    expect(ret.elementType.type).toBe('UnionTypeAnnotation');
  });
});
