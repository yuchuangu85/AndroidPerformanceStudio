import { randomUUID } from 'node:crypto';
import { statSync } from 'node:fs';
import { mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { homedir, tmpdir } from 'node:os';
import { basename, delimiter, dirname } from 'node:path';

import { join } from 'node:path';
import { app, BrowserWindow, dialog, ipcMain, nativeTheme, shell } from 'electron';
import { encodeCaptureArtifact, fail, ok, type StudioResult } from '@aps/contracts';
import { sha256File } from '@aps/contracts/node';
import { walkNode, type LayoutSnapshot } from '@aps/layout-inspector';
import { AdbClient } from '@aps/platform-adb';
import type { SourceResolutionEvidence } from '@aps/source-workspace';
import { findPerfettoUiAssetsDirectory, type PerfettoUiAssetProbe } from '@aps/platform-perfetto';
import { DEFAULT_DISPLAY_SCALE_PERCENT, JsonSettingsStore } from '@aps/settings';
import { buildAppInfo } from '../shared/app-info.js';
import { installViewerMenu, idleViewerMenuState } from './menu.js';
import { RecentPathStore } from './recent-path-store.js';
import type { ViewerMenuCommand, ViewerMenuState } from '../shared/viewer-menu.js';
import { resolveLanguage } from '../shared/i18n.js';
import { shouldMaximizeWindow } from '../shared/destinations.js';
import {
  IPC_CHANNELS,
  type LayoutArchiveOutcome,
  type MigrationStatus,
  type MethodSessionRecord,
  type NativeHeapCaptureRecord,
  type ShellSnapshot,
  type TraceAnalyzerSnapshot,
  type TraceCaptureInput,
  type TraceCaptureOutcome,
  type TraceOpenOutcome,
} from '../shared/ipc.js';
import {
  requireAdbSerial,
  requireAiAnalyzeRequest,
  requireAiConfigurationInput,
  requireAiCredential,
  requireAiSourceCandidateOpenRequest,
  requireAppDestination,
  requireApplicationUiSettingsPatch,
  requireBenchmarkCompareInput,
  requireAndroidAppTarget,
  requireBatteryCaptureInput,
  requireBitmapImageRequest,
  requireCpuCaptureInput,
  requireCpuSnapshotRequest,
  requireFrameCaptureInput,
  requireLayoutCaptureInput,
  requireMemoryDiffRequest,
  requireMemoryInstanceDetailRequest,
  requireMemoryInstanceRequest,
  requireMethodCaptureInput,
  requireMethodSnapshotRequest,
  requireOpaqueRecordId,
  requireSourceAiUploadRequest,
  requireSourceReadRequest,
  requireSourceResolveRequest,
  requireSourceSearchRequest,
  requireStartupCaptureInput,
  requireTraceCaptureInput,
  requireTraceImportPath,
  requireUiLanguage,
  requireViewerMenuState,
} from '../shared/ipc-validation.js';
import {
  DEFAULT_SETTINGS,
  mergeApplicationUiSettings,
  type ApplicationUiSettings,
  type ApplicationUiSettingsPatch,
} from '../shared/settings-contract.js';
import { openAdb } from './device-service.js';
import { createLegacyPreferenceSource, defaultLegacySourceDependencies } from './legacy-prefs-source.js';
import {
  PERFETTO_UI_SCHEME,
  TRACE_SCHEME,
  installPerfettoProtocolHandlers,
  registerPerfettoSchemes,
} from './perfetto-protocol.js';
import { loadApplicationSettings } from './settings-service.js';
import { captureFrameSession } from './frame-capture-service.js';
import { FrameSessionStore } from './frame-session-store.js';
import { captureLayoutSnapshot } from './layout-capture-service.js';
import { LayoutCaptureStore } from './layout-capture-store.js';
import { CaptureArchiveService } from './capture-archive-service.js';
import { captureWithVerifiedComposeAgent } from './verified-compose-capture.js';
import { kotlinApplicationDataDirectory, migrateLegacyElectronData } from './app-data-directory.js';
import { UserDocumentationService } from './user-documentation-service.js';
import { FirefoxProfilerService, geckoProfileForCpuSession } from './firefox-profiler-service.js';
import { runBatteryExperiment } from './battery-capture-service.js';
import { BatterySessionStore } from './battery-session-store.js';
import { compareBenchmarkRuns, DEFAULT_REGRESSION_POLICY } from '@aps/benchmark-regression';
import { launchHostProcess, runHostProcessText } from '@aps/platform-host';
import { locateAgi, safeLaunchArguments, type AgiCapability, type AgiLocatorDependencies } from '@aps/gpu-inspector';
import { GpuArtifactStore } from './gpu-artifact-store.js';
import { importBenchmarkJson } from './benchmark-import-service.js';
import { BenchmarkStore } from './benchmark-store.js';
import { importHarFile } from './network-import-service.js';
import { NetworkSessionStore } from './network-session-store.js';
import { runStartupExperiment } from './startup-capture-service.js';
import { StartupSessionStore } from './startup-session-store.js';
import { readKotlinStartupJsonFile } from './startup-json-file.js';
import { importKotlinStartupSqlite } from './startup-sqlite-import.js';
import { exportKotlinStartupJson, importKotlinStartupJson } from '@aps/startup-profiler';
import {
  buildFlameGraphPayload,
  createCpuProfileSession,
  directionOf,
  parseSimpleperfEventNames,
  reportSampleArguments,
  metadataRecord,
  transformFromRequest,
  type CpuProfileSessionRecord,
} from '@aps/simpleperf-profiler';
import {
  buildFlameGraphPayload as buildFlameGraphPayloadOf,
  directionOf as directionOfQuery,
  type CallStackTable,
  type FlameGraphPayload,
} from '@aps/profile-analysis';
import { threadKeyOf, topMethods, type ArtTraceAnalysis } from '@aps/art-trace';
import { captureMethodRecording, discoverMethodTraceProcesses } from './method-capture-service.js';
import { importMethodTraceFile } from './method-import-service.js';
import {
  migrateLegacyWorkspaces,
  readBackendSource,
  searchBackendSymbols,
  sourceBackend,
  toSourceWorkspaceRecords,
  type LegacySourceWorkspace,
} from './source-backend.js';
import { SafeStorageCredentialStore, SqliteAnalysisSessionRepository, fetchAiTransport, isPersistentBackend } from '@aps/ai-core/node';
import { AiAnalysisService, aiCredentialFilePath, aiSessionsDatabasePath, ensureAiDirectory, layoutPerformanceEvidence } from './ai-service.js';
import { safeStorage } from 'electron';
import { MethodSessionStore, type StoredMethodSession } from './method-session-store.js';
import { MethodRecordingLifecycle } from './method-recording-lifecycle.js';
import {
  defaultConversionDependencies,
  defaultHostSimpleperfLocatorDependencies,
  locateHostSimpleperf,
  runReportSample,
  type HostSimpleperfLocatorDependencies,
} from '@aps/simpleperf-profiler/node';
import { captureCpuProfile } from './cpu-capture-service.js';
import { formatOfFile, importCpuProfileAsync } from './cpu-import-service.js';
import { defaultReportFile } from './cpu-profile-store.js';
import { CpuProfileStore } from './cpu-profile-store.js';
import { importCpuSessionPackage, isCpuSessionPackageFileName } from './cpu-session-package-import.js';
import { exportCpuSessionPackage } from './cpu-session-package-export.js';
import { SessionPackageCodec } from './session-package-codec.js';
import { parseCpuProfileReport } from './cpu-profile-parser.js';
import {
  importOfflineCpuProfileInWorker,
  parseHeapDumpInWorker,
  parseMethodTraceInWorker,
  parseSimpleperfReportInWorker,
} from './parser-worker-runner.js';
import {
  captureBitmapDump,
  captureHeapDump,
  captureNativeHeapTrace,
  type ExtendedMemoryCaptureDependencies,
} from './memory-capture-service.js';
import { compareMemorySessions } from './memory-diff-service.js';
import { sourceLocationForPersistedAiCandidate } from './ai-source-navigation.js';
import { MemorySessionStore } from './memory-session-store.js';
import { loadPersistedMemoryHeap } from './memory-persisted-heap.js';
import { BitmapDumpStore, NativeHeapStore } from './memory-artifact-stores.js';
import { cacheHeap, cachedHeap } from './memory-heap-cache.js';
import { instancesOf, instanceDetail, type HprofParseResult } from '@aps/memory-profiler';
import { capturePerfettoTrace } from './trace-capture-service.js';
import { TraceStore } from './trace-store.js';
import { resolveTraceProcessorStatus } from './trace-service.js';

// Electron defaults to a platform-specific application-support directory while Kotlin
// persists sessions under ~/.android-performance-studio. Set the shared root before
// ready, then fill it from the legacy root without overwriting existing Kotlin data.
const legacyElectronUserDataDirectory = app.getPath('userData');
const sharedApplicationDataDirectory = kotlinApplicationDataDirectory(homedir());
if (legacyElectronUserDataDirectory !== sharedApplicationDataDirectory) {
  app.setPath('userData', sharedApplicationDataDirectory);
}

// Must happen before app ready.
registerPerfettoSchemes();

const settingsIo = {
  readFile: (path: string) => readFile(path, 'utf8'),
  writeFile: async (path: string, contents: string) => {
    await writeFile(path, contents, 'utf8');
  },
  mkdir: async (path: string) => {
    await mkdir(path, { recursive: true });
  },
};

let window: BrowserWindow | undefined;
let settings: ApplicationUiSettings | undefined;
let migration: MigrationStatus = { source: 'default', migratedKeys: [] };
let userDocumentation: UserDocumentationService | undefined;
let firefoxProfiler: FirefoxProfilerService | undefined;
let currentViewerMenuState: ViewerMenuState | undefined;
let recentArchivePaths: readonly string[] = [];
let recentArchiveImportInProgress = false;
let recentArchivePathStore: RecentPathStore | undefined;
const methodRecordingLifecycle = new MethodRecordingLifecycle();

function userDataDirectory(): string {
  return app.getPath('userData');
}

function userDocumentationService(): UserDocumentationService {
  if (userDocumentation !== undefined) return userDocumentation;
  const root = app.isPackaged ? process.resourcesPath : join(app.getAppPath(), '..', '..', '..');
  userDocumentation = new UserDocumentationService({ root, openExternal: async (url) => await shell.openExternal(url) });
  return userDocumentation;
}

function traceStore(): TraceStore {
  return new TraceStore(join(userDataDirectory(), 'traces'));
}

function countSnapshotNodes(snapshot: LayoutSnapshot): number {
  let count = 0;
  walkNode(snapshot.root, () => {
    count += 1;
  });
  return count;
}

function layoutStore(): LayoutCaptureStore {
  return new LayoutCaptureStore(join(userDataDirectory(), 'layout-captures'), {
    countNodes: countSnapshotNodes,
  });
}

function captureArchiveService(): CaptureArchiveService {
  return new CaptureArchiveService({
    store: layoutStore(),
    producerVersion: () => app.getVersion(),
    snapshotSizeMultiplier:
      settings?.layoutInspector.snapshotSizeMultiplier ?? DEFAULT_SETTINGS.layoutInspector.snapshotSizeMultiplier,
  });
}

/** Kotlin uses this exact shared-data filename for its Layout Inspector File > Open Recent menu. */
function recentArchiveStore(): RecentPathStore {
  if (recentArchivePathStore === undefined) {
    recentArchivePathStore = new RecentPathStore(join(userDataDirectory(), 'recent-layout-inspector-archives.txt'));
  }
  return recentArchivePathStore;
}

function refreshViewerMenu(): void {
  const state = currentViewerMenuState ?? idleViewerMenuState(resolveLanguage(settings?.language ?? 'system', app.getLocale()));
  installViewerMenu(state, dispatchViewerCommand, {
    recentEntries: recentArchivePaths,
    enabled: !recentArchiveImportInProgress,
    openRecent: (path) => { void openRecentLayoutArchive(path); },
    clearRecent: () => { void clearRecentLayoutArchives(); },
  });
}

async function recordRecentLayoutArchive(path: string): Promise<void> {
  try {
    recentArchivePaths = await recentArchiveStore().record(path);
  } catch {
    // A successful import must not be turned into a failure solely because the optional menu history cannot persist.
  }
}

async function clearRecentLayoutArchives(): Promise<void> {
  try {
    await recentArchiveStore().clear();
    recentArchivePaths = [];
  } catch {
    // Recent paths are convenience state; a permissions failure must not crash the menu callback.
  } finally {
    refreshViewerMenu();
  }
}

async function importLayoutArchiveFromPath(source: string): Promise<LayoutArchiveOutcome> {
  try {
    const imported = await captureArchiveService().import(source);
    return {
      ok: true,
      id: imported.id,
      archiveVersion: imported.archiveVersion,
      detail: {
        snapshot: imported.snapshot,
        ...(imported.screenshotBase64 !== undefined ? { screenshotBase64: imported.screenshotBase64 } : {}),
        ...(imported.composeInspectionJson !== undefined ? { composeInspectionJson: imported.composeInspectionJson } : {}),
        ...(imported.analysisReportJson !== undefined ? { analysisReportJson: imported.analysisReportJson } : {}),
        ...(imported.aiAnalysisReportJson !== undefined ? { aiAnalysisReportJson: imported.aiAnalysisReportJson } : {}),
        ...(imported.timelineHistoryJson !== undefined ? { timelineHistoryJson: imported.timelineHistoryJson } : {}),
      },
    };
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : 'Capture archive import failed' };
  }
}

