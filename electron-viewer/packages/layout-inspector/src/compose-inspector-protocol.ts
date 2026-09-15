import type { ComposableDetail, ComposableNode, ComposeCapabilityState, ComposeDetailCoverage, ComposeInspectionFrame, ComposeParameterReference, ComposeTruncation, ComposeValue } from './compose-inspection.js';

const MAX_NODES = 100_000;
const MAX_STRING_CHARS = 64 * 1024;
const TYPE_NAMES = ['UNSPECIFIED', 'STRING', 'BOOLEAN', 'DOUBLE', 'FLOAT', 'INT32', 'INT64', 'COLOR', 'RESOURCE', 'DIMENSION_DP', 'DIMENSION_SP', 'DIMENSION_EM', 'LAMBDA', 'FUNCTION_REFERENCE', 'ITERABLE'] as const;
const FLAGS: readonly [number, string][] = [[1, 'SYSTEM_CREATED'], [2, 'HAS_MERGED_SEMANTICS'], [4, 'HAS_UNMERGED_SEMANTICS'], [8, 'INLINED'], [16, 'NESTED_SINGLE_CHILDREN'], [32, 'HAS_DRAW_MODIFIER'], [64, 'HAS_CHILD_DRAW_MODIFIER']];

export class ComposeInspectorProtocolError extends Error { constructor(message: string) { super(message); this.name = 'ComposeInspectorProtocolError'; } }

export function encodeGetComposablesCommand(rootViewId: bigint, extractAllParameters: boolean, generation = 0): Uint8Array {
  if (!Number.isInteger(generation) || generation < 0) throw new ComposeInspectorProtocolError('Invalid Compose generation');
  return bytesField(1, concat([varintField(1, rootViewId), varintField(3, BigInt(generation)), varintField(4, extractAllParameters ? 1n : 0n)]));
}
export function encodeGetAllParametersCommand(rootViewId: bigint): Uint8Array {
  return bytesField(3, concat([varintField(1, rootViewId), varintField(3, 2n), varintField(4, 5n), varintField(5, 0n)]));
}

export function decodeComposeInspectionFrame(payload: Uint8Array, frameId: string, generation: number, includeDetails = false): ComposeInspectionFrame {
  const response = readFields(payload);
  const treePayload = oneBytes(response, 1, 'get composables response');
  if (treePayload === undefined) throw new ComposeInspectorProtocolError('Compose inspector did not return a composable tree');
  const tree = decodeTree(treePayload);
  const parameterPayload = oneBytes(response, 3, 'all parameters response');
  if (includeDetails && parameterPayload === undefined) throw new ComposeInspectorProtocolError('Compose inspector did not return requested parameters');
  const details = parameterPayload === undefined ? new Map<number, ComposableDetail>() : decodeDetails(parameterPayload);
  const budget = { remaining: MAX_NODES, exhausted: false };
  const truncations: ComposeTruncation[] = [];
  const roots = tree.roots.map((root) => ({ ...root, nodes: root.nodes.map((node) => convertNode(node, tree.strings, budget, truncations)).filter(isDefined) }));
  const collected = parameterPayload !== undefined;
  const allNodes = roots.flatMap((root) => flatten(root.nodes));
  const coverage: ComposeDetailCoverage[] = collected
    ? [...details.keys()].flatMap((nodeId) => detailFields().map((field) => ({ nodeId, field, state: 'COLLECTED' as const, recursionDepth: 2, loadedElements: 0 })))
    : allNodes.flatMap((node) => detailFields().map((field) => ({ nodeId: node.id, field, state: 'NOT_COLLECTED' as const, recursionDepth: 0, loadedElements: 0 })));
  return { frameId, generation, mode: 'FULL', capabilities: capabilities(collected), roots, details, coverage, completeness: budget.exhausted ? 'INCOMPLETE_RESOURCE_LIMIT' : 'COMPLETE', truncations };
}

function capabilities(collected: boolean): ComposeCapabilityState[] {
  return [
    { capability: 'FULL_TREE', availability: 'AVAILABLE' },
    ...(['PARAMETERS', 'MODIFIERS', 'MERGED_SEMANTICS', 'UNMERGED_SEMANTICS'] as const).map((capability) => ({ capability, availability: collected ? 'AVAILABLE' as const : 'NOT_REQUESTED' as const })),
    { capability: 'SOURCE_LOCATION', availability: 'AVAILABLE' },
    { capability: 'RECOMPOSITION_COUNTS', availability: 'NOT_REQUESTED' },
    { capability: 'SKIP_COUNTS', availability: 'NOT_REQUESTED' },
    { capability: 'STATE_READS', availability: 'UNAVAILABLE', reason: 'experimental capability deferred' },
  ];
}
function detailFields(): readonly ('parameters' | 'modifiers' | 'mergedSemantics' | 'unmergedSemantics')[] { return ['parameters', 'modifiers', 'mergedSemantics', 'unmergedSemantics']; }

