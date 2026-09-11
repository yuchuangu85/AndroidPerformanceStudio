/**
 * Port of GeckoProfileReader.kt: reads a Firefox Profiler JSON export (the
 * gzipped variant is decompressed by the caller) and turns each thread's tables
 * into normalized samples.
 *
 * The tables are the Gecko profile format: `stackTable` chains frames through
 * `prefix` indexes, `frameTable` points at `stringTable` entries written as
 * "symbol (in /path/to/file)", and `samples` carry a stack index and a
 * millisecond timestamp.
 */
import { fail, ok, type StudioResult } from '@aps/contracts';
import type { NormalizedSample, ProfileExecutionType, ProfileFrame } from './model.js';

export const GECKO_SAMPLE_EVENT = 'samples';
const NANOS_PER_MILLISECOND = 1_000_000;
const KERNEL_CATEGORY_ID = 1;
const LOCATION_SEPARATOR = ' (in ';

export interface GeckoReadResult {
  readonly samples: readonly NormalizedSample[];
  readonly threadCount: number;
}

interface GeckoTable {
  readonly schema: Record<string, number>;
  readonly data: readonly (readonly unknown[])[];
}

interface GeckoThread {
  readonly pid: number;
  readonly tid: number;
  readonly name: string;
  readonly samples: GeckoTable;
  readonly frameTable: GeckoTable;
  readonly stackTable: GeckoTable;
  readonly stringTable: readonly string[];
}

class GeckoProfileError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'GeckoProfileError';
  }
}

export function readGeckoProfileText(json: string): StudioResult<GeckoReadResult> {
  let root: unknown;
  try {
    root = JSON.parse(json);
  } catch (error) {
    return fail(
      'DATA_VALIDATION',
      'GECKO_PROFILE_INVALID',
      'Gecko profile is not valid JSON: ' + (error instanceof Error ? error.message : String(error)),
    );
  }
  try {
    return ok(readProfile(root));
  } catch (error) {
    return fail(
      'DATA_VALIDATION',
      'GECKO_PROFILE_INVALID',
      error instanceof Error ? error.message : 'Invalid Gecko profile',
    );
  }
}

function readProfile(root: unknown): GeckoReadResult {
  const profile = asObject(root, 'profile');
  const threads = profile['threads'];
  if (!Array.isArray(threads)) throw new GeckoProfileError('Gecko profile does not contain a threads array');
  const samples: NormalizedSample[] = [];
  for (const entry of threads) {
    const thread = readThread(asObject(entry, 'thread'));
    samples.push(...samplesOf(thread));
  }
  return { samples, threadCount: threads.length };
}

function readThread(thread: Record<string, unknown>): GeckoThread {
  const pid = requiredInt(thread['pid'], 'thread.pid');
  const tid = requiredInt(thread['tid'], 'thread.tid');
  const name =
    typeof thread['name'] === 'string' && thread['name'].length > 0
      ? (thread['name'] as string)
      : '<unknown-thread:' + String(tid) + '>';
  return {
    pid,
    tid,
    name,
    samples: readTable(thread['samples'], 'samples'),
    frameTable: readTable(thread['frameTable'], 'frameTable'),
    stackTable: readTable(thread['stackTable'], 'stackTable'),
    stringTable: readStringTable(thread['stringTable']),
  };
}

function readTable(value: unknown, label: string): GeckoTable {
  if (value === undefined) throw new GeckoProfileError('Gecko thread is missing ' + label);
  const table = asObject(value, label);
  const schemaRaw = asObject(table['schema'], label + '.schema');
  const schema: Record<string, number> = {};
  for (const [key, position] of Object.entries(schemaRaw)) {
    schema[key] = requiredInt(position, label + '.schema.' + key);
  }
  const data = table['data'];
  if (!Array.isArray(data)) throw new GeckoProfileError('Gecko table is missing data');
  return { schema, data: data.map((row) => (Array.isArray(row) ? row : [])) };
}

function readStringTable(value: unknown): string[] {
  if (!Array.isArray(value)) throw new GeckoProfileError('Gecko thread is missing stringTable');
  return value.map((entry) => (typeof entry === 'string' ? entry : ''));
}

interface DecodedThread {
  readonly stackIds: readonly (number | undefined)[];
  readonly times: readonly number[];
  readonly locationIds: readonly number[];
  readonly categoryIds: readonly (number | undefined)[];
  readonly prefixes: readonly (number | undefined)[];
  readonly frameIds: readonly number[];
}

/**
 * Decodes and validates every table before converting anything, matching the
 * reference reader: an out of range index or a stack prefix that does not point
 * backwards is a format error, not a silently dropped sample.
 */
