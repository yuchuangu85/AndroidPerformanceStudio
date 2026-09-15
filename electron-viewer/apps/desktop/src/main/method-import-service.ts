import { fail, ok, type StudioResult } from '@aps/contracts';
import { MAX_METHOD_TRACE_BYTES, threadKeyOf, type ArtTraceAnalysis } from '@aps/art-trace';
import type { CallStackTable } from '@aps/profile-analysis';
import type { MethodSessionRecord } from '../shared/ipc.js';
import { importMethodTraceAsync, type MethodTraceParser } from './method-capture-service.js';

export interface MethodTraceFileImportDependencies {
  readonly isRegularFile: (path: string) => boolean;
  readonly fileSize: (path: string) => Promise<number>;
  readonly readFile: (path: string) => Promise<Uint8Array>;
  readonly now: () => number;
  readonly newId: () => string;
  readonly parseTrace?: MethodTraceParser;
}

export interface ImportedMethodSession {
  readonly record: MethodSessionRecord;
  readonly trace: Uint8Array;
  readonly analysis: ArtTraceAnalysis;
  readonly table: CallStackTable;
}

/**
 * Loads an offline ART trace through a bounded file boundary, then builds a
 * session whose provenance stays explicitly separate from device capture.
 */
export async function importMethodTraceFile(
  dependencies: MethodTraceFileImportDependencies,
  input: { readonly path: string; readonly fileName: string },
): Promise<StudioResult<ImportedMethodSession>> {
  if (!dependencies.isRegularFile(input.path)) {
    return fail('IO', 'METHOD_TRACE_IMPORT_NOT_FOUND', 'Method trace does not exist: ' + input.path);
  }

  let size: number;
  try {
    size = await dependencies.fileSize(input.path);
  } catch (error) {
    return fail('IO', 'METHOD_TRACE_IMPORT_UNREADABLE', describe(error));
  }
  if (size > MAX_METHOD_TRACE_BYTES) {
    return fail(
      'DATA_VALIDATION',
      'METHOD_TRACE_IMPORT_TOO_LARGE',
      'Method trace is ' + String(size) + ' bytes; the limit is ' + String(MAX_METHOD_TRACE_BYTES),
    );
  }

  let trace: Uint8Array;
  try {
    trace = await dependencies.readFile(input.path);
  } catch (error) {
    return fail('IO', 'METHOD_TRACE_IMPORT_UNREADABLE', describe(error));
  }

  const parsed = await importMethodTraceAsync(dependencies, { fileName: input.fileName, trace });
  if (!parsed.ok) return parsed;
  const record: MethodSessionRecord = {
    id: parsed.value.id,
    capturedAtEpochMillis: parsed.value.capturedAtEpochMillis,
    origin: 'IMPORTED',
    sourceFileName: parsed.value.sourceFileName,
    traceVersion: parsed.value.analysis.header.version,
    traceBytes: parsed.value.trace.length,
    eventCount: parsed.value.analysis.events.length,
    methodCount: parsed.value.analysis.methods.size,
    threadCount: parsed.value.analysis.threads.size,
    threadKeys: [...parsed.value.analysis.threads.keys()].map((threadId) => threadKeyOf(parsed.value.analysis, threadId)),
    warnings: [...parsed.value.analysis.warnings],
  };
  return ok({ record, trace: parsed.value.trace, analysis: parsed.value.analysis, table: parsed.value.table });
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : 'Failed to read method trace';
}
