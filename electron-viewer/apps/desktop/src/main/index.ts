import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { app, BrowserWindow, ipcMain, shell } from 'electron';
import { JsonSettingsStore } from '@aps/settings';
import { buildAppInfo } from '../shared/app-info.js';
import { IPC_CHANNELS, type MigrationStatus, type ShellSnapshot } from '../shared/ipc.js';
import type { ApplicationUiSettings } from '../shared/settings-contract.js';
import { openAdb } from './device-service.js';
import { createLegacyPreferenceSource, defaultLegacySourceDependencies } from './legacy-prefs-source.js';
import { loadApplicationSettings } from './settings-service.js';
import { resolveTraceProcessorStatus } from './trace-service.js';

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

