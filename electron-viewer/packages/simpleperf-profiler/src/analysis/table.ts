/**
 * Bridges normalized simpleperf samples into the analysis CallStackTable.
 *
 * simpleperf reports a callchain leaf first; the analysis model wants frames
 * root to leaf, so every stack is reversed here. Sample weight is the event
 * count, matching what the Kotlin storage layer stores in the weight column.
 */
import type { NormalizedSample, ProfileExecutionType, ProfileFrame } from '../model.js';
import {
  CallStackTable,
  weightedCallStack,
  type CallStackFrame,
  type FrameImplementation,
  type WeightedCallStack,
} from '@aps/profile-analysis';

export interface CallStackTableOptions {
  /** Overrides the per-sample weight; defaults to the sample event count. */
  readonly weightOf?: (sample: NormalizedSample) => bigint;
  readonly threadKeyOf?: (sample: NormalizedSample) => string;
}

/** Sequential allocation keeps ids collision free and stable per import. */
class IdAllocator {
  private readonly ids = new Map<string, bigint>();
  private next = 1n;

  idFor(key: string): bigint {
    const existing = this.ids.get(key);
    if (existing !== undefined) return existing;
    const allocated = this.next;
    this.next += 1n;
    this.ids.set(key, allocated);
    return allocated;
  }
}

/**
 * Frame identity is address level, function identity is symbol level: a symbol
 * reached through two addresses is one function with several frames, which is
 * what lets recursion collapse and the call tree merge. Mirrors the Kotlin
 * FrameKey versus symbol-id split in the storage layer.
 */
export function frameKeyOf(frame: ProfileFrameLike): string {
  return [
    String(frame.virtualAddress),
    String(frame.fileId),
    String(frame.symbolId),
    frame.executionType,
    frame.filePath,
    frame.symbolName,
  ].join('|');
}

export function functionKeyOf(frame: ProfileFrameLike): string {
  return [String(frame.fileId), frame.filePath, frame.symbolName].join('|');
}

type ProfileFrameLike = ProfileFrame;

export function implementationOf(executionType: ProfileExecutionType): FrameImplementation {
  switch (executionType) {
    case 'NATIVE':
      return 'NATIVE';
    case 'INTERPRETED_JVM':
    case 'JIT_JVM':
    case 'ART':
      return 'MANAGED';
    case 'KERNEL':
      return 'KERNEL';
    default:
      return 'UNKNOWN';
  }
}

export function defaultThreadKey(sample: NormalizedSample): string {
  return sample.threadName + ' (tid ' + String(sample.threadId) + ')';
}

export function samplesToCallStackTable(
  samples: readonly NormalizedSample[],
  options: CallStackTableOptions = {},
): CallStackTable {
  const frameIds = new IdAllocator();
  const functionIds = new IdAllocator();
  const framesById = new Map<bigint, CallStackFrame>();
  const stacks: WeightedCallStack[] = [];
  let sampleId = 0n;

  samples.forEach((sample) => {
    if (sample.frames.length === 0) return;
    const framePath = [...sample.frames].reverse();
    const pathIds: bigint[] = [];
    framePath.forEach((frame) => {
      const frameId = frameIds.idFor(frameKeyOf(frame));
      pathIds.push(frameId);
      if (!framesById.has(frameId)) {
        framesById.set(frameId, {
          frameId,
          functionId: functionIds.idFor(functionKeyOf(frame)),
          symbolName: frame.symbolName,
          resource: frame.filePath,
          virtualAddress: frame.virtualAddress,
          implementation: implementationOf(frame.executionType),
        });
      }
    });
    stacks.push(
      weightedCallStack({
        sampleId,
        timestampNanos: sample.timestampNanos,
        weight: options.weightOf?.(sample) ?? sample.eventCount,
        threadKey: options.threadKeyOf?.(sample) ?? defaultThreadKey(sample),
        frameIdsRootToLeaf: pathIds,
      }),
    );
    sampleId += 1n;
  });

  return new CallStackTable(framesById, stacks);
}