function notifyLayoutArchiveOpened(outcome: LayoutArchiveOutcome): void {
  for (const target of BrowserWindow.getAllWindows()) {
    target.webContents.send(IPC_CHANNELS.layoutArchiveOpened, outcome);
  }
}

async function openRecentLayoutArchive(path: string): Promise<void> {
  if (recentArchiveImportInProgress) return;
  recentArchiveImportInProgress = true;
  refreshViewerMenu();
  try {
    const outcome = await importLayoutArchiveFromPath(path);
    if (outcome.ok) await recordRecentLayoutArchive(path);
    notifyLayoutArchiveOpened(outcome);
  } finally {
    recentArchiveImportInProgress = false;
    refreshViewerMenu();
  }
}

function composeAgentBundleRoot(): string {
  return app.isPackaged
    ? join(process.resourcesPath, 'compose-agent')
    : join(app.getAppPath(), '..', '..', '..', 'build', 'compose-agent');
}

async function composeAgentBundleIsAvailable(): Promise<boolean> {
  try { return (await stat(composeAgentBundleRoot())).isDirectory(); } catch { return false; }
}

function frameStore(): FrameSessionStore {
  return new FrameSessionStore(join(userDataDirectory(), 'frame-sessions'));
}

function startupStore(): StartupSessionStore {
  return new StartupSessionStore(join(userDataDirectory(), 'startup-sessions'));
}

function batteryStore(): BatterySessionStore {
  return new BatterySessionStore(join(userDataDirectory(), 'battery-sessions'));
}

function networkStore(): NetworkSessionStore {
  return new NetworkSessionStore(join(userDataDirectory(), 'network-sessions'));
}

function benchmarkStore(): BenchmarkStore {
  return new BenchmarkStore(join(userDataDirectory(), 'benchmark-runs'));
}

function gpuStore(): GpuArtifactStore {
  return new GpuArtifactStore(join(userDataDirectory(), 'gpu-artifacts'));
}

/**
 * The symbols a layout finding can cite. The selected node is the subject when
 * there is one, otherwise the root class stands in for the screen.
 */
function sourceEvidenceFor(
  snapshot: LayoutSnapshot,
  selectedNodeId: string | undefined,
): SourceResolutionEvidence[] {
  let subject = snapshot.root;
  if (selectedNodeId !== undefined) {
    walkNode(snapshot.root, (node) => {
      if (node.id === selectedNodeId) subject = node;
    });
  }
  const evidence: SourceResolutionEvidence[] = [];
  if (subject.className.length > 0) {
    evidence.push({ kind: 'TYPE_NAME', id: 'layout:' + subject.id, qualifiedName: subject.className });
  }
  const resourceName = subject.type === 'view' ? subject.resourceName : undefined;
  const separator = resourceName?.indexOf('/') ?? -1;
  if (resourceName !== undefined && separator > 0 && separator < resourceName.length - 1) {
    evidence.push({
      kind: 'ANDROID_RESOURCE',
      id: 'layout-resource:' + subject.id,
      resourceType: resourceName.slice(0, separator),
      resourceName: resourceName.slice(separator + 1),
    });
  }
  return evidence;
}

/** A backend failure as the strings the source panels show. */
function describeSourceError(error: unknown): string {
  return error instanceof Error ? error.message : 'Source workspace operation failed';
}

/**
 * Imports the JSON store the app used before the shared database existed.
 *
 * The files stay on disk after a successful import: it is idempotent per root,
 * so re-running costs a directory read and keeps a failure recoverable.
 */
async function migrateLegacySourceWorkspaces(): Promise<void> {
  try {
    const directory = join(userDataDirectory(), 'source-workspaces');
    const entries = await readdir(directory, { withFileTypes: true });
    const legacy: LegacySourceWorkspace[] = [];
    for (const entry of entries) {
      if (!entry.isDirectory()) continue;
      try {
        const parsed: unknown = JSON.parse(await readFile(join(directory, entry.name, 'workspace.json'), 'utf8'));
        if (parsed === null || typeof parsed !== 'object') continue;
        const record = parsed as { id?: unknown; displayName?: unknown; root?: unknown; phase?: unknown };
        if (typeof record.root !== 'string') continue;
        legacy.push({
          id: typeof record.id === 'string' ? record.id : entry.name,
          displayName: typeof record.displayName === 'string' ? record.displayName : entry.name,
          root: record.root,
          phase: typeof record.phase === 'string' ? record.phase : 'READY',
        });
      } catch {
        // A half-written record is skipped; the rest of the list still migrates.
      }
    }
    if (legacy.length === 0) return;
    await migrateLegacyWorkspaces(legacy);
  } catch {
    // No legacy directory is the normal case for a fresh install.
  }
}

function methodStore(): MethodSessionStore {
  return new MethodSessionStore(join(userDataDirectory(), 'method-sessions'));
}

/** Rebuilds a method session from its retained trace when it is not cached. */
async function methodSessionFor(record: MethodSessionRecord): Promise<StoredMethodSession | undefined> {
  const store = methodStore();
  const cached = store.cached(record.id);
  if (cached !== undefined) return cached;
  const trace = await store.readTrace(record.id);
  if (trace === undefined) return undefined;
  const parsed = await parseMethodTraceInWorker(trace);
  if (!parsed.ok) return undefined;
  const session: StoredMethodSession = {
    record,
    table: parsed.value.table,
    analysis: parsed.value.analysis,
  };
  store.cache(session);
  return session;
}

function hostSimpleperfDependencies(): HostSimpleperfLocatorDependencies {
  const configured = process.env['APS_SIMPLEPERF'];
  return defaultHostSimpleperfLocatorDependencies({
    ...(configured !== undefined && configured.length > 0 ? { configuredExecutable: configured } : {}),
    pathDirectories: (process.env['PATH'] ?? '').split(delimiter).filter((entry) => entry.length > 0),
  });
}

function cpuStore(): CpuProfileStore {
  return new CpuProfileStore(join(userDataDirectory(), 'cpu-profiles'));
}

/** Parsed tables are cached per session so a re-query never reparses a report. */
async function cpuTableFor(record: CpuProfileSessionRecord): Promise<StudioResult<CallStackTable>> {
  const store = cpuStore();
  const cached = store.cachedTable(record.id);
  if (cached !== undefined) return ok(cached);
  const report = await store.readReport(record);
  if (report === undefined) {
    return fail('IO', 'CPU_REPORT_MISSING', 'The stored report for this session is gone');
  }
  if (record.sourceFormat === 'GECKO_PROFILE_JSON_GZIP') {
    // Imported Gecko archives stay compressed at rest; decode and parse them in a worker on reload.
    const parsed = await importOfflineCpuProfileInWorker({
      format: 'GECKO_PROFILE_JSON_GZIP',
      bytes: report,
    });
    if (!parsed.ok) return parsed;
    store.cacheTable(record.id, parsed.value.table);
    return ok(parsed.value.table);
  }
  const parsed = await parseCpuProfileReport(report, parseSimpleperfReportInWorker);
  if (!parsed.ok) return parsed;
  store.cacheTable(record.id, parsed.value.table);
  return ok(parsed.value.table);
}

/** One instance per process: the sessions database connection is pooled. */
let aiServiceInstance: AiAnalysisService | undefined;

function aiService(): AiAnalysisService {
  if (aiServiceInstance !== undefined) return aiServiceInstance;
  ensureAiDirectory();
  const credentials = new SafeStorageCredentialStore({
    safeStorage,
    filePath: aiCredentialFilePath(),
  });
  aiServiceInstance = new AiAnalysisService({
    credentials,
    persistent: isPersistentBackend(safeStorage),
    repository: new SqliteAnalysisSessionRepository(aiSessionsDatabasePath()),
    transport: fetchAiTransport(),
    now: () => Date.now(),
  });
  return aiServiceInstance;
}

function bitmapStore(): BitmapDumpStore {
  return new BitmapDumpStore(join(userDataDirectory(), 'bitmap-dumps'));
}

function nativeHeapStore(): NativeHeapStore {
  return new NativeHeapStore(join(userDataDirectory(), 'native-heap'));
}

/** The extended dependency set the bitmap and heapprofd captures need. */
function extendedCaptureDependencies(
  client: AdbClient,
  serial: string,
  temporaryDirectory: string,
): ExtendedMemoryCaptureDependencies {
  return {
    adb: {
      shell: (args, options) => client.shell(serial, args, options),
      pull: async (remote, local, options) => {
        await client.pull(serial, remote, local, options);
      },
    },
    push: async (local, remote, options) => {
      await client.push(serial, local, remote, options);
    },
    writeTextFile: async (path, contents) => {
      await writeFile(path, contents, 'utf8');
    },
    directoryOf: (path) => dirname(path),
    sizeOf: async (path) => (await stat(path)).size,
    readFile: async (path) => new Uint8Array(await readFile(path)),
    removeFile: async (path) => {
      await rm(path, { force: true });
    },
    temporaryPath: (name) => join(temporaryDirectory, name),
    now: () => Date.now(),
    newId: () => String(Date.now()),
  };
}

function memoryStore(): MemorySessionStore {
  return new MemorySessionStore(join(userDataDirectory(), 'memory-sessions'));
}

