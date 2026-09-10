import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fail, ok, type CaptureArtifact, type ErrorCategory, type StudioResult } from '@aps/contracts';
import type {
  HostProcessLaunchRequest,
  HostProcessRequest,
  HostProcessTextResult,
  RunningHostProcess,
} from '@aps/platform-host';
import { parseTraceQueryResult, type TraceQuery, type TraceQueryResult } from './query-result.js';
import type { TraceProcessorTool } from './tool-resolver.js';

const DEFAULT_STARTUP_TIMEOUT_MS = 10_000;
const DEFAULT_QUERY_TIMEOUT_MS = 30_000;
const READINESS_POLL_MS = 50;

export interface TraceAnalysisContextFactoryOptions {
  readonly tool: TraceProcessorTool;
  readonly traceFile: string;
  readonly port: number;
  readonly launch: (request: HostProcessLaunchRequest) => RunningHostProcess;
  readonly executeText: (request: HostProcessRequest) => Promise<HostProcessTextResult>;
  readonly isReady: (port: number) => Promise<boolean>;
  readonly workDirectory?: string;
  readonly startupTimeoutMs?: number;
  readonly onClose?: (context: TraceAnalysisContext) => void;
}

function contextFailure<T>(code: string, message: string): StudioResult<T> {
  return fail('PROCESS_EXIT' as ErrorCategory, code, message);
}

async function acquireLoopbackPort(): Promise<number> {
  return await new Promise<number>((resolve, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      const port = typeof address === 'object' && address !== null ? address.port : 0;
      server.close(() => resolve(port));
    });
  });
}

export class TraceAnalysisContext {
  readonly tool: TraceProcessorTool;
  readonly traceFile: string;
  readonly port: number;
  private readonly launch: TraceAnalysisContextFactoryOptions['launch'];
  private readonly executeText: TraceAnalysisContextFactoryOptions['executeText'];
  private readonly isReady: TraceAnalysisContextFactoryOptions['isReady'];
  private readonly startupTimeoutMs: number;
  private readonly onClose: (context: TraceAnalysisContext) => void;
  private workDirectory = '';
  private server: RunningHostProcess | undefined;
  private closed = false;

  private constructor(options: TraceAnalysisContextFactoryOptions) {
    this.tool = options.tool;
    this.traceFile = options.traceFile;
    this.port = options.port;
    this.launch = options.launch;
    this.executeText = options.executeText;
    this.isReady = options.isReady;
    this.startupTimeoutMs = options.startupTimeoutMs ?? DEFAULT_STARTUP_TIMEOUT_MS;
    this.onClose = options.onClose ?? (() => undefined);
  }

  static async start(options: TraceAnalysisContextFactoryOptions): Promise<StudioResult<TraceAnalysisContext>> {
    const context = new TraceAnalysisContext(options);
    context.workDirectory = options.workDirectory ?? (await mkdtemp(join(tmpdir(), 'aps-trace-context-')));
    try {
      context.server = context.launch({
        executable: context.tool.path,
        args: [
          'server',
          'http',
          '--ip-address',
          '127.0.0.1',
          '--port',
          String(context.port),
          context.traceFile,
        ],
        outputFile: join(context.workDirectory, 'trace-processor.log'),
      });
      const deadline = Date.now() + context.startupTimeoutMs;
      while (Date.now() < deadline && context.server.isAlive) {
        if (await context.isReady(context.port)) return ok(context);
        await new Promise((resolve) => setTimeout(resolve, READINESS_POLL_MS));
      }
      await context.close();
      return contextFailure('TRACE_PROCESSOR_NOT_READY', 'Trace processor did not become ready');
    } catch (error) {
      await context.close();
      return contextFailure(
        'TRACE_PROCESSOR_START_FAILED',
        error instanceof Error ? error.message : 'Trace processor could not start',
      );
    }
  }

  async query<T>(query: TraceQuery<T>): Promise<StudioResult<T[]>> {
    if (query.schema.traceProcessorVersion !== this.tool.version) {
      return contextFailure('TRACE_SCHEMA_INCOMPATIBLE', 'Query schema does not match the pinned trace processor');
    }
    const result = await this.queryRaw(query.sql);
    if (!result.ok) return result;
    try {
      return ok(query.map(result.value));
    } catch (error) {
      return contextFailure(
        'TRACE_QUERY_RESULT_INVALID',
        error instanceof Error ? error.message : 'Trace query returned invalid data',
      );
    }
  }

