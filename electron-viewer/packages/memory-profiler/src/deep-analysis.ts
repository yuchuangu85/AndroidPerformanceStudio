/**
 * Port of MemoryDeepAnalysis.kt and HeapDiffAnalyzer.kt.
 *
 * The base session ranks objects by retained size, which answers "what is
 * large". This module answers "what is retained by what": destroyed Activities
 * that are still reachable, Fragments whose manager is gone, static fields
 * holding a Context, and Handlers or Threads holding an Activity.
 *
 * Every result is a reviewable signal, never a confirmed leak: no calibrated
 * model exists, so confidence stays unset and requiresManualVerification stays
 * true. Matching the Kotlin, the reasons are part of the result.
 */
import type { GraphAnalysis } from './dominators.js';
import { analyzeGraph } from './dominators.js';
import type { ObjectGraph } from './graph.js';
import type { HprofClassRecord, HprofInstanceRecord, HprofParseResult, Identifier } from './hprof.js';

export const DEFAULT_BITMAP_THRESHOLD_BYTES = 10 * 1024 * 1024;
const ARGB_8888_BYTES_PER_PIXEL = 4;

const FRAGMENT_BASE_CLASSES = [
  'android.app.Fragment',
  'android.support.v4.app.Fragment',
  'androidx.fragment.app.Fragment',
] as const;

const ACTIVITY_BASE_CLASS = 'android.app.Activity';

export interface MemoryReferenceStep {
  /** The field the reference was read from, or the GC root kind. */
  readonly fieldName: string;
  readonly objectId: string;
  readonly className: string;
}

export interface DeepLeakSuspect {
  readonly className: string;
  readonly reason: string;
  readonly retainedBytes: number;
  readonly instanceCount: number;
  readonly referenceChain: readonly MemoryReferenceStep[];
  /** Unset on purpose: the heuristics are not a calibrated model. */
  readonly confidence: number;
  readonly requiresManualVerification: boolean;
  /** True only for Activity/Fragment lifecycle candidates. */
  readonly activityOrFragmentLeak: boolean;
}

export interface ActivityLeakEntry {
  readonly className: string;
  readonly liveInstanceCount: number;
  readonly destroyedInstanceCount: number;
  readonly retainedBytes: number;
  readonly referenceChain: readonly MemoryReferenceStep[];
}

export interface BitmapInstanceStats {
  readonly objectId: string;
  readonly width?: number;
  readonly height?: number;
  readonly retainedBytes: number;
  readonly referenceChain: readonly MemoryReferenceStep[];
  /** rowBytes × height when the field is present, otherwise width × height × 4. */
  readonly estimatedPixelBytes?: number;
  readonly javaSizeBytes: number;
  /** Only set when the backing allocation is known to be native. */
  readonly nativeSizeBytes?: number;
  readonly className: string;
  readonly bitmapId?: string;
  readonly bitmapSourceId?: string;
}

export type HeapDiffMatchMode = 'CLASS_NAME' | 'CLASS_NAME_AND_HIERARCHY';

export interface HeapDiffEntry {
  readonly className: string;
  readonly beforeCount: number;
  readonly afterCount: number;
  readonly countDelta: number;
  readonly beforeShallowBytes: number;
  readonly afterShallowBytes: number;
  readonly shallowBytesDelta: number;
  readonly matchedBy: HeapDiffMatchMode;
  readonly hierarchyDepth?: number;
}

export interface HeapDiff {
  readonly entries: readonly HeapDiffEntry[];
  readonly added: readonly HeapDiffEntry[];
  readonly removed: readonly HeapDiffEntry[];
  readonly changed: readonly HeapDiffEntry[];
}

export interface MemoryDeepAnalysis {
  readonly suspects: readonly DeepLeakSuspect[];
  readonly activityLeaks: readonly ActivityLeakEntry[];
  readonly bitmaps: readonly BitmapInstanceStats[];
}

export function hexId(id: Identifier): string {
  return '0x' + id.toString(16);
}

export function fieldNameOf(result: HprofParseResult, nameId: Identifier): string {
  return result.strings.get(nameId) ?? '<unnamed field ' + nameId.toString(16) + '>';
}

