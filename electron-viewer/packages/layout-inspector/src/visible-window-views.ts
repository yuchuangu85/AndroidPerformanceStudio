import type { Bounds, EdgeInsets, ViewAttributes, ViewNode } from './model.js';
import type { WindowSnapshot, WindowType } from './snapshot.js';

/**
 * Port of the Kotlin VisibleWindowHierarchyParser: the hierarchy the reference
 * application reads from a device.
 *
 * `cmd window dump-visible-window-views` returns a ZIP with one entry per
 * visible window, each holding the ViewServer encoding of the real View tree.
 * The encoding is a flat, self-describing value stream: every value starts with
 * a signature byte, maps hold short-keyed entries terminated by key 0, and the
 * last map in the stream is the property-name index that names every key.
 *
 * This is the difference that matters for parity. uiautomator's dump describes
 * the accessibility tree and carries a dozen booleans; this dump describes the
 * View tree and carries the full attribute set (layout, drawing, scrolling,
 * padding, measured sizes, layout params) that the details pane renders.
 */

const MAX_DEPTH = 512;
const MAX_STRING_BYTES = 32_767;
const MAX_VALUES = 1_000_000;
const MAX_ENTRY_BYTES = 16 * 1024 * 1024;

const CHILD_PREFIX = 'meta:__child__';
const NAME_PROPERTY = 'meta:__name__';
const VISIBLE = 0;

const SYSTEM_UI_PACKAGE = 'com.android.systemui';
const SYSTEM_UI_WINDOW_TITLES = [
  'BackPanel',
  'Bouncer',
  'GlobalActions',
  'HeadsUp',
  'Keyguard',
  'NavigationBar',
  'NotificationShade',
  'PipMenu',
  'ScreenDecor',
  'Screenshot',
  'Shell',
  'StatusBar',
  'Taskbar',
  'VolumeDialog',
];

/** A float that must render like Kotlin's Float.toString rather than JavaScript's. */
class Float32Value {
  constructor(readonly value: number) {}
}

class Float64Value {
  constructor(readonly value: number) {}
}

interface EncodedMap {
  readonly map: Map<number, EncodedValue>;
}

type EncodedValue = boolean | number | bigint | string | Float32Value | Float64Value | EncodedMap;

function isMap(value: EncodedValue | undefined): value is EncodedMap {
  return typeof value === 'object' && value !== null && 'map' in value;
}

class EncodedReader {
  private offset = 0;
  private valueCount = 0;

  constructor(private readonly bytes: Uint8Array) {}

  get exhausted(): boolean {
    return this.offset >= this.bytes.length;
  }

  readValue(depth: number): EncodedValue {
    if (depth > MAX_DEPTH) throw new Error('Encoded hierarchy is too deeply nested');
    this.valueCount += 1;
    if (this.valueCount > MAX_VALUES) throw new Error('Encoded hierarchy has too many values');
    const signature = String.fromCharCode(this.readUint8());
    switch (signature) {
      case 'Z':
        return this.readUint8() !== 0;
      case 'B':
        return this.readInt8();
      case 'S':
        return this.readInt16();
      case 'I':
        return this.readInt32();
      case 'J':
        return this.readInt64();
      case 'F':
        return new Float32Value(this.readFloat32());
      case 'D':
        return new Float64Value(this.readFloat64());
      case 'R':
        return this.readString();
      case 'M':
        return this.readMap(depth + 1);
      default:
        throw new Error('Unsupported encoded hierarchy type: ' + signature);
    }
  }

  private readMap(depth: number): EncodedMap {
    const map = new Map<number, EncodedValue>();
    for (;;) {
      const key = this.readValue(depth);
      if (typeof key !== 'number') throw new Error('Encoded hierarchy map key is not a short');
      if (key === 0) return { map };
      map.set(key, this.readValue(depth));
    }
  }

  private readString(): string {
    const length = this.readUint16();
    if (length > MAX_STRING_BYTES) throw new Error('Encoded hierarchy string is too large');
    const start = this.offset;
    this.offset += length;
    if (this.offset > this.bytes.length) throw new Error('Encoded hierarchy is truncated');
    return new TextDecoder().decode(this.bytes.subarray(start, this.offset));
  }

