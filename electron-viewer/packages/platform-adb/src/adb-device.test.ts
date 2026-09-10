import { describe, expect, it } from 'vitest';
import { parseAdbDevices } from './adb-device.js';
import { AdbOutputParseError } from './adb-errors.js';

const OUTPUT = [
  'List of devices attached',
  '* daemon not running; starting now at tcp:5037',
  'adb server version (41) does not match this client (39)',
  'emulator-5554 device product:sdk_gphone64 model:sdk_gphone64 device:emu64a transport_id:1',
  'ABC123 offline',
  'XYZ789 unauthorized',
  'NOAUTH no permissions (user in plugdev group; are your udev rules wrong?); see [http://developer.android.com/tools/device.html]',
  '',
].join('\n');

describe('parseAdbDevices', () => {
  it('parses devices, states, attributes, and noise', () => {
    const devices = parseAdbDevices(OUTPUT);
    expect(devices).toHaveLength(4);
    expect(devices[0]).toMatchObject({
      serial: 'emulator-5554',
      state: 'ONLINE',
      product: 'sdk_gphone64',
      model: 'sdk_gphone64',
      device: 'emu64a',
      transportId: 1,
    });
    expect(devices[1]).toMatchObject({ serial: 'ABC123', state: 'OFFLINE', rawState: 'offline' });
    expect(devices[2]).toMatchObject({ serial: 'XYZ789', state: 'UNAUTHORIZED' });
    expect(devices[3]).toMatchObject({ serial: 'NOAUTH', state: 'NO_PERMISSIONS' });
    expect(devices[3]?.statusDetail).toContain('udev rules');
  });

  it('returns an empty list for an empty attached list', () => {
    expect(parseAdbDevices('List of devices attached\n\n')).toEqual([]);
  });

  it('rejects a malformed line', () => {
    expect(() => parseAdbDevices('List of devices attached\njustaserial\n')).toThrow(AdbOutputParseError);
  });
});