function primitiveFieldOf(
  result: HprofParseResult,
  instance: HprofInstanceRecord,
  name: string,
): bigint | undefined {
  for (const value of instance.primitiveValues) {
    if (fieldNameOf(result, value.nameId) === name) return value.value;
  }
  return undefined;
}

function referenceIdFor(
  result: HprofParseResult,
  instance: HprofInstanceRecord,
  name: string,
): Identifier | undefined {
  for (const reference of instance.fieldReferences) {
    if (fieldNameOf(result, reference.nameId) === name) return reference.targetObjectId;
  }
  return undefined;
}

/**
 * True when the class declares the field and the instance holds nothing in it.
 * Zero references are not recorded, so absence plus a declared layout is the
 * only way to tell "null" from "field does not exist".
 */
function hasNullReference(
  result: HprofParseResult,
  instance: HprofInstanceRecord,
  name: string,
): boolean {
  const layout = result.classes.get(instance.classObjectId)?.instanceFields ?? [];
  const observed = instance.fieldReferences.some((reference) => fieldNameOf(result, reference.nameId) === name);
  // A dump may declare the field without recording it, or record a zero target;
  // either way the field exists and holds nothing.
  if (!layout.some((field) => fieldNameOf(result, field.nameId) === name) && !observed) return false;
  return referenceIdFor(result, instance, name) === undefined;
}

/** Class object ids whose superclass chain reaches the named class. */
export function classIdsAssignableTo(result: HprofParseResult, baseClassName: string): Set<Identifier> {
  const matching = new Set<Identifier>();
  for (const record of result.classes.values()) {
    let current: Identifier | undefined = record.objectId;
    let guard = 0;
    while (current !== undefined && current !== 0n && guard < 256) {
      guard += 1;
      const klass: HprofClassRecord | undefined = result.classes.get(current);
      if (klass === undefined) break;
      if (classNameOf(result, klass.nameId) === baseClassName) {
        matching.add(record.objectId);
        break;
      }
      current = klass.superClassId;
    }
  }
  return matching;
}

function classNameOf(result: HprofParseResult, nameId: Identifier): string {
  return result.strings.get(nameId) ?? '<unknown>';
}

export interface ReferenceChainFinder {
  /** Number of strong edges from a GC root; undefined when unreachable. */
  depthOf(objectId: Identifier): number | undefined;
  chainTo(objectId: Identifier): readonly MemoryReferenceStep[];
}

interface NamedReference {
  readonly fieldName: string;
  readonly targetObjectId: Identifier;
}

/**
 * Breadth-first from the GC roots, keeping the field each edge came from. The
 * Kotlin does the same walk; the field names are what make a chain readable in
 * the UI ("mActivity -> mContext -> ...").
 */
export function createReferenceChainFinder(
  result: HprofParseResult,
  graph: ObjectGraph,
): ReferenceChainFinder {
  const instancesById = new Map<Identifier, HprofInstanceRecord>();
  for (const instance of result.instances) instancesById.set(instance.objectId, instance);

  const classNameById = new Map<Identifier, string>();
  for (const node of graph.nodes.values()) classNameById.set(node.objectId, node.className);

  const outgoing = (objectId: Identifier): readonly NamedReference[] => {
    const instance = instancesById.get(objectId);
    if (instance === undefined) return [];
    return instance.fieldReferences.map((reference) => ({
      fieldName: fieldNameOf(result, reference.nameId),
      targetObjectId: reference.targetObjectId,
    }));
  };

  const depths = new Map<Identifier, number>();
  const rootStep = new Map<Identifier, MemoryReferenceStep>();
  const predecessor = new Map<Identifier, { source: Identifier; step: MemoryReferenceStep }>();
  const queue: Identifier[] = [];
  const known = new Set(graph.nodes.keys());

  for (const root of [...graph.roots].sort((left, right) => (left < right ? -1 : left > right ? 1 : 0))) {
    if (!known.has(root) || depths.has(root)) continue;
    depths.set(root, 0);
    rootStep.set(root, {
      fieldName: 'GC Root',
      objectId: hexId(root),
      className: classNameById.get(root) ?? '<unknown>',
    });
    queue.push(root);
  }

  for (let index = 0; index < queue.length; index += 1) {
    const current = queue[index];
    if (current === undefined) continue;
    const depth = depths.get(current) ?? 0;
    for (const reference of outgoing(current)) {
      if (depths.has(reference.targetObjectId)) continue;
      depths.set(reference.targetObjectId, depth + 1);
      predecessor.set(reference.targetObjectId, {
        source: current,
        step: {
          fieldName: reference.fieldName,
          objectId: hexId(reference.targetObjectId),
          className: classNameById.get(reference.targetObjectId) ?? '<unknown>',
        },
      });
      queue.push(reference.targetObjectId);
    }
  }

  const cache = new Map<Identifier, readonly MemoryReferenceStep[]>();
  return {
    depthOf: (objectId) => depths.get(objectId),
    chainTo(objectId) {
      const cached = cache.get(objectId);
      if (cached !== undefined) return cached;
      const start = rootStep.get(objectId);
      if (start !== undefined) {
        const chain = [start];
        cache.set(objectId, chain);
        return chain;
      }
      const reversed: MemoryReferenceStep[] = [];
      let current = objectId;
      let guard = 0;
      while (guard < 4096) {
        guard += 1;
        const link = predecessor.get(current);
        if (link === undefined) break;
        reversed.push(link.step);
        current = link.source;
      }
      const root = rootStep.get(current);
      if (root === undefined) {
        cache.set(objectId, []);
        return [];
      }
      const chain = [root, ...[...reversed].reverse()];
      cache.set(objectId, chain);
      return chain;
    },
  };
}

