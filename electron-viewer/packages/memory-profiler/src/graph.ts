import type { HprofParseResult, Identifier } from './hprof.js';
import { primitiveSize } from './hprof.js';

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
      references: instance.references,
      isRoot: rootIds.has(instance.objectId),
    });
  }

  for (const array of result.arrays) {
    nodes.set(array.objectId, {
      objectId: array.objectId,
      kind: 'object-array',
      className: array.kind === 'object' ? '<object array>' : '<primitive array>',
      shallowBytes: array.shallowBytes,
      references: array.references,
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
