/**
 * Where `packages/sdk-shared-fixtures` lives, resolved from a test.
 *
 * 🔴 NEVER HARDCODE A RELATIVE PATH TO THE FIXTURES.
 *
 * `mapRouteBridge.test.ts` did — `join(__dirname, '../../sdk-shared-fixtures/...')` — which resolves
 * only inside the monorepo, where `packages/appdna-sdk-react-native/__tests__/../../` happens to be
 * `packages/`. The Mac build bridge syncs this package to a STANDALONE checkout
 * (`~/Projects/appdna-sdk-react-native`), so the same expression walked up to `~/Projects/` and the
 * suite died with `ENOENT ... /Users/<me>/Projects/sdk-shared-fixtures/...` — a whole suite failing
 * to run, reported by jest as `Test suite failed to run` with zero failed tests, which reads like a
 * config problem rather than a missing file.
 *
 * It was green in CI the entire time, because CI runs from the monorepo. That is exactly the shape
 * of bug that only shows up somewhere other than CI, so the fix is a resolver rather than a better
 * relative path.
 *
 * Resolution order, unchanged from the one `sharedFixtures.test.ts` has always used:
 *   1. `APPDNA_SDK_FIXTURES_DIR` — an absolute path; CI sets this
 *   2. Walk up from `__dirname` looking for `packages/sdk-shared-fixtures/`
 *   3. Codespace fallback
 *
 * © 2026 AppDNA AI, Inc.
 */

import * as fs from 'node:fs';
import * as path from 'node:path';

/** The fixtures directory, or a thrown error naming the env var that fixes it. */
export function resolveFixturesRoot(): string {
  const env = process.env.APPDNA_SDK_FIXTURES_DIR;
  if (env && fs.existsSync(env)) return env;

  let here = __dirname;
  for (let i = 0; i < 10; i++) {
    const candidate = path.join(here, 'packages', 'sdk-shared-fixtures');
    if (fs.existsSync(candidate)) return candidate;
    const parent = path.dirname(here);
    if (parent === here) break;
    here = parent;
  }
  const codespace = '/workspaces/appdna-ai/packages/sdk-shared-fixtures';
  if (fs.existsSync(codespace)) return codespace;
  throw new Error(
    'Could not locate packages/sdk-shared-fixtures. Set APPDNA_SDK_FIXTURES_DIR.',
  );
}

/** One fixture, by the path segments under the fixtures root. */
export function fixturePath(...segments: readonly string[]): string {
  return path.join(resolveFixturesRoot(), ...segments);
}
