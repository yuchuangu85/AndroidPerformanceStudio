import type { HprofParseResult, Identifier } from './hprof.js';

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

export interface ClassTotals {
  readonly classObjectId: Identifier;
  readonly instanceCount: number;
  readonly shallowBytes: number;
}

/**
 * Groups instances by class.
 *
 * The hot loop keys a Map by number rather than by bigint: object ids in a real
 * dump fit a double, and bigint keys make this loop about twice as slow as the
 * JVM's HashMap<Long, Long>. Ids that do not fit fall back to a second map keyed
 * by the bigint, which keeps large synthetic ids correct.
 */
export function groupInstancesByClass(instances: HprofParseResult['instances']): ClassTotals[] {
  const small = new Map<number, { classObjectId: Identifier; instanceCount: number; shallowBytes: number }>();
  let large: Map<Identifier, { classObjectId: Identifier; instanceCount: number; shallowBytes: number }> | undefined;
  for (const instance of instances) {
    const classObjectId = instance.classObjectId;
    if (classObjectId <= MAX_SAFE_IDENTIFIER) {
      const key = Number(classObjectId);
      const entry = small.get(key);
      if (entry === undefined) {
        small.set(key, { classObjectId, instanceCount: 1, shallowBytes: instance.shallowBytes });
      } else {
        entry.instanceCount += 1;
        // The dump already reports the runtime's instance size; nothing is added.
        entry.shallowBytes += instance.shallowBytes;
      }
      continue;
    }
    large ??= new Map();
    const entry = large.get(classObjectId);
    if (entry === undefined) {
      large.set(classObjectId, { classObjectId, instanceCount: 1, shallowBytes: instance.shallowBytes });
    } else {
      entry.instanceCount += 1;
      entry.shallowBytes += instance.shallowBytes;
    }
  }
  const totals: ClassTotals[] = [...small.values(), ...(large?.values() ?? [])];
  return totals;
}

const MAX_SAFE_IDENTIFIER = BigInt(Number.MAX_SAFE_INTEGER);

/** Per-class instance counts and shallow sizes, largest first. */
export function classHistogram(result: HprofParseResult): ClassHistogramEntry[] {
  const entries = groupInstancesByClass(result.instances).map((totals) => ({
    className: classNameOf(result, totals.classObjectId),
    instanceCount: totals.instanceCount,
    shallowBytes: totals.shallowBytes,
  }));
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