  private require(count: number): number {
    const start = this.offset;
    if (start + count > this.bytes.length) throw new Error('Encoded hierarchy is truncated');
    this.offset += count;
    return start;
  }

  private readUint8(): number {
    const start = this.require(1);
    return this.bytes[start] as number;
  }

  private readInt8(): number {
    const start = this.require(1);
    return new DataView(this.bytes.buffer, this.bytes.byteOffset + start, 1).getInt8(0);
  }

  private readUint16(): number {
    const start = this.require(2);
    return new DataView(this.bytes.buffer, this.bytes.byteOffset + start, 2).getUint16(0);
  }

  private readInt16(): number {
    const start = this.require(2);
    return new DataView(this.bytes.buffer, this.bytes.byteOffset + start, 2).getInt16(0);
  }

  private readInt32(): number {
    const start = this.require(4);
    return new DataView(this.bytes.buffer, this.bytes.byteOffset + start, 4).getInt32(0);
  }

  private readInt64(): bigint {
    const start = this.require(8);
    return new DataView(this.bytes.buffer, this.bytes.byteOffset + start, 8).getBigInt64(0);
  }

  private readFloat32(): number {
    const start = this.require(4);
    return new DataView(this.bytes.buffer, this.bytes.byteOffset + start, 4).getFloat32(0);
  }

  private readFloat64(): number {
    const start = this.require(8);
    return new DataView(this.bytes.buffer, this.bytes.byteOffset + start, 8).getFloat64(0);
  }
}

export interface DecodedHierarchy {
  /** Window properties that precede the root, for example `window:left`. */
  readonly prefix: ReadonlyMap<string, EncodedValue>;
  readonly root: EncodedMap;
  readonly propertyNames: ReadonlyMap<number, string>;
}

/** Decodes one window payload: prefix values, the root map, then the name index. */
export function decodeEncodedHierarchy(bytes: Uint8Array): DecodedHierarchy {
  const reader = new EncodedReader(bytes);
  const values: EncodedValue[] = [];
  while (!reader.exhausted) values.push(reader.readValue(0));

  const rootIndex = values.findIndex(isMap);
  if (rootIndex < 0) throw new Error('Encoded hierarchy has no root view');
  const root = values[rootIndex] as EncodedMap;

  let propertyIndex: EncodedMap | undefined;
  for (let index = rootIndex + 1; index < values.length; index += 1) {
    const value = values[index];
    if (isMap(value)) propertyIndex = value;
  }
  if (propertyIndex === undefined) throw new Error('Encoded hierarchy has no property index');

  const propertyNames = new Map<number, string>();
  for (const [id, value] of propertyIndex.map) {
    if (typeof value === 'string') propertyNames.set(id, value);
  }

  const prefix = new Map<string, EncodedValue>();
  for (let index = 0; index + 1 < rootIndex; index += 2) {
    const id = values[index];
    const value = values[index + 1];
    if (typeof id !== 'number' || value === undefined) continue;
    const name = propertyNames.get(id);
    if (name !== undefined) prefix.set(name, value);
  }
  return { prefix, root, propertyNames };
}

/** Names every short key in a map, dropping keys the index does not know. */
function namedProperties(map: EncodedMap, propertyNames: ReadonlyMap<number, string>): Map<string, EncodedValue> {
  const named = new Map<string, EncodedValue>();
  for (const [id, value] of map.map) {
    const name = propertyNames.get(id);
    if (name !== undefined) named.set(name, value);
  }
  return named;
}

function asNumber(value: EncodedValue | undefined): number | undefined {
  if (typeof value === 'number') return value;
  if (typeof value === 'bigint') return Number(value);
  if (value instanceof Float32Value || value instanceof Float64Value) return value.value;
  return undefined;
}

function intOf(properties: ReadonlyMap<string, EncodedValue>, name: string): number {
  return Math.trunc(asNumber(properties.get(name)) ?? 0);
}

function intOrNull(properties: ReadonlyMap<string, EncodedValue>, name: string): number | undefined {
  const value = asNumber(properties.get(name));
  return value === undefined ? undefined : Math.trunc(value);
}

function floatOrNull(properties: ReadonlyMap<string, EncodedValue>, name: string): number | undefined {
  return asNumber(properties.get(name));
}

