import { describe, expect, it } from 'vitest';
import { parseBatteryDeviceState, parseBatteryHistory, parseBatteryStats, splitCheckinCsv } from './parser.js';

// Section layout per timer record: durationUs, count, then three unused columns.
const CHECKIN = [
  '7,0,l,start,1700000000',
  '9,11000,l,wl,AudioMix,1500000,4,0,0,0,0',
  '9,11000,l,wl,AudioMix,500000,1,0,0,0,0',
  '9,11000,l,wl,*,5000000,2,0,0,0',
  '9,11000,l,apk,com.example.app.Alarm,0,3,0,0,0',
  '9,11000,l,jb,com.example.app.JobService,2000000,2,0,0,0',
  '9,11000,l,sr,android.sensor.accelerometer,1000000,5,0,0,0',
  '9,11000,l,nt,100,200,300,400,0,0,1,2,3,4,500000',
  '9,11000,p,wl,OtherAggregation,999,9,0,0,0',
  '9,11001,l,wl,OtherUid,999,9,0,0,0',
  'not,a,record',
].join('\n');

const REPORT = [
  'Estimated power use (mAh):',
  '  Capacity: 3000, Computed drain: 45.6, EXCLUDING',
  '  Uid u0a1000: 12.5 ( cpu=8.0 wifi=4.5 )',
  '  Uid u0a1001: 99.9 ( cpu=99.9 )',
].join('\n');

const BATTERY = [
  'Current Battery Service state:',
  '  AC powered: false',
  '  USB powered: true',
  '  status: 2',
  '  level: 87',
  '  temperature: 305',
  '  voltage: 4200',
].join('\n');

const TARGET_UID = 10_000 + 1000;

describe('splitCheckinCsv', () => {
  it('honours quoted fields', () => {
    expect(splitCheckinCsv('a,"b,c",d')).toEqual(['a', 'b,c', 'd']);
    expect(splitCheckinCsv('a,"say ""hi""",d')).toEqual(['a', 'say "hi"', 'd']);
  });
});

describe('parseBatteryStats', () => {
  it('aggregates timers for the target uid and skips other aggregations and uids', () => {
    const parsed = parseBatteryStats(CHECKIN, REPORT, BATTERY, TARGET_UID);
    expect(parsed.uidStats.uid).toBe(TARGET_UID);
    expect(parsed.uidStats.wakelocks['AudioMix']).toEqual({
      name: 'AudioMix',
      durationMs: 2000,
      count: 5,
      confidence: 'EXACT',
    });
    expect(parsed.uidStats.wakelocks['OtherAggregation']).toBeUndefined();
    expect(parsed.uidStats.wakelocks['OtherUid']).toBeUndefined();
    expect(parsed.uidStats.alarms['com.example.app.Alarm']?.count).toBe(3);
    expect(parsed.uidStats.jobs['com.example.app.JobService']?.durationMs).toBe(2000);
    expect(parsed.uidStats.sensors['android.sensor.accelerometer']?.count).toBe(5);
    expect(parsed.statsPeriodId).toBe('1700000000');
  });

  it('parses network counters with microsecond radio time', () => {
    const parsed = parseBatteryStats(CHECKIN, REPORT, BATTERY, TARGET_UID);
    expect(parsed.uidStats.network).toMatchObject({
      mobileRxBytes: 100,
      mobileTxBytes: 200,
      wifiRxBytes: 300,
      wifiTxBytes: 400,
      mobileRadioActiveMs: 500,
    });
  });

  it('attributes modeled energy to the uid and ignores other uids', () => {
    const parsed = parseBatteryStats(CHECKIN, REPORT, BATTERY, TARGET_UID);
    expect(parsed.uidStats.energy['total']).toEqual({
      component: 'total',
      energyMah: 12.5,
      source: 'SYSTEM_MODEL',
      attributionScope: 'UID',
      confidence: 'MODELED',
    });
    expect(parsed.uidStats.energy['cpu']?.energyMah).toBe(8);
    expect(Object.values(parsed.uidStats.energy).some((estimate) => estimate.energyMah === 99.9)).toBe(false);
  });

  it('warns about framework-mediated names and malformed lines', () => {
    const parsed = parseBatteryStats(CHECKIN, REPORT, BATTERY, TARGET_UID);
    expect(parsed.warnings.some((warning) => warning.includes('Framework-mediated'))).toBe(true);
    expect(parsed.warnings.some((warning) => warning.includes('malformed checkin line'))).toBe(true);
  });

  it('warns when the target uid has no records', () => {
    const parsed = parseBatteryStats(CHECKIN, REPORT, BATTERY, 12345);
    expect(parsed.warnings).toContain('No checkin records were found for UID 12345.');
  });
});

describe('parseBatteryDeviceState', () => {
  it('parses level, temperature, voltage, and charging state', () => {
    const state = parseBatteryDeviceState(BATTERY);
    expect(state.levelPercent).toBe(87);
    expect(state.temperatureTenthsCelsius).toBe(305);
    expect(state.voltageMillivolts).toBe(4200);
    expect(state.powered).toBe(true);
    expect(state.status).toBe('2');
  });
});

describe('parseBatteryHistory', () => {
  it('classifies marked history events and keeps unmarked ones inferred', () => {
    const events = parseBatteryHistory(
      ['  9,h,+wake_lock=AudioMix,uid=10123', '  10,h,-wake_lock=AudioMix', '  11,h,unmarked line'].join('\n'),
    );
    expect(events).toHaveLength(3);
    expect(events[0]).toMatchObject({ kind: 'WAKELOCK', active: true, elapsedMs: 9 });
    expect(events[1]).toMatchObject({ kind: 'WAKELOCK', active: false });
    expect(events[2]?.confidence).toBe('INFERRED');
  });
});