type RawNode = { id: number; children: RawNode[]; packageHash: number; filename: number; line: number; offset: number; name: number; bounds?: Bounds; flags: number; hostedViewId: number; recomposeCount: number; skipCount: number; anchorHash: number };
type Bounds = { x: number; y: number; w: number; h: number };
type RawRoot = { viewId: number; nodes: RawNode[]; viewsToSkip: number[] };
function decodeTree(bytes: Uint8Array): { strings: Map<number, string>; roots: RawRoot[] } { const f = readFields(bytes); return { strings: stringTable(f, 1), roots: allBytes(f, 2).map(decodeRoot) }; }
function decodeRoot(bytes: Uint8Array): RawRoot { const f = readFields(bytes); return { viewId: integer(oneVarint(f, 1), 'root view id'), nodes: allBytes(f, 2).map(decodeNode), viewsToSkip: repeatedVarints(f, 3).map((value) => integer(value, 'view id')) }; }
function decodeNode(bytes: Uint8Array): RawNode { const f = readFields(bytes); const bounds = oneBytes(f, 8, 'node bounds'); return { id: zigzag64(oneVarint(f, 1) ?? 0n), children: allBytes(f, 2).map(decodeNode), packageHash: int32(oneVarint(f, 3) ?? 0n), filename: int32(oneVarint(f, 4) ?? 0n), line: int32(oneVarint(f, 5) ?? 0n), offset: int32(oneVarint(f, 6) ?? 0n), name: int32(oneVarint(f, 7) ?? 0n), ...(bounds === undefined ? {} : { bounds: decodeBounds(bounds) }), flags: int32(oneVarint(f, 9) ?? 0n), hostedViewId: integer(oneVarint(f, 10) ?? 0n, 'hosted view id'), recomposeCount: int32(oneVarint(f, 11) ?? 0n), skipCount: int32(oneVarint(f, 12) ?? 0n), anchorHash: zigzag32(oneVarint(f, 13) ?? 0n) }; }
function decodeBounds(bytes: Uint8Array): Bounds | undefined { const f = readFields(bytes); const layout = oneBytes(f, 1, 'layout bounds'); if (layout === undefined) return undefined; const r = readFields(layout); return { x: int32(oneVarint(r, 1) ?? 0n), y: int32(oneVarint(r, 2) ?? 0n), w: int32(oneVarint(r, 3) ?? 0n), h: int32(oneVarint(r, 4) ?? 0n) }; }
function convertNode(raw: RawNode, strings: Map<number, string>, budget: { remaining: number; exhausted: boolean }, truncations: ComposeTruncation[]): ComposableNode | undefined {
  if (budget.remaining-- <= 0) { budget.exhausted = true; if (truncations.length === 0) truncations.push({ field: 'tree', reason: 'node limit exceeded', retainedSize: MAX_NODES }); return undefined; }
  const b = raw.bounds;
  return { id: raw.id, anchorHash: raw.anchorHash, name: strings.get(raw.name) ?? '<unknown composable>', bounds: b === undefined ? zeroBounds() : { left: b.x, top: b.y, right: saturatedAdd(b.x, b.w), bottom: saturatedAdd(b.y, b.h) }, ...(raw.hostedViewId === 0 ? {} : { hostedViewId: raw.hostedViewId }), ...(strings.has(raw.filename) ? { source: { packageHash: raw.packageHash, fileName: strings.get(raw.filename) as string, lineNumber: raw.line, offset: raw.offset } } : {}), systemCreated: (raw.flags & 1) !== 0, flags: FLAGS.filter(([bit]) => (raw.flags & bit) !== 0).map(([, name]) => name), recomposeCount: raw.recomposeCount, skipCount: raw.skipCount, children: raw.children.map((child) => convertNode(child, strings, budget, truncations)).filter(isDefined) };
}