function numberOr(properties: ReadonlyMap<string, EncodedValue>, name: string, fallback: number): number {
  return asNumber(properties.get(name)) ?? fallback;
}

function booleanOrNull(properties: ReadonlyMap<string, EncodedValue>, name: string): boolean | undefined {
  const value = properties.get(name);
  return typeof value === 'boolean' ? value : undefined;
}

function stringOrNull(properties: ReadonlyMap<string, EncodedValue>, name: string): string | undefined {
  const value = properties.get(name);
  if (typeof value !== 'string') return undefined;
  if (value.trim().length === 0 || value === 'null') return undefined;
  return value;
}

/** Kotlin renders a Float with a decimal point; String(8) would lose it. */
function renderFloat(value: number): string {
  if (Number.isInteger(value)) return value.toFixed(1);
  return String(value);
}

function renderRawValue(value: EncodedValue): string {
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'string') return value;
  if (typeof value === 'bigint') return value.toString();
  if (value instanceof Float32Value || value instanceof Float64Value) return renderFloat(value.value);
  return String(value);
}

function clampOrder(name: string): number {
  const suffix = name.slice(CHILD_PREFIX.length);
  return /^-?\d+$/.test(suffix) ? Number(suffix) : Number.NEGATIVE_INFINITY;
}

function toVisibilityLabel(value: number): string {
  if (value === 0) return 'VISIBLE';
  if (value === 4) return 'INVISIBLE';
  if (value === 8) return 'GONE';
  return 'UNKNOWN(' + String(value) + ')';
}

function toLayerTypeLabel(value: number): string {
  if (value === 0) return 'NONE';
  if (value === 1) return 'SOFTWARE';
  if (value === 2) return 'HARDWARE';
  return 'UNKNOWN(' + String(value) + ')';
}

const RECT_PATTERN = /^Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)$/;

function toBoundsOrNull(value: string): Bounds | undefined {
  const match = RECT_PATTERN.exec(value);
  if (match === null) return undefined;
  return {
    left: Number(match[1]),
    top: Number(match[2]),
    right: Number(match[3]),
    bottom: Number(match[4]),
  };
}

function rawPropertiesOf(
  properties: ReadonlyMap<string, EncodedValue>,
  propertyNames: ReadonlyMap<number, string>,
): Record<string, string> {
  const raw: Record<string, string> = {};
  for (const [name, value] of properties) {
    if (name === 'layoutParams' && isMap(value)) {
      const layoutParams = namedProperties(value, propertyNames);
      const className = layoutParams.get('class') ?? value.map.get(3);
      if (className !== undefined && !isMap(className)) {
        raw['layoutParams:class'] = renderRawValue(className);
      }
      for (const [paramName, paramValue] of layoutParams) {
        if (!isMap(paramValue)) raw['layoutParams:' + paramName] = renderRawValue(paramValue);
      }
      continue;
    }
    if (name.startsWith('meta:') || isMap(value)) continue;
    raw[name] = renderRawValue(value);
  }
  const sorted: Record<string, string> = {};
  for (const key of Object.keys(raw).sort()) sorted[key] = raw[key] as string;
  return sorted;
}