function isLikelyActivityName(className: string): boolean {
  return className.endsWith('Activity') || className.includes('.Activity$');
}

function isContextClass(className: string): boolean {
  return isLikelyActivityName(className) || className.endsWith('Context') || className.endsWith('ContextWrapper');
}

function isHandlerOrThreadClass(className: string): boolean {
  return className.endsWith('Handler') || className.endsWith('Thread') || className.includes('Handler$');
}

function isBitmapClass(className: string): boolean {
  return className === 'android.graphics.Bitmap' || className.endsWith('.Bitmap');
}

function isDestroyed(result: HprofParseResult, instance: HprofInstanceRecord): boolean {
  return (
    primitiveFieldOf(result, instance, 'mDestroyed') === 1n ||
    primitiveFieldOf(result, instance, 'mFinished') === 1n
  );
}

interface Candidate {
  readonly className: string;
  readonly reason: string;
  readonly retainedBytes: number;
  readonly instanceCount: number;
  readonly chain: readonly MemoryReferenceStep[];
  readonly activityOrFragmentLeak: boolean;
}

function toSuspect(candidate: Candidate): DeepLeakSuspect {
  return {
    className: candidate.className,
    reason: candidate.reason,
    retainedBytes: candidate.retainedBytes,
    instanceCount: candidate.instanceCount,
    referenceChain: candidate.chain,
    confidence: 0,
    requiresManualVerification: true,
    activityOrFragmentLeak: candidate.activityOrFragmentLeak,
  };
}

function sortSuspects(suspects: readonly DeepLeakSuspect[]): DeepLeakSuspect[] {
  return [...suspects].sort((left, right) => {
    if (right.retainedBytes !== left.retainedBytes) return right.retainedBytes - left.retainedBytes;
    return left.className < right.className ? -1 : left.className > right.className ? 1 : 0;
  });
}

/**
 * The Activity/Fragment/static/Handler heuristics, each one requiring that the
 * object is still reachable (depth > 0) before it is reported.
 */
