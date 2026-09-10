import { contextBridge, ipcRenderer } from 'electron';
import type { ApsApi, AppInfo } from '../shared/app-info';

const api: ApsApi = {
  getAppInfo: (): Promise<AppInfo> => ipcRenderer.invoke('app:getInfo') as Promise<AppInfo>,
};

contextBridge.exposeInMainWorld('aps', api);