function toViewAttributes(
  properties: ReadonlyMap<string, EncodedValue>,
  propertyNames: ReadonlyMap<number, string>,
): ViewAttributes {
  const encodedLayoutParams = properties.get('layoutParams');
  const layoutParams = isMap(encodedLayoutParams)
    ? namedProperties(encodedLayoutParams, propertyNames)
    : new Map<string, EncodedValue>();
  const layoutParamsClass =
    (layoutParams.get('class') as string | undefined) ??
    (isMap(encodedLayoutParams) ? (encodedLayoutParams.map.get(3) as string | undefined) : undefined);
  const elevation = floatOrNull(properties, 'drawing:elevation');
  const translationZ = floatOrNull(properties, 'drawing:translationZ');
  const background = properties.get('drawing:background');
  const foreground = properties.get('drawing:foreground');
  const nestedClassName = (value: EncodedValue | undefined): string | undefined =>
    isMap(value) ? (namedProperties(value, propertyNames).get('class') as string | undefined) : undefined;
  const padding: EdgeInsets = {
    left: intOf(properties, 'padding:paddingLeft'),
    top: intOf(properties, 'padding:paddingTop'),
    right: intOf(properties, 'padding:paddingRight'),
    bottom: intOf(properties, 'padding:paddingBottom'),
  };
  const margin: EdgeInsets | undefined =
    layoutParams.size > 0
      ? {
          left: intOf(layoutParams, 'leftMargin'),
          top: intOf(layoutParams, 'topMargin'),
          right: intOf(layoutParams, 'rightMargin'),
          bottom: intOf(layoutParams, 'bottomMargin'),
        }
      : undefined;
  const clipBounds = typeof properties.get('drawing:clipBounds') === 'string'
    ? toBoundsOrNull(properties.get('drawing:clipBounds') as string)
    : undefined;
  const visibility = intOrNull(properties, 'misc:visibility');
  const layoutParamsClassName = typeof layoutParamsClass === 'string' && layoutParamsClass.trim().length > 0
    ? layoutParamsClass
    : undefined;
  const attributes: Record<string, unknown> = {
    rawProperties: rawPropertiesOf(properties, propertyNames),
    padding,
    layoutBounds: {
      left: intOf(properties, 'layout:left'),
      top: intOf(properties, 'layout:top'),
      right: intOf(properties, 'layout:right'),
      bottom: intOf(properties, 'layout:bottom'),
    },
  };
  // Every other field is optional in the protocol: absent means the device did
  // not report it, which the details pane renders as a dash.
  const put = (key: string, value: unknown): void => {
    if (value !== undefined) attributes[key] = value;
  };
  const stringValue = (value: EncodedValue | undefined): string | undefined =>
    typeof value === 'string' && value.trim().length > 0 && value !== 'null' ? value : undefined;

  if (visibility !== undefined) put('visibility', toVisibilityLabel(visibility));
  put('elevation', elevation);
  put(
    'z',
    elevation !== undefined || translationZ !== undefined ? (elevation ?? 0) + (translationZ ?? 0) : undefined,
  );
  put('translationX', floatOrNull(properties, 'drawing:translationX'));
  put('translationY', floatOrNull(properties, 'drawing:translationY'));
  put('translationZ', translationZ);
  put('rotation', floatOrNull(properties, 'drawing:rotation'));
  put('rotationX', floatOrNull(properties, 'drawing:rotationX'));
  put('rotationY', floatOrNull(properties, 'drawing:rotationY'));
  put('scaleX', floatOrNull(properties, 'drawing:scaleX'));
  put('scaleY', floatOrNull(properties, 'drawing:scaleY'));
  put('pivotX', floatOrNull(properties, 'drawing:pivotX'));
  put('pivotY', floatOrNull(properties, 'drawing:pivotY'));
  put('margin', margin);
  put('layoutWidth', intOrNull(layoutParams, 'width'));
  put('layoutHeight', intOrNull(layoutParams, 'height'));
  put('layoutParamsClass', layoutParamsClassName);
  put('measuredWidth', intOrNull(properties, 'measurement:measuredWidth'));
  put('measuredHeight', intOrNull(properties, 'measurement:measuredHeight'));
  put('minWidth', intOrNull(properties, 'measurement:minWidth'));
  put('minHeight', intOrNull(properties, 'measurement:minHeight'));
  put('scrollX', intOrNull(properties, 'scrolling:scrollX'));
  put('scrollY', intOrNull(properties, 'scrolling:scrollY'));
  put('clipBounds', clipBounds);
  put('clipChildren', booleanOrNull(properties, 'drawing:clipChildren'));
  put('clipToPadding', booleanOrNull(properties, 'drawing:clipToPadding'));
  put('background', stringValue(background) ?? nestedClassName(background));
  put('backgroundColor', stringOrNull(properties, 'drawing:backgroundColor'));
  put('foreground', stringValue(foreground) ?? nestedClassName(foreground));
  put('opaque', booleanOrNull(properties, 'drawing:opaque'));
  put('willNotDraw', booleanOrNull(properties, 'drawing:willNotDraw'));
  put('hardwareAccelerated', booleanOrNull(properties, 'drawing:hardwareAccelerated'));
  const layerType = intOrNull(properties, 'drawing:layerType');
  if (layerType !== undefined) put('layerType', toLayerTypeLabel(layerType));
  put('enabled', booleanOrNull(properties, 'misc:enabled'));
  put('clickable', booleanOrNull(properties, 'misc:clickable'));
  put('longClickable', booleanOrNull(properties, 'misc:longClickable'));
  put('focusable', booleanOrNull(properties, 'focus:isFocusable'));
  put('focused', booleanOrNull(properties, 'focus:isFocused'));
  put('selected', booleanOrNull(properties, 'misc:selected'));
  put(
    'contentDescription',
    stringOrNull(properties, 'accessibility:getContentDescription()') ??
      stringOrNull(properties, 'contentDescription'),
  );
  return attributes as unknown as ViewAttributes;
}

