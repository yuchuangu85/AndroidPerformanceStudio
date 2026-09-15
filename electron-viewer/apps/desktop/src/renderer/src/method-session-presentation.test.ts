import { describe, expect, it } from 'vitest';
import type { MethodSessionRecord } from '../../shared/ipc';
import { methodSessionLabel, methodSessionMetadata } from './method-session-presentation';

const COMMON = {
  id: 'session-1',
  capturedAtEpochMillis: 123,
  traceVersion: 5,
  traceBytes: 64,
  eventCount: 2,
  methodCount: 2,
  threadCount: 1,
  threadKeys: ['main (tid 7)'],
  warnings: [],
} satisfies Omit<MethodSessionRecord, 'origin' | 'sourceFileName' | 'serial' | 'packageName' | 'pid' | 'durationSeconds' | 'deviceSdkApiLevel'>;

describe('method session presentation', () => {
  it('shows an imported filename and omits device metadata', () => {
    const record: MethodSessionRecord = { ...COMMON, origin: 'IMPORTED', sourceFileName: 'offline.trace' };

    expect(methodSessionLabel(record, 'en')).toBe('Imported trace: offline.trace');
    expect(methodSessionMetadata(record, 'en')).not.toContain('Device API');
  });

  it('preserves captured and legacy package/device presentation', () => {
    const captured: MethodSessionRecord = {
      ...COMMON,
      origin: 'CAPTURED',
      serial: 'emulator-5554',
      packageName: 'com.example.app',
      pid: 42,
      durationSeconds: 10,
      deviceSdkApiLevel: 34,
    };
    const legacy: MethodSessionRecord = { ...captured, origin: undefined };

    expect(methodSessionLabel(captured, 'en')).toBe('com.example.app');
    expect(methodSessionLabel(legacy, 'en')).toBe('com.example.app');
    expect(methodSessionMetadata(captured, 'en')).toContain('Device API: 34');
    expect(methodSessionMetadata(legacy, 'en')).toContain('Device API: 34');
  });
});
