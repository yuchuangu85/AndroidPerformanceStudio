import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { createWriteStream } from 'node:fs';

export const DEFAULT_HOST_TIMEOUT_MS = 30_000;
export const DEFAULT_MAX_OUTPUT_BYTES = 16 * 1024 * 1024;

const TERMINATION_GRACE_MS = 250;

export interface HostProcessRequest {
  readonly executable: string;
  readonly args?: readonly string[];
  readonly cwd?: string;
  /** Environment variables added on top of the current process environment. */
  readonly env?: Readonly<Record<string, string>>;
  readonly timeoutMs?: number;
  readonly maxOutputBytesPerStream?: number;
  readonly encoding?: BufferEncoding;
  readonly signal?: AbortSignal;
}

export interface HostProcessTextResult {
  readonly pid: number;
  readonly exitCode: number;
  readonly stdout: string;
  readonly stderr: string;
  readonly durationMs: number;
  readonly stdoutTruncated: boolean;
  readonly stderrTruncated: boolean;
}

export interface HostProcessBinaryResult {
  readonly pid: number;
  readonly exitCode: number;
  readonly stdout: Buffer;
  readonly stderr: Buffer;
  readonly durationMs: number;
  readonly stdoutTruncated: boolean;
  readonly stderrTruncated: boolean;
}

export class HostProcessStartError extends Error {
  readonly command: readonly string[];
  constructor(command: readonly string[], cause: unknown) {
    super('Failed to start host process: ' + command.join(' '), { cause });
    this.name = 'HostProcessStartError';
    this.command = command;
  }
}

export class HostProcessTimeoutError extends Error {
  readonly command: readonly string[];
  readonly timeoutMs: number;
  readonly pid: number;
  constructor(command: readonly string[], timeoutMs: number, pid: number) {
    super('Host process timed out after ' + timeoutMs + 'ms: ' + command.join(' '));
    this.name = 'HostProcessTimeoutError';
    this.command = command;
    this.timeoutMs = timeoutMs;
    this.pid = pid;
  }
}

export class HostProcessCancelledError extends Error {
  readonly command: readonly string[];
  readonly pid: number;
  constructor(command: readonly string[], pid: number) {
    super('Host process was cancelled: ' + command.join(' '));
    this.name = 'HostProcessCancelledError';
    this.command = command;
    this.pid = pid;
  }
}

function buildCommand(request: HostProcessRequest): string[] {
  return [request.executable, ...(request.args ?? [])];
}

function spawnRequest(request: HostProcessRequest, detached: boolean): ChildProcess {
  const environment = request.env === undefined ? process.env : { ...process.env, ...request.env };
  return spawn(request.executable, [...(request.args ?? [])], {
    cwd: request.cwd,
    env: environment,
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
    detached,
  });
}

interface Captured {
  chunks: Buffer[];
  length: number;
  truncated: boolean;
}

function captureChunk(target: Captured, chunk: Buffer, maximumBytes: number): void {
  const remaining = maximumBytes - target.length;
  if (remaining > 0) {
    const slice = chunk.length <= remaining ? chunk : chunk.subarray(0, remaining);
    target.chunks.push(slice);
    target.length += slice.length;
  }
  if (chunk.length > remaining) {
    target.truncated = true;
  }
}

async function terminateProcessTree(child: ChildProcess): Promise<void> {
  const pid = child.pid;
  if (pid === undefined || child.exitCode !== null || child.signalCode !== null) {
    return;
  }
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/pid', String(pid), '/T', '/F'], { stdio: 'ignore' });
    return;
  }
  const signalGroup = (signal: NodeJS.Signals): void => {
    try {
      // detached spawns a new process group whose id equals the child pid.
      process.kill(-pid, signal);
    } catch {
      try {
        child.kill(signal);
      } catch {
        // The process already exited.
      }
    }
  };
  signalGroup('SIGTERM');
  await new Promise((resolve) => setTimeout(resolve, TERMINATION_GRACE_MS));
  if (child.exitCode === null && child.signalCode === null) {
    signalGroup('SIGKILL');
    await new Promise((resolve) => setTimeout(resolve, TERMINATION_GRACE_MS));
  }
}