interface DecodedNodeContext {
  readonly propertyNames: ReadonlyMap<number, string>;
}

function toViewNode(
  map: EncodedMap,
  context: DecodedNodeContext,
  path: string,
  parentLeft: number,
  parentTop: number,
  parentScrollX: number,
  parentScrollY: number,
  parentVisible: boolean,
): ViewNode {
  const properties = namedProperties(map, context.propertyNames);
  const left = intOf(properties, 'layout:left');
  const top = intOf(properties, 'layout:top');
  const right = intOf(properties, 'layout:right');
  const bottom = intOf(properties, 'layout:bottom');
  const absoluteLeft =
    parentLeft - parentScrollX + left + Math.round(numberOr(properties, 'drawing:translationX', 0));
  const absoluteTop =
    parentTop - parentScrollY + top + Math.round(numberOr(properties, 'drawing:translationY', 0));
  const alpha = numberOr(properties, 'drawing:alpha', 1);
  const visible = parentVisible && intOf(properties, 'misc:visibility') === VISIBLE && alpha > 0;

  const rawId = properties.get('id');
  const resourceName =
    typeof rawId === 'string' && rawId.trim().length > 0 && rawId !== 'NO_ID' ? rawId : undefined;

  const childEntries: Array<{ name: string; value: EncodedMap }> = [];
  for (const [name, value] of properties) {
    if (name.startsWith(CHILD_PREFIX) && isMap(value)) childEntries.push({ name, value });
  }
  childEntries.sort((first, second) => clampOrder(first.name) - clampOrder(second.name));
  const children = childEntries.map((entry, index) =>
    toViewNode(
      entry.value,
      context,
      path + '/' + String(index),
      absoluteLeft,
      absoluteTop,
      intOf(properties, 'scrolling:scrollX'),
      intOf(properties, 'scrolling:scrollY'),
      visible,
    ),
  );

  const className = stringOrNull(properties, NAME_PROPERTY) ?? 'android.view.View';
  const text = stringOrNull(properties, 'text:text') ?? stringOrNull(properties, 'text:mText');
  return {
    type: 'view',
    id: path,
    className,
    bounds: {
      left: absoluteLeft,
      top: absoluteTop,
      right: absoluteLeft + Math.max(0, right - left),
      bottom: absoluteTop + Math.max(0, bottom - top),
    },
    visible,
    alpha,
    children,
    ...(resourceName !== undefined ? { resourceName } : {}),
    ...(text !== undefined ? { text } : {}),
    attributes: toViewAttributes(properties, context.propertyNames),
  };
}

function decodeWindowEntry(bytes: Uint8Array): ViewNode {
  const document = decodeEncodedHierarchy(bytes);
  const parentLeft = Math.trunc(asNumber(document.prefix.get('window:left')) ?? 0);
  const parentTop = Math.trunc(asNumber(document.prefix.get('window:top')) ?? 0);
  return toViewNode(document.root, { propertyNames: document.propertyNames }, 'root', parentLeft, parentTop, 0, 0, true);
}

function nodeCount(node: ViewNode): number {
  let total = 1;
  for (const child of node.children) {
    total += child.type === 'view' ? nodeCount(child) : 0;
  }
  return total;
}

export function countSnapshotNodes(node: ViewNode): number {
  return nodeCount(node);
}

/** Namespaces ids per window, the way the reference keeps the panes independent. */
function namespace(node: ViewNode, windowId: string): ViewNode {
  return {
    ...node,
    id: node.id.startsWith(windowId + '/') ? node.id : windowId + '/' + node.id,
    children: node.children.map((child) => (child.type === 'view' ? namespace(child, windowId) : child)),
  };
}

