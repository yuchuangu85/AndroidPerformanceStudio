import { describe, expect, it } from 'vitest';
import type { CaptureArtifact } from '@aps/contracts';
import type { HostProcessRequest, HostProcessTextResult, RunningHostProcess } from '@aps/platform-host';
import { TraceAnalysisContext, TraceAnalysisContexts } from './analysis-context.js';

const TOOL = { path: '/app/trace_processor_shell', version: 'v57.2', sha256: 'a'.repeat(64) };

function textResult(stdout: string, exitCode = 0): HostProcessTextResult {
  return { pid: 1, exitCode, stdout, stderr: '', durationMs: 1, stdoutTruncated: false, stderrTruncated: false };
}

function fakeProcess(): RunningHostProcess {
  return { pid: 99, isAlive: true, terminate: async () => undefined };
}

function artifact(overrides: Partial<CaptureArtifact> = {}): CaptureArtifact {
  return {
    contractVersion: 1,
    id: 'capture-1',
    kind: 'perfetto-trace',
    location: '/tmp/trace',
    sha256: 'b'.repeat(64),
    format: null,
    provenance: {
      producer: { producerType: 'unknown' },
      acquisition: { kind: 'CAPTURE', application: 'APS', performedAtEpochMillis: 0n },
      processors: [],
    },
    capturedAt: null,
    device: null,
    process: null,
    clockDomains: [],
    clockMappings: [],
    requestedCapabilities: null,
    availableCapabilities: [],
    completeness: 'UNKNOWN',
    limitations: [],
    warnings: [],
    privacy: { containsSensitiveIdentity: false, redactions: ['DEVICE_SERIAL'] },
    ...overrides,
  };
}

describe('TraceAnalysisContext', () => {
  it('starts a warm server and queries it over --remote', async () => {
    const requests: HostProcessRequest[] = [];
    const context = await TraceAnalysisContext.start({
      tool: TOOL,
      traceFile: '/tmp/trace',
      port: 4321,
      launch: () => fakeProcess(),
      isReady: async () => true,
      executeText: async (request) => {
        requests.push(request);
        return textResult('name,dur\nslice,10\n');
      },
    });
    expect(context.ok).toBe(true);
    if (!context.ok) return;
    const result = await context.value.queryRaw('select name, dur from slice');
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.columns).toEqual(['name', 'dur']);
      expect(result.value.rows[0]?.long('dur')).toBe(10);
    }
    expect(requests[0]?.args).toEqual(['query', '--remote', '127.0.0.1:4321', 'select name, dur from slice']);
    await context.value.close();
    expect((await context.value.queryRaw('select 1')).ok).toBe(false);
  });

  it('fails when the processor never becomes ready', async () => {
    const result = await TraceAnalysisContext.start({
      tool: TOOL,
      traceFile: '/tmp/trace',
      port: 1,
      launch: () => fakeProcess(),
      isReady: async () => false,
      executeText: async () => textResult(''),
      startupTimeoutMs: 60,
    });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('TRACE_PROCESSOR_NOT_READY');
  });

  it('reports query failures and blank SQL', async () => {
    const context = await TraceAnalysisContext.start({
      tool: TOOL,
      traceFile: '/tmp/trace',
      port: 2,
      launch: () => fakeProcess(),
      isReady: async () => true,
      executeText: async () => textResult('', 1),
    });
    expect(context.ok).toBe(true);
    if (!context.ok) return;
    expect((await context.value.queryRaw('   ')).ok).toBe(false);
    const failed = await context.value.queryRaw('select 1');
    expect(failed.ok).toBe(false);
    if (!failed.ok) expect(failed.error.code).toBe('TRACE_QUERY_FAILED');
    await context.value.close();
  });
});

describe('TraceAnalysisContexts', () => {
  it('rejects a trace whose bytes no longer match the artifact', async () => {
    const contexts = new TraceAnalysisContexts({
      tool: TOOL,
      launch: () => fakeProcess(),
      executeText: async () => textResult(''),
      isReady: async () => true,
      isRegularFile: () => true,
      sha256File: async () => 'c'.repeat(64),
    });
    const result = await contexts.open(artifact(), '/tmp/trace');
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('TRACE_ARTIFACT_HASH_MISMATCH');
  });

  it('rejects a missing trace file', async () => {
    const contexts = new TraceAnalysisContexts({
      tool: TOOL,
      launch: () => fakeProcess(),
      executeText: async () => textResult(''),
      isReady: async () => true,
      isRegularFile: () => false,
      sha256File: async () => 'b'.repeat(64),
    });
    const result = await contexts.open(artifact(), '/missing');
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('TRACE_FILE_NOT_FOUND');
  });

  it('reuses the warm context for the same artifact id', async () => {
    let launches = 0;
    const contexts = new TraceAnalysisContexts({
      tool: TOOL,
      launch: () => {
        launches += 1;
        return fakeProcess();
      },
      executeText: async () => textResult(''),
      isReady: async () => true,
      isRegularFile: () => true,
      sha256File: async () => 'b'.repeat(64),
      allocatePort: async () => 5000,
    });
    const first = await contexts.open(artifact(), '/tmp/trace');
    const second = await contexts.open(artifact(), '/tmp/trace');
    expect(first.ok && second.ok).toBe(true);
    expect(launches).toBe(1);
    await contexts.closeAll();
  });
});
