import { statSync } from 'node:fs';
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { homedir, tmpdir } from 'node:os';
import { delimiter } from 'node:path';

import { join } from 'node:path';
import { app, BrowserWindow, dialog, ipcMain, shell } from 'electron';
import { fail, ok, type StudioResult } from '@aps/contracts';
import { sha256File } from '@aps/contracts/node';
import { walkNode, type LayoutSnapshot } from '@aps/layout-inspector';
import { AdbClient } from '@aps/platform-adb';
import { findPerfettoUiAssetsDirectory, type PerfettoUiAssetProbe } from '@aps/platform-perfetto';
import { JsonSettingsStore } from '@aps/settings';
import { buildAppInfo } from '../shared/app-info.js';
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
  type BenchmarkCompareInput,
  type ShellSnapshot,
  type StartupCaptureInput,
  type TraceAnalyzerSnapshot,
  type TraceCaptureInput,
  type TraceCaptureOutcome,
  type TraceOpenOutcome,
} from '../shared/ipc.js';
import type { ApplicationUiSettings } from '../shared/settings-contract.js';
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
  createCpuProfileSession,
  directionOf,
  reportSampleArguments,
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
import { MethodSessionStore, type StoredMethodSession } from './method-session-store.js';
import {
  defaultConversionDependencies,
  defaultHostSimpleperfLocatorDependencies,
  locateHostSimpleperf,
  runReportSample,
} from '@aps/simpleperf-profiler/node';
import { captureCpuProfile } from './cpu-capture-service.js';
import { CpuProfileStore } from './cpu-profile-store.js';
import { parseCpuProfileReport } from './cpu-profile-parser.js';
import { captureHeapDump } from './memory-capture-service.js';
import { MemorySessionStore } from './memory-session-store.js';
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
  const parsed = parseCpuProfileReport(report);
  if (!parsed.ok) return parsed;
  store.cacheTable(record.id, parsed.value.table);
  return ok(parsed.value.table);
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

async function updateSettings(patch: Partial<ApplicationUiSettings>): Promise<ShellSnapshot> {
  const current = await ensureSettings();
  const merged: ApplicationUiSettings = {
    theme: patch.theme ?? current.theme,
    language: patch.language ?? current.language,
    ...(patch.androidSdkPath !== undefined
      ? { androidSdkPath: patch.androidSdkPath }
      : current.androidSdkPath !== undefined
        ? { androidSdkPath: current.androidSdkPath }
        : {}),
  };
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
  ipcMain.handle(IPC_CHANNELS.updateSettings, (_event, patch: Partial<ApplicationUiSettings>) =>
    updateSettings(patch),
  );
  ipcMain.handle(IPC_CHANNELS.refreshDevices, () => buildSnapshot());
  ipcMain.handle(IPC_CHANNELS.openDestination, (_event, destination: string) => {
    if (window === undefined) return;
    if (destination === 'HOME') window.unmaximize();
    else window.maximize();
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
            const configured = process.env['APS_SIMPLEPERF'];
            const located = await locateHostSimpleperf(
              defaultHostSimpleperfLocatorDependencies({
                ...(configured !== undefined && configured.length > 0 ? { configuredExecutable: configured } : {}),
                pathDirectories: (process.env['PATH'] ?? '')
                  .split(delimiter)
                  .filter((entry) => entry.length > 0),
              }),
            );
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
        { serial: input.serial, packageName: input.packageName },
      );
      if (!result.ok) return { ok: false, error: result.error.code + ': ' + result.error.message };
      const summary = await memoryStore().add(result.value);
      return { ok: true, id: summary.id };
    } finally {
      await rm(temporaryDirectory, { recursive: true, force: true }).catch(() => undefined);
    }
  });
  ipcMain.handle(IPC_CHANNELS.memoryList, () => memoryStore().list());
  ipcMain.handle(IPC_CHANNELS.memoryLoad, (_event, id: string) => memoryStore().load(id));
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
      ...(png !== undefined && png.length <= MAX_INLINE_SCREENSHOT_BYTES
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
  window = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 1100,
    minHeight: 720,
    show: false,
    backgroundColor: '#111318',
    title: 'Android Performance Studio',
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
    },
  });

  window.once('ready-to-show', () => {
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

app.whenReady().then(() => {
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
