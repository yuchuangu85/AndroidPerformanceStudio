import type { HprofParseResult } from './hprof.js';

export interface ClassHistogramEntry {
  readonly className: string;
  readonly instanceCount: number;
  readonly shallowBytes: number;
}

export interface MemorySummary {
  readonly version: string;
  readonly identifierSize: number;
  readonly classCount: number;
  readonly instanceCount: number;
  readonly arrayCount: number;
  readonly shallowBytes: number;
}

function classNameOf(result: HprofParseResult, classObjectId: bigint): string {
  const record = result.classes.get(classObjectId);
  if (record === undefined) return '<unknown class ' + classObjectId.toString(16) + '>';
  return result.strings.get(record.nameId) ?? '<unnamed class ' + classObjectId.toString(16) + '>';
}

/** Per-class instance counts and shallow sizes, largest first. */
export function classHistogram(result: HprofParseResult): ClassHistogramEntry[] {
  const byClass = new Map<bigint, { instanceCount: number; shallowBytes: number }>();
  for (const instance of result.instances) {
    const entry = byClass.get(instance.classObjectId) ?? { instanceCount: 0, shallowBytes: 0 };
    entry.instanceCount += 1;
    // The dump already reports the runtime's instance size; nothing is added.
    entry.shallowBytes += instance.shallowBytes;
    byClass.set(instance.classObjectId, entry);
  }
  const entries: ClassHistogramEntry[] = [];
  for (const [classObjectId, entry] of byClass) {
    entries.push({
      className: classNameOf(result, classObjectId),
      instanceCount: entry.instanceCount,
      shallowBytes: entry.shallowBytes,
    });
  }
  return entries.sort((left, right) => right.shallowBytes - left.shallowBytes);
}

/** Array shallow sizes are reported separately because they have no class id. */
export function arrayShallowBytes(result: HprofParseResult): number {
  return result.arrays.reduce((total, array) => total + array.shallowBytes, 0);
}

export function summarizeMemory(result: HprofParseResult): MemorySummary {
  const instanceBytes = result.instances.reduce((total, instance) => total + instance.shallowBytes, 0);
  return {
    version: result.header.version,
    identifierSize: result.header.identifierSize,
    classCount: result.classes.size,
    instanceCount: result.instances.length,
    arrayCount: result.arrays.length,
    shallowBytes: instanceBytes + arrayShallowBytes(result),
  };
}

/**
 * Shallow sizes come from the dump (declared instance sizes and ART's array
 * header), so they are exact for the objects present. Retained sizes are the
 * estimates, and they are labelled as such where they are shown.
 */
export function histogramIsEstimated(): boolean {
  return false;
}