function substringAfter(value: string, delimiter: string): string {
  const index = value.indexOf(delimiter);
  return index < 0 ? value : value.slice(index + delimiter.length);
}

function substringBefore(value: string, delimiter: string): string {
  const index = value.indexOf(delimiter);
  return index < 0 ? value : value.slice(0, index);
}

function substringAfterLast(value: string, delimiter: string): string {
  const index = value.lastIndexOf(delimiter);
  return index < 0 ? value : value.slice(index + delimiter.length);
}

function entryTitle(entryName: string): string {
  return substringAfterLast(substringAfter(entryName, ' '), '/');
}

function isLauncherTaskbarWindow(entryName: string): boolean {
  const lowered = entryName.toLowerCase();
  return lowered.includes('launcher') && lowered.includes('taskbar') && !lowered.includes(SYSTEM_UI_PACKAGE);
}

function matchesTargetPackage(entryName: string, packageName: string): boolean {
  if (entryName.includes(packageName)) return true;
  if (packageName !== SYSTEM_UI_PACKAGE) return false;
  if (SYSTEM_UI_WINDOW_TITLES.some((title) => entryTitle(entryName).startsWith(title))) return true;
  return isLauncherTaskbarWindow(entryName);
}

function jvmHashCode(value: string): number {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = (Math.imul(31, hash) + value.charCodeAt(index)) | 0;
  }
  return hash;
}

function windowIdOf(entryName: string): string {
  const token = substringBefore(substringBefore(entryName, ' '), '/').replace(/[^A-Za-z0-9._-]/g, '_');
  const stable = token.length > 0 ? token : (jvmHashCode(entryName) >>> 0).toString(16);
  return 'window:' + stable;
}

function windowTitleOf(entryName: string, packageName: string): string {
  const component = substringAfterLast(substringAfterLast(entryName, '/'), ' ');
  const withoutPackage = component.startsWith(packageName + '.')
    ? component.slice(packageName.length + 1)
    : component.startsWith(packageName)
      ? component.slice(packageName.length)
      : component;
  const trimmed = withoutPackage.replace(/^[./]+/, '');
  const title = substringAfterLast(trimmed, '.');
  return title.trim().length > 0 ? title : 'Window';
}

function windowTypeOf(entryName: string): WindowType {
  const lowered = entryName.toLowerCase();
  if (lowered.includes('activity')) return 'ACTIVITY';
  if (lowered.includes('popup')) return 'POPUP';
  if (lowered.includes('dialog')) return 'DIALOG';
  return 'OTHER';
}

export interface ZipEntryLike {
  readonly name: string;
  readonly data: Uint8Array;
}

/**
 * Minimal ZIP reader for the dump archive.
 *
 * The device writes the archive straight into a pipe, so the local headers may
 * carry zeroed sizes and a trailing data descriptor; the central directory is
 * the only place with trustworthy sizes, which is what this reads.
 */
