import { statSync } from 'node:fs';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { homedir, tmpdir } from 'node:os';
import { join } from 'node:path';
import { app, BrowserWindow, ipcMain, shell } from 'electron';
import { sha256File } from '@aps/contracts/node';
import { AdbClient } from '@aps/platform-adb';
import { findPerfettoUiAssetsDirectory, type PerfettoUiAssetProbe } from '@aps/platform-perfetto';
import { JsonSettingsStore } from '@aps/settings';
import { buildAppInfo } from '../shared/app-info.js';
import {
  IPC_CHANNELS,
  type MigrationStatus,
  type ShellSnapshot,
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
