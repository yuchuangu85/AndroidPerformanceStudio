import { contextBridge, ipcRenderer } from 'electron';
import { IPC_CHANNELS, type ApsApi, type ApplicationUiSettingsPatch } from '../shared/ipc.js';

const api: ApsApi = {
  getShellSnapshot: () => ipcRenderer.invoke(IPC_CHANNELS.shellSnapshot),
  updateSettings: (patch: ApplicationUiSettingsPatch) => ipcRenderer.invoke(IPC_CHANNELS.updateSettings, patch),
  refreshDevices: () => ipcRenderer.invoke(IPC_CHANNELS.refreshDevices),
  openDestination: (destination) => ipcRenderer.invoke(IPC_CHANNELS.openDestination, destination),
};

contextBridge.exposeInMainWorld('aps', api);
