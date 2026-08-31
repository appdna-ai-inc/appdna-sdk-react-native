/**
 * SPEC-451 — the React Native half of the `map_delegate_route` shared fixture.
 *
 * A thin wrapper FORWARDS, so the only thing this side can prove is that what it forwards is the
 * shape the native decoder accepts. That is not a small claim: the map route crosses the bridge as
 * an untyped object, so a renamed key here compiles, ships, and silently leaves every RN host's map
 * on its authored route with no error anywhere.
 *
 * The expected shape is READ FROM THE FIXTURE the iOS and Android runners drive, not restated here.
 * An expectation copied into this file could be "fixed" to match a regression without either native
 * noticing — which is the whole failure mode the shared fixtures exist to prevent.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { mapRoute, type MapRouteStop } from '../src/mapRoute';

const FIXTURE = join(
  __dirname,
  '../../sdk-shared-fixtures/config_overrides/map_delegate_route.fixture.json',
);

describe('map route bridge shape', () => {
  const fixture = JSON.parse(readFileSync(FIXTURE, 'utf8')) as {
    setup: { session_data: { host_map_routes: Record<string, { polyline?: string; stops?: MapRouteStop[] }> } };
  };
  const expected = fixture.setup.session_data.host_map_routes.delivery_map;

  it('is exactly the shape the native decoder reads', () => {
    expect(mapRoute({ polyline: expected.polyline, stops: expected.stops })).toEqual(expected);
  });

  it('sends nothing for an empty route rather than nulls', () => {
    // The native decoder treats "neither a line nor a place" as no route at all and keeps the
    // authored one, which is the right outcome for a routing call that came back empty. Sending
    // explicit nulls would decode as a route with no geometry and blank the map.
    expect(mapRoute({})).toEqual({});
    expect(mapRoute({ polyline: '', stops: [] })).toEqual({});
  });
});
