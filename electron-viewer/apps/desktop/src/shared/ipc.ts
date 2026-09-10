import type { AppDestination } from './destinations.js';
import type { ApplicationUiSettings } from './settings-contract.js';

export interface AppInfo {
  readonly name: string;
  readonly version: string;
  readonly contractVersion: number;
  readonly platform: string;
}

export type AdbDeviceState = 'ONLINE' | 'OFFLINE' | 'UNAUTHORIZED' | 'NO_PERMISSIONS' | 'UNKNOWN';

export interface DeviceSummary {
  readonly serial: string;
  readonly state: AdbDeviceState;
  readonly model?: string;
  readonly product?: string;
}

export interface AdbStatus {
  readonly available: boolean;
  readonly executable?: string;
  readonly source?: string;
  readonly error?: string;
}

export interface TraceProcessorStatus {
  readonly available: boolean;
  readonly path?: string;
  readonly version?: string;
  readonly error?: string;
}

export interface MigrationStatus {
  readonly source: 'stored' | 'migrated' | 'default';
  readonly migratedKeys: readonly string[];
}

export interface ShellSnapshot {
  readonly appInfo: AppInfo;
  readonly settings: ApplicationUiSettings;
  readonly adb: AdbStatus;
  readonly devices: readonly DeviceSummary[];
  readonly traceProcessor: TraceProcessorStatus;
  readonly migration: MigrationStatus;
}

export type ApplicationUiSettingsPatch = Partial<ApplicationUiSettings>;

export interface ApsApi {
  getShellSnapshot(): Promise<ShellSnapshot>;
  updateSettings(patch: ApplicationUiSettingsPatch): Promise<ShellSnapshot>;
  refreshDevices(): Promise<ShellSnapshot>;
  openDestination(destination: AppDestination): Promise<void>;
}

export const IPC_CHANNELS = {
  shellSnapshot: 'shell:getSnapshot',
  updateSettings: 'shell:updateSettings',
  refreshDevices: 'shell:refreshDevices',
  openDestination: 'shell:openDestination',
} as const;
