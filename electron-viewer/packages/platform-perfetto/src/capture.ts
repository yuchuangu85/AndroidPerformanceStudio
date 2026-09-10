import { composePerfettoConfig, type PerfettoCaptureDocument } from './perfetto-config.js';

export const DEFAULT_DEVICE_TRACE_DIRECTORY = '/data/misc/perfetto-traces';

export interface PerfettoCapturePlan {
  readonly configText: string;
  readonly deviceConfigPath: string;
  readonly deviceOutputPath: string;
}

const SAFE_FILE_NAME = /^[A-Za-z0-9._-]+$/;

/**
 * Plans a Perfetto capture: the pbtxt config plus the device paths it is pushed
 * to and written from.
 */
export function planPerfettoCapture(options: {
  readonly document: PerfettoCaptureDocument;
  readonly fileName: string;
  readonly deviceDirectory?: string;
  readonly deviceConfigDirectory?: string;
}): PerfettoCapturePlan {
  if (!SAFE_FILE_NAME.test(options.fileName)) {
    throw new Error('capture file name must be a safe identifier');
  }
  const directory = options.deviceDirectory ?? DEFAULT_DEVICE_TRACE_DIRECTORY;
  const configDirectory = options.deviceConfigDirectory ?? '/data/local/tmp';
  return {
    configText: composePerfettoConfig(options.document),
    deviceConfigPath: configDirectory.replace(/\/+$/, '') + '/aps-' + options.fileName + '.pbtxt',
    deviceOutputPath: directory.replace(/\/+$/, '') + '/' + options.fileName,
  };
}

/** Perfetto arguments for a text-protobuf config capture. */
export function buildPerfettoCaptureArgs(plan: PerfettoCapturePlan): string[] {
  return ['perfetto', '-c', plan.deviceConfigPath, '--txt', '-o', plan.deviceOutputPath];
}

/** Capture timeout leaves a margin beyond the configured duration. */
export function captureTimeoutMs(durationMillis: number, marginMillis = 15_000): number {
  return durationMillis + marginMillis;
}
