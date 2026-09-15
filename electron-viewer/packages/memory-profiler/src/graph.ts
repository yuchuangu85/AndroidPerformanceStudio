import type { HprofInstanceRecord, HprofParseResult, Identifier } from './hprof.js';
import { primitiveSize } from './hprof.js';

const REFERENCE_BASE_CLASS = 'java.lang.ref.Reference';
const DIRECT_REFERENCE_CLASSES = new Set([
  'java.lang.ref.WeakReference',
  'java.lang.ref.SoftReference',
  'java.lang.ref.PhantomReference',
]);

/**
 * Builds the same Reference hierarchy filter as Kotlin's HeapGraph: a
 * `referent` edge is not a strong edge, whether it comes from a framework
 * reference class or an indirect subclass.
 */
export function createStrongInstanceReferencePredicate(
  result: HprofParseResult,
): (instance: HprofInstanceRecord, index: number) => boolean {
  const holders = new Set<Identifier>();
  const referenceBaseIds = new Set<Identifier>();
  for (const klass of result.classes.values()) {
    const className = result.strings.get(klass.nameId);
    if (className === REFERENCE_BASE_CLASS) referenceBaseIds.add(klass.objectId);
    if (className !== undefined && DIRECT_REFERENCE_CLASSES.has(className)) holders.add(klass.objectId);
  }

  for (const candidate of result.classes.values()) {
    const visited = new Set<Identifier>();
    let current: Identifier | undefined = candidate.objectId;
    while (current !== undefined && current !== 0n && visited.add(current)) {
      if (referenceBaseIds.has(current)) {
        holders.add(candidate.objectId);
        break;
      }
      current = result.classes.get(current)?.superClassId;
    }
  }

  return (instance, index) => {
    if (!holders.has(instance.classObjectId)) return true;
    return result.strings.get(instance.referenceNameIds[index] ?? 0n) !== 'referent';
  };
}

export interface GraphNode {
  readonly objectId: Identifier;
  readonly kind: 'instance' | 'object-array';
  readonly className: string;
  readonly shallowBytes: number;
  readonly references: readonly Identifier[];
  readonly isRoot: boolean;
}

export interface ObjectGraph {
  readonly nodes: ReadonlyMap<Identifier, GraphNode>;
  readonly roots: readonly Identifier[];
  /** Root ids that never appeared in the heap dump. */
  readonly danglingRoots: readonly Identifier[];
  readonly warnings: readonly string[];
}

/** Builds the object graph that dominator and leak analysis operate on. */
export function buildObjectGraph(result: HprofParseResult): ObjectGraph {
  const warnings: string[] = [];
  const nodes = new Map<Identifier, GraphNode>();

  const classNameOf = (classObjectId: Identifier): string => {
    const record = result.classes.get(classObjectId);
    if (record === undefined) return '<unknown class ' + classObjectId.toString(16) + '>';
    return result.strings.get(record.nameId) ?? '<unnamed class ' + classObjectId.toString(16) + '>';
  };

  const rootIds = new Set(result.roots);
  const isStrongInstanceReference = createStrongInstanceReferencePredicate(result);
  for (const instance of result.instances) {
    const layoutSize = (result.classes.get(instance.classObjectId)?.instanceFields ?? []).reduce(
      (total, field) => total + (field.type === 2 ? result.header.identifierSize : primitiveSize(field.type)),
      0,
    );
    if (layoutSize !== 0 && layoutSize !== instance.fieldBytes) {
      warnings.push(
        'Instance ' +
          instance.objectId.toString(16) +
          ' payload is ' +
          String(instance.fieldBytes) +
          ' bytes but its class layout is ' +
          String(layoutSize) +
          ' bytes; references may be incomplete.',
      );
    }
    nodes.set(instance.objectId, {
      objectId: instance.objectId,
      kind: 'instance',
      className: classNameOf(instance.classObjectId),
      shallowBytes: instance.shallowBytes,
      references: instance.references.filter(
        (target, index) => target !== 0n && isStrongInstanceReference(instance, index),
      ),
      isRoot: rootIds.has(instance.objectId),
    });
  }

  for (const array of result.arrays) {
    nodes.set(array.objectId, {
      objectId: array.objectId,
      kind: 'object-array',
      className:
        array.className ?? (array.kind === 'object' ? '<object array>' : '<primitive array>'),
      shallowBytes: array.shallowBytes,
      references: array.references.filter((target) => target !== 0n),
      isRoot: rootIds.has(array.objectId),
    });
  }

  const danglingRoots: Identifier[] = [];
  for (const root of rootIds) {
    if (!nodes.has(root)) danglingRoots.push(root);
  }
  // Roots that reference nothing are still roots; the virtual root links to every
  // root id, so unreachable cycles are detectable.
  return { nodes, roots: [...rootIds], danglingRoots, warnings };
}