function waitForExit(child: ChildProcess): Promise<{ code: number | null; signal: NodeJS.Signals | null }> {
  return new Promise((resolve, reject) => {
    let settled = false;
    child.once('error', (error) => {
      if (settled) return;
      settled = true;
      reject(error);
    });
    child.once('close', (code, signal) => {
      if (settled) return;
      settled = true;
      resolve({ code, signal });
    });
  });
}

export async function runHostProcessBinary(request: HostProcessRequest): Promise<HostProcessBinaryResult> {
  const command = buildCommand(request);
  const timeoutMs = request.timeoutMs ?? DEFAULT_HOST_TIMEOUT_MS;
  const maximumBytes = request.maxOutputBytesPerStream ?? DEFAULT_MAX_OUTPUT_BYTES;
  const startedAt = Date.now();

  const child = spawnRequest(request, process.platform !== 'win32');
  const stdout: Captured = { chunks: [], length: 0, truncated: false };
  const stderr: Captured = { chunks: [], length: 0, truncated: false };
  child.stdout?.on('data', (chunk: Buffer) => captureChunk(stdout, chunk, maximumBytes));
  child.stderr?.on('data', (chunk: Buffer) => captureChunk(stderr, chunk, maximumBytes));

  let timedOut = false;
  let cancelled = false;
  const timer = setTimeout(() => {
    timedOut = true;
    void terminateProcessTree(child);
  }, timeoutMs);
  const onAbort = (): void => {
    cancelled = true;
    void terminateProcessTree(child);
  };
  request.signal?.addEventListener('abort', onAbort, { once: true });

  try {
    await waitForExit(child);
    if (timedOut) throw new HostProcessTimeoutError(command, timeoutMs, child.pid ?? -1);
    if (cancelled) throw new HostProcessCancelledError(command, child.pid ?? -1);
    return {
      pid: child.pid ?? -1,
      exitCode: child.exitCode ?? -1,
      stdout: Buffer.concat(stdout.chunks),
      stderr: Buffer.concat(stderr.chunks),
      durationMs: Date.now() - startedAt,
      stdoutTruncated: stdout.truncated,
      stderrTruncated: stderr.truncated,
    };
  } catch (error) {
    if (error instanceof HostProcessTimeoutError || error instanceof HostProcessCancelledError) {
      throw error;
    }
    throw new HostProcessStartError(command, error);
  } finally {
    clearTimeout(timer);
    request.signal?.removeEventListener('abort', onAbort);
    await terminateProcessTree(child);
  }
}

export async function runHostProcessText(request: HostProcessRequest): Promise<HostProcessTextResult> {
  const result = await runHostProcessBinary(request);
  const encoding = request.encoding ?? 'utf8';
  return {
    pid: result.pid,
    exitCode: result.exitCode,
    stdout: result.stdout.toString(encoding),
    stderr: result.stderr.toString(encoding),
    durationMs: result.durationMs,
    stdoutTruncated: result.stdoutTruncated,
    stderrTruncated: result.stderrTruncated,
  };
}

export interface HostProcessLaunchRequest {
  readonly executable: string;
  readonly args?: readonly string[];
  readonly cwd?: string;
  readonly env?: Readonly<Record<string, string>>;
  readonly outputFile?: string;
}

export interface RunningHostProcess {
  readonly pid: number;
  readonly isAlive: boolean;
  terminate(): Promise<void>;
}

/** Launches a detached process without waiting for it (used for long captures). */
export function launchHostProcess(request: HostProcessLaunchRequest): RunningHostProcess {
  const environment = request.env === undefined ? process.env : { ...process.env, ...request.env };
  const child = spawn(request.executable, [...(request.args ?? [])], {
    cwd: request.cwd,
    env: environment,
    shell: false,
    detached: process.platform !== 'win32',
    stdio: ['ignore', request.outputFile === undefined ? 'ignore' : createWriteStream(request.outputFile), 'ignore'],
  });
  return {
    get pid() {
      return child.pid ?? -1;
    },
    get isAlive() {
      return child.exitCode === null && child.signalCode === null;
    },
    terminate: () => terminateProcessTree(child),
  };
}
