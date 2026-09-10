import { fail, ok, type StudioResult } from '@aps/contracts';
import {
  buildPerfettoCaptureArgs,
  captureTimeoutMs,
  planPerfettoCapture,
  type PerfettoCaptureDocument,
} from '@aps/platform-perfetto';
import type { TraceRecordSummary } from '../shared/ipc.js';

export interface CaptureAdb {
  push(localPath: string, remotePath: string): Promise<void>;
  shell(args: readonly string[], timeoutMs: number): Promise<void>;
  pull(remotePath: string, localPath: string): Promise<void>;
}

export interface CaptureStoreAdd {
  (
    sourcePath: string,
    meta: {
      readonly sha256: string;
      readonly capturedAtEpochMillis: number;
      readonly durationMillis: number;
      readonly deviceSerial?: string;
    },
  ): Promise<TraceRecordSummary>;
}

export interface CaptureDependencies {
  readonly adb: CaptureAdb;
  readonly store: { readonly addTrace: CaptureStoreAdd };
  readonly createTempDirectory: () => Promise<string>;
  readonly writeFile: (path: string, contents: string) => Promise<void>;
  readonly removeDirectory: (path: string) => Promise<void>;
  readonly sha256File: (path: string) => Promise<string>;
  readonly now: () => number;
}

export interface CaptureRequest {
  readonly serial: string;
  readonly document: PerfettoCaptureDocument;
  readonly fileName: string;
  readonly deviceDirectory?: string;
}

/**
 * Captures a Perfetto trace end to end: push config, run perfetto, pull the
 * trace, store it, then remove the device-side temporaries.
 */
export async function capturePerfettoTrace(
  dependencies: CaptureDependencies,
  request: CaptureRequest,
): Promise<StudioResult<TraceRecordSummary>> {
  const plan = planPerfettoCapture({
    document: request.document,
    fileName: request.fileName,
    ...(request.deviceDirectory !== undefined ? { deviceDirectory: request.deviceDirectory } : {}),
  });
  const workDirectory = await dependencies.createTempDirectory();
  const hostConfigPath = workDirectory + '/capture.pbtxt';
  const hostTracePath = workDirectory + '/' + request.fileName;
  try {
    await dependencies.writeFile(hostConfigPath, plan.configText);
    try {
      await dependencies.adb.push(hostConfigPath, plan.deviceConfigPath);
    } catch (error) {
      return fail('IO', 'CAPTURE_PUSH_FAILED', messageOf(error));
    }
    try {
      await dependencies.adb.shell(buildPerfettoCaptureArgs(plan), captureTimeoutMs(request.document.durationMillis));
    } catch (error) {
      return fail('PROCESS_EXIT', 'CAPTURE_FAILED', messageOf(error));
    }
    try {
      await dependencies.adb.pull(plan.deviceOutputPath, hostTracePath);
    } catch (error) {
      return fail('IO', 'CAPTURE_PULL_FAILED', messageOf(error));
    }
    const sha256 = await dependencies.sha256File(hostTracePath);
    try {
      return ok(
        await dependencies.store.addTrace(hostTracePath, {
          sha256,
          capturedAtEpochMillis: dependencies.now(),
          durationMillis: request.document.durationMillis,
          deviceSerial: request.serial,
        }),
      );
    } catch (error) {
      return fail('IO', 'CAPTURE_STORE_FAILED', messageOf(error));
    }
  } finally {
    // Device-side temporaries are best effort; host temporaries always go away.
    await dependencies.adb.shell(['rm', '-f', plan.deviceConfigPath, plan.deviceOutputPath], 10_000).catch(() => undefined);
    await dependencies.removeDirectory(workDirectory).catch(() => undefined);
  }
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : 'trace capture failed';
}