function decodeThread(thread: GeckoThread): DecodedThread {
  const sampleStack = columnOf(thread.samples, 'stack');
  const sampleTime = columnOf(thread.samples, 'time');
  const stackPrefix = columnOf(thread.stackTable, 'prefix');
  const stackFrame = columnOf(thread.stackTable, 'frame');
  const frameLocation = columnOf(thread.frameTable, 'location');
  const categoryColumn = thread.frameTable.schema['category'];

  const stackIds: (number | undefined)[] = [];
  const times: number[] = [];
  thread.samples.data.forEach((row, index) => {
    stackIds.push(nullableInt(row[sampleStack]));
    times.push(requiredNumber(row[sampleTime], 'samples[' + String(index) + '].time'));
  });

  const locationIds: number[] = [];
  const categoryIds: (number | undefined)[] = [];
  thread.frameTable.data.forEach((row, index) => {
    locationIds.push(requiredInt(row[frameLocation], 'frameTable[' + String(index) + '].location'));
    categoryIds.push(categoryColumn === undefined ? undefined : nullableInt(row[categoryColumn]));
  });

  const prefixes: (number | undefined)[] = [];
  const frameIds: number[] = [];
  thread.stackTable.data.forEach((row, index) => {
    const prefix = nullableInt(row[stackPrefix]);
    if (prefix !== undefined && (prefix < 0 || prefix >= thread.stackTable.data.length || prefix >= index)) {
      throw new GeckoProfileError('Invalid stack prefix ' + String(prefix) + ' at stackTable[' + String(index) + ']');
    }
    const frame = requiredInt(row[stackFrame], 'stackTable[' + String(index) + '].frame');
    if (frame < 0 || frame >= locationIds.length) {
      throw new GeckoProfileError('Invalid frame ' + String(frame) + ' at stackTable[' + String(index) + ']');
    }
    prefixes.push(prefix);
    frameIds.push(frame);
  });

  locationIds.forEach((locationId, index) => {
    if (locationId < 0 || locationId >= thread.stringTable.length) {
      throw new GeckoProfileError('Invalid string ' + String(locationId) + ' at frameTable[' + String(index) + ']');
    }
  });
  stackIds.forEach((stackId, index) => {
    if (stackId !== undefined && (stackId < 0 || stackId >= prefixes.length)) {
      throw new GeckoProfileError('Invalid stack ' + String(stackId) + ' at samples[' + String(index) + ']');
    }
  });

  return { stackIds, times, locationIds, categoryIds, prefixes, frameIds };
}

function samplesOf(thread: GeckoThread): NormalizedSample[] {
  const decoded = decodeThread(thread);
  const frames: ProfileFrame[] = [];
  const frameIndex = new Map<string, number>();
  let nextFrameId = 1n;

  /** Leaf first, following the prefix chain, exactly like the reference reader. */
  const framesFor = (stackId: number | undefined): ProfileFrame[] => {
    const chain: ProfileFrame[] = [];
    let cursor = stackId;
    while (cursor !== undefined) {
      const frameId = decoded.frameIds[cursor] as number;
      const locationId = decoded.locationIds[frameId] as number;
      const parsed = parseLocation(thread.stringTable[locationId] as string);
      const key = parsed.filePath + '|' + parsed.symbolName;
      let id = frameIndex.get(key);
      if (id === undefined) {
        id = Number(nextFrameId);
        nextFrameId += 1n;
        frameIndex.set(key, id);
        frames.push({
          virtualAddress: 0n,
          fileId: id,
          symbolId: id,
          filePath: parsed.filePath,
          symbolName: parsed.symbolName,
          executionType: executionTypeOf(parsed.filePath, decoded.categoryIds[frameId]),
        });
      }
      chain.push(frames[id - 1] as ProfileFrame);
      cursor = decoded.prefixes[cursor];
    }
    return chain;
  };

  const samples: NormalizedSample[] = [];
  decoded.stackIds.forEach((stackId, index) => {
    const timestampNanos = BigInt(Math.round((decoded.times[index] as number) * NANOS_PER_MILLISECOND));
    samples.push({
      timestampNanos,
      processId: thread.pid,
      threadId: thread.tid,
      threadName: thread.name,
      eventType: GECKO_SAMPLE_EVENT,
      eventCount: 1n,
      frames: framesFor(stackId),
    });
  });
  return samples;
}

function columnOf(table: GeckoTable, name: string): number {
  const index = table.schema[name];
  if (index === undefined) throw new GeckoProfileError('Gecko table schema is missing ' + name);
  return index;
}

function asObject(value: unknown, label: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new GeckoProfileError('Gecko ' + label + ' is not an object');
  }
  return value as Record<string, unknown>;
}

function requiredInt(value: unknown, label: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new GeckoProfileError('Gecko ' + label + ' is not a number');
  }
  return Math.trunc(value);
}

function nullableInt(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? Math.trunc(value) : undefined;
}

function requiredNumber(value: unknown, label: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new GeckoProfileError('Gecko ' + label + ' is not a number');
  }
  return value;
}

/** "symbol (in /path/to/file)" is the format the Gecko writer emits. */
export function parseLocation(location: string): { readonly symbolName: string; readonly filePath: string } {
  const marker = location.lastIndexOf(LOCATION_SEPARATOR);
  if (marker <= 0 || !location.endsWith(')')) {
    return {
      symbolName: location.trim().length === 0 ? '<unknown-symbol>' : location,
      filePath: '<gecko-profile>',
    };
  }
  const symbol = location.slice(0, marker);
  const file = location.slice(marker + LOCATION_SEPARATOR.length, location.length - 1);
  return {
    symbolName: symbol.trim().length === 0 ? '<unknown-symbol>' : symbol,
    filePath: file.trim().length === 0 ? '<unknown-file>' : file,
  };
}

/** Port of the reference heuristic, including the kernel category fallback. */
export function executionTypeOf(filePath: string, categoryId: number | undefined): ProfileExecutionType {
  if (filePath.includes('kallsyms') || filePath.endsWith('.ko')) return 'KERNEL';
  if (filePath.endsWith('.vdex')) return 'INTERPRETED_JVM';
  if (filePath.endsWith('.oat')) return 'ART';
  if (filePath.includes('[JIT app cache]')) return 'JIT_JVM';
  if (filePath.includes('.so')) return 'NATIVE';
  if (categoryId === KERNEL_CATEGORY_ID) return 'KERNEL';
  return 'UNKNOWN';
}
