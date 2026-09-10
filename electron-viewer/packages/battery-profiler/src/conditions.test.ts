import { describe, expect, it } from 'vitest';
import { parseBatteryConditions, parseDozeState, parseScreenBrightness, parseScreenOn } from './conditions.js';

describe('battery condition parsing', () => {
  it('parses screen brightness only when numeric', () => {
    expect(parseScreenBrightness('128\n')).toBe('128');
    expect(parseScreenBrightness('null')).toBeUndefined();
  });

  it('prefers wakefulness over display power for screen state', () => {
    expect(parseScreenOn('mWakefulness=Awake')).toBe(true);
    expect(parseScreenOn('mWakefulness=Dozing')).toBe(false);
    expect(parseScreenOn('Display Power: state=ON')).toBe(true);
    expect(parseScreenOn('unrelated')).toBeUndefined();
  });

  it('reports doze state or its light/deep components', () => {
    expect(parseDozeState('mState=ACTIVE')).toBe('ACTIVE');
    expect(parseDozeState('mDeepSleepState=1\nmLightState=0')).toBe('deep=1,light=0');
    expect(parseDozeState('nothing')).toBeUndefined();
  });

  it('assembles the conditions record without enforcing anything', () => {
    expect(parseBatteryConditions('128', 'mWakefulness=Awake', 'mState=ACTIVE')).toEqual({
      screenBrightness: '128',
      screenOn: 'on',
      doze: 'ACTIVE',
    });
    expect(parseBatteryConditions('null', '', '')).toEqual({});
  });
});
