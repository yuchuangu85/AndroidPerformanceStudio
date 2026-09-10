import { describe, expect, it } from 'vitest';
import { parseAmStartOutput } from './am-start.js';
import { parseStartupEventLog } from './event-log.js';
import { observedStartupType } from './model.js';

const AM_START = [
  'Starting: Intent { cmp=com.example.app/.MainActivity }',
  'Status: ok',
  'Activity: com.example.app/.MainActivity',
  'ThisTime: 210',
  'TotalTime: 512',
  'WaitTime: 530',
  'Complete',
].join('\n');

describe('parseAmStartOutput', () => {
  it('parses the metric block', () => {
    const result = parseAmStartOutput(AM_START);
    expect(result.warnings).toEqual([]);
    expect(result.metrics.status).toBe('ok');
    expect(result.metrics.activity).toBe('com.example.app/.MainActivity');
    expect(result.metrics.thisTimeMs).toBe(210);
    expect(result.metrics.totalTimeMs).toBe(512);
    expect(result.metrics.waitTimeMs).toBe(530);
    expect(result.metrics.complete).toBe(true);
  });

  it('records LaunchState and tolerates ms suffixes', () => {
    const result = parseAmStartOutput('Status: ok\nLaunchState: COLD\nTotalTime: 512ms\nComplete: true');
    expect(result.metrics.launchState).toBe('COLD');
    expect(result.metrics.totalTimeMs).toBe(512);
    expect(result.metrics.complete).toBe(true);
  });

  it('warns about a missing TotalTime and a non-ok status', () => {
    const result = parseAmStartOutput('Status: Error: Activity not started\nWarning: something');
    expect(result.metrics.totalTimeMs).toBeUndefined();
    expect(result.warnings).toContain('am start -W did not report TotalTime.');
    expect(result.warnings.some((warning) => warning.startsWith('Activity Manager returned status'))).toBe(true);
    expect(result.warnings.some((warning) => warning.startsWith('Warning:'))).toBe(true);
  });
});

describe('observedStartupType', () => {
  it('maps platform LaunchState values and defaults to UNKNOWN', () => {
    expect(observedStartupType('COLD')).toBe('COLD');
    expect(observedStartupType(' warm ')).toBe('WARM');
    expect(observedStartupType('HOT')).toBe('HOT');
    expect(observedStartupType(undefined)).toBe('UNKNOWN');
    expect(observedStartupType('FAST')).toBe('UNKNOWN');
  });
});

describe('parseStartupEventLog', () => {
  it('extracts TTID and TTFD from matching lines only', () => {
    const output = [
      'I/ActivityTaskManager( 900): Displayed com.example.app/.MainActivity: +512ms',
      'I/ActivityTaskManager( 900): Displayed com.other.app/.Main: +999ms',
      'I/ActivityTaskManager( 900): Fully drawn com.example.app/.MainActivity: +845ms',
    ].join('\n');
    const result = parseStartupEventLog(output, 'com.example.app');
    expect(result.displayedTimeMs).toBe(512);
    expect(result.fullyDrawnTimeMs).toBe(845);
    expect(result.warnings).toEqual([]);
  });

  it('parses wm_activity_launch_time payloads', () => {
    const output = 'I/am_activity_launch_time( 900): [0,123456789,com.example.app/.MainActivity,512,530]';
    const result = parseStartupEventLog(output, 'com.example.app');
    expect(result.displayedTimeMs).toBe(530);
  });

  it('keeps a missing TTFD missing instead of estimating it', () => {
    const output = 'I/ActivityTaskManager( 900): Displayed com.example.app/.MainActivity: +512ms';
    const result = parseStartupEventLog(output, 'com.example.app');
    expect(result.displayedTimeMs).toBe(512);
    expect(result.fullyDrawnTimeMs).toBeUndefined();
    expect(result.warnings.some((warning) => warning.includes('TTFD'))).toBe(true);
  });
});
