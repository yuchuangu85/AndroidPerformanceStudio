import { statSync } from 'node:fs';
import {
  HostProcessCancelledError,
  HostProcessStartError,
  HostProcessTimeoutError,
  runHostProcessBinary,
  type HostProcessBinaryResult,
  type HostProcessRequest,
} from '@aps/platform-host';
import { DEFAULT_ADB_MAX_OUTPUT_BYTES, DEFAULT_ADB_TIMEOUT_MS, buildAdbDeviceArgs, quoteRemoteShellArgument } from './adb-command.js';
import { parseAdbDevices, type AdbDevice } from './adb-device.js';
import {
  AdbCommandCancelledError,
  AdbCommandFailedError,
  AdbCommandTimeoutError,
  AdbInputError,
  AdbProcessStartError,
} from './adb-errors.js';
import { requireForwardEndpoint, requireRemotePath } from './adb-input-validator.js';

export type AdbBinaryExecutor = (request: HostProcessRequest) => Promise<HostProcessBinaryResult>;

export interface AdbTextResult {
  readonly pid: number;
  readonly exitCode: number;
  readonly stdout: string;
  readonly stderr: string;
  readonly durationMs: number;
  readonly stdoutTruncated: boolean;
  readonly stderrTruncated: boolean;
}

export interface AdbOptions {
  readonly timeoutMs?: number;
  readonly maxOutputBytesPerStream?: number;
  readonly signal?: AbortSignal;
}

export interface AdbClientOptions {
  readonly executable: string;
  readonly execute?: AdbBinaryExecutor;
  readonly timeoutMs?: number;
  readonly maxOutputBytesPerStream?: number;
  readonly isRegularFile?: (path: string) => boolean;
}

function defaultIsRegularFile(path: string): boolean {
  try {
    return statSync(path).isFile();
  } catch {
    return false;
  }
}

export class AdbClient {
  private readonly executable: string;
  private readonly execute: AdbBinaryExecutor;
  private readonly timeoutMs: number;
  private readonly maxOutputBytesPerStream: number;
  private readonly isRegularFile: (path: string) => boolean;

  constructor(options: AdbClientOptions) {
    this.executable = options.executable;
    this.execute = options.execute ?? runHostProcessBinary;
    this.timeoutMs = options.timeoutMs ?? DEFAULT_ADB_TIMEOUT_MS;
    this.maxOutputBytesPerStream = options.maxOutputBytesPerStream ?? DEFAULT_ADB_MAX_OUTPUT_BYTES;
    this.isRegularFile = options.isRegularFile ?? defaultIsRegularFile;
  }

  async listDevices(options: AdbOptions = {}): Promise<AdbDevice[]> {
    const result = await this.runText(['devices', '-l'], options);
    return parseAdbDevices(result.stdout);
  }

  async shell(serial: string, args: readonly string[], options: AdbOptions = {}): Promise<AdbTextResult> {
    return this.runText(
      buildAdbDeviceArgs(serial, 'shell', args.map((argument) => quoteRemoteShellArgument(argument))),
      options,
    );
  }

  async execOut(serial: string, args: readonly string[], options: AdbOptions = {}): Promise<HostProcessBinaryResult> {
    return this.runBinary(buildAdbDeviceArgs(serial, 'exec-out', args), options);
  }

  async push(serial: string, localPath: string, remotePath: string, options: AdbOptions = {}): Promise<AdbTextResult> {
    if (!this.isRegularFile(localPath)) {
      throw new AdbInputError('Local push source is not a file: ' + localPath);
    }
    return this.runText(buildAdbDeviceArgs(serial, 'push', [localPath, requireRemotePath(remotePath)]), options);
  }

  async pull(serial: string, remotePath: string, localPath: string, options: AdbOptions = {}): Promise<AdbTextResult> {
    return this.runText(buildAdbDeviceArgs(serial, 'pull', [requireRemotePath(remotePath), localPath]), options);
  }

  async forward(serial: string, local: string, remote: string, options: AdbOptions = {}): Promise<AdbTextResult> {
    return this.runText(
      buildAdbDeviceArgs(serial, 'forward', [requireForwardEndpoint(local), requireForwardEndpoint(remote)]),
      options,
    );
  }

  async removeForward(serial: string, local: string, options: AdbOptions = {}): Promise<AdbTextResult> {
    return this.runText(buildAdbDeviceArgs(serial, 'forward', ['--remove', requireForwardEndpoint(local)]), options);
  }

  async bugreport(serial: string, outputPath: string, options: AdbOptions = {}): Promise<AdbTextResult> {
    return this.runText(buildAdbDeviceArgs(serial, 'bugreport', [outputPath]), options);
  }

  private request(args: readonly string[], options: AdbOptions): HostProcessRequest {
    return {
      executable: this.executable,
      args,
      timeoutMs: options.timeoutMs ?? this.timeoutMs,
      maxOutputBytesPerStream: options.maxOutputBytesPerStream ?? this.maxOutputBytesPerStream,
      ...(options.signal !== undefined ? { signal: options.signal } : {}),
    };
  }

  private async runBinary(args: readonly string[], options: AdbOptions): Promise<HostProcessBinaryResult> {
    const request = this.request(args, options);
    const command = [request.executable, ...args];
    let result: HostProcessBinaryResult;
    try {
      result = await this.execute(request);
    } catch (error) {
      if (error instanceof HostProcessStartError) throw new AdbProcessStartError(command, error);
      if (error instanceof HostProcessTimeoutError) throw new AdbCommandTimeoutError(command, error.timeoutMs, error.pid);
      if (error instanceof HostProcessCancelledError) throw new AdbCommandCancelledError(command, error.pid);
      throw error;
    }
    if (result.exitCode !== 0) {
      throw new AdbCommandFailedError(command, result.exitCode, result.stderr.toString('utf8'));
    }
    return result;
  }

  private async runText(args: readonly string[], options: AdbOptions): Promise<AdbTextResult> {
    const result = await this.runBinary(args, options);
    return {
      pid: result.pid,
      exitCode: result.exitCode,
      stdout: result.stdout.toString('utf8'),
      stderr: result.stderr.toString('utf8'),
      durationMs: result.durationMs,
      stdoutTruncated: result.stdoutTruncated,
      stderrTruncated: result.stderrTruncated,
    };
  }
}
