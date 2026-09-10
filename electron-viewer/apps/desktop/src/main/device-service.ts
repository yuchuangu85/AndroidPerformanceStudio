import { AdbClient, AdbExecutableLocator, AdbNotFoundError, type AdbLocation } from '@aps/platform-adb';
import type { AdbStatus, DeviceSummary } from '../shared/ipc.js';

export interface AdbAccess {
  readonly status: AdbStatus;
  listDevices(): Promise<DeviceSummary[]>;
}

export function openAdb(androidSdkPath: string | undefined): AdbAccess {
  let location: AdbLocation;
  try {
    location = new AdbExecutableLocator().locate(
      androidSdkPath !== undefined && androidSdkPath.trim().length > 0
        ? { androidSdkPath }
        : {},
    );
  } catch (error) {
    const message = error instanceof AdbNotFoundError ? error.message : error instanceof Error ? error.message : 'ADB unavailable';
    return { status: { available: false, error: message }, listDevices: async () => [] };
  }
  const client = new AdbClient({ executable: location.executable });
  return {
    status: { available: true, executable: location.executable, source: location.source },
    listDevices: async (): Promise<DeviceSummary[]> => {
      const devices = await client.listDevices();
      return devices.map((device) => ({
        serial: device.serial,
        state: device.state,
        ...(device.model !== undefined ? { model: device.model } : {}),
        ...(device.product !== undefined ? { product: device.product } : {}),
      }));
    },
  };
}
