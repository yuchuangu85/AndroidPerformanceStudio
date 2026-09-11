import type { BatteryCaptureMode, BatteryExperimentResult } from '@aps/battery-profiler';
import type { AgiCapability, ArtifactLocationStatus, ArtifactOpenRoute, GpuArtifactKind } from '@aps/gpu-inspector';
import type { MemorySession } from '@aps/memory-profiler';
import type { FlameGraphPayload, ImplementationFilter } from '@aps/profile-analysis';
import type {
  BuildIdentityMatch,
  ResolutionConfidence,
  SourceLanguage,
  SourceResolutionEvidence,
  SourceSymbolKind,
} from '@aps/source-workspace';
import type {
  CallGraphMode,
  CpuProfileFlameGraph,
  CpuProfileSessionRecord,
  CpuTransformRequest,
  EventScope,
} from '@aps/simpleperf-profiler';
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

export interface GpuArtifactSummary {
  readonly id: string;
  readonly kind: GpuArtifactKind;
  readonly path: string;
  readonly sizeBytes: number;
  readonly sha256: string;
  readonly openRoute: ArtifactOpenRoute;
  readonly locationStatus: ArtifactLocationStatus;
  readonly locationCount: number;
  readonly importedAtEpochMillis: number;
  readonly warningCount: number;
  readonly agiVersion?: string;
}

export interface GpuArtifactLocationReport {
  readonly id: string;
  readonly status: ArtifactLocationStatus;
  readonly path?: string;
}

export interface GpuOutcome {
  readonly ok: boolean;
  readonly id?: string;
  readonly cancelled?: boolean;
  readonly error?: string;
  readonly note?: string;
}

export interface MemorySessionSummary {
  readonly id: string;
  readonly capturedAtEpochMillis: number;
  readonly instanceCount: number;
  readonly classCount: number;
  readonly shallowBytes: number;
  readonly suspectCount: number;
  readonly warningCount: number;
  readonly packageName?: string;
  readonly deviceSerial?: string;
}

export interface MemoryCaptureInput {
  readonly serial: string;
  readonly packageName: string;
}

export interface MemoryCaptureOutcome {
  readonly ok: boolean;
  readonly id?: string;
  readonly error?: string;
}

export interface CpuCaptureRequest {
  readonly serial: string;
  readonly packageName?: string;
  readonly target: 'APP' | 'SYSTEM_WIDE';
  readonly event: string;
  readonly frequencyHertz: number;
  readonly durationSeconds: number;
  readonly callGraph: CallGraphMode;
  readonly scope: EventScope;
}

export interface CpuSnapshotRequest {
  readonly id: string;
  readonly threadKey?: string;
  readonly searchText: string;
  readonly implementation: ImplementationFilter;
  readonly direction: 'FORWARD' | 'INVERTED';
  readonly transforms: readonly CpuTransformRequest[];
}

export interface CpuSnapshotOutcome {
  readonly ok: boolean;
  readonly graph?: CpuProfileFlameGraph;
  readonly error?: string;
}

export interface MethodCaptureRequest {
  readonly serial: string;
  readonly packageName: string;
  readonly pid: number;
  readonly durationSeconds: number;
}

export interface MethodSessionRecord {
  readonly id: string;
  readonly capturedAtEpochMillis: number;
  readonly serial: string;
  readonly packageName: string;
  readonly pid: number;
  readonly durationSeconds: number;
  readonly deviceSdkApiLevel: number;
  readonly traceVersion: number;
  readonly traceBytes: number;
  readonly eventCount: number;
  readonly methodCount: number;
  readonly threadCount: number;
  readonly threadKeys: readonly string[];
  readonly warnings: readonly string[];
}

export type MethodRankBy = 'SELF_MICROS' | 'TOTAL_MICROS' | 'CALL_COUNT' | 'SYMBOL';

export interface MethodSnapshotRequest {
  readonly id: string;
  readonly threadKey?: string;
  readonly searchText: string;
  readonly direction: 'FORWARD' | 'INVERTED';
  readonly transforms: readonly CpuTransformRequest[];
  readonly rankBy: MethodRankBy;
}

export interface MethodTopRow {
  readonly functionId: string;
  readonly symbolName: string;
  readonly resource: string;
  readonly selfMicros: number;
  readonly totalMicros: number;
  readonly callCount: number;
  readonly threadCount: number;
}

export interface MethodSnapshotOutcome {
  readonly ok: boolean;
  readonly graph?: FlameGraphPayload;
  readonly methods?: readonly MethodTopRow[];
  readonly error?: string;
}

export interface SourceWorkspaceRecord {
  readonly id: string;
  readonly displayName: string;
  readonly root: string;
  readonly phase: 'READY' | 'PARTIAL' | 'FAILED';
  readonly message?: string;
  readonly revision?: string;
  readonly manifestHash?: string;
  readonly fileCount: number;
  readonly symbolCount: number;
  readonly indexedAtEpochMillis?: number;
}

export interface SourceCandidateSummary {
  readonly id: string;
  readonly evidenceId: string;
  readonly relativePath: string;
  readonly startLine?: number;
  readonly confidence: ResolutionConfidence;
  readonly reasons: readonly string[];
  readonly indexComplete: boolean;
}

export interface SourceSymbolSummary {
  readonly kind: SourceSymbolKind;
  readonly qualifiedName: string;
  readonly relativePath: string;
  readonly signature?: string;
  readonly startLine: number;
}

export interface SourceResolveRequest {
  readonly workspaceId: string;
  readonly evidence: readonly SourceResolutionEvidence[];
  readonly buildIdentityMatch: BuildIdentityMatch;
}

export interface SourceResolveOutcome {
  readonly ok: boolean;
  readonly candidates?: readonly SourceCandidateSummary[];
  readonly error?: string;
}

