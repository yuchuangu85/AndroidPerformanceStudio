/**
 * Port of SimpleperfProfileNormalizer.kt: turns protobuf records into the
 * profile model, resolving file, symbol, thread, and event names.
 *
 * The normalizer is stateful on purpose. simpleperf emits File and Thread
 * records before the samples that reference them, so a sample only means
 * something in the context of everything decoded so far.
 */
import { executionTypeName, unwindErrorCodeName, type ProtoRecord } from './proto.js';
import {
  UNKNOWN_PROCESS_ID,
  UNKNOWN_SYMBOL,
  type NormalizedProfileRecord,
  type NormalizedSample,
  type ProfileExecutionType,
  type ProfileFile,
  type ProfileFrame,
  type ProfileMetadata,
  type ProfileThread,
} from './model.js';

export class SimpleperfProfileNormalizer {
  private readonly files = new Map<number, ProfileFile>();
  private readonly threads = new Map<number, ProfileThread>();
  private eventTypes: readonly string[] = [];
  private metadata: ProfileMetadata | undefined;

  get latestMetadata(): ProfileMetadata | undefined {
    return this.metadata;
  }

  /** Files and threads seen so far, keyed by id, for diagnostics and tests. */
  get knownFiles(): ReadonlyMap<number, ProfileFile> {
    return this.files;
  }

  get knownThreads(): ReadonlyMap<number, ProfileThread> {
    return this.threads;
  }

  normalize(record: ProtoRecord): NormalizedProfileRecord {
    switch (record.kind) {
      case 'SAMPLE':
        return { kind: 'SAMPLE', value: this.normalizeSample(record.sample) };
      case 'LOST':
        return { kind: 'LOST', sampleCount: record.lost.sampleCount, lostCount: record.lost.lostCount };
      case 'FILE':
        return { kind: 'FILE', value: this.normalizeFile(record.file) };
      case 'THREAD':
        return { kind: 'THREAD', value: this.normalizeThread(record.thread) };
      case 'META_INFO':
        return { kind: 'METADATA', value: this.normalizeMetadata(record.metaInfo) };
      case 'CONTEXT_SWITCH':
        return {
          kind: 'CONTEXT_SWITCH',
          threadId: record.contextSwitch.threadId,
          timestampNanos: record.contextSwitch.time,
          switchedOnCpu: record.contextSwitch.switchOn,
        };
      default:
        return { kind: 'UNKNOWN' };
    }
  }

  private normalizeFile(file: {
    readonly id: number;
    readonly path: string;
    readonly symbols: readonly string[];
    readonly mangledSymbols: readonly string[];
  }): ProfileFile {
    const normalized: ProfileFile = {
      id: file.id,
      path: file.path,
      symbols: file.symbols,
      mangledSymbols: file.mangledSymbols,
    };
    this.files.set(file.id, normalized);
    return normalized;
  }

  private normalizeThread(thread: {
    readonly processId: number;
    readonly threadId: number;
    readonly threadName: string;
  }): ProfileThread {
    const normalized: ProfileThread = {
      processId: thread.processId,
      threadId: thread.threadId,
      name: thread.threadName,
    };
    this.threads.set(thread.threadId, normalized);
    return normalized;
  }

  private normalizeMetadata(metaInfo: {
    readonly eventTypes: readonly string[];
    readonly appPackageName?: string;
    readonly appType?: string;
    readonly androidSdkVersion?: string;
    readonly androidBuildType?: string;
    readonly traceOffCpu: boolean;
  }): ProfileMetadata {
    this.eventTypes = metaInfo.eventTypes;
    const metadata: ProfileMetadata = {
      eventTypes: metaInfo.eventTypes,
      traceOffCpu: metaInfo.traceOffCpu,
      ...(metaInfo.appPackageName !== undefined ? { appPackageName: metaInfo.appPackageName } : {}),
      ...(metaInfo.appType !== undefined ? { appType: metaInfo.appType } : {}),
      ...(metaInfo.androidSdkVersion !== undefined ? { androidSdkVersion: metaInfo.androidSdkVersion } : {}),
      ...(metaInfo.androidBuildType !== undefined ? { androidBuildType: metaInfo.androidBuildType } : {}),
    };
    this.metadata = metadata;
    return metadata;
  }

  private normalizeSample(sample: {
    readonly time: bigint;
    readonly threadId: number;
    readonly callchain: readonly {
      readonly vaddrInFile: bigint;
      readonly fileId: number;
      readonly symbolId: number;
      readonly executionType: number;
    }[];
    readonly eventCount: bigint;
    readonly eventTypeId: number;
    readonly unwindingResult?: { readonly rawErrorCode: number; readonly errorAddr: bigint; readonly errorCode: number };
  }): NormalizedSample {
    const thread = this.threads.get(sample.threadId);
    return {
      timestampNanos: sample.time,
      processId: thread?.processId ?? UNKNOWN_PROCESS_ID,
      threadId: sample.threadId,
      threadName: thread?.name ?? '<unknown-thread:' + String(sample.threadId) + '>',
      eventType:
        this.eventTypes[sample.eventTypeId] ?? '<unknown-event:' + String(sample.eventTypeId) + '>',
      eventCount: sample.eventCount,
      frames: sample.callchain.map((entry) => this.normalizeFrame(entry)),
      ...(sample.unwindingResult !== undefined
        ? {
            unwindError: {
              code: unwindErrorCodeName(sample.unwindingResult.errorCode),
              rawCode: sample.unwindingResult.rawErrorCode,
              address: sample.unwindingResult.errorAddr,
            },
          }
        : {}),
    };
  }

  private normalizeFrame(entry: {
    readonly vaddrInFile: bigint;
    readonly fileId: number;
    readonly symbolId: number;
    readonly executionType: number;
  }): ProfileFrame {
    const file = this.files.get(entry.fileId);
    const filePath = file?.path ?? '<unknown-file:' + String(entry.fileId) + '>';
    const symbolName = file?.symbols[entry.symbolId] ?? UNKNOWN_SYMBOL;
    return {
      virtualAddress: entry.vaddrInFile,
      fileId: entry.fileId,
      symbolId: entry.symbolId,
      filePath,
      symbolName,
      executionType: resolveExecutionType(filePath, entry.executionType),
    };
  }
}

export function resolveExecutionType(filePath: string, reported: number): ProfileExecutionType {
  if (filePath === '[kernel.kallsyms]' || filePath.startsWith('[kernel.')) return 'KERNEL';
  if (filePath.startsWith('<unknown-file:')) return 'UNKNOWN';
  switch (executionTypeName(reported)) {
    case 'INTERPRETED_JVM_METHOD':
      return 'INTERPRETED_JVM';
    case 'JIT_JVM_METHOD':
      return 'JIT_JVM';
    case 'ART_METHOD':
      return 'ART';
    default:
      return 'NATIVE';
  }
}