/** Restores a persisted HPROF on cache miss so historical sessions can browse instances. */
async function heapForMemorySession(sessionId: string) {
  return loadPersistedMemoryHeap(sessionId, {
    store: memoryStore(),
    cached: cachedHeap,
    cache: cacheHeap,
    parse: parseHeapDumpInWorker,
  });
}

async function agiCapability(): Promise<AgiCapability> {
  const dependencies: AgiLocatorDependencies = {
    platform: process.platform,
    env: process.env,
    userHome: homedir(),
    isExecutableFile: (path) => {
      try {
        const stats = statSync(path);
        return stats.isFile() && (process.platform === 'win32' || (stats.mode & 0o111) !== 0);
      } catch {
        return false;
      }
    },
    join,
    run: async (executable, args) => {
      try {
        const result = await runHostProcessText({ executable, args: [...args], timeoutMs: 3_000 });
        return { exitCode: result.exitCode, stdout: result.stdout, stderr: result.stderr, timedOut: false };
      } catch (error) {
        const timedOut = error instanceof Error && error.name === 'HostProcessTimeoutError';
        return { exitCode: -1, stdout: '', stderr: '', timedOut };
      }
    },
  };
  return await locateAgi(dependencies, process.env['APS_AGI_PATH']);
}

async function importTraceFromPath(path: string): Promise<{ ok: boolean; id?: string; error?: string }> {
  try {
    const digest = await sha256File(path);
    const record = await traceStore().addTrace(path, {
      sha256: digest,
      capturedAtEpochMillis: Date.now(),
      durationMillis: 0,
    });
    return { ok: true, id: record.id };
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : 'trace import failed' };
  }
}

const MAX_INLINE_SCREENSHOT_BYTES = 16 * 1024 * 1024;

/** The reference's CaptureTargetMode.SYSTEM_UI names System UI's package. */
const SYSTEM_UI_PACKAGE_NAME = 'com.android.systemui';

function assetProbe(): PerfettoUiAssetProbe {
  const isDirectory = (path: string): boolean => {
    try {
      return statSync(path).isDirectory();
    } catch {
      return false;
    }
  };
  return {
    isDirectory,
    isFile: (path) => {
      try {
        return statSync(path).isFile();
      } catch {
        return false;
      }
    },
  };
}

function perfettoUiDirectory(): string | undefined {
  const electronResources = (process as NodeJS.Process & { resourcesPath?: string }).resourcesPath;
  return findPerfettoUiAssetsDirectory(
    [
      process.env['APS_PERFETTO_UI_DIST'],
      electronResources === undefined ? undefined : join(electronResources, 'perfetto-ui'),
      join(app.getAppPath(), '..', '..', '..', 'third_party', 'perfetto', 'out', 'ui', 'dist'),
      join(homedir(), '.android-performance-studio', 'tools', 'perfetto', 'ui'),
    ],
    assetProbe(),
  );
}

function firefoxProfilerService(): FirefoxProfilerService {
  if (firefoxProfiler !== undefined) return firefoxProfiler;
  const electronResources = (process as NodeJS.Process & { resourcesPath?: string }).resourcesPath;
  firefoxProfiler = new FirefoxProfilerService({
    openExternal: shell.openExternal,
    ...(electronResources !== undefined ? { resourcesPath: electronResources } : {}),
    repositoryRoot: join(app.getAppPath(), '..', '..', '..'),
  });
  return firefoxProfiler;
}

async function ensureSettings(): Promise<ApplicationUiSettings> {
  if (settings !== undefined) return settings;
  const loaded = await loadApplicationSettings({
    userDataDirectory: userDataDirectory(),
    io: settingsIo,
    legacySource: createLegacyPreferenceSource(defaultLegacySourceDependencies()),
  });
  settings = loaded.settings;
  migration = loaded.migration;
  return settings;
}

async function buildSnapshot(): Promise<ShellSnapshot> {
  const current = await ensureSettings();
  const adb = openAdb(current.androidSdkPath);
  let devices: ShellSnapshot['devices'] = [];
  if (adb.status.available) {
    try {
      devices = await adb.listDevices();
    } catch {
      devices = [];
    }
  }
  return {
    appInfo: buildAppInfo(app.getVersion(), process.platform),
    settings: current,
    adb: adb.status,
    devices,
    traceProcessor: await resolveTraceProcessorStatus(),
    migration,
  };
}

/**
 * The display size the settings page offers is the window's zoom factor: one
 * percentage for the whole shell. It is re-applied after every navigation,
 * because the factor belongs to the contents and a load resets it.
 */
function applyDisplayScale(): void {
  const factor = (settings?.displayScalePercent ?? DEFAULT_DISPLAY_SCALE_PERCENT) / 100;
  for (const target of BrowserWindow.getAllWindows()) target.webContents.setZoomFactor(factor);
}

async function updateSettings(patch: ApplicationUiSettingsPatch): Promise<ShellSnapshot> {
  const current = await ensureSettings();
  // One deep merge for every page: a patch that carries one toggle must not
  // erase the sections the sender never read.
  const merged = mergeApplicationUiSettings(current, patch);
  const store = new JsonSettingsStore(join(userDataDirectory(), 'settings.json'), settingsIo);
  if (await store.save(merged)) {
    settings = merged;
    // The display size takes effect as it is chosen, not on the next launch.
    applyDisplayScale();
  }
  return await buildSnapshot();
}

function adbClientFor(): AdbClient | undefined {
  if (settings === undefined) return undefined;
  const adb = openAdb(settings.androidSdkPath);
  if (!adb.status.available || adb.status.executable === undefined) return undefined;
  return new AdbClient({ executable: adb.status.executable });
}

async function buildTraceAnalyzerSnapshot(): Promise<TraceAnalyzerSnapshot> {
  const directory = perfettoUiDirectory();
  return {
    traces: await traceStore().list(),
    ui: directory === undefined ? { available: false } : { available: true, directory },
  };
}

async function runCapture(input: TraceCaptureInput): Promise<TraceCaptureOutcome> {
  const client = adbClientFor();
  if (client === undefined) return { ok: false, error: 'ADB is not available' };
  const store = traceStore();
  const result = await capturePerfettoTrace(
    {
      adb: {
        push: async (local, remote) => {
          await client.push(input.serial, local, remote, { timeoutMs: 60_000 });
        },
        shell: async (args, timeoutMs) => {
          await client.shell(input.serial, args, { timeoutMs });
        },
        pull: async (remote, local) => {
          await client.pull(input.serial, remote, local, { timeoutMs: 120_000 });
        },
      },
      store: { addTrace: (sourcePath, meta) => store.addTrace(sourcePath, meta) },
      createTempDirectory: () => mkdtemp(join(tmpdir(), 'aps-capture-')),
      writeFile: async (path, contents) => {
        await writeFile(path, contents, 'utf8');
      },
      removeDirectory: async (path) => {
        await rm(path, { recursive: true, force: true });
      },
      sha256File,
      now: () => Date.now(),
    },
    {
      serial: input.serial,
      fileName: 'capture-' + String(Date.now()) + '.pftrace',
      document: {
        durationMillis: input.durationMillis,
        bufferSizeKb: input.bufferSizeKb,
        dataSources: [{ name: input.dataSource }],
      },
    },
  );
  return result.ok ? { ok: true, record: result.value } : { ok: false, error: result.error.code + ': ' + result.error.message };
}

async function openTraceInAnalyzer(id: string): Promise<TraceOpenOutcome> {
  const directory = perfettoUiDirectory();
  if (directory === undefined) return { ok: false, error: 'Perfetto UI assets are not bundled' };
  const record = (await traceStore().list()).find((entry) => entry.id === id);
  if (record === undefined) return { ok: false, error: 'Trace not found' };
  const traceUrl = TRACE_SCHEME + '://trace/' + encodeURIComponent(record.id);
  const url =
    PERFETTO_UI_SCHEME + '://ui/index.html#!/?url=' + encodeURIComponent(traceUrl);
  const analyzer = new BrowserWindow({
    width: 1400,
    height: 900,
    backgroundColor: '#111318',
    title: 'Trace Analyzer',
    webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true },
  });
  await analyzer.loadURL(url);
  return { ok: true };
}

/**
 * Menu clicks travel to the renderer that owns the state behind them; the menu
 * has no window of its own, so every window hears the command.
 */
function dispatchViewerCommand(command: ViewerMenuCommand): void {
  for (const window of BrowserWindow.getAllWindows()) {
    window.webContents.send(IPC_CHANNELS.viewerMenuCommand, command);
  }
}