export function deepLeakSuspects(
  result: HprofParseResult,
  graph: ObjectGraph,
  analysis: GraphAnalysis,
  finder: ReferenceChainFinder,
): DeepLeakSuspect[] {
  const retained = analysis.dominators.retainedBytes;
  const classNameById = new Map<Identifier, string>();
  for (const node of graph.nodes.values()) classNameById.set(node.objectId, node.className);

  const activityClassIds = classIdsAssignableTo(result, ACTIVITY_BASE_CLASS);
  const fragmentClassIds = new Set<Identifier>();
  for (const base of FRAGMENT_BASE_CLASSES) {
    for (const id of classIdsAssignableTo(result, base)) fragmentClassIds.add(id);
  }

  const reachable = (objectId: Identifier): boolean => (finder.depthOf(objectId) ?? -1) > 0;
  const retainedOf = (instance: HprofInstanceRecord): number =>
    retained.get(instance.objectId) ?? instance.shallowBytes;

  const instancesByClass = new Map<string, HprofInstanceRecord[]>();
  for (const instance of result.instances) {
    const className = classNameById.get(instance.objectId) ?? '<unknown>';
    const bucket = instancesByClass.get(className);
    if (bucket === undefined) instancesByClass.set(className, [instance]);
    else bucket.push(instance);
  }

  const candidates: Candidate[] = [];

  for (const instance of result.instances) {
    const className = classNameById.get(instance.objectId) ?? '<unknown>';
    if (activityClassIds.has(instance.classObjectId) && isDestroyed(result, instance) && reachable(instance.objectId)) {
      candidates.push({
        className,
        reason: 'Destroyed or finished Activity remains strongly reachable',
        retainedBytes: retainedOf(instance),
        instanceCount: instancesByClass.get(className)?.length ?? 1,
        chain: finder.chainTo(instance.objectId),
        activityOrFragmentLeak: true,
      });
      continue;
    }
    if (fragmentClassIds.has(instance.classObjectId) && hasNullReference(result, instance, 'mFragmentManager')) {
      if (!reachable(instance.objectId)) continue;
      candidates.push({
        className,
        reason:
          'Detached Fragment remains strongly reachable (mFragmentManager is null; may be a false positive)',
        retainedBytes: retainedOf(instance),
        instanceCount: instancesByClass.get(className)?.length ?? 1,
        chain: finder.chainTo(instance.objectId),
        activityOrFragmentLeak: true,
      });
    }
  }

  for (const klass of result.classes.values()) {
    const holder = classNameOf(result, klass.nameId);
    for (const reference of klass.staticReferences) {
      const targetClass = classNameById.get(reference.targetObjectId) ?? '';
      if (!isContextClass(targetClass) || !reachable(reference.targetObjectId)) continue;
      const field = fieldNameOf(result, reference.nameId).replace(/^static /, '');
      candidates.push({
        className: targetClass,
        reason: 'Static/singleton field ' + holder + '.' + field + ' retains a Context',
        retainedBytes: retained.get(reference.targetObjectId) ?? 0,
        instanceCount: instancesByClass.get(targetClass)?.length ?? 1,
        chain: finder.chainTo(reference.targetObjectId),
        activityOrFragmentLeak: false,
      });
    }
  }

  const instanceById = new Map<Identifier, HprofInstanceRecord>();
  for (const instance of result.instances) instanceById.set(instance.objectId, instance);

  for (const holder of result.instances) {
    const holderName = classNameById.get(holder.objectId) ?? '';
    if (!isHandlerOrThreadClass(holderName)) continue;
    for (const reference of holder.fieldReferences) {
      const target = instanceById.get(reference.targetObjectId);
      if (target === undefined || !activityClassIds.has(target.classObjectId)) continue;
      const chain = finder.chainTo(reference.targetObjectId);
      if (chain.length === 0) continue;
      const targetClass = classNameById.get(reference.targetObjectId) ?? '<unknown>';
      candidates.push({
        className: targetClass,
        reason:
          holderName + ' retains an Activity through ' + fieldNameOf(result, reference.nameId),
        retainedBytes: retained.get(reference.targetObjectId) ?? 0,
        instanceCount: instancesByClass.get(targetClass)?.length ?? 1,
        chain,
        activityOrFragmentLeak: false,
      });
    }
  }

  // One row per class and reason, keeping the largest retention as the evidence.
  const grouped = new Map<string, Candidate>();
  for (const candidate of candidates) {
    const key = candidate.className + '\u0000' + candidate.reason;
    const existing = grouped.get(key);
    if (existing === undefined || candidate.retainedBytes > existing.retainedBytes) grouped.set(key, candidate);
  }
  return sortSuspects([...grouped.values()].map(toSuspect));
}

