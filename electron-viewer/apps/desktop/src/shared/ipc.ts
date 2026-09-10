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

export interface TraceRecordSummary {
  readonly id: string;
  readonly path: string;
  readonly sha256: string;
  readonly capturedAtEpochMillis: number;
  readonly durationMillis: number;
  readonly deviceSerial?: string;
}

export interface PerfettoUiStatus {
  readonly available: boolean;
  readonly directory?: string;
}

export interface TraceAnalyzerSnapshot {
  readonly traces: readonly TraceRecordSummary[];
  readonly ui: PerfettoUiStatus;
}

export interface TraceCaptureInput {
  readonly serial: string;
  readonly durationMillis: number;
  readonly bufferSizeKb: number;
  readonly dataSource: string;
}

export interface TraceCaptureOutcome {
  readonly ok: boolean;
  readonly record?: TraceRecordSummary;
  readonly error?: string;
}

export interface TraceOpenOutcome {
  readonly ok: boolean;
  readonly error?: string;
}

export type ApplicationUiSettingsPatch = Partial<ApplicationUiSettings>;

export interface ApsApi {
  getShellSnapshot(): Promise<ShellSnapshot>;
  updateSettings(patch: ApplicationUiSettingsPatch): Promise<ShellSnapshot>;
  refreshDevices(): Promise<ShellSnapshot>;
  openDestination(destination: AppDestination): Promise<void>;
  getTraceAnalyzer(): Promise<TraceAnalyzerSnapshot>;
  captureTrace(input: TraceCaptureInput): Promise<TraceCaptureOutcome>;
  openTraceInAnalyzer(id: string): Promise<TraceOpenOutcome>;
}

export const IPC_CHANNELS = {
  shellSnapshot: 'shell:getSnapshot',
  updateSettings: 'shell:updateSettings',
  refreshDevices: 'shell:refreshDevices',
  openDestination: 'shell:openDestination',
  traceAnalyzer: 'trace:getAnalyzer',
  traceCapture: 'trace:capture',
  traceOpen: 'trace:openInAnalyzer',
} as const;