function decodeDetails(bytes: Uint8Array): Map<number, ComposableDetail> {
  const f = readFields(bytes), strings = stringTable(f, 2), out = new Map<number, ComposableDetail>();
  for (const raw of allBytes(f, 3)) { const group = readFields(raw), values = allBytes(group, 3).map((value) => parameter(value, strings)); const nodeId = zigzag64(oneVarint(group, 1) ?? 0n); out.set(nodeId, { nodeId, anchorHash: values[0]?.reference?.anchorHash ?? 0, parameters: values.filter((value) => value.name.toLowerCase() !== 'modifier'), modifiers: values.filter((value) => value.name.toLowerCase() === 'modifier'), mergedSemantics: allBytes(group, 4).map((value) => parameter(value, strings)), unmergedSemantics: allBytes(group, 5).map((value) => parameter(value, strings)) }); }
  return out;
}
function parameter(bytes: Uint8Array, strings: Map<number, string>): ComposeValue {
  const f = readFields(bytes), type = int32(oneVarint(f, 1) ?? 0n), raw = rawParameterValue(type, f, strings), referenceBytes = oneBytes(f, 4, 'parameter reference'), reference = referenceBytes === undefined ? undefined : parameterReference(referenceBytes);
  const value = raw?.slice(0, MAX_STRING_CHARS);
  return { name: strings.get(int32(oneVarint(f, 2) ?? 0n)) ?? '', type: TYPE_NAMES[type] ?? 'UNSPECIFIED', ...(value === undefined ? {} : { value }), elements: allBytes(f, 3).map((element) => parameter(element, strings)), ...(reference === undefined ? {} : { reference }), ...(raw !== undefined && raw.length > MAX_STRING_CHARS ? { originalSize: raw.length } : {}), truncated: reference !== undefined || (raw?.length ?? 0) > MAX_STRING_CHARS };
}
function rawParameterValue(type: number, f: readonly Field[], strings: Map<number, string>): string | undefined {
  const intValue = int32(oneVarint(f, 11) ?? 0n);
  if (type === 1 || type === 14) return strings.get(intValue);
  if (type === 2) return String(intValue === 1);
  if (type === 3) return String(oneFixed64(f, 13) ?? 0);
  if ([4, 9, 10, 11].includes(type)) return String(oneFixed32(f, 14) ?? 0);
  if (type === 5 || type === 7) return String(intValue);
  if (type === 6) return String(int64(oneVarint(f, 12) ?? 0n));
  if (type === 8) { const value = oneBytes(f, 15, 'resource value'); if (value === undefined) return undefined; const r = readFields(value); return [1, 2, 3].map((n) => strings.get(int32(oneVarint(r, n) ?? 0n)) ?? '').join(':'); }
  if (type === 12 || type === 13) { const value = oneBytes(f, 16, 'lambda value'); if (value === undefined) return undefined; const r = readFields(value); return (strings.get(int32(oneVarint(r, 2) ?? 0n)) ?? '') + ':' + String(int32(oneVarint(r, 5) ?? 0n)); }
  return undefined;
}
function parameterReference(bytes: Uint8Array): ComposeParameterReference { const f = readFields(bytes); return { composableId: zigzag64(oneVarint(f, 1) ?? 0n), parameterIndex: int32(oneVarint(f, 2) ?? 0n), compositeIndex: repeatedVarints(f, 3).map(int32), kind: (['UNSPECIFIED', 'NORMAL', 'MERGED_SEMANTICS', 'UNMERGED_SEMANTICS'] as const)[int32(oneVarint(f, 4) ?? 0n)] ?? 'UNSPECIFIED', anchorHash: zigzag32(oneVarint(f, 5) ?? 0n) }; }

