import type { BatteryCaptureMode, BatteryExperimentResult } from '@aps/battery-profiler';
import type { RegressionReport } from '@aps/benchmark-regression';
import type { FrameSession } from '@aps/frame-profiler';
import type { LayoutSnapshot } from '@aps/layout-inspector';
import type { NetworkCaptureResult } from '@aps/network-profiler';
import type { StartupSession, StartupType } from '@aps/startup-profiler';
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

export interface TraceRevealOutcome {
  readonly ok: boolean;
  readonly error?: string;
}

export interface LayoutCaptureSummary {
  readonly id: string;
  readonly packageName: string;
  readonly capturedAtEpochMillis: number;
  readonly nodeCount: number;
}

export interface LayoutCaptureOutcome {
  readonly ok: boolean;
  readonly id?: string;
  readonly error?: string;
}

export interface LayoutCaptureDetail {
  readonly snapshot: LayoutSnapshot;
  readonly screenshotBase64?: string;
}

export interface FrameSessionSummary {
  readonly id: string;
  readonly packageName: string;
  readonly capturedAtEpochMillis: number;
  readonly frameCount: number;
  readonly deadlineMissRate?: number;
  readonly platformJankRate?: number;
  readonly worstDurationNs?: number;
}

export interface FrameCaptureInput {
  readonly serial: string;
  readonly packageName: string;
}

export interface FrameCaptureOutcome {
  readonly ok: boolean;
  readonly id?: string;
  readonly error?: string;
}

export interface StartupSessionSummary {
  readonly id: string;
  readonly packageName: string;
  readonly capturedAtEpochMillis: number;
  readonly measuredRuns: number;
  readonly medianTotalTimeMs?: number;
  readonly p90TotalTimeMs?: number;
  readonly p90LowResolution: boolean;
}

export interface StartupCaptureInput {
  readonly serial: string;
  readonly packageName: string;
  readonly componentName?: string;
  readonly requestedType: StartupType;
  readonly warmupRuns: number;
  readonly measuredRuns: number;
  readonly timeoutSeconds: number;
}

export interface StartupCaptureOutcome {
  readonly ok: boolean;
  readonly id?: string;
  readonly error?: string;
}

export interface BatterySessionSummary {
  readonly id: string;
  readonly packageName: string;
  readonly uid: number;
  readonly capturedAtEpochMillis: number;
  readonly runCount: number;
  readonly warningCount: number;
  readonly wakelockMedianMs?: number;
  readonly networkMedianBytes?: number;
  readonly energyMedianMah?: number;
}

export interface BatteryCaptureInput {
  readonly serial: string;
  readonly packageName: string;
  readonly uid: number;
  readonly mode: BatteryCaptureMode;
  readonly durationSeconds: number;
  readonly pollingIntervalSeconds: number;
  readonly measuredRuns: number;
  readonly cooldownSeconds: number;
}

export interface BatteryCaptureOutcome {
  readonly ok: boolean;
  readonly id?: string;
  readonly error?: string;
}

export interface NetworkSessionSummary {
  readonly id: string;
  readonly callCount: number;
  readonly failedCallCount: number;
  readonly incompleteCallCount: number;
  readonly status: string;
  readonly startedAtEpochMillis: number;
  readonly producer?: string;
  readonly sourceFormatVersion?: string;
}

export interface NetworkImportOutcome {
  readonly ok: boolean;
  readonly id?: string;
  readonly cancelled?: boolean;
  readonly error?: string;
}

export interface BenchmarkRunSummary {
  readonly id: string;
  readonly sourceFile: string;
  readonly caseCount: number;
  readonly importedAtEpochMillis: number;
  readonly warningCount: number;
  readonly deviceModel?: string;
  readonly apiLevel?: number;
  readonly abi?: string;
  readonly variant?: string;
}

export interface BenchmarkImportOutcome {
  readonly ok: boolean;
  readonly id?: string;
  readonly cancelled?: boolean;
  readonly error?: string;
}

export interface BenchmarkCompareInput {
  readonly baselineId: string;
  readonly currentId: string;
  readonly relativeThresholdPercent?: number;
  readonly absoluteThreshold?: number;
}

export interface BenchmarkCompareOutcome {
  readonly ok: boolean;
  readonly report?: RegressionReport;
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
  openPublicPerfettoUi(): Promise<void>;
  revealTrace(id: string): Promise<TraceRevealOutcome>;
  captureLayout(serial: string): Promise<LayoutCaptureOutcome>;
  listLayoutCaptures(): Promise<readonly LayoutCaptureSummary[]>;
  loadLayoutCapture(id: string): Promise<LayoutCaptureDetail | undefined>;
  captureFrame(input: FrameCaptureInput): Promise<FrameCaptureOutcome>;
  listFrameSessions(): Promise<readonly FrameSessionSummary[]>;
  loadFrameSession(id: string): Promise<FrameSession | undefined>;
  captureStartup(input: StartupCaptureInput): Promise<StartupCaptureOutcome>;
  listStartupSessions(): Promise<readonly StartupSessionSummary[]>;
  loadStartupSession(id: string): Promise<StartupSession | undefined>;
  captureBattery(input: BatteryCaptureInput): Promise<BatteryCaptureOutcome>;
  listBatterySessions(): Promise<readonly BatterySessionSummary[]>;
  loadBatterySession(id: string): Promise<BatteryExperimentResult | undefined>;
  importNetworkHar(): Promise<NetworkImportOutcome>;
  listNetworkSessions(): Promise<readonly NetworkSessionSummary[]>;
  loadNetworkSession(id: string): Promise<NetworkCaptureResult | undefined>;
  importBenchmarkRun(): Promise<BenchmarkImportOutcome>;
  listBenchmarkRuns(): Promise<readonly BenchmarkRunSummary[]>;
  compareBenchmarkRuns(input: BenchmarkCompareInput): Promise<BenchmarkCompareOutcome>;
}

export const IPC_CHANNELS = {
  shellSnapshot: 'shell:getSnapshot',
  updateSettings: 'shell:updateSettings',
  refreshDevices: 'shell:refreshDevices',
  openDestination: 'shell:openDestination',
  traceAnalyzer: 'trace:getAnalyzer',
  traceCapture: 'trace:capture',
  traceOpen: 'trace:openInAnalyzer',
  traceOpenPublicUi: 'trace:openPublicUi',
  traceReveal: 'trace:reveal',
  layoutCapture: 'layout:capture',
  layoutList: 'layout:list',
  layoutLoad: 'layout:load',
  frameCapture: 'frame:capture',
  frameList: 'frame:list',
  frameLoad: 'frame:load',
  startupCapture: 'startup:capture',
  startupList: 'startup:list',
  startupLoad: 'startup:load',
  batteryCapture: 'battery:capture',
  batteryList: 'battery:list',
  batteryLoad: 'battery:load',
  networkImport: 'network:import',
  networkList: 'network:list',
  networkLoad: 'network:load',
  benchmarkImport: 'benchmark:import',
  benchmarkList: 'benchmark:list',
  benchmarkCompare: 'benchmark:compare',
} as const;