export interface SourceReadOutcome {
  readonly ok: boolean;
  readonly relativePath?: string;
  readonly text?: string;
  readonly language?: SourceLanguage;
  readonly state?: 'CURRENT' | 'STALE';
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
  getAgiStatus(): Promise<AgiCapability>;
  launchAgi(): Promise<GpuOutcome>;
  importGpuArtifact(): Promise<GpuOutcome>;
  listGpuArtifacts(): Promise<readonly GpuArtifactSummary[]>;
  openGpuArtifact(id: string): Promise<GpuOutcome>;
  revealGpuArtifact(id: string): Promise<GpuOutcome>;
  relocateGpuArtifact(id: string): Promise<GpuOutcome>;
  importTraceFromPath(path: string): Promise<GpuOutcome>;
  listSourceWorkspaces(): Promise<readonly SourceWorkspaceRecord[]>;
  addSourceWorkspace(): Promise<MemoryCaptureOutcome>;
  removeSourceWorkspace(id: string): Promise<boolean>;
  reindexSourceWorkspace(id: string): Promise<MemoryCaptureOutcome>;
  searchSourceSymbols(input: { readonly workspaceId: string; readonly query: string; readonly limit: number }): Promise<readonly SourceSymbolSummary[]>;
  resolveSourceEvidence(input: SourceResolveRequest): Promise<SourceResolveOutcome>;
  readSourceFile(input: { readonly workspaceId: string; readonly relativePath: string }): Promise<SourceReadOutcome>;
  captureMethodRecording(input: MethodCaptureRequest): Promise<MemoryCaptureOutcome>;
  listMethodSessions(): Promise<readonly MethodSessionRecord[]>;
  methodSnapshot(input: MethodSnapshotRequest): Promise<MethodSnapshotOutcome>;
  removeMethodSession(id: string): Promise<boolean>;
  captureCpuProfile(input: CpuCaptureRequest): Promise<MemoryCaptureOutcome>;
  importCpuProfile(): Promise<MemoryCaptureOutcome>;
  listCpuProfiles(): Promise<readonly CpuProfileSessionRecord[]>;
  cpuSnapshot(input: CpuSnapshotRequest): Promise<CpuSnapshotOutcome>;
  removeCpuProfile(id: string): Promise<boolean>;
  captureMemory(input: MemoryCaptureInput): Promise<MemoryCaptureOutcome>;
  listMemorySessions(): Promise<readonly MemorySessionSummary[]>;
  loadMemorySession(id: string): Promise<MemorySession | undefined>;
  getAiSettings(): Promise<AiSettingsSnapshot>;
  saveAiCredential(value: string): Promise<AiSettingsSnapshot>;
  clearAiCredential(): Promise<AiSettingsSnapshot>;
  listAiModels(): Promise<readonly string[]>;
  analyzeLayoutWithAi(input: AiAnalyzeRequest): Promise<AiAnalyzeOutcome>;
  listAiSessions(): Promise<readonly AiSessionSummary[]>;
  loadAiFindings(sessionId: string): Promise<readonly AnalysisFinding[]>;
}

export interface AiSettingsSnapshot {
  readonly configured: boolean;
  readonly persistent: boolean;
  readonly model: string;
}

export interface AiAnalyzeRequest {
  readonly captureId: string;
  readonly selectedNodeId?: string;
  readonly model?: string;
}

export interface AiAnalyzeOutcome {
  readonly ok: boolean;
  readonly sessionId: string;
  readonly model?: string;
  readonly summary?: string;
  readonly findings?: readonly AnalysisFinding[];
  readonly error?: string;
}

export interface AnalysisFinding {
  readonly id: string;
  readonly severity: 'INFO' | 'WARNING' | 'ERROR';
  readonly title: string;
  readonly explanation: string;
  readonly recommendation: string;
  readonly analysisConfidence: number;
  readonly performanceEvidenceIds: readonly string[];
  readonly sourceCandidateIds: readonly string[];
}

export interface AiSessionSummary {
  readonly id: string;
  readonly createdAt: string;
  readonly status: string;
  readonly model: string | null;
  readonly provider: string | null;
  readonly scope: string;
  readonly summary: string | null;
  readonly errorMessage: string | null;
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
  gpuStatus: 'gpu:status',
  gpuLaunch: 'gpu:launch',
  gpuImport: 'gpu:import',
  gpuList: 'gpu:list',
  gpuOpen: 'gpu:open',
  gpuReveal: 'gpu:reveal',
  gpuRelocate: 'gpu:relocate',
  traceImportFromPath: 'trace:importFromPath',
  sourceList: 'source:list',
  sourceAdd: 'source:add',
  sourceRemove: 'source:remove',
  sourceReindex: 'source:reindex',
  sourceSearch: 'source:search',
  sourceResolve: 'source:resolve',
  sourceRead: 'source:read',
  methodCapture: 'method:capture',
  methodList: 'method:list',
  methodSnapshot: 'method:snapshot',
  methodRemove: 'method:remove',
  cpuCapture: 'cpu:capture',
  cpuImport: 'cpu:import',
  cpuList: 'cpu:list',
  cpuSnapshot: 'cpu:snapshot',
  cpuRemove: 'cpu:remove',
  memoryCapture: 'memory:capture',
  memoryList: 'memory:list',
  memoryLoad: 'memory:load',
  aiSettings: 'ai:settings',
  aiSaveCredential: 'ai:saveCredential',
  aiClearCredential: 'ai:clearCredential',
  aiModels: 'ai:models',
  aiAnalyze: 'ai:analyze',
  aiSessions: 'ai:sessions',
  aiFindings: 'ai:findings',
} as const;
