/**
 * SPEC-497 §13h (D2) — `AppDNA.getLocationData` passes the native object through, nulls included.
 *
 * A typed-but-unselected address crosses as `{formatted_address, raw_query}` with every other key
 * `null` (both natives emit `null` for a missing field, never `0.0` / `""`). The facade must not
 * invent values for them — a host must be able to tell "no coordinates" from a real point.
 */

let nativeAnswer: string = 'null';

const mockModule: Record<string, unknown> = {
  onInitDegraded: () => ({ remove: () => undefined }),
  getLocationData: jest.fn(async () => nativeAnswer),
};

jest.mock('react-native', () => ({
  TurboModuleRegistry: { get: () => mockModule, getEnforcing: () => mockModule },
  Platform: { OS: 'ios', select: (spec: Record<string, unknown>) => spec.ios ?? spec.default },
}));

import { AppDNA } from '../src/index';

describe('getLocationData', () => {
  it('a typed answer keeps its nulls', async () => {
    nativeAnswer = JSON.stringify({
      formatted_address: 'Somewhere typed',
      raw_query: 'Somewhere typed',
      city: null,
      state: null,
      country: null,
      latitude: null,
      longitude: null,
      timezone: null,
      timezone_offset: null,
    });
    const loc = await AppDNA.getLocationData('field_location');
    expect(mockModule.getLocationData).toHaveBeenCalledWith('field_location');
    expect(loc).toEqual({
      formatted_address: 'Somewhere typed',
      raw_query: 'Somewhere typed',
      city: null,
      state: null,
      country: null,
      latitude: null,
      longitude: null,
      timezone: null,
      timezone_offset: null,
    });
  });

  it('a selection passes through untouched', async () => {
    nativeAnswer = JSON.stringify({ formatted_address: 'A City, A Country', city: 'A City', latitude: 12.5, longitude: -3 });
    expect(await AppDNA.getLocationData('field_location')).toEqual({
      formatted_address: 'A City, A Country',
      city: 'A City',
      latitude: 12.5,
      longitude: -3,
    });
  });

  it('no answer is null', async () => {
    nativeAnswer = 'null';
    expect(await AppDNA.getLocationData('field_location')).toBeNull();
  });
});