/** Per-Activity-class counts: how many are live and how many are destroyed. */
export function activityLeakReport(
  result: HprofParseResult,
  graph: ObjectGraph,
  analysis: GraphAnalysis,
  finder: ReferenceChainFinder,
): ActivityLeakEntry[] {
  const retained = analysis.dominators.retainedBytes;
  const classNameById = new Map<Identifier, string>();
  for (const node of graph.nodes.values()) classNameById.set(node.objectId, node.className);
  const activityClassIds = classIdsAssignableTo(result, ACTIVITY_BASE_CLASS);

  const byClass = new Map<string, HprofInstanceRecord[]>();
  for (const instance of result.instances) {
    if (!activityClassIds.has(instance.classObjectId)) continue;
    const className = classNameById.get(instance.objectId) ?? '<unknown>';
    const bucket = byClass.get(className);
    if (bucket === undefined) byClass.set(className, [instance]);
    else bucket.push(instance);
  }

  const entries: ActivityLeakEntry[] = [];
  for (const [className, instances] of byClass) {
    const live = instances.filter((instance) => (finder.depthOf(instance.objectId) ?? -1) > 0);
    if (live.length === 0) continue;
    const destroyed = live.filter((instance) => isDestroyed(result, instance));
    if (destroyed.length === 0) continue;
    const representative = destroyed.reduce((best, instance) =>
      (retained.get(instance.objectId) ?? instance.shallowBytes) >
      (retained.get(best.objectId) ?? best.shallowBytes)
        ? instance
        : best,
    );
    entries.push({
      className,
      liveInstanceCount: live.length,
      destroyedInstanceCount: destroyed.length,
      retainedBytes: retained.get(representative.objectId) ?? representative.shallowBytes,
      referenceChain: finder.chainTo(representative.objectId),
    });
  }
  return entries.sort((left, right) => {
    if (right.destroyedInstanceCount !== left.destroyedInstanceCount) {
      return right.destroyedInstanceCount - left.destroyedInstanceCount;
    }
    if (right.liveInstanceCount !== left.liveInstanceCount) return right.liveInstanceCount - left.liveInstanceCount;
    return left.className < right.className ? -1 : left.className > right.className ? 1 : 0;
  });
}

function dimensionOf(
  result: HprofParseResult,
  instance: HprofInstanceRecord,
  names: readonly string[],
): number | undefined {
  for (const name of names) {
    const value = primitiveFieldOf(result, instance, name);
    if (value !== undefined && value >= 0n) return Number(value);
  }
  return undefined;
}

function estimatedPixelBytesOf(
  width: number | undefined,
  height: number | undefined,
  rowBytes: number | undefined,
): number | undefined {
  if (height === undefined || height <= 0) return undefined;
  const bytesPerRow = rowBytes !== undefined && rowBytes > 0 ? rowBytes : undefined;
  const row = bytesPerRow ?? (width !== undefined && width > 0 ? width * ARGB_8888_BYTES_PER_PIXEL : undefined);
  if (row === undefined) return undefined;
  const total = row * height;
  return Number.isSafeInteger(total) ? total : undefined;
}

/** Every Bitmap instance, largest retained first, with its pixel estimate. */
export function bitmapInstanceStats(
  result: HprofParseResult,
  graph: ObjectGraph,
  analysis: GraphAnalysis,
  finder: ReferenceChainFinder,
  thresholdBytes: number = DEFAULT_BITMAP_THRESHOLD_BYTES,
): BitmapInstanceStats[] {
  const retained = analysis.dominators.retainedBytes;
  const classNameById = new Map<Identifier, string>();
  for (const node of graph.nodes.values()) classNameById.set(node.objectId, node.className);

  const stats: BitmapInstanceStats[] = [];
  for (const instance of result.instances) {
    const className = classNameById.get(instance.objectId) ?? '<unknown>';
    if (!isBitmapClass(className)) continue;
    const width = dimensionOf(result, instance, ['mWidth', 'width']);
    const height = dimensionOf(result, instance, ['mHeight', 'height']);
    const rowBytes = dimensionOf(result, instance, ['mRowBytes', 'rowBytes']);
    const retainedBytes = retained.get(instance.objectId) ?? instance.shallowBytes;
    if (retainedBytes < thresholdBytes) continue;
    const id = primitiveFieldOf(result, instance, 'mId');
    const sourceId = primitiveFieldOf(result, instance, 'mSourceId');
    stats.push({
      objectId: hexId(instance.objectId),
      ...(width !== undefined ? { width } : {}),
      ...(height !== undefined ? { height } : {}),
      retainedBytes,
      referenceChain: finder.chainTo(instance.objectId),
      ...(estimatedPixelBytesOf(width, height, rowBytes) !== undefined
        ? { estimatedPixelBytes: estimatedPixelBytesOf(width, height, rowBytes) }
        : {}),
      javaSizeBytes: instance.shallowBytes,
      className,
      ...(id !== undefined ? { bitmapId: hexId(id) } : {}),
      ...(sourceId !== undefined ? { bitmapSourceId: hexId(sourceId) } : {}),
    });
  }
  return stats.sort((left, right) => {
    if (right.retainedBytes !== left.retainedBytes) return right.retainedBytes - left.retainedBytes;
    return left.objectId < right.objectId ? -1 : left.objectId > right.objectId ? 1 : 0;
  });
}