function registerHandlers(): void {
  ipcMain.handle(IPC_CHANNELS.shellSnapshot, () => buildSnapshot());
  // The renderer reports the menu's enabled and checked state whenever any of it
  // moves, and the menu is rebuilt from it: Electron menus are immutable.
  ipcMain.on(IPC_CHANNELS.viewerMenuState, (_event, state: unknown) => {
    currentViewerMenuState = requireViewerMenuState(state, IPC_CHANNELS.viewerMenuState);
    refreshViewerMenu();
  });
  ipcMain.handle(IPC_CHANNELS.updateSettings, (_event, patch: unknown) =>
    updateSettings(requireApplicationUiSettingsPatch(patch, IPC_CHANNELS.updateSettings)),
  );
  // The General page's Browse button: the renderer never sees a path it did not
  // get from the platform picker or from the stored settings.
  ipcMain.handle(IPC_CHANNELS.chooseAndroidSdkDirectory, async () => {
    const selection = await dialog.showOpenDialog({
      title: 'Select Android SDK directory',
      properties: ['openDirectory', 'createDirectory'],
    });
    const directory = selection.filePaths[0];
    if (selection.canceled || directory === undefined) return undefined;
    return directory;
  });
  ipcMain.handle(IPC_CHANNELS.refreshDevices, () => buildSnapshot());
  ipcMain.handle(IPC_CHANNELS.openUserGuide, async (_event, language: unknown) => {
    await userDocumentationService().open(requireUiLanguage(language, IPC_CHANNELS.openUserGuide));
  });
  ipcMain.handle(IPC_CHANNELS.openDestination, (_event, destination: unknown) => {
    const requestedDestination = requireAppDestination(destination, IPC_CHANNELS.openDestination);
    if (window === undefined) return;
    // The window opens maximized and stays that way: coming back to the grid
    // never shrinks it, because a launcher that resizes on the way home reads
    // as a state change the user did not ask for.
    if (shouldMaximizeWindow(requestedDestination)) window.maximize();
  });
  ipcMain.handle(IPC_CHANNELS.traceAnalyzer, () => buildTraceAnalyzerSnapshot());
  ipcMain.handle(IPC_CHANNELS.traceCapture, (_event, input: unknown) =>
    runCapture(requireTraceCaptureInput(input, IPC_CHANNELS.traceCapture)),
  );
  ipcMain.handle(IPC_CHANNELS.traceOpen, (_event, id: unknown) =>
    openTraceInAnalyzer(requireOpaqueRecordId(id, IPC_CHANNELS.traceOpen)),
  );
  ipcMain.handle(IPC_CHANNELS.traceOpenPublicUi, async () => {
    await shell.openExternal('https://ui.perfetto.dev');
  });
  ipcMain.handle(IPC_CHANNELS.frameCapture, async (_event, input: unknown) => {
    const request = requireFrameCaptureInput(input, IPC_CHANNELS.frameCapture);
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const result = await captureFrameSession(
      {
        adb: { shell: (args, options) => client.shell(request.serial, args, options) },
        now: () => Date.now(),
        newSessionId: () => String(Date.now()),
      },
      { serial: request.serial, packageName: request.packageName },
    );
    if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
    const summary = await frameStore().add(result.value);
    return { ok: true, id: summary.id };
  });
  ipcMain.handle(IPC_CHANNELS.startupCapture, async (_event, input: unknown) => {
    const request = requireStartupCaptureInput(input, IPC_CHANNELS.startupCapture);
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const result = await runStartupExperiment(
      {
        adb: { shell: (args, options) => client.shell(request.serial, args, options) },
        now: () => Date.now(),
        newId: () => String(Date.now()),
      },
      {
        serial: request.serial,
        packageName: request.packageName,
        ...(request.componentName !== undefined ? { componentName: request.componentName } : {}),
        config: {
          requestedType: request.requestedType,
          warmupRuns: request.warmupRuns,
          measuredRuns: request.measuredRuns,
          timeoutSeconds: request.timeoutSeconds,
        },
      },
    );
    if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
    const summary = await startupStore().add(result.value);
    return { ok: true, id: summary.id };
  });
  ipcMain.handle(IPC_CHANNELS.startupImport, async () => {
    const selection = await dialog.showOpenDialog({
      title: 'Import Kotlin Startup JSON',
      properties: ['openFile'],
      filters: [{ name: 'Startup JSON', extensions: ['json'] }],
    });
    const filePath = selection.filePaths[0];
    if (selection.canceled || filePath === undefined) return { ok: false, cancelled: true };
    try {
      const session = importKotlinStartupJson(await readKotlinStartupJsonFile(filePath), {
        id: 'startup-import-' + randomUUID(),
        capturedAtEpochMillis: Date.now(),
        sourceFileName: basename(filePath),
      });
      const summary = await startupStore().add(session);
      return { ok: true, id: summary.id };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : 'Startup JSON import failed' };
    }
  });
  ipcMain.handle(IPC_CHANNELS.startupSqliteImport, async () => {
    const selection = await dialog.showOpenDialog({
      title: 'Import Kotlin Startup SQLite',
      properties: ['openFile'],
      filters: [{ name: 'Startup SQLite', extensions: ['db', 'sqlite'] }],
    });
    const filePath = selection.filePaths[0];
    if (selection.canceled || filePath === undefined) return { ok: false, cancelled: true };
    try {
      const sessions = await importKotlinStartupSqlite(filePath, {
        idForSourceSession: () => 'startup-sqlite-import-' + randomUUID(),
        sourceFileName: basename(filePath),
      });
      // StartupSessionStore rewrites a shared index for every add, so preserve
      // source ordering while writing sequentially rather than racing updates.
      let firstId: string | undefined;
      for (const session of sessions) {
        const summary = await startupStore().add(session);
        firstId ??= summary.id;
      }
      return { ok: true, ...(firstId !== undefined ? { id: firstId } : {}), importedSessions: sessions.length };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : 'Startup SQLite import failed' };
    }
  });
  ipcMain.handle(IPC_CHANNELS.startupExport, async (_event, id: unknown) => {
    const recordId = requireOpaqueRecordId(id, IPC_CHANNELS.startupExport);
    const session = await startupStore().load(recordId);
    if (session === undefined) return { ok: false, error: 'Startup session not found' };
    const selection = await dialog.showSaveDialog({
      title: 'Export Kotlin Startup JSON',
      defaultPath: recordId + '.startup.json',
      filters: [{ name: 'Startup JSON', extensions: ['json'] }],
    });
    if (selection.canceled || selection.filePath === undefined) return { ok: false, cancelled: true };
    try {
      await writeFile(selection.filePath, exportKotlinStartupJson(session), 'utf8');
      return { ok: true, id: recordId };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : 'Startup JSON export failed' };
    }
  });
  ipcMain.handle(IPC_CHANNELS.batteryCapture, async (_event, input: unknown) => {
    const request = requireBatteryCaptureInput(input, IPC_CHANNELS.batteryCapture);
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const result = await runBatteryExperiment(
      {
        adb: { shell: (args, options) => client.shell(request.serial, args, options) },
        now: () => Date.now(),
        newId: () => String(Date.now()),
        sleep: (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
      },
      {
        serial: request.serial,
        packageName: request.packageName,
        uid: request.uid,
        config: {
          mode: request.mode,
          durationSeconds: request.durationSeconds,
          pollingIntervalSeconds: request.pollingIntervalSeconds,
          measuredRuns: request.measuredRuns,
          launchApp: false,
          cooldownSeconds: request.cooldownSeconds,
        },
      },
    );
    if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
    const summary = await batteryStore().add(result.value);
    return { ok: true, id: summary.id };
  });
  ipcMain.handle(IPC_CHANNELS.networkImport, async () => {
    const selection = await dialog.showOpenDialog({
      title: 'Import HAR',
      properties: ['openFile'],
      filters: [{ name: 'HTTP Archive', extensions: ['har', 'json'] }],
    });
    const filePath = selection.filePaths[0];
    if (selection.canceled || filePath === undefined) return { ok: false, cancelled: true };
    const imported = await importHarFile(
      {
        readFileText: (path) => readFile(path, 'utf8'),
        fileSize: async (path) => (await stat(path)).size,
        isRegularFile: (path) => {
          try {
            return statSync(path).isFile();
          } catch {
            return false;
          }
        },
      },
      filePath,
    );
    if (!imported.ok) return { ok: false, error: imported.error.code + ': ' + imported.error.message };
    const summary = await networkStore().add(imported.value);
    return { ok: true, id: summary.id };
  });
  ipcMain.handle(IPC_CHANNELS.benchmarkImport, async () => {
    const selection = await dialog.showOpenDialog({
      title: 'Import benchmark JSON',
      properties: ['openFile'],
      filters: [{ name: 'Benchmark JSON', extensions: ['json'] }],
    });
    const filePath = selection.filePaths[0];
    if (selection.canceled || filePath === undefined) return { ok: false, cancelled: true };
    const imported = await importBenchmarkJson(
      {
        readFileText: (path) => readFile(path, 'utf8'),
        fileSize: async (path) => (await stat(path)).size,
        isRegularFile: (path) => {
          try {
            return statSync(path).isFile();
          } catch {
            return false;
          }
        },
        newId: () => String(Date.now()),
        now: () => Date.now(),
      },
      filePath,
    );
    if (!imported.ok) return { ok: false, error: imported.error.code + ': ' + imported.error.message };
    const summary = await benchmarkStore().add(imported.value);
    return { ok: true, id: summary.id };
  });
  ipcMain.handle(IPC_CHANNELS.benchmarkList, () => benchmarkStore().list());
  ipcMain.handle(IPC_CHANNELS.benchmarkCompare, async (_event, input: unknown) => {
    const request = requireBenchmarkCompareInput(input, IPC_CHANNELS.benchmarkCompare);
    const store = benchmarkStore();
    const baseline = await store.load(request.baselineId);
    const current = await store.load(request.currentId);
    if (baseline === undefined || current === undefined) {
      return { ok: false, error: 'Select both a baseline and a current run' };
    }
    const policy = {
      ...DEFAULT_REGRESSION_POLICY,
      ...(request.relativeThresholdPercent !== undefined
        ? { relativeThresholdPercent: request.relativeThresholdPercent }
        : {}),
      ...(request.absoluteThreshold !== undefined ? { absoluteThreshold: request.absoluteThreshold } : {}),
    };
    return { ok: true, report: compareBenchmarkRuns(baseline, current, policy) };
  });
  ipcMain.handle(IPC_CHANNELS.traceImportFromPath, (_event, path: unknown) =>
    importTraceFromPath(requireTraceImportPath(path, IPC_CHANNELS.traceImportFromPath)),
  );
  ipcMain.handle(IPC_CHANNELS.gpuStatus, () => agiCapability());
  ipcMain.handle(IPC_CHANNELS.gpuLaunch, async () => {
    const capability = await agiCapability();
    if (capability.executable === undefined || !capability.launchSupported) {
      return { ok: false, error: 'Android GPU Inspector is not available' };
    }
    launchHostProcess({ executable: capability.executable, args: safeLaunchArguments(capability, []) });
    return { ok: true };
  });
  ipcMain.handle(IPC_CHANNELS.gpuImport, async () => {
    const selection = await dialog.showOpenDialog({
      title: 'Import GPU artifact',
      properties: ['openFile'],
      filters: [{ name: 'GPU artifacts', extensions: ['pftrace', 'perfetto-trace', 'gfxtrace', 'agi', 'trace', 'png', 'jpg', 'jpeg', 'html', 'pdf'] }],
    });
    const filePath = selection.filePaths[0];
    if (selection.canceled || filePath === undefined) return { ok: false, cancelled: true };
    try {
      const capability = await agiCapability();
      const artifact = await gpuStore().importArtifact({
        path: filePath,
        id: String(Date.now()),
        now: () => Date.now(),
        ...(capability.version !== undefined ? { agiVersion: capability.version } : {}),
      });
      return { ok: true, id: artifact.id };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : 'artifact import failed' };
    }
  });
  ipcMain.handle(IPC_CHANNELS.gpuList, () => gpuStore().summarize());
  ipcMain.handle(IPC_CHANNELS.gpuReveal, async (_event, id: unknown) => {
    const recordId = requireOpaqueRecordId(id, IPC_CHANNELS.gpuReveal);
    const artifact = (await gpuStore().list()).find((entry) => entry.id === recordId);
    if (artifact === undefined) return { ok: false, error: 'Artifact not found' };
    shell.showItemInFolder(artifact.path);
    return { ok: true };
  });
  ipcMain.handle(IPC_CHANNELS.gpuRelocate, async (_event, id: unknown) => {
    const recordId = requireOpaqueRecordId(id, IPC_CHANNELS.gpuRelocate);
    const selection = await dialog.showOpenDialog({ title: 'Locate GPU artifact', properties: ['openFile'] });
    const filePath = selection.filePaths[0];
    if (selection.canceled || filePath === undefined) return { ok: false, cancelled: true };
    try {
      const relocated = await gpuStore().relocate(recordId, filePath);
      if (relocated === undefined) return { ok: false, error: 'Artifact not found' };
      return { ok: true, id: relocated.id };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : 'relocation failed' };
    }
  });
  ipcMain.handle(IPC_CHANNELS.gpuOpen, async (_event, id: unknown) => {
    const recordId = requireOpaqueRecordId(id, IPC_CHANNELS.gpuOpen);
    const store = gpuStore();
    const artifact = (await store.list()).find((entry) => entry.id === recordId);
    if (artifact === undefined) return { ok: false, error: 'Artifact not found' };
    const location = await store.resolveLocation(recordId);
    if (location?.status !== 'AVAILABLE' || location.path === undefined) {
      return { ok: false, error: 'Artifact content is no longer available at any known location' };
    }
    if (artifact.openRoute === 'PERFETTO') {
      // Hand the trace to Trace Analyzer instead of duplicating a viewer.
      const imported = await importTraceFromPath(location.path);
      if (!imported.ok || imported.id === undefined) {
        return { ok: false, error: imported.error ?? 'trace handoff failed' };
      }
      const opened = await openTraceInAnalyzer(imported.id);
      return opened.ok ? { ok: true, note: 'Opened in Trace Analyzer' } : { ok: false, error: opened.error };
    }
    if (artifact.openRoute === 'AGI') {
      const capability = await agiCapability();
      if (!capability.artifactOpenSupported || capability.executable === undefined) {
        return { ok: false, error: 'Opening this artifact with AGI was not verified' };
      }
      launchHostProcess({ executable: capability.executable, args: [location.path] });
      return { ok: true };
    }
    const message = await shell.openPath(location.path);
    return message.length === 0 ? { ok: true } : { ok: false, error: message };
  });
  // The shared database is the only source of truth for workspaces; the older
  // JSON store was migrated into it at startup.
  ipcMain.handle(IPC_CHANNELS.sourceList, () => toSourceWorkspaceRecords());
  ipcMain.handle(IPC_CHANNELS.sourceAdd, async () => {
    const selection = await dialog.showOpenDialog({
      title: 'Add a source workspace',
      properties: ['openDirectory'],
    });
    const root = selection.filePaths[0];
    if (selection.canceled || root === undefined) return { ok: false, cancelled: true };
    try {
      const workspace = await sourceBackend().service.add(basename(root), { kind: 'LOCAL', root });
      // Registering always succeeds; indexing can still fail, and the panel
      // shows the reason instead of claiming the workspace is ready.
      if (workspace.phase === 'FAILED') {
        return { ok: false, id: workspace.id, error: workspace.message ?? 'Source indexing failed' };
      }
      return { ok: true, id: workspace.id };
    } catch (error) {
      return { ok: false, error: describeSourceError(error) };
    }
  });
  ipcMain.handle(IPC_CHANNELS.sourceRemove, (_event, id: unknown) => {
    const recordId = requireOpaqueRecordId(id, IPC_CHANNELS.sourceRemove);
    try {
      sourceBackend().service.remove(recordId);
      return true;
    } catch {
      return false;
    }
  });
  ipcMain.handle(IPC_CHANNELS.sourceReindex, async (_event, id: unknown) => {
    const workspaceId = requireOpaqueRecordId(id, IPC_CHANNELS.sourceReindex);
    try {
      const workspace = await sourceBackend().service.refresh(workspaceId);
      if (workspace.phase === 'FAILED') {
        return { ok: false, id: workspaceId, error: workspace.message ?? 'Source indexing failed' };
      }
      return { ok: true, id: workspaceId };
    } catch (error) {
      return { ok: false, error: describeSourceError(error) };
    }
  });
  ipcMain.handle(IPC_CHANNELS.sourceSearch, (_event, input: unknown) => {
    const request = requireSourceSearchRequest(input, IPC_CHANNELS.sourceSearch);
    return searchBackendSymbols(request.workspaceId, request.query, request.limit);
  });
  ipcMain.handle(IPC_CHANNELS.sourceResolve, async (_event, input: unknown) => {
    const request = requireSourceResolveRequest(input, IPC_CHANNELS.sourceResolve);
    const backend = sourceBackend();
    // An empty candidate list and an unindexed workspace mean different things to
    // the user, so the second one is an error rather than "no candidates".
    if (backend.snapshotIdOf(request.workspaceId) === undefined) {
      return { ok: false, error: 'SOURCE_WORKSPACE_NOT_READY: this workspace has no indexed snapshot yet' };
    }
    try {
      const candidates = backend.resolveForWorkspace(request.workspaceId, request.evidence, request.buildIdentityMatch);
      return {
        ok: true,
        candidates: candidates.map((candidate) => ({
          id: candidate.id,
          evidenceId: candidate.evidenceId,
          relativePath: candidate.location.relativePath,
          ...(candidate.location.range !== undefined ? { startLine: candidate.location.range.startLine } : {}),
          confidence: candidate.confidence,
          reasons: candidate.reasons,
          indexComplete: candidate.indexComplete,
        })),
      };
    } catch (error) {
      return { ok: false, error: describeSourceError(error) };
    }
  });
  ipcMain.handle(IPC_CHANNELS.sourceRead, async (_event, input: unknown) => {
    const request = requireSourceReadRequest(input, IPC_CHANNELS.sourceRead);
    const backend = sourceBackend();
    if (backend.snapshotIdOf(request.workspaceId) === undefined) {
      return { ok: false, error: 'SOURCE_WORKSPACE_NOT_READY: this workspace has no indexed snapshot yet' };
    }
    try {
      const content = await readBackendSource(request.workspaceId, request.relativePath);
      if (content === undefined) return { ok: false, error: 'File is not part of the index' };
      return {
        ok: true,
        relativePath: request.relativePath,
        text: content.text,
        language: content.language,
        state: content.state,
      };
    } catch (error) {
      return { ok: false, error: describeSourceError(error) };
    }
  });
  ipcMain.handle(IPC_CHANNELS.methodProcesses, async (_event, serial: unknown) => {
    const client = adbClientFor();
    if (client === undefined) return { ok: false, processes: [], error: 'ADB is not available' };
    const selectedSerial = requireAdbSerial(serial, IPC_CHANNELS.methodProcesses);
    const result = await discoverMethodTraceProcesses({
      shell: (args, options) => client.shell(selectedSerial, args, options),
      pull: async () => { throw new Error('Method process discovery does not pull files'); },
    });
    return result.ok
      ? { ok: true, processes: result.value }
      : { ok: false, processes: [], error: result.error.code + ': ' + result.error.message };
  });
  ipcMain.handle(IPC_CHANNELS.methodCapture, async (_event, input: unknown) => {
    const request = requireMethodCaptureInput(input, IPC_CHANNELS.methodCapture);
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const stopSignal = methodRecordingLifecycle.begin();
    if (stopSignal === undefined) return { ok: false, error: 'METHOD_TRACE_CAPTURE_IN_PROGRESS: another method recording is active' };
    try {
      const temporaryDirectory = await mkdtemp(join(tmpdir(), 'aps-method-'));
      try {
        const result = await captureMethodRecording(
          {
            adb: {
              shell: (args, options) => client.shell(request.serial, args, options),
              pull: async (remote, local, options) => {
                await client.pull(request.serial, remote, local, options);
              },
            },
            temporaryPath: (name) => join(temporaryDirectory, name),
            sizeOf: async (path) => (await stat(path)).size,
            readFile: async (path) => new Uint8Array(await readFile(path)),
            removeFile: async (path) => {
              await rm(path, { force: true });
            },
            sleep: (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
            now: () => Date.now(),
            newId: () => String(Date.now()),
            shouldStop: () => stopSignal.shouldStop(),
            parseTrace: parseMethodTraceInWorker,
          },
          request,
        );
        if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
        const store = methodStore();
        const record: MethodSessionRecord = {
          id: result.value.id,
          capturedAtEpochMillis: result.value.capturedAtEpochMillis,
          origin: 'CAPTURED',
          serial: request.serial,
          packageName: result.value.packageName,
          pid: result.value.pid,
          durationSeconds: result.value.durationSeconds,
          deviceSdkApiLevel: result.value.deviceSdkApiLevel,
          traceVersion: result.value.analysis.header.version,
          traceBytes: result.value.trace.length,
          eventCount: result.value.analysis.events.length,
          methodCount: result.value.analysis.methods.size,
          threadCount: result.value.analysis.threads.size,
          threadKeys: [...result.value.analysis.threads.keys()].map((threadId) =>
            threadKeyOf(result.value.analysis, threadId),
          ),
          warnings: [
            ...result.value.analysis.warnings,
            ...result.value.warnings.map((warning) => warning.code + ': ' + warning.message),
          ],
        };
        await store.save(record, result.value.trace, {
          record,
          table: result.value.table,
          analysis: result.value.analysis,
        });
        return { ok: true, id: record.id };
      } finally {
        await rm(temporaryDirectory, { recursive: true, force: true }).catch(() => undefined);
      }
    } finally {
      methodRecordingLifecycle.finish(stopSignal);
    }
  });
  ipcMain.handle(IPC_CHANNELS.methodStop, () => {
    return methodRecordingLifecycle.requestStop()
      ? { ok: true }
      : { ok: false, error: 'METHOD_TRACE_NO_ACTIVE_CAPTURE: no method recording is active' };
  });
  ipcMain.handle(IPC_CHANNELS.methodImport, async () => {
    const selection = await dialog.showOpenDialog({
      title: 'Import a method trace',
      properties: ['openFile'],
      filters: [{ name: 'ART trace', extensions: ['trace'] }],
    });
    const filePath = selection.filePaths[0];
    if (selection.canceled || filePath === undefined) return { ok: false, cancelled: true };

    const importedAtEpochMillis = Date.now();
    const result = await importMethodTraceFile(
      {
        isRegularFile: (path) => {
          try {
            return statSync(path).isFile();
          } catch {
            return false;
          }
        },
        fileSize: async (path) => (await stat(path)).size,
        readFile: async (path) => new Uint8Array(await readFile(path)),
        now: () => importedAtEpochMillis,
        newId: () => String(importedAtEpochMillis),
        parseTrace: parseMethodTraceInWorker,
      },
      { path: filePath, fileName: basename(filePath) },
    );
    if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };

    await methodStore().save(result.value.record, result.value.trace, {
      record: result.value.record,
      table: result.value.table,
      analysis: result.value.analysis,
    });
    return { ok: true, id: result.value.record.id };
  });
  ipcMain.handle(IPC_CHANNELS.methodList, () => methodStore().list());
  ipcMain.handle(IPC_CHANNELS.methodRemove, (_event, id: unknown) =>
    methodStore().remove(requireOpaqueRecordId(id, IPC_CHANNELS.methodRemove)),
  );
  ipcMain.handle(IPC_CHANNELS.methodSnapshot, async (_event, input: unknown) => {
    const request = requireMethodSnapshotRequest(input, IPC_CHANNELS.methodSnapshot);
    const record = await methodStore().readRecord(request.id);
    if (record === undefined) return { ok: false, error: 'Session not found' };
    const session = await methodSessionFor(record);
    if (session === undefined) return { ok: false, error: 'The stored trace for this session is gone' };
    try {
      const graph: FlameGraphPayload = buildFlameGraphPayloadOf(
        session.table,
        {
          searchText: request.searchText,
          implementation: 'ALL',
          direction: directionOfQuery(request.direction),
          transforms: request.transforms.map(transformFromRequest),
        },
        {
          ...(request.threadKey !== undefined && request.threadKey.length > 0 ? { threadKey: request.threadKey } : {}),
          selectedThreadHasNoSamples:
            request.threadKey !== undefined &&
            request.threadKey.length > 0 &&
            !session.table.stacks.some((stack) => stack.threadKey === request.threadKey),
        },
      );
      const rows = topMethods(session.table, session.analysis as ArtTraceAnalysis, {
        search: request.searchText,
        limit: 200,
        sort: request.rankBy,
      });
      return { ok: true, graph, methods: rows };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : 'snapshot failed' };
    }
  });
  ipcMain.handle(IPC_CHANNELS.cpuCapture, async (_event, input: unknown) => {
    const validated = requireCpuCaptureInput(input, IPC_CHANNELS.cpuCapture);
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const temporaryDirectory = await mkdtemp(join(tmpdir(), 'aps-cpu-'));
    try {
      const result = await captureCpuProfile(
        {
          adb: {
            shell: (args, options) => client.shell(validated.serial, args, options),
            pull: async (remote, local, options) => {
              await client.pull(validated.serial, remote, local, options);
            },
          },
          locateHostSimpleperf: async () => {
            const located = await locateHostSimpleperf(hostSimpleperfDependencies());
            return located.ok ? ok({ executable: located.value.executable }) : located;
          },
          convert: (conversion) =>
            runReportSample(defaultConversionDependencies(), {
              simpleperf: conversion.executable,
              args: reportSampleArguments(conversion),
              protobufTrace: conversion.protobufTrace,
            }),
          temporaryPath: (name) => join(temporaryDirectory, name),
          sizeOf: async (path) => (await stat(path)).size,
          readFile: async (path) => new Uint8Array(await readFile(path)),
          removeFile: async (path) => {
            await rm(path, { force: true });
          },
          now: () => Date.now(),
          newId: () => String(Date.now()),
          retainPerfData: true,
          parseReport: parseSimpleperfReportInWorker,
        },
        validated,
      );
      if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
      if (result.value.perfDataPath === undefined) {
        return { ok: false, error: 'CPU_CAPTURE_PERF_DATA_MISSING: Raw perf.data was not retained for this capture' };
      }
      const session = createCpuProfileSession(
        {
          id: result.value.id,
          capturedAtEpochMillis: result.value.capturedAtEpochMillis,
          serial: validated.serial,
          parameters: result.value.parameters,
          profile: result.value.profile,
          table: result.value.table,
          protobufTrace: '',
          perfDataBytes: result.value.perfDataBytes,
          ...(result.value.simpleperfVersion !== undefined
            ? { simpleperfVersion: result.value.simpleperfVersion }
            : {}),
          devicePath: result.value.parameters.outputPath,
        },
        {
          reportFile: result.value.id + '/report.pb',
          table: result.value.table,
          ...(validated.packageName !== undefined ? { packageName: validated.packageName } : {}),
        },
      );
      const captureArtifactJson = encodeCaptureArtifact({
        id: session.record.id,
        kind: 'cpu.simpleperf',
        location: 'perf.data',
        sha256: await sha256File(result.value.perfDataPath),
        provenance: {
          producer: {
            producerType: 'known',
            name: 'Android simpleperf',
            ...(result.value.simpleperfVersion !== undefined ? { version: result.value.simpleperfVersion } : {}),
          },
          acquisition: {
            kind: 'CAPTURE',
            application: 'Android Performance Studio Electron',
            applicationVersion: app.getVersion(),
            performedAtEpochMillis: result.value.capturedAtEpochMillis,
          },
        },
        requestedCapabilities: ['cpu.samples'],
        availableCapabilities: ['cpu.samples'],
        completeness: 'COMPLETE',
      });
      await cpuStore().save(session.record, result.value.report, session.table, {
        perfDataPath: result.value.perfDataPath,
        simpleperfProtobuf: result.value.report,
        captureArtifactJson,
      });
      return { ok: true, id: session.record.id };
    } finally {
      await rm(temporaryDirectory, { recursive: true, force: true }).catch(() => undefined);
    }
  });
  ipcMain.handle(IPC_CHANNELS.cpuExport, async (_event, id: unknown) => {
    const recordId = requireOpaqueRecordId(id, IPC_CHANNELS.cpuExport);
    const selection = await dialog.showSaveDialog({
      title: 'Export CPU session package',
      defaultPath: recordId + '.apsession.zip',
      filters: [{ name: 'Android Performance Studio session package', extensions: ['apsession.zip', 'zip'] }],
    });
    if (selection.canceled || selection.filePath === undefined) return { ok: false, cancelled: true };
    const result = await exportCpuSessionPackage(
      { id: recordId, destinationArchive: selection.filePath },
      {
        sessionPackageDirectoryFor: (sessionId) => cpuStore().sessionPackageDirectoryFor(sessionId),
        exportPackage: (sessionDirectory, destinationArchive) =>
          new SessionPackageCodec().export(sessionDirectory, destinationArchive),
      },
    );
    return result.ok ? { ok: true } : { ok: false, error: result.error.code + ': ' + result.error.message };
  });
  ipcMain.handle(IPC_CHANNELS.cpuImport, async () => {
    const selection = await dialog.showOpenDialog({
      title: 'Import a CPU profile',
      properties: ['openFile'],
      filters: [
        { name: 'CPU profiles and Kotlin session packages', extensions: ['data', 'pb', 'protobuf', 'simpleperf', 'gz', 'zip'] },
        { name: 'All files', extensions: ['*'] },
      ],
    });
    const filePath = selection.filePaths[0];
    if (selection.canceled || filePath === undefined) return { ok: false, cancelled: true };
    const fileName = basename(filePath);
    const isSessionPackage = isCpuSessionPackageFileName(fileName);
    const directFormat = isSessionPackage ? undefined : formatOfFile(fileName);
    if (directFormat !== undefined && !directFormat.ok) {
      return { ok: false, error: directFormat.error.code + ': ' + directFormat.error.message };
    }

    const temporaryDirectory = await mkdtemp(join(tmpdir(), 'aps-import-'));
    try {
      let sourceBytes: Uint8Array | undefined;
      let importFileName: string;
      let sourceFormat: 'PERF_DATA' | 'SIMPLEPERF_PROTOBUF' | 'GECKO_PROFILE_JSON_GZIP';
      let perfData: string | undefined;
      let sourceSessionDirectory: string | undefined;
      let symbolDirectory: string | undefined;
      let proguardMapping: string | undefined;

      if (isSessionPackage) {
        const sessionPackage = await importCpuSessionPackage({
          archive: filePath,
          destinationRoot: temporaryDirectory,
        });
        if (!sessionPackage.ok) return { ok: false, error: sessionPackage.error.code + ': ' + sessionPackage.error.message };
        sourceSessionDirectory = sessionPackage.value.sessionDirectory;
        perfData = sessionPackage.value.perfData;
        symbolDirectory = sessionPackage.value.symbolDirectory;
        proguardMapping = sessionPackage.value.proguardMapping;
        importFileName = 'perf.data';
        sourceFormat = 'PERF_DATA';
      } else {
        if (directFormat === undefined) return { ok: false, error: 'CPU_IMPORT_FORMAT_UNKNOWN: Unsupported profile file' };
        sourceFormat = directFormat.value;
        importFileName = fileName;
        if (sourceFormat === 'PERF_DATA') {
          perfData = filePath;
        } else {
          sourceBytes = new Uint8Array(await readFile(filePath));
        }
      }

      if (sourceFormat === 'PERF_DATA') {
        // Keep Kotlin's captured-session rule: perf.data is converted with the
        // session's optional symbols and mapping, even if a stale protobuf is present.
        const located = await locateHostSimpleperf(hostSimpleperfDependencies());
        if (!located.ok) return { ok: false, error: located.error.code + ': ' + located.error.message };
        const converted = join(temporaryDirectory, 'imported.pb');
        const conversion = await runReportSample(defaultConversionDependencies(), {
          simpleperf: located.value.executable,
          args: reportSampleArguments({
            perfData: perfData as string,
            protobufTrace: converted,
            ...(symbolDirectory !== undefined ? { symbolDirectory } : {}),
            ...(proguardMapping !== undefined ? { proguardMapping } : {}),
          }),
          protobufTrace: converted,
        });
        if (!conversion.ok) return { ok: false, error: conversion.error.code + ': ' + conversion.error.message };
        sourceBytes = new Uint8Array(await readFile(converted));
        importFileName = 'imported.pb';
        sourceFormat = 'SIMPLEPERF_PROTOBUF';
      }

      if (sourceBytes === undefined) return { ok: false, error: 'CPU_IMPORT_INPUT_EMPTY: Imported profile content is empty' };
      const imported = await importCpuProfileAsync(
        { fileName: importFileName, bytes: sourceBytes },
        importOfflineCpuProfileInWorker,
      );
      if (!imported.ok) return { ok: false, error: imported.error.code + ': ' + imported.error.message };

      const now = Date.now();
      const date = new Date(now);
      const reportFile = date.toISOString().replaceAll(':', '-') + '-' + defaultReportFile(sourceFormat);
      const record: CpuProfileSessionRecord = {
        id: String(now),
        capturedAtEpochMillis: now,
        serial: 'imported',
        reportFile,
        sourceFormat,
        perfDataBytes: sourceBytes.length,
        sampleCount: imported.value.samples.length,
        lostCount: Number(imported.value.lostCount),
        eventTypes: [...(imported.value.metadata?.eventTypes ?? [])],
        threadKeys: imported.value.threadKeys,
        metadata: metadataRecord(imported.value.metadata),
        parameters: {
          target: fileName,
          event: imported.value.metadata?.eventTypes[0] ?? 'cpu-cycles',
          rate: '-',
          callGraph: '-',
          scope: '-',
        },
      };
      await cpuStore().save(
        record,
        sourceBytes,
        imported.value.table,
        sourceSessionDirectory === undefined ? undefined : { sourceSessionDirectory },
      );
      return { ok: true, id: record.id };
    } finally {
      await rm(temporaryDirectory, { recursive: true, force: true }).catch(() => undefined);
    }
  });
  ipcMain.handle(IPC_CHANNELS.cpuList, () => cpuStore().list());
  ipcMain.handle(IPC_CHANNELS.cpuEvents, async (_event, serial: unknown) => {
    const client = adbClientFor();
    if (client === undefined) return { ok: false, events: [], error: 'ADB is not available' };
    try {
      const output = await client.shell(requireAdbSerial(serial, IPC_CHANNELS.cpuEvents), ['simpleperf', 'list']);
      return { ok: true, events: parseSimpleperfEventNames(output.stdout) };
    } catch (error) {
      return { ok: false, events: [], error: error instanceof Error ? error.message : 'simpleperf list failed' };
    }
  });
  ipcMain.handle(IPC_CHANNELS.cpuRemove, (_event, id: unknown) =>
    cpuStore().remove(requireOpaqueRecordId(id, IPC_CHANNELS.cpuRemove)),
  );
  ipcMain.handle(IPC_CHANNELS.cpuOpen, async (_event, id: unknown) => {
    const record = await cpuStore().readRecord(requireOpaqueRecordId(id, IPC_CHANNELS.cpuOpen));
    if (record === undefined) return { ok: false, error: 'Session not found' };
    const profile = await geckoProfileForCpuSession(cpuStore(), record);
    if (!profile.ok) return { ok: false, error: profile.error.code + ': ' + profile.error.message };
    try {
      switch ((await ensureSettings()).simpleperf.engine) {
        case 'firefox-local':
          await firefoxProfilerService().openLocal(profile.value);
          break;
        case 'firefox':
          await firefoxProfilerService().openOfficial(profile.value);
          break;
        case 'local':
          // The current Electron CPU panel is the local engine and already owns
          // the selected session's interactive flame graph.
          break;
      }
      return { ok: true };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : 'Failed to open CPU profile' };
    }
  });
  ipcMain.handle(IPC_CHANNELS.cpuSnapshot, async (_event, input: unknown) => {
    const request = requireCpuSnapshotRequest(input, IPC_CHANNELS.cpuSnapshot);
    const record = await cpuStore().readRecord(request.id);
    if (record === undefined) return { ok: false, error: 'Session not found' };
    const table = await cpuTableFor(record);
    if (!table.ok) return { ok: false, error: table.error.code + ': ' + table.error.message };
    try {
      const transforms = request.transforms.map(transformFromRequest);
      const graph = buildFlameGraphPayload(
        table.value,
        {
          searchText: request.searchText,
          implementation: request.implementation,
          direction: directionOf(request.direction),
          transforms,
        },
        {
          ...(request.threadKey !== undefined && request.threadKey.length > 0 ? { threadKey: request.threadKey } : {}),
          selectedThreadHasNoSamples:
            request.threadKey !== undefined &&
            request.threadKey.length > 0 &&
            !table.value.stacks.some((stack) => stack.threadKey === request.threadKey),
        },
      );
      return { ok: true, graph };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : 'snapshot failed' };
    }
  });
  ipcMain.handle(IPC_CHANNELS.memoryCapture, async (_event, input: unknown) => {
    const request = requireAndroidAppTarget(input, IPC_CHANNELS.memoryCapture);
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const temporaryDirectory = await mkdtemp(join(tmpdir(), 'aps-heap-'));
    let parsedHeap: HprofParseResult | undefined;
    const store = memoryStore();
    try {
      const result = await captureHeapDump(
        {
          adb: {
            shell: (args, options) => client.shell(request.serial, args, options),
            pull: async (remote, local, options) => {
              await client.pull(request.serial, remote, local, options);
            },
          },
          sizeOf: async (path) => (await stat(path)).size,
          readFile: async (path) => new Uint8Array(await readFile(path)),
          removeFile: async (path) => {
            await rm(path, { force: true });
          },
          temporaryPath: (name) => join(temporaryDirectory, name),
          now: () => Date.now(),
          newId: () => String(Date.now()),
          persistRawDump: (id, bytes) => store.stageRawHeap(id, bytes),
          discardPersistedRawDump: (id) => store.discardStagedRawHeap(id),
          parseHeapDump: parseHeapDumpInWorker,
        },
        {
          serial: request.serial,
          packageName: request.packageName,
          // The raw dump is deleted below, so the parse is what browsing keeps.
          onParsed: (parsed) => {
            parsedHeap = parsed;
          },
        },
      );
      if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
      try {
        const summary = await store.addCaptured(result.value);
        if (parsedHeap !== undefined) cacheHeap(result.value.id, parsedHeap);
        return { ok: true, id: summary.id };
      } catch (error) {
        await store.discardStagedRawHeap(result.value.id).catch(() => undefined);
        return {
          ok: false,
          error: 'MEMORY_SESSION_SAVE_FAILED: ' + (error instanceof Error ? error.message : 'Failed to save heap session'),
        };
      }
    } finally {
      await rm(temporaryDirectory, { recursive: true, force: true }).catch(() => undefined);
    }
  });
  ipcMain.handle(IPC_CHANNELS.memoryList, () => memoryStore().list());
  ipcMain.handle(IPC_CHANNELS.memoryDiff, (_event, input: unknown) =>
    compareMemorySessions(memoryStore(), requireMemoryDiffRequest(input, IPC_CHANNELS.memoryDiff)),
  );
  ipcMain.handle(IPC_CHANNELS.memoryLoad, (_event, id: unknown) =>
    memoryStore().load(requireOpaqueRecordId(id, IPC_CHANNELS.memoryLoad)),
  );
  ipcMain.handle(IPC_CHANNELS.memoryInstances, async (_event, input: unknown) => {
    const request = requireMemoryInstanceRequest(input, IPC_CHANNELS.memoryInstances);
    const cached = await heapForMemorySession(request.sessionId);
    if (cached === undefined) return [];
    return instancesOf(cached.result, cached.graph, request.className, {
      ...(request.heap !== undefined ? { heap: request.heap } : {}),
      ...(request.limit !== undefined ? { limit: request.limit } : {}),
      retainedBytes: cached.analysis.dominators.retainedBytes,
      reachable: cached.analysis.reachability.reachable,
    });
  });
  ipcMain.handle(IPC_CHANNELS.memoryInstanceDetail, async (_event, input: unknown) => {
    const request = requireMemoryInstanceDetailRequest(input, IPC_CHANNELS.memoryInstanceDetail);
    const cached = await heapForMemorySession(request.sessionId);
    if (cached === undefined) return undefined;
    return instanceDetail(cached.result, cached.graph, request.objectId, cached.analysis);
  });
  ipcMain.handle(IPC_CHANNELS.bitmapCapture, async (_event, input: unknown) => {
    const request = requireAndroidAppTarget(input, IPC_CHANNELS.bitmapCapture);
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const temporaryDirectory = await mkdtemp(join(tmpdir(), 'aps-bitmap-'));
    try {
      const result = await captureBitmapDump(
        extendedCaptureDependencies(client, request.serial, temporaryDirectory),
        { serial: request.serial, packageName: request.packageName },
      );
      if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
      const summary = await bitmapStore().add(result.value.session);
      return { ok: true, id: summary.id };
    } finally {
      await rm(temporaryDirectory, { recursive: true, force: true }).catch(() => undefined);
    }
  });
  ipcMain.handle(IPC_CHANNELS.bitmapList, () => bitmapStore().list());
  ipcMain.handle(IPC_CHANNELS.bitmapLoad, (_event, id: unknown) =>
    bitmapStore().load(requireOpaqueRecordId(id, IPC_CHANNELS.bitmapLoad)),
  );
  ipcMain.handle(IPC_CHANNELS.bitmapImage, (_event, input: unknown) => {
    const request = requireBitmapImageRequest(input, IPC_CHANNELS.bitmapImage);
    return bitmapStore().loadImage(request.sessionId, request.recordIndex);
  });
  ipcMain.handle(IPC_CHANNELS.bitmapRemove, (_event, id: unknown) =>
    bitmapStore().remove(requireOpaqueRecordId(id, IPC_CHANNELS.bitmapRemove)),
  );
  ipcMain.handle(IPC_CHANNELS.nativeHeapCapture, async (_event, input: unknown) => {
    const request = requireAndroidAppTarget(input, IPC_CHANNELS.nativeHeapCapture);
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const temporaryDirectory = await mkdtemp(join(tmpdir(), 'aps-native-heap-'));
    try {
      const result = await captureNativeHeapTrace(
        extendedCaptureDependencies(client, request.serial, temporaryDirectory),
        { serial: request.serial, packageName: request.packageName },
      );
      if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
      const record: NativeHeapCaptureRecord = {
        id: result.value.id,
        packageName: result.value.packageName,
        deviceSerial: result.value.deviceSerial,
        capturedAtEpochMillis: result.value.capturedAtEpochMillis,
        sdkLevel: result.value.sdkLevel,
        traceFile: result.value.traceFile,
        fileName: result.value.fileName,
        fileSizeBytes: result.value.fileSizeBytes,
        analysis: result.value.analysis,
        evidenceSource: result.value.evidenceSource,
        warnings: result.value.warnings,
      };
      const summary = await nativeHeapStore().add(record);
      return { ok: true, id: summary.id };
    } finally {
      await rm(temporaryDirectory, { recursive: true, force: true }).catch(() => undefined);
    }
  });
  ipcMain.handle(IPC_CHANNELS.nativeHeapList, () => nativeHeapStore().list());
  ipcMain.handle(IPC_CHANNELS.nativeHeapLoad, (_event, id: unknown) =>
    nativeHeapStore().load(requireOpaqueRecordId(id, IPC_CHANNELS.nativeHeapLoad)),
  );
  ipcMain.handle(IPC_CHANNELS.nativeHeapRemove, (_event, id: unknown) =>
    nativeHeapStore().remove(requireOpaqueRecordId(id, IPC_CHANNELS.nativeHeapRemove)),
  );
  ipcMain.handle(IPC_CHANNELS.sourceSetAiUpload, (_event, input: unknown) => {
    const request = requireSourceAiUploadRequest(input, IPC_CHANNELS.sourceSetAiUpload);
    try {
      sourceBackend().setAiUploadAllowed(request.workspaceId, request.allowed);
      return true;
    } catch {
      return false;
    }
  });
  ipcMain.handle(IPC_CHANNELS.aiSettings, () => aiService().status());
  ipcMain.handle(IPC_CHANNELS.aiSaveConfiguration, (_event, input: unknown) => {
    const request = requireAiConfigurationInput(input, IPC_CHANNELS.aiSaveConfiguration);
    return aiService().saveConfiguration(request.model, request.endpoint);
  });
  ipcMain.handle(IPC_CHANNELS.aiSaveCredential, (_event, value: unknown) =>
    aiService().saveCredential(requireAiCredential(value, IPC_CHANNELS.aiSaveCredential)),
  );
  ipcMain.handle(IPC_CHANNELS.aiClearCredential, () => aiService().clearCredential());
  ipcMain.handle(IPC_CHANNELS.aiModels, () => aiService().models());
  ipcMain.handle(IPC_CHANNELS.aiSessions, () =>
    aiService()
      .sessions()
      .map((session) => ({
        id: session.id,
        createdAt: session.createdAt,
        status: session.status,
        model: session.model,
        provider: session.provider ?? null,
        scope: session.scope.description,
        summary: session.summary ?? null,
        errorMessage: session.errorMessage ?? null,
      })),
  );
  ipcMain.handle(IPC_CHANNELS.aiFindings, (_event, sessionId: unknown) =>
    aiService().findings(requireOpaqueRecordId(sessionId, IPC_CHANNELS.aiFindings)),
  );
  ipcMain.handle(IPC_CHANNELS.aiSourceCandidate, async (_event, input: unknown) => {
    const request = requireAiSourceCandidateOpenRequest(input, IPC_CHANNELS.aiSourceCandidate);
    const reference = aiService().sourceCandidate(request.sessionId, request.candidateId);
    if (reference === undefined) {
      return { ok: false, error: 'AI_SOURCE_CANDIDATE_NOT_CITED: this candidate is not cited by the selected AI session' };
    }
    const backend = sourceBackend();
    const snapshots = new Map(
      reference.sourceSnapshotIds.flatMap((id) => {
        const snapshot = backend.repository.snapshot(id);
        return snapshot === undefined ? [] : [[id, snapshot] as const];
      }),
    );
    const files = new Map(reference.sourceSnapshotIds.map((id) => [id, backend.repository.files(id)] as const));
    const location = sourceLocationForPersistedAiCandidate(reference, snapshots, files);
    if (location === undefined) {
      return { ok: false, error: 'AI_SOURCE_CANDIDATE_STALE: the historical source snapshot is unavailable' };
    }
    try {
      const content = await backend.service.read(location);
      const language = backend.repository.files(location.snapshotId).find((file) => file.relativePath === location.relativePath)?.language;
      if (language === undefined) return { ok: false, error: 'AI_SOURCE_CANDIDATE_STALE: indexed file metadata is unavailable' };
      return {
        ok: true,
        location: {
          workspaceId: location.workspaceId,
          relativePath: location.relativePath,
          ...(location.range === undefined ? {} : { startLine: location.range.startLine }),
          text: content.text,
          language,
          state: content.state,
        },
      };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : 'Unable to read cited source content' };
    }
  });
  ipcMain.handle(IPC_CHANNELS.aiAnalyze, async (_event, input: unknown) => {
    const request = requireAiAnalyzeRequest(input, IPC_CHANNELS.aiAnalyze);
    const snapshot = await layoutStore().loadSnapshot(request.captureId);
    if (snapshot === undefined) return { ok: false, sessionId: '', error: 'Layout capture not found: ' + request.captureId };
    const evidence = layoutPerformanceEvidence({
      captureId: request.captureId,
      ...(request.selectedNodeId !== undefined ? { selectedNodeId: request.selectedNodeId } : {}),
      highlights: [],
      payload: snapshot,
    });
    return aiService().analyze({
      evidence: [evidence],
      scopeDescription: request.selectedNodeId === undefined ? 'Layout report summary' : 'Layout node ' + request.selectedNodeId,
      ...(request.model !== undefined ? { model: request.model } : {}),
      ...(request.workspaceId !== undefined && request.workspaceId.length > 0
        ? {
            source: {
              workspaceId: request.workspaceId,
              buildIdentityMatch: request.buildIdentityMatch ?? 'UNVERIFIED',
              evidence: sourceEvidenceFor(snapshot, request.selectedNodeId),
            },
          }
        : {}),
    });
  });
  ipcMain.handle(IPC_CHANNELS.networkList, () => networkStore().list());
  ipcMain.handle(IPC_CHANNELS.networkLoad, (_event, id: unknown) =>
    networkStore().load(requireOpaqueRecordId(id, IPC_CHANNELS.networkLoad)),
  );
  ipcMain.handle(IPC_CHANNELS.batteryList, () => batteryStore().list());
  ipcMain.handle(IPC_CHANNELS.batteryLoad, (_event, id: unknown) =>
    batteryStore().load(requireOpaqueRecordId(id, IPC_CHANNELS.batteryLoad)),
  );
  ipcMain.handle(IPC_CHANNELS.startupList, () => startupStore().list());
  ipcMain.handle(IPC_CHANNELS.startupLoad, (_event, id: unknown) =>
    startupStore().load(requireOpaqueRecordId(id, IPC_CHANNELS.startupLoad)),
  );
  ipcMain.handle(IPC_CHANNELS.frameList, () => frameStore().list());
  ipcMain.handle(IPC_CHANNELS.frameLoad, (_event, id: unknown) =>
    frameStore().load(requireOpaqueRecordId(id, IPC_CHANNELS.frameLoad)),
  );
  ipcMain.handle(
    IPC_CHANNELS.layoutCapture,
    async (_event, requestedSerial: unknown, rawOptions: unknown) => {
    const request = requireLayoutCaptureInput(requestedSerial, rawOptions, IPC_CHANNELS.layoutCapture);
    const { requestedSerial: requestedSerialValue, options } = request;
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    // The reference's device selector offers "Auto device"; an empty serial is
    // that choice, and the first online device is the one it means.
    const serial =
      requestedSerialValue.length > 0
        ? requestedSerialValue
        : (await buildSnapshot()).devices.find((device) => device.state === 'ONLINE')?.serial;
    if (serial === undefined) return { ok: false, error: 'No online device' };
    const archive = options.archive ?? true;
    const result = await captureLayoutSnapshot(
      {
        adb: {
          shell: (args, shellOptions) => client.shell(serial, args, shellOptions),
          execOut: async (args, execOptions) => (await client.execOut(serial, args, execOptions)).stdout,
        },
        now: () => Date.now(),
      },
      serial,
      options.target === 'systemUi' ? SYSTEM_UI_PACKAGE_NAME : undefined,
      { retainRawArtifacts: archive },
    );
    if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
    let snapshot = result.value.snapshot;
    let composeInspectionJson: string | undefined;
    // The ordinary View capture remains authoritative when no verified agent bundle exists or
    // when an exact Compose inspector cannot be resolved. This keeps the longstanding silent
    // Compose-host fallback rather than turning a failed optional enhancement into a lost capture.
    if (snapshot.capabilities.composeSemantics && await composeAgentBundleIsAvailable()) {
      try {
        const compose = await captureWithVerifiedComposeAgent(
          {
            shell: (args, shellOptions) => client.shell(serial, args, shellOptions),
            push: (localPath, remotePath, pushOptions) => client.push(serial, localPath, remotePath, pushOptions),
            forward: (local, remote, forwardOptions) => client.forward(serial, local, remote, forwardOptions),
            removeForward: (local, forwardOptions) => client.removeForward(serial, local, forwardOptions),
          },
          snapshot.packageName,
          () => Date.now(),
          {
            bundleRoot: composeAgentBundleRoot(),
            artifactResolver: { cacheDirectory: join(userDataDirectory(), 'compose-inspector') },
          },
        );
        snapshot = {
          ...compose.snapshot,
          display: result.value.display,
          capabilities: { ...compose.snapshot.capabilities, screenshots: result.value.screenshotPng.length > 0 },
        };
        composeInspectionJson = compose.composeInspectionJson;
      } catch {
        // A deliberately silent compatible fallback; the View tree and screenshot above are usable.
      }
    }
    if (!archive) {
      // Auto scan's path: the frame goes straight to the page, and the store
      // keeps the captures the user actually asked for.
      return {
        ok: true,
        detail: {
          snapshot,
          ...(result.value.screenshotPng.length > 0 &&
          result.value.screenshotPng.length <= MAX_INLINE_SCREENSHOT_BYTES
            ? { screenshotBase64: result.value.screenshotPng.toString('base64') }
            : {}),
          ...(composeInspectionJson !== undefined ? { composeInspectionJson } : {}),
        },
      };
    }
    const record = await layoutStore().add(
      snapshot,
      result.value.screenshotPng,
      composeInspectionJson,
      result.value.rawArtifacts,
    );
    return { ok: true, id: record.id };
  });
  ipcMain.handle(IPC_CHANNELS.layoutArchiveImport, async () => {
    const selection = await dialog.showOpenDialog({
      title: 'Import capture archive',
      properties: ['openFile'],
      filters: [{ name: 'Layout Inspector capture', extensions: ['apinspect'] }],
    });
    const source = selection.filePaths[0];
    if (selection.canceled || source === undefined) return { ok: false };
    const outcome = await importLayoutArchiveFromPath(source);
    if (outcome.ok) {
      await recordRecentLayoutArchive(source);
      refreshViewerMenu();
    }
    return outcome;
  });
  ipcMain.handle(IPC_CHANNELS.layoutArchiveExport, async (_event, id: unknown) => {
    const recordId = requireOpaqueRecordId(id, IPC_CHANNELS.layoutArchiveExport);
    const record = (await layoutStore().list()).find((capture) => capture.id === recordId);
    if (record === undefined) return { ok: false, error: 'Layout capture not found' };
    const safePackage = record.packageName.replace(/[^A-Za-z0-9._-]+/g, '_');
    const selection = await dialog.showSaveDialog({
      title: 'Export capture archive',
      defaultPath: `${safePackage}-${record.capturedAtEpochMillis}.apinspect`,
      filters: [{ name: 'Layout Inspector capture', extensions: ['apinspect'] }],
    });
    if (selection.canceled || selection.filePath === undefined) return { ok: false };
    try {
      const exported = await captureArchiveService().export(recordId, selection.filePath);
      return { ok: true, path: exported.path };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : 'Capture archive export failed' };
    }
  });
  ipcMain.handle(IPC_CHANNELS.layoutList, () => layoutStore().list());
  ipcMain.handle(IPC_CHANNELS.layoutLoad, async (_event, id: unknown) => {
    const recordId = requireOpaqueRecordId(id, IPC_CHANNELS.layoutLoad);
    const store = layoutStore();
    const snapshot = await store.loadSnapshot(recordId);
    if (snapshot === undefined) return undefined;
    const png = await store.loadScreenshot(recordId);
    const composeInspectionJson = await store.loadComposeInspection(recordId);
    const archivePayloads = await store.loadArchivePayloads(recordId);
    return {
      snapshot,
      // An empty file is a capture taken without pixels, which the canvas
      // reports as unavailable rather than rendering a broken image.
      ...(png !== undefined && png.length > 0 && png.length <= MAX_INLINE_SCREENSHOT_BYTES
        ? { screenshotBase64: png.toString('base64') }
        : {}),
      ...(composeInspectionJson !== undefined ? { composeInspectionJson } : {}),
      ...archivePayloads,
    };
  });
  ipcMain.handle(IPC_CHANNELS.traceReveal, async (_event, id: unknown) => {
    const recordId = requireOpaqueRecordId(id, IPC_CHANNELS.traceReveal);
    const record = (await traceStore().list()).find((entry) => entry.id === recordId);
    if (record === undefined) return { ok: false, error: 'Trace not found' };
    shell.showItemInFolder(record.path);
    return { ok: true };
  });
}

