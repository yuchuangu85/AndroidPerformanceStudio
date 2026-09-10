import { contextBridge, ipcRenderer } from 'electron';
import {
  IPC_CHANNELS,
  type ApsApi,
  type ApplicationUiSettingsPatch,
  type FrameCaptureInput,
  type TraceCaptureInput,
} from '../shared/ipc.js';

const api: ApsApi = {
  getShellSnapshot: () => ipcRenderer.invoke(IPC_CHANNELS.shellSnapshot),
  updateSettings: (patch: ApplicationUiSettingsPatch) => ipcRenderer.invoke(IPC_CHANNELS.updateSettings, patch),
  refreshDevices: () => ipcRenderer.invoke(IPC_CHANNELS.refreshDevices),
  openDestination: (destination) => ipcRenderer.invoke(IPC_CHANNELS.openDestination, destination),
  getTraceAnalyzer: () => ipcRenderer.invoke(IPC_CHANNELS.traceAnalyzer),
  captureTrace: (input: TraceCaptureInput) => ipcRenderer.invoke(IPC_CHANNELS.traceCapture, input),
  openTraceInAnalyzer: (id: string) => ipcRenderer.invoke(IPC_CHANNELS.traceOpen, id),
  openPublicPerfettoUi: () => ipcRenderer.invoke(IPC_CHANNELS.traceOpenPublicUi),
  revealTrace: (id: string) => ipcRenderer.invoke(IPC_CHANNELS.traceReveal, id),
  captureLayout: (serial: string) => ipcRenderer.invoke(IPC_CHANNELS.layoutCapture, serial),
  listLayoutCaptures: () => ipcRenderer.invoke(IPC_CHANNELS.layoutList),
  loadLayoutCapture: (id: string) => ipcRenderer.invoke(IPC_CHANNELS.layoutLoad, id),
  captureFrame: (input: FrameCaptureInput) => ipcRenderer.invoke(IPC_CHANNELS.frameCapture, input),
  listFrameSessions: () => ipcRenderer.invoke(IPC_CHANNELS.frameList),
  loadFrameSession: (id: string) => ipcRenderer.invoke(IPC_CHANNELS.frameLoad, id),
};

contextBridge.exposeInMainWorld('aps', api);
