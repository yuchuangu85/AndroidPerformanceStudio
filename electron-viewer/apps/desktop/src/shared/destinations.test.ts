import { describe, expect, it } from 'vitest';
import {
  activateDestination,
  DESTINATIONS,
  DESTINATION_TITLE_KEYS,
  HOME_DESTINATIONS,
  INITIAL_NAVIGATION_STATE,
  shouldMaximizeWindow,
} from './destinations.js';

describe('navigation model', () => {
  it('defines 13 destinations with a title key each', () => {
    expect(DESTINATIONS).toHaveLength(13);
    for (const destination of DESTINATIONS) {
      expect(DESTINATION_TITLE_KEYS[destination]).toBeTruthy();
    }
  });

  it('surfaces exactly nine home cards and omits GPU, benchmark, and method recording', () => {
    expect(HOME_DESTINATIONS).toHaveLength(9);
    expect(HOME_DESTINATIONS).not.toContain('GPU_INSPECTOR');
    expect(HOME_DESTINATIONS).not.toContain('BENCHMARK_REGRESSION');
    expect(HOME_DESTINATIONS).not.toContain('METHOD_RECORDING');
  });

  it('maximizes every destination except home', () => {
    expect(shouldMaximizeWindow('HOME')).toBe(false);
    expect(shouldMaximizeWindow('PERFETTO')).toBe(true);
  });

  it('retains visited destinations without duplicates', () => {
    const first = activateDestination(INITIAL_NAVIGATION_STATE, 'PERFETTO');
    const second = activateDestination(first, 'SIMPLEPERF');
    const third = activateDestination(second, 'PERFETTO');
    expect(third.current).toBe('PERFETTO');
    expect(third.retained).toEqual(['HOME', 'PERFETTO', 'SIMPLEPERF']);
  });
});