export async function readZipEntries(bytes: Uint8Array): Promise<ZipEntryLike[]> {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let eocd = -1;
  const lowest = Math.max(0, bytes.length - 65_557);
  for (let offset = bytes.length - 22; offset >= lowest; offset -= 1) {
    if (view.getUint32(offset, true) === 0x06054b50) {
      eocd = offset;
      break;
    }
  }
  if (eocd < 0) throw new Error('Visible window views is not a ZIP archive');

  const entryCount = view.getUint16(eocd + 10, true);
  const directoryOffset = view.getUint32(eocd + 16, true);
  const entries: ZipEntryLike[] = [];
  let cursor = directoryOffset;
  for (let index = 0; index < entryCount; index += 1) {
    if (cursor + 46 > bytes.length || view.getUint32(cursor, true) !== 0x02014b50) {
      throw new Error('Visible window views has a malformed central directory');
    }
    const method = view.getUint16(cursor + 10, true);
    const compressedSize = view.getUint32(cursor + 20, true);
    const uncompressedSize = view.getUint32(cursor + 24, true);
    const nameLength = view.getUint16(cursor + 28, true);
    const extraLength = view.getUint16(cursor + 30, true);
    const commentLength = view.getUint16(cursor + 32, true);
    const localOffset = view.getUint32(cursor + 42, true);
    const name = new TextDecoder().decode(bytes.subarray(cursor + 46, cursor + 46 + nameLength));
    cursor += 46 + nameLength + extraLength + commentLength;

    if (compressedSize === 0xffffffff || uncompressedSize === 0xffffffff || localOffset === 0xffffffff) {
      throw new Error('Visible window views entry uses ZIP64, which is not supported');
    }
    if (view.getUint32(localOffset, true) !== 0x04034b50) {
      throw new Error('Visible window views has a malformed local header');
    }
    const localNameLength = view.getUint16(localOffset + 26, true);
    const localExtraLength = view.getUint16(localOffset + 28, true);
    const dataStart = localOffset + 30 + localNameLength + localExtraLength;
    const data = bytes.subarray(dataStart, dataStart + compressedSize);
    if (uncompressedSize > MAX_ENTRY_BYTES) {
      throw new Error('Visible window views entry is too large: ' + name);
    }
    entries.push({ name, data: method === 0 ? data : await inflateRaw(data) });
  }
  return entries;
}

async function inflateRaw(data: Uint8Array): Promise<Uint8Array> {
  // DecompressionStream is a web standard, so this module stays usable from both
  // the Electron main process and the renderer bundle.
  const stream = new DecompressionStream('deflate-raw');
  const writer = stream.writable.getWriter();
  // Copied into a fresh view so the chunk is a plain ArrayBuffer view, which is
  // what both the Node and the DOM typings accept.
  const chunk = new Uint8Array(data.length);
  chunk.set(data);
  await writer.write(chunk);
  await writer.close();
  return new Uint8Array(await new Response(stream.readable).arrayBuffer());
}

/**
 * Parses every window in the dump that belongs to the package, in archive order.
 * Ids are `window:<token>/root/...`, so two windows never share a node id.
 */
export function parseVisibleWindowViews(zipEntries: readonly ZipEntryLike[], packageName: string): WindowSnapshot[] {
  const windows: WindowSnapshot[] = [];
  for (const entry of zipEntries) {
    if (!matchesTargetPackage(entry.name, packageName)) continue;
    let root: ViewNode;
    try {
      root = decodeWindowEntry(entry.data);
    } catch {
      // A window the device could not encode is skipped, not fatal.
      continue;
    }
    const id = windowIdOf(entry.name);
    const namespaced = namespace(root, id);
    windows.push({
      id,
      title: windowTitleOf(entry.name, packageName),
      type: windowTypeOf(entry.name),
      bounds: namespaced.bounds,
      root: namespaced,
    });
  }
  return windows;
}

export class VisibleWindowViewsUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'VisibleWindowViewsUnavailableError';
  }
}

export async function parseVisibleWindowViewsArchive(
  zipBytes: Uint8Array,
  packageName: string,
): Promise<WindowSnapshot[]> {
  if (zipBytes.length === 0) {
    throw new VisibleWindowViewsUnavailableError('Visible window views dump is empty');
  }
  const windows = parseVisibleWindowViews(await readZipEntries(zipBytes), packageName);
  if (windows.length === 0) {
    throw new VisibleWindowViewsUnavailableError(
      'Visible window views has no decodable window for ' + packageName,
    );
  }
  return windows;
}

/** The window the panes open on: the richest tree, ties going to the first. */
export function selectDefaultWindow(windows: readonly WindowSnapshot[]): WindowSnapshot {
  let best: WindowSnapshot | undefined;
  let bestCount = -1;
  for (const window of windows) {
    if (window.root.type !== 'view') continue;
    const count = nodeCount(window.root);
    if (count > bestCount) {
      best = window;
      bestCount = count;
    }
  }
  const resolved = best ?? windows[0];
  if (resolved === undefined) throw new VisibleWindowViewsUnavailableError('No window to select');
  return resolved;
}

export function containsComposeHost(root: ViewNode): boolean {
  if (root.className.endsWith('.ComposeView') || root.className.endsWith('.AndroidComposeView')) return true;
  return root.children.some((child) => child.type === 'view' && containsComposeHost(child));
}