  async queryRaw(sql: string): Promise<StudioResult<TraceQueryResult>> {
    if (this.closed || this.server?.isAlive !== true) {
      return contextFailure('TRACE_CONTEXT_CLOSED', 'Trace analysis context is closed');
    }
    if (sql.trim().length === 0) {
      return contextFailure('TRACE_QUERY_INVALID', 'Trace SQL must not be blank');
    }
    try {
      const result = await this.executeText({
        executable: this.tool.path,
        args: ['query', '--remote', '127.0.0.1:' + this.port, sql],
        timeoutMs: DEFAULT_QUERY_TIMEOUT_MS,
      });
      if (result.exitCode !== 0) {
        return contextFailure('TRACE_QUERY_FAILED', 'Trace query failed with exit code ' + result.exitCode);
      }
      return ok(parseTraceQueryResult(result.stdout));
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Trace query failed';
      return contextFailure('TRACE_QUERY_FAILED', message);
    }
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    await this.server?.terminate();
    this.server = undefined;
    if (this.workDirectory.length > 0) {
      await rm(this.workDirectory, { recursive: true, force: true });
    }
    this.onClose(this);
  }
}

export interface TraceAnalysisContextsOptions {
  readonly tool: TraceProcessorTool;
  readonly launch: TraceAnalysisContextFactoryOptions['launch'];
  readonly executeText: TraceAnalysisContextFactoryOptions['executeText'];
  readonly isReady: TraceAnalysisContextFactoryOptions['isReady'];
  readonly isRegularFile: (path: string) => boolean;
  readonly sha256File: (path: string) => Promise<string>;
  readonly allocatePort?: () => Promise<number>;
  readonly startupTimeoutMs?: number;
  readonly createWorkDirectory?: () => Promise<string>;
}

/** Artifact-scoped warm Trace Processor contexts, keyed by Capture Artifact id. */
export class TraceAnalysisContexts {
  private readonly options: TraceAnalysisContextsOptions;
  private readonly contexts = new Map<string, TraceAnalysisContext>();
  private opening: Promise<StudioResult<TraceAnalysisContext>> | undefined;

  constructor(options: TraceAnalysisContextsOptions) {
    this.options = options;
  }

  async open(artifact: CaptureArtifact, traceFile: string): Promise<StudioResult<TraceAnalysisContext>> {
    const existing = this.contexts.get(artifact.id);
    if (existing !== undefined) return ok(existing);
    // Serialize concurrent opens to avoid parsing the same trace twice.
    while (this.opening !== undefined) {
      await this.opening;
      const afterWait = this.contexts.get(artifact.id);
      if (afterWait !== undefined) return ok(afterWait);
    }
    this.opening = this.openInternal(artifact, traceFile);
    try {
      return await this.opening;
    } finally {
      this.opening = undefined;
    }
  }

  private async openInternal(
    artifact: CaptureArtifact,
    traceFile: string,
  ): Promise<StudioResult<TraceAnalysisContext>> {
    if (!this.options.isRegularFile(traceFile)) {
      return fail('IO', 'TRACE_FILE_NOT_FOUND', 'Trace file does not exist');
    }
    const actual = await this.options.sha256File(traceFile);
    if (actual !== artifact.sha256) {
      return fail(
        'DATA_VALIDATION',
        'TRACE_ARTIFACT_HASH_MISMATCH',
        'Trace bytes no longer match the registered Capture Artifact',
      );
    }
    const port = await (this.options.allocatePort ?? acquireLoopbackPort)();
    const workDirectory =
      this.options.createWorkDirectory === undefined ? undefined : await this.options.createWorkDirectory();
    const started = await TraceAnalysisContext.start({
      tool: this.options.tool,
      traceFile,
      port,
      launch: this.options.launch,
      executeText: this.options.executeText,
      isReady: this.options.isReady,
      startupTimeoutMs: this.options.startupTimeoutMs ?? DEFAULT_STARTUP_TIMEOUT_MS,
      ...(workDirectory !== undefined ? { workDirectory } : {}),
      onClose: () => this.contexts.delete(artifact.id),
    });
    if (started.ok) this.contexts.set(artifact.id, started.value);
    return started;
  }

  async closeAll(): Promise<void> {
    const contexts = [...this.contexts.values()];
    this.contexts.clear();
    await Promise.all(contexts.map((context) => context.close()));
  }
}
