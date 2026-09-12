import { describe, expect, it } from 'vitest';
import { parseForegroundPackage } from './foreground-activity.js';

/** Samples are the ones the Kotlin AdbGatewayTest and LiveDeviceClientTest use. */
describe('parseForegroundPackage', () => {
  it('prefers the focused app in multi-window output', () => {
    const output = [
      'topResumedActivity=ActivityRecord{111 u0 org.chromium.home.pc/.HostActivity t2}',
      'topResumedActivity=ActivityRecord{222 u0 com.codemx.anrdemo/.MainActivity t10}',
      'mFocusedApp=ActivityRecord{222 u0 com.codemx.anrdemo/.MainActivity t10}',
    ].join('\n');
    expect(parseForegroundPackage(output)).toBe('com.codemx.anrdemo');
  });

  it('falls back to the resumed activity when there is no focused app line', () => {
    const output = 'topResumedActivity=ActivityRecord{abc u0 com.androidperformancestudio.sample/.MainActivity t1}';
    expect(parseForegroundPackage(output)).toBe('com.androidperformancestudio.sample');
  });

  it('reads the legacy mResumedActivity field', () => {
    const output = 'mResumedActivity=ActivityRecord{9f2 u0 com.example.app/.MainActivity t3}';
    expect(parseForegroundPackage(output)).toBe('com.example.app');
  });

  it('reports nothing when no activity record is present', () => {
    expect(parseForegroundPackage('No activities in the system')).toBeUndefined();
  });
});
