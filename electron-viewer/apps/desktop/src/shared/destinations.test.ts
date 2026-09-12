import { describe, expect, it } from 'vitest';
import {
  activateDestination,
  DESTINATIONS,
  DESTINATION_SUMMARY_KEYS,
  DESTINATION_TITLE_KEYS,
  HOME_DESTINATIONS,
  INITIAL_NAVIGATION_STATE,
  shouldMaximizeWindow,
} from './destinations.js';
import { translate, UI_LANGUAGES } from './i18n.js';

describe('navigation model', () => {
  it('defines 14 destinations with a title key each', () => {
    expect(DESTINATIONS).toHaveLength(14);
    for (const destination of DESTINATIONS) {
      expect(DESTINATION_TITLE_KEYS[destination]).toBeTruthy();
    }
  });

  it('surfaces every destination but home as a card', () => {
    const features = DESTINATIONS.filter((destination) => destination !== 'HOME');
    expect(HOME_DESTINATIONS).toHaveLength(13);
    expect([...HOME_DESTINATIONS].sort()).toEqual([...features].sort());
    expect(HOME_DESTINATIONS).not.toContain('HOME');
  });

  it('keeps the reference home order and appends the three cards it dropped', () => {
    expect(HOME_DESTINATIONS.slice(0, 9)).toEqual([
      'LAYOUT_INSPECTOR',
      'SIMPLEPERF',
      'PERFETTO',
      'MEMORY_PROFILER',
      'FRAME_PROFILER',
      'STARTUP_PROFILER',
      'BATTERY_PROFILER',
      'NETWORK_PROFILER',
      'SOURCE_WORKSPACES',
    ]);
    expect(HOME_DESTINATIONS.slice(9)).toEqual([
      'GPU_INSPECTOR',
      'BENCHMARK_REGRESSION',
      'METHOD_RECORDING',
      'AI_ANALYSIS',
    ]);
  });

  it('gives every card a title and a summary in both languages', () => {
    for (const destination of HOME_DESTINATIONS) {
      expect(DESTINATION_SUMMARY_KEYS[destination], destination).toBeTruthy();
      const summary = DESTINATION_SUMMARY_KEYS[destination];
      const title = DESTINATION_TITLE_KEYS[destination];
      for (const language of UI_LANGUAGES) {
        // translate() echoes the key when one is missing, which is what a card
        // with no copy would render.
        expect(translate(summary, language), destination + '.' + language).not.toBe(summary);
        expect(translate(title, language), destination + '.' + language).not.toBe(title);
      }
    }
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
