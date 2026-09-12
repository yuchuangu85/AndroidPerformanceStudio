import { statSync } from 'node:fs';
import { mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { homedir, tmpdir } from 'node:os';
import { basename, delimiter, dirname } from 'node:path';

import { join } from 'node:path';
import { app, BrowserWindow, dialog, ipcMain, nativeTheme, shell } from 'electron';
import { fail, ok, type StudioResult } from '@aps/contracts';
import { sha256File } from '@aps/contracts/node';
import { walkNode, type LayoutSnapshot } from '@aps/layout-inspector';
import { AdbClient } from '@aps/platform-adb';
import type { SourceResolutionEvidence } from '@aps/source-workspace';
import { findPerfettoUiAssetsDirectory, type PerfettoUiAssetProbe } from '@aps/platform-perfetto';
import { JsonSettingsStore } from '@aps/settings';
import { buildAppInfo } from '../shared/app-info.js';
import { shouldMaximizeWindow, type AppDestination } from '../shared/destinations.js';
import {
  IPC_CHANNELS,
  type FrameCaptureInput,
  type MigrationStatus,
  type BatteryCaptureInput,
  type CpuCaptureRequest,
  type CpuSnapshotRequest,
  type MemoryCaptureInput,
  type MethodCaptureRequest,
  type MethodSessionRecord,
  type MethodSnapshotRequest,
  type AiAnalyzeRequest,
  type AiConfigurationInput,
  type BitmapCaptureRequest,
  type MemoryInstanceDetailRequest,
  type MemoryInstanceRequest,
  type NativeHeapCaptureRequest,
  type NativeHeapCaptureRecord,
  type SourceResolveRequest,
  type BenchmarkCompareInput,
  type ShellSnapshot,
  type StartupCaptureInput,
  type TraceAnalyzerSnapshot,
  type TraceCaptureInput,
  type TraceCaptureOutcome,
  type TraceOpenOutcome,
} from '../shared/ipc.js';
import {
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
import {
  buildFlameGraphPayload,
  samplesToCallStackTable,
  createCpuProfileSession,
  directionOf,
  readGeckoProfileText,
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
import {
  parseArtTrace,
  threadKeyOf,
  topMethods,
  toCallStackTable,
  type ArtTraceAnalysis,
} from '@aps/art-trace';
import { captureMethodRecording } from './method-capture-service.js';
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
import {
  defaultConversionDependencies,
  defaultHostSimpleperfLocatorDependencies,
  locateHostSimpleperf,
  runReportSample,
  gunzipProfileText,
  type HostSimpleperfLocatorDependencies,
} from '@aps/simpleperf-profiler/node';
import { captureCpuProfile } from './cpu-capture-service.js';
import { formatOfFile, importCpuProfile } from './cpu-import-service.js';
import { defaultReportFile } from './cpu-profile-store.js';
import { CpuProfileStore } from './cpu-profile-store.js';
import { parseCpuProfileReport } from './cpu-profile-parser.js';
import {
  captureBitmapDump,
  captureHeapDump,
  captureNativeHeapTrace,
  type ExtendedMemoryCaptureDependencies,
} from './memory-capture-service.js';
import { MemorySessionStore } from './memory-session-store.js';
import { BitmapDumpStore, NativeHeapStore } from './memory-artifact-stores.js';
import { cacheHeap, cachedHeap } from './memory-heap-cache.js';
import { instancesOf, instanceDetail, type HprofParseResult } from '@aps/memory-profiler';
import { capturePerfettoTrace } from './trace-capture-service.js';
import { TraceStore } from './trace-store.js';
import { resolveTraceProcessorStatus } from './trace-service.js';

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

function userDataDirectory(): string {
  return app.getPath('userData');
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
  const parsed = parseArtTrace(trace);
  if (!parsed.ok) return undefined;
  const session: StoredMethodSession = {
    record,
    table: toCallStackTable(parsed.value),
    analysis: parsed.value,
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
    // Imported Gecko sessions keep the original archive and are re-read on load.
    const text = gunzipProfileText(report);
    if (!text.ok) return text;
    const gecko = readGeckoProfileText(text.value);
    if (!gecko.ok) return gecko;
    const table = samplesToCallStackTable(gecko.value.samples);
    store.cacheTable(record.id, table);
    return ok(table);
  }
  const parsed = parseCpuProfileReport(report);
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

async function updateSettings(patch: ApplicationUiSettingsPatch): Promise<ShellSnapshot> {
  const current = await ensureSettings();
  // One deep merge for every page: a patch that carries one toggle must not
  // erase the sections the sender never read.
  const merged = mergeApplicationUiSettings(current, patch);
  const store = new JsonSettingsStore(join(userDataDirectory(), 'settings.json'), settingsIo);
  if (await store.save(merged)) settings = merged;
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

function registerHandlers(): void {
  ipcMain.handle(IPC_CHANNELS.shellSnapshot, () => buildSnapshot());
  ipcMain.handle(IPC_CHANNELS.updateSettings, (_event, patch: ApplicationUiSettingsPatch) =>
    updateSettings(patch),
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
  ipcMain.handle(IPC_CHANNELS.openDestination, (_event, destination: AppDestination) => {
    if (window === undefined) return;
    // The window opens maximized and stays that way: coming back to the grid
    // never shrinks it, because a launcher that resizes on the way home reads
    // as a state change the user did not ask for.
    if (shouldMaximizeWindow(destination)) window.maximize();
  });
  ipcMain.handle(IPC_CHANNELS.traceAnalyzer, () => buildTraceAnalyzerSnapshot());
  ipcMain.handle(IPC_CHANNELS.traceCapture, (_event, input: TraceCaptureInput) => runCapture(input));
  ipcMain.handle(IPC_CHANNELS.traceOpen, (_event, id: string) => openTraceInAnalyzer(id));
  ipcMain.handle(IPC_CHANNELS.traceOpenPublicUi, async () => {
    await shell.openExternal('https://ui.perfetto.dev');
  });
  ipcMain.handle(IPC_CHANNELS.frameCapture, async (_event, input: FrameCaptureInput) => {
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const result = await captureFrameSession(
      {
        adb: { shell: (args, options) => client.shell(input.serial, args, options) },
        now: () => Date.now(),
        newSessionId: () => String(Date.now()),
      },
      { serial: input.serial, packageName: input.packageName },
    );
    if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
    const summary = await frameStore().add(result.value);
    return { ok: true, id: summary.id };
  });
  ipcMain.handle(IPC_CHANNELS.startupCapture, async (_event, input: StartupCaptureInput) => {
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const result = await runStartupExperiment(
      {
        adb: { shell: (args, options) => client.shell(input.serial, args, options) },
        now: () => Date.now(),
        newId: () => String(Date.now()),
      },
      {
        serial: input.serial,
        packageName: input.packageName,
        ...(input.componentName !== undefined && input.componentName.length > 0
          ? { componentName: input.componentName }
          : {}),
        config: {
          requestedType: input.requestedType,
          warmupRuns: input.warmupRuns,
          measuredRuns: input.measuredRuns,
          timeoutSeconds: input.timeoutSeconds,
        },
      },
    );
    if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
    const summary = await startupStore().add(result.value);
    return { ok: true, id: summary.id };
  });
  ipcMain.handle(IPC_CHANNELS.batteryCapture, async (_event, input: BatteryCaptureInput) => {
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const result = await runBatteryExperiment(
      {
        adb: { shell: (args, options) => client.shell(input.serial, args, options) },
        now: () => Date.now(),
        newId: () => String(Date.now()),
        sleep: (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
      },
      {
        serial: input.serial,
        packageName: input.packageName,
        uid: input.uid,
        config: {
          mode: input.mode,
          durationSeconds: input.durationSeconds,
          pollingIntervalSeconds: input.pollingIntervalSeconds,
          measuredRuns: input.measuredRuns,
          launchApp: false,
          cooldownSeconds: input.cooldownSeconds,
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
  ipcMain.handle(IPC_CHANNELS.benchmarkCompare, async (_event, input: BenchmarkCompareInput) => {
    const store = benchmarkStore();
    const baseline = await store.load(input.baselineId);
    const current = await store.load(input.currentId);
    if (baseline === undefined || current === undefined) {
      return { ok: false, error: 'Select both a baseline and a current run' };
    }
    const policy = {
      ...DEFAULT_REGRESSION_POLICY,
      ...(input.relativeThresholdPercent !== undefined
        ? { relativeThresholdPercent: input.relativeThresholdPercent }
        : {}),
      ...(input.absoluteThreshold !== undefined ? { absoluteThreshold: input.absoluteThreshold } : {}),
    };
    return { ok: true, report: compareBenchmarkRuns(baseline, current, policy) };
  });
  ipcMain.handle(IPC_CHANNELS.traceImportFromPath, (_event, path: string) => importTraceFromPath(path));
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
  ipcMain.handle(IPC_CHANNELS.gpuReveal, async (_event, id: string) => {
    const artifact = (await gpuStore().list()).find((entry) => entry.id === id);
    if (artifact === undefined) return { ok: false, error: 'Artifact not found' };
    shell.showItemInFolder(artifact.path);
    return { ok: true };
  });
  ipcMain.handle(IPC_CHANNELS.gpuRelocate, async (_event, id: string) => {
    const selection = await dialog.showOpenDialog({ title: 'Locate GPU artifact', properties: ['openFile'] });
    const filePath = selection.filePaths[0];
    if (selection.canceled || filePath === undefined) return { ok: false, cancelled: true };
    try {
      const relocated = await gpuStore().relocate(id, filePath);
      if (relocated === undefined) return { ok: false, error: 'Artifact not found' };
      return { ok: true, id: relocated.id };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : 'relocation failed' };
    }
  });
  ipcMain.handle(IPC_CHANNELS.gpuOpen, async (_event, id: string) => {
    const store = gpuStore();
    const artifact = (await store.list()).find((entry) => entry.id === id);
    if (artifact === undefined) return { ok: false, error: 'Artifact not found' };
    const location = await store.resolveLocation(id);
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
  ipcMain.handle(IPC_CHANNELS.sourceRemove, (_event, id: string) => {
    try {
      sourceBackend().service.remove(id);
      return true;
    } catch {
      return false;
    }
  });
  ipcMain.handle(IPC_CHANNELS.sourceReindex, async (_event, id: string) => {
    try {
      const workspace = await sourceBackend().service.refresh(id);
      if (workspace.phase === 'FAILED') {
        return { ok: false, id, error: workspace.message ?? 'Source indexing failed' };
      }
      return { ok: true, id };
    } catch (error) {
      return { ok: false, error: describeSourceError(error) };
    }
  });
  ipcMain.handle(
    IPC_CHANNELS.sourceSearch,
    (_event, input: { workspaceId: string; query: string; limit: number }) =>
      searchBackendSymbols(input.workspaceId, input.query, input.limit),
  );
  ipcMain.handle(IPC_CHANNELS.sourceResolve, async (_event, input: SourceResolveRequest) => {
    const backend = sourceBackend();
    // An empty candidate list and an unindexed workspace mean different things to
    // the user, so the second one is an error rather than "no candidates".
    if (backend.snapshotIdOf(input.workspaceId) === undefined) {
      return { ok: false, error: 'SOURCE_WORKSPACE_NOT_READY: this workspace has no indexed snapshot yet' };
    }
    try {
      const candidates = backend.resolveForWorkspace(input.workspaceId, input.evidence, input.buildIdentityMatch);
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
  ipcMain.handle(
    IPC_CHANNELS.sourceRead,
    async (_event, input: { workspaceId: string; relativePath: string }) => {
      const backend = sourceBackend();
      if (backend.snapshotIdOf(input.workspaceId) === undefined) {
        return { ok: false, error: 'SOURCE_WORKSPACE_NOT_READY: this workspace has no indexed snapshot yet' };
      }
      try {
        const content = await readBackendSource(input.workspaceId, input.relativePath);
        if (content === undefined) return { ok: false, error: 'File is not part of the index' };
        return {
          ok: true,
          relativePath: input.relativePath,
          text: content.text,
          language: content.language,
          state: content.state,
        };
      } catch (error) {
        return { ok: false, error: describeSourceError(error) };
      }
    },
  );
  ipcMain.handle(IPC_CHANNELS.methodCapture, async (_event, input: MethodCaptureRequest) => {
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const temporaryDirectory = await mkdtemp(join(tmpdir(), 'aps-method-'));
    try {
      const result = await captureMethodRecording(
        {
          adb: {
            shell: (args, options) => client.shell(input.serial, args, options),
            pull: async (remote, local, options) => {
              await client.pull(input.serial, remote, local, options);
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
        },
        input,
      );
      if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
      const store = methodStore();
      const record: MethodSessionRecord = {
        id: result.value.id,
        capturedAtEpochMillis: result.value.capturedAtEpochMillis,
        serial: input.serial,
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
  });
  ipcMain.handle(IPC_CHANNELS.methodList, () => methodStore().list());
  ipcMain.handle(IPC_CHANNELS.methodRemove, (_event, id: string) => methodStore().remove(id));
  ipcMain.handle(IPC_CHANNELS.methodSnapshot, async (_event, input: MethodSnapshotRequest) => {
    const record = await methodStore().readRecord(input.id);
    if (record === undefined) return { ok: false, error: 'Session not found' };
    const session = await methodSessionFor(record);
    if (session === undefined) return { ok: false, error: 'The stored trace for this session is gone' };
    try {
      const graph: FlameGraphPayload = buildFlameGraphPayloadOf(
        session.table,
        {
          searchText: input.searchText,
          implementation: 'ALL',
          direction: directionOfQuery(input.direction),
          transforms: input.transforms.map(transformFromRequest),
        },
        {
          ...(input.threadKey !== undefined && input.threadKey.length > 0 ? { threadKey: input.threadKey } : {}),
          selectedThreadHasNoSamples:
            input.threadKey !== undefined &&
            input.threadKey.length > 0 &&
            !session.table.stacks.some((stack) => stack.threadKey === input.threadKey),
        },
      );
      const rows = topMethods(session.table, session.analysis as ArtTraceAnalysis, {
        search: input.searchText,
        limit: 200,
        sort: input.rankBy,
      });
      return { ok: true, graph, methods: rows };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : 'snapshot failed' };
    }
  });
  ipcMain.handle(IPC_CHANNELS.cpuCapture, async (_event, input: CpuCaptureRequest) => {
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const temporaryDirectory = await mkdtemp(join(tmpdir(), 'aps-cpu-'));
    try {
      const result = await captureCpuProfile(
        {
          adb: {
            shell: (args, options) => client.shell(input.serial, args, options),
            pull: async (remote, local, options) => {
              await client.pull(input.serial, remote, local, options);
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
        },
        input,
      );
      if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
      const parsed = parseCpuProfileReport(result.value.report);
      if (!parsed.ok) return { ok: false, error: parsed.error.code + ': ' + parsed.error.message };
      const session = createCpuProfileSession(
        {
          id: result.value.id,
          capturedAtEpochMillis: result.value.capturedAtEpochMillis,
          serial: input.serial,
          parameters: result.value.parameters,
          profile: result.value.profile,
          protobufTrace: '',
          perfDataBytes: result.value.perfDataBytes,
          ...(result.value.simpleperfVersion !== undefined
            ? { simpleperfVersion: result.value.simpleperfVersion }
            : {}),
          devicePath: result.value.parameters.outputPath,
        },
        {
          reportFile: result.value.id + '/report.pb',
          table: parsed.value.table,
          ...(input.packageName !== undefined ? { packageName: input.packageName } : {}),
        },
      );
      await cpuStore().save(session.record, result.value.report, session.table);
      return { ok: true, id: session.record.id };
    } finally {
      await rm(temporaryDirectory, { recursive: true, force: true }).catch(() => undefined);
    }
  });
  ipcMain.handle(IPC_CHANNELS.cpuImport, async () => {
    const selection = await dialog.showOpenDialog({
      title: 'Import a CPU profile',
      properties: ['openFile'],
      filters: [
        { name: 'CPU profiles', extensions: ['data', 'pb', 'protobuf', 'simpleperf', 'gz'] },
        { name: 'All files', extensions: ['*'] },
      ],
    });
    const filePath = selection.filePaths[0];
    if (selection.canceled || filePath === undefined) return { ok: false, cancelled: true };
    const fileName = basename(filePath);
    const format = formatOfFile(fileName);
    if (!format.ok) return { ok: false, error: format.error.code + ': ' + format.error.message };

    const temporaryDirectory = await mkdtemp(join(tmpdir(), 'aps-import-'));
    try {
      let sourceBytes = new Uint8Array(await readFile(filePath));
      let importFileName = fileName;
      let text: string | undefined;
      let sourceFormat = format.value;

      if (format.value === 'PERF_DATA') {
        // perf.data is not portable, so it is converted exactly like a capture.
        const located = await locateHostSimpleperf(hostSimpleperfDependencies());
        if (!located.ok) return { ok: false, error: located.error.code + ': ' + located.error.message };
        const converted = join(temporaryDirectory, 'imported.pb');
        const conversion = await runReportSample(defaultConversionDependencies(), {
          simpleperf: located.value.executable,
          args: reportSampleArguments({ perfData: filePath, protobufTrace: converted }),
          protobufTrace: converted,
        });
        if (!conversion.ok) return { ok: false, error: conversion.error.code + ': ' + conversion.error.message };
        sourceBytes = new Uint8Array(await readFile(converted));
        importFileName = 'imported.pb';
        sourceFormat = 'SIMPLEPERF_PROTOBUF';
      } else if (format.value === 'GECKO_PROFILE_JSON_GZIP') {
        const gunzipped = gunzipProfileText(sourceBytes);
        if (!gunzipped.ok) return { ok: false, error: gunzipped.error.code + ': ' + gunzipped.error.message };
        text = gunzipped.value;
      }

      const imported = importCpuProfile({
        fileName: importFileName,
        ...(text !== undefined ? { text } : { bytes: sourceBytes }),
      });
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
      await cpuStore().save(record, sourceBytes, imported.value.table);
      return { ok: true, id: record.id };
    } finally {
      await rm(temporaryDirectory, { recursive: true, force: true }).catch(() => undefined);
    }
  });
  ipcMain.handle(IPC_CHANNELS.cpuList, () => cpuStore().list());
  ipcMain.handle(IPC_CHANNELS.cpuRemove, (_event, id: string) => cpuStore().remove(id));
  ipcMain.handle(IPC_CHANNELS.cpuSnapshot, async (_event, input: CpuSnapshotRequest) => {
    const record = await cpuStore().readRecord(input.id);
    if (record === undefined) return { ok: false, error: 'Session not found' };
    const table = await cpuTableFor(record);
    if (!table.ok) return { ok: false, error: table.error.code + ': ' + table.error.message };
    try {
      const transforms = input.transforms.map(transformFromRequest);
      const graph = buildFlameGraphPayload(
        table.value,
        {
          searchText: input.searchText,
          implementation: input.implementation,
          direction: directionOf(input.direction),
          transforms,
        },
        {
          ...(input.threadKey !== undefined && input.threadKey.length > 0 ? { threadKey: input.threadKey } : {}),
          selectedThreadHasNoSamples:
            input.threadKey !== undefined &&
            input.threadKey.length > 0 &&
            !table.value.stacks.some((stack) => stack.threadKey === input.threadKey),
        },
      );
      return { ok: true, graph };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : 'snapshot failed' };
    }
  });
  ipcMain.handle(IPC_CHANNELS.memoryCapture, async (_event, input: MemoryCaptureInput) => {
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const temporaryDirectory = await mkdtemp(join(tmpdir(), 'aps-heap-'));
    let parsedHeap: HprofParseResult | undefined;
    try {
      const result = await captureHeapDump(
        {
          adb: {
            shell: (args, options) => client.shell(input.serial, args, options),
            pull: async (remote, local, options) => {
              await client.pull(input.serial, remote, local, options);
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
        },
        {
          serial: input.serial,
          packageName: input.packageName,
          // The raw dump is deleted below, so the parse is what browsing keeps.
          onParsed: (parsed) => {
            parsedHeap = parsed;
          },
        },
      );
      if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
      if (parsedHeap !== undefined) cacheHeap(result.value.id, parsedHeap);
      const summary = await memoryStore().add(result.value);
      return { ok: true, id: summary.id };
    } finally {
      await rm(temporaryDirectory, { recursive: true, force: true }).catch(() => undefined);
    }
  });
  ipcMain.handle(IPC_CHANNELS.memoryList, () => memoryStore().list());
  ipcMain.handle(IPC_CHANNELS.memoryLoad, (_event, id: string) => memoryStore().load(id));
  ipcMain.handle(IPC_CHANNELS.memoryInstances, (_event, input: MemoryInstanceRequest) => {
    const cached = cachedHeap(input.sessionId);
    if (cached === undefined) return [];
    return instancesOf(cached.result, cached.graph, input.className, {
      ...(input.heap !== undefined ? { heap: input.heap } : {}),
      ...(input.limit !== undefined ? { limit: input.limit } : {}),
      retainedBytes: cached.analysis.dominators.retainedBytes,
      reachable: cached.analysis.reachability.reachable,
    });
  });
  ipcMain.handle(IPC_CHANNELS.memoryInstanceDetail, (_event, input: MemoryInstanceDetailRequest) => {
    const cached = cachedHeap(input.sessionId);
    if (cached === undefined) return undefined;
    return instanceDetail(cached.result, cached.graph, input.objectId, cached.analysis);
  });
  ipcMain.handle(IPC_CHANNELS.bitmapCapture, async (_event, input: BitmapCaptureRequest) => {
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const temporaryDirectory = await mkdtemp(join(tmpdir(), 'aps-bitmap-'));
    try {
      const result = await captureBitmapDump(
        extendedCaptureDependencies(client, input.serial, temporaryDirectory),
        { serial: input.serial, packageName: input.packageName },
      );
      if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
      const summary = await bitmapStore().add(result.value.session);
      return { ok: true, id: summary.id };
    } finally {
      await rm(temporaryDirectory, { recursive: true, force: true }).catch(() => undefined);
    }
  });
  ipcMain.handle(IPC_CHANNELS.bitmapList, () => bitmapStore().list());
  ipcMain.handle(IPC_CHANNELS.bitmapLoad, (_event, id: string) => bitmapStore().load(id));
  ipcMain.handle(IPC_CHANNELS.bitmapRemove, (_event, id: string) => bitmapStore().remove(id));
  ipcMain.handle(IPC_CHANNELS.nativeHeapCapture, async (_event, input: NativeHeapCaptureRequest) => {
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const temporaryDirectory = await mkdtemp(join(tmpdir(), 'aps-native-heap-'));
    try {
      const result = await captureNativeHeapTrace(
        extendedCaptureDependencies(client, input.serial, temporaryDirectory),
        { serial: input.serial, packageName: input.packageName },
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
  ipcMain.handle(IPC_CHANNELS.nativeHeapLoad, (_event, id: string) => nativeHeapStore().load(id));
  ipcMain.handle(IPC_CHANNELS.nativeHeapRemove, (_event, id: string) => nativeHeapStore().remove(id));
  ipcMain.handle(IPC_CHANNELS.sourceSetAiUpload, (_event, input: { workspaceId: string; allowed: boolean }) => {
    try {
      sourceBackend().setAiUploadAllowed(input.workspaceId, input.allowed);
      return true;
    } catch {
      return false;
    }
  });
  ipcMain.handle(IPC_CHANNELS.aiSettings, () => aiService().status());
  ipcMain.handle(IPC_CHANNELS.aiSaveConfiguration, (_event, input: AiConfigurationInput) =>
    aiService().saveConfiguration(input.model, input.endpoint),
  );
  ipcMain.handle(IPC_CHANNELS.aiSaveCredential, (_event, value: string) => aiService().saveCredential(value));
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
  ipcMain.handle(IPC_CHANNELS.aiFindings, (_event, sessionId: string) => aiService().findings(sessionId));
  ipcMain.handle(IPC_CHANNELS.aiAnalyze, async (_event, input: AiAnalyzeRequest) => {
    const snapshot = await layoutStore().loadSnapshot(input.captureId);
    if (snapshot === undefined) return { ok: false, sessionId: '', error: 'Layout capture not found: ' + input.captureId };
    const evidence = layoutPerformanceEvidence({
      captureId: input.captureId,
      ...(input.selectedNodeId !== undefined ? { selectedNodeId: input.selectedNodeId } : {}),
      highlights: [],
      payload: snapshot,
    });
    return aiService().analyze({
      evidence: [evidence],
      scopeDescription: input.selectedNodeId === undefined ? 'Layout report summary' : 'Layout node ' + input.selectedNodeId,
      ...(input.model !== undefined ? { model: input.model } : {}),
      ...(input.workspaceId !== undefined && input.workspaceId.length > 0
        ? {
            source: {
              workspaceId: input.workspaceId,
              buildIdentityMatch: input.buildIdentityMatch ?? 'UNVERIFIED',
              evidence: sourceEvidenceFor(snapshot, input.selectedNodeId),
            },
          }
        : {}),
    });
  });
  ipcMain.handle(IPC_CHANNELS.networkList, () => networkStore().list());
  ipcMain.handle(IPC_CHANNELS.networkLoad, (_event, id: string) => networkStore().load(id));
  ipcMain.handle(IPC_CHANNELS.batteryList, () => batteryStore().list());
  ipcMain.handle(IPC_CHANNELS.batteryLoad, (_event, id: string) => batteryStore().load(id));
  ipcMain.handle(IPC_CHANNELS.startupList, () => startupStore().list());
  ipcMain.handle(IPC_CHANNELS.startupLoad, (_event, id: string) => startupStore().load(id));
  ipcMain.handle(IPC_CHANNELS.frameList, () => frameStore().list());
  ipcMain.handle(IPC_CHANNELS.frameLoad, (_event, id: string) => frameStore().load(id));
  ipcMain.handle(IPC_CHANNELS.layoutCapture, async (_event, serial: string) => {
    const client = adbClientFor();
    if (client === undefined) return { ok: false, error: 'ADB is not available' };
    const result = await captureLayoutSnapshot(
      {
        adb: {
          shell: (args, options) => client.shell(serial, args, options),
          execOut: async (args, options) => (await client.execOut(serial, args, options)).stdout,
        },
        now: () => Date.now(),
      },
      serial,
    );
    if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
    const record = await layoutStore().add(result.value.snapshot, result.value.screenshotPng);
    return { ok: true, id: record.id };
  });
  ipcMain.handle(IPC_CHANNELS.layoutList, () => layoutStore().list());
  ipcMain.handle(IPC_CHANNELS.layoutLoad, async (_event, id: string) => {
    const store = layoutStore();
    const snapshot = await store.loadSnapshot(id);
    if (snapshot === undefined) return undefined;
    const png = await store.loadScreenshot(id);
    return {
      snapshot,
      // An empty file is a capture taken without pixels, which the canvas
      // reports as unavailable rather than rendering a broken image.
      ...(png !== undefined && png.length > 0 && png.length <= MAX_INLINE_SCREENSHOT_BYTES
        ? { screenshotBase64: png.toString('base64') }
        : {}),
    };
  });
  ipcMain.handle(IPC_CHANNELS.traceReveal, async (_event, id: string) => {
    const record = (await traceStore().list()).find((entry) => entry.id === id);
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
          // Centred in the 26px toolbar the shell reserves for it; the buttons
          // are about 16px tall, so 5px above and below.
          trafficLightPosition: { x: 16, y: 5 },
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
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