export interface AnalysisInput {
  readonly className: string;
  readonly instanceCount: number;
  readonly shallowBytes: number;
  readonly hierarchyDepth?: number;
}

/**
 * Diffs two histograms. Matching by class name alone can merge two classes that
 * share a name across class loaders, so the hierarchy mode stays available but
 * is not the default.
 */
export function diffHistograms(
  before: readonly AnalysisInput[],
  after: readonly AnalysisInput[],
  matchMode: HeapDiffMatchMode = 'CLASS_NAME',
): HeapDiff {
  const keyOf = (entry: AnalysisInput): string =>
    matchMode === 'CLASS_NAME_AND_HIERARCHY' && entry.hierarchyDepth !== undefined
      ? entry.className + '|depth=' + String(entry.hierarchyDepth)
      : entry.className;
  const beforeByKey = new Map<string, AnalysisInput>();
  for (const entry of before) beforeByKey.set(keyOf(entry), entry);
  const afterByKey = new Map<string, AnalysisInput>();
  for (const entry of after) afterByKey.set(keyOf(entry), entry);

  const keys = [...new Set([...beforeByKey.keys(), ...afterByKey.keys()])].sort();
  const entries: HeapDiffEntry[] = [];
  for (const key of keys) {
    const old = beforeByKey.get(key);
    const next = afterByKey.get(key);
    const countDelta = (next?.instanceCount ?? 0) - (old?.instanceCount ?? 0);
    const shallowDelta = (next?.shallowBytes ?? 0) - (old?.shallowBytes ?? 0);
    if (countDelta === 0 && shallowDelta === 0) continue;
    const depth = next?.hierarchyDepth ?? old?.hierarchyDepth;
    entries.push({
      className: next?.className ?? old?.className ?? key,
      beforeCount: old?.instanceCount ?? 0,
      afterCount: next?.instanceCount ?? 0,
      countDelta,
      beforeShallowBytes: old?.shallowBytes ?? 0,
      afterShallowBytes: next?.shallowBytes ?? 0,
      shallowBytesDelta: shallowDelta,
      matchedBy: matchMode,
      ...(depth !== undefined ? { hierarchyDepth: depth } : {}),
    });
  }
  entries.sort((left, right) => {
    if (right.countDelta !== left.countDelta) return right.countDelta - left.countDelta;
    return left.className < right.className ? -1 : left.className > right.className ? 1 : 0;
  });
  return {
    entries,
    added: entries.filter((entry) => entry.beforeCount === 0 && entry.afterCount > 0),
    removed: entries.filter((entry) => entry.beforeCount > 0 && entry.afterCount === 0),
    changed: entries.filter(
      (entry) => entry.beforeCount > 0 && entry.afterCount > 0 && entry.countDelta !== 0,
    ),
  };
}

export interface DeepAnalysisOptions {
  readonly bitmapThresholdBytes?: number;
  readonly analysis?: GraphAnalysis;
}

/** One graph build and one dominator pass shared by every report. */
export function analyzeHeapDeeply(
  result: HprofParseResult,
  graph: ObjectGraph,
  options: DeepAnalysisOptions = {},
): MemoryDeepAnalysis {
  const analysis = options.analysis ?? analyzeGraph(graph);
  const finder = createReferenceChainFinder(result, graph);
  return {
    suspects: deepLeakSuspects(result, graph, analysis, finder),
    activityLeaks: activityLeakReport(result, graph, analysis, finder),
    bitmaps: bitmapInstanceStats(
      result,
      graph,
      analysis,
      finder,
      options.bitmapThresholdBytes ?? DEFAULT_BITMAP_THRESHOLD_BYTES,
    ),
  };
}
