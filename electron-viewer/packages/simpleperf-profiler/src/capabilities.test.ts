import { describe, expect, it } from 'vitest';
import { parseSimpleperfEventNames, simpleperfEventChoices } from './capabilities.js';

describe('simpleperf capabilities', () => {
  it('ports Kotlin simpleperf list event parsing', () => {
    expect(parseSimpleperfEventNames('List of hardware events:\n  cpu-cycles\n  cache-misses (raw)\nsoftware events:\n task-clock:u\ninvalid/event\n cpu-cycles\n')).toEqual([
      'cpu-cycles', 'cache-misses', 'task-clock:u',
    ]);
  });

  it('keeps defaults and a saved free-form event alongside device events', () => {
    expect(simpleperfEventChoices(['instructions', 'cpu-clock'], 'vendor-event')).toEqual([
      'cpu-clock', 'cpu-cycles', 'task-clock', 'instructions', 'vendor-event',
    ]);
  });
});