type Field = { n: number; w: number; v: bigint | Uint8Array | number };
function readFields(bytes: Uint8Array): Field[] { const r = new Reader(bytes), out: Field[] = []; while (!r.end) { const tag = r.varint(), n = Number(tag >> 3n), w = Number(tag & 7n); if (n === 0) throw new ComposeInspectorProtocolError('Invalid protobuf field number'); if (w === 0) out.push({ n, w, v: r.varint() }); else if (w === 2) out.push({ n, w, v: r.bytes() }); else if (w === 1) out.push({ n, w, v: r.float64() }); else if (w === 5) out.push({ n, w, v: r.float32() }); else throw new ComposeInspectorProtocolError('Unsupported protobuf wire type'); } return out; }
function allBytes(f: readonly Field[], n: number): Uint8Array[] { return f.filter((x) => x.n === n && x.w === 2).map((x) => x.v as Uint8Array); }
function oneBytes(f: readonly Field[], n: number, label: string): Uint8Array | undefined { const values = allBytes(f, n); if (values.length > 1) throw new ComposeInspectorProtocolError('Repeated singular ' + label); return values[0]; }
function oneVarint(f: readonly Field[], n: number): bigint | undefined { const values = f.filter((x) => x.n === n && x.w === 0).map((x) => x.v as bigint); if (values.length > 1) throw new ComposeInspectorProtocolError('Repeated singular varint'); return values[0]; }
function oneFixed32(f: readonly Field[], n: number): number | undefined { const v = f.filter((x) => x.n === n && x.w === 5); if (v.length > 1) throw new ComposeInspectorProtocolError('Repeated singular fixed32'); return v[0]?.v as number | undefined; }
function oneFixed64(f: readonly Field[], n: number): number | undefined { const v = f.filter((x) => x.n === n && x.w === 1); if (v.length > 1) throw new ComposeInspectorProtocolError('Repeated singular fixed64'); return v[0]?.v as number | undefined; }
function repeatedVarints(f: readonly Field[], n: number): bigint[] { const direct = f.filter((x) => x.n === n && x.w === 0).map((x) => x.v as bigint); const packed = allBytes(f, n).flatMap((bytes) => { const r = new Reader(bytes), out: bigint[] = []; while (!r.end) out.push(r.varint()); return out; }); return [...direct, ...packed]; }
function stringTable(f: readonly Field[], n: number): Map<number, string> { const out = new Map<number, string>(), decoder = new TextDecoder('utf-8', { fatal: true }); for (const bytes of allBytes(f, n)) { const e = readFields(bytes), id = int32(oneVarint(e, 1) ?? 0n), value = oneBytes(e, 2, 'string table value'); if (value === undefined) continue; if (out.has(id)) throw new ComposeInspectorProtocolError('Duplicate Compose string table id'); out.set(id, decoder.decode(value)); } return out; }
function flatten(nodes: readonly ComposableNode[]): ComposableNode[] { return nodes.flatMap((node) => [node, ...flatten(node.children)]); }
function isDefined<T>(value: T | undefined): value is T { return value !== undefined; }
function zeroBounds() { return { left: 0, top: 0, right: 0, bottom: 0 }; }
function int32(v: bigint): number { const n = Number(BigInt.asUintN(32, v)); return n > 0x7fffffff ? n - 0x1_0000_0000 : n; }
function int64(v: bigint): number { return Number(BigInt.asIntN(64, v)); }
function zigzag32(v: bigint): number { return Number((v >> 1n) ^ -(v & 1n)); }
function zigzag64(v: bigint): number { return Number((v >> 1n) ^ -(v & 1n)); }
function integer(v: bigint | undefined, label: string): number { if (v === undefined || v > BigInt(Number.MAX_SAFE_INTEGER)) throw new ComposeInspectorProtocolError('Invalid ' + label); return Number(v); }
function saturatedAdd(a: number, b: number): number { return Math.max(-2147483648, Math.min(2147483647, a + b)); }
function varintField(n: number, v: bigint): Uint8Array { return concat([varint(BigInt(n << 3)), varint(v)]); }
function bytesField(n: number, v: Uint8Array): Uint8Array { return concat([varint(BigInt((n << 3) | 2)), varint(BigInt(v.length)), v]); }
function varint(v: bigint): Uint8Array { const out: number[] = []; do { const b = Number(v & 127n); v >>= 7n; out.push(v === 0n ? b : b | 128); } while (v !== 0n); return Uint8Array.from(out); }
function concat(parts: readonly Uint8Array[]): Uint8Array { const length = parts.reduce((sum, part) => sum + part.length, 0), out = new Uint8Array(length); let offset = 0; for (const part of parts) { out.set(part, offset); offset += part.length; } return out; }
class Reader { private offset = 0; constructor(private readonly data: Uint8Array) {} get end(): boolean { return this.offset === this.data.length; } varint(): bigint { let value = 0n; for (let shift = 0n; shift < 70n; shift += 7n) { if (this.offset >= this.data.length) throw new ComposeInspectorProtocolError('Truncated protobuf'); const b = this.data[this.offset++] as number; value |= BigInt(b & 127) << shift; if ((b & 128) === 0) return value; } throw new ComposeInspectorProtocolError('Oversized protobuf varint'); } bytes(): Uint8Array { const size = Number(this.varint()); if (!Number.isSafeInteger(size) || this.offset + size > this.data.length) throw new ComposeInspectorProtocolError('Truncated protobuf bytes'); const result = this.data.subarray(this.offset, this.offset + size); this.offset += size; return result; } float32(): number { if (this.offset + 4 > this.data.length) throw new ComposeInspectorProtocolError('Truncated fixed32'); const value = new DataView(this.data.buffer, this.data.byteOffset + this.offset, 4).getFloat32(0, true); this.offset += 4; return value; } float64(): number { if (this.offset + 8 > this.data.length) throw new ComposeInspectorProtocolError('Truncated fixed64'); const value = new DataView(this.data.buffer, this.data.byteOffset + this.offset, 8).getFloat64(0, true); this.offset += 8; return value; } }