function createWindow(): void {
  // macOS gets the system window chrome: the traffic lights sit inside the
  // shell's own toolbar, and the window supplies the vibrancy material behind
  // it, which only shows when the page and the base colour stay transparent.
  // Other platforms keep a normal frame and the shell's own window colour.
  const mac = process.platform === 'darwin';
  window = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 1100,
    minHeight: 720,
    show: false,
    backgroundColor: mac ? '#00000000' : nativeTheme.shouldUseDarkColors ? '#1e1e1e' : '#ececec',
    title: 'Android Performance Studio',
    ...(mac
      ? {
          titleBarStyle: 'hiddenInset' as const,
          // Centred in the 39px toolbar the shell reserves for it; the buttons
          // are about 16px tall, so 11px above and 12px below.
          trafficLightPosition: { x: 16, y: 11 },
          vibrancy: 'sidebar' as const,
          visualEffectState: 'followWindow' as const,
        }
      : {}),
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
    },
  });

  // The app opens filling the work area rather than at 1280x800: the home page
  // is a launcher grid, and every feature page has always maximized on entry.
  window.once('ready-to-show', () => {
    window?.maximize();
    window?.show();
  });

  // The stored display size has to be on before the first paint: the shell's
  // layout is dense, and a scale applied later reads as a resize.
  window.webContents.on('did-finish-load', () => applyDisplayScale());

  window.webContents.setWindowOpenHandler(({ url }) => {
    void shell.openExternal(url);
    return { action: 'deny' };
  });

  const rendererUrl = process.env['ELECTRON_RENDERER_URL'];
  if (rendererUrl !== undefined && rendererUrl.length > 0) {
    void window.loadURL(rendererUrl);
  } else {
    void window.loadFile(join(__dirname, '../renderer/index.html'));
  }
}

app.whenReady().then(async () => {
  await migrateLegacyElectronData(legacyElectronUserDataDirectory, sharedApplicationDataDirectory);
  // Import the workspaces the app created before the shared database existed.
  // It runs before the window so the source page never shows an empty list and
  // then fills in; a failed migration still must not stop the app from starting.
  await migrateLegacySourceWorkspaces();
  registerHandlers();
  installPerfettoProtocolHandlers({
    uiDirectory: perfettoUiDirectory(),
    readTrace: async (id) => {
      const record = (await traceStore().list()).find((entry) => entry.id === id);
      if (record === undefined) return undefined;
      return await readFile(record.path);
    },
  });
  createWindow();
  // A menu exists before the renderer reports, so the app never falls back to
  // Electron's default menu; the renderer replaces this state as it mounts.
  const storedSettings = await ensureSettings();
  currentViewerMenuState = idleViewerMenuState(resolveLanguage(storedSettings.language ?? 'system', app.getLocale()));
  recentArchivePaths = await recentArchiveStore().load();
  refreshViewerMenu();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('before-quit', () => {
  void userDocumentation?.close();
  void firefoxProfiler?.close();
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
