/**
 * Test fixtures for the Visible Window Views reader.
 *
 * Kept browser-safe on purpose (no node: imports): the archive writer stores
 * entries uncompressed, which the reader supports, and the tests that need the
 * deflate path recompress locally. The payload builder is a port of the Kotlin
 * EncodedHierarchyFixture, so both implementations are checked on the same bytes.
 */

const SHORT = 83;
const INT = 73;
const FLOAT = 70;
const BOOLEAN = 90;
const STRING = 82;
const MAP = 77;

export interface ArchiveEntry {
  readonly name: string;
  /** The bytes as stored: raw for method 0, deflated for method 8. */
  readonly data: Uint8Array;
  readonly method?: number;
  /** Uncompressed length; defaults to the stored length for stored entries. */
  readonly uncompressedSize?: number;
}

export class EncodedHierarchyBuilder {
  private readonly bytes: number[] = [];
  private readonly propertyIds = new Map<string, number>();

  property(name: string, value: number | boolean | string): void {
    this.writeShort(this.propertyId(name));
    this.writeValue(value);
  }

  /** Kotlin's fixture passes Float values; the raw pane renders them with a decimal point. */
  float(name: string, value: number): void {
    this.writeShort(this.propertyId(name));
    this.writeFloat(value);
  }

  map(block: () => void): void {
    this.bytes.push(MAP);
    block();
    this.writeShort(0);
  }

  nestedMap(name: string, block: () => void): void {
    this.writeShort(this.propertyId(name));
    this.map(block);
  }

  writePropertyIndex(): void {
    const nameKey = this.propertyId('__name__');
    this.bytes.push(MAP);
    this.writeShort(nameKey);
    this.writeString('propertyIndex');
    for (const [name, id] of this.propertyIds) {
      this.writeShort(id);
      this.writeString(name);
    }
    this.writeShort(0);
  }

  toBytes(): Uint8Array {
    return Uint8Array.from(this.bytes);
  }

  private propertyId(name: string): number {
    const existing = this.propertyIds.get(name);
    if (existing !== undefined) return existing;
    const id = this.propertyIds.size + 1;
    this.propertyIds.set(name, id);
    return id;
  }

  private writeValue(value: number | boolean | string): void {
    if (typeof value === 'boolean') {
      this.bytes.push(BOOLEAN, value ? 1 : 0);
      return;
    }
    if (typeof value === 'string') {
      this.writeString(value);
      return;
    }
    if (Number.isInteger(value)) this.writeInt(value);
    else this.writeFloat(value);
  }

  private writeShort(value: number): void {
    this.bytes.push(SHORT, (value >> 8) & 0xff, value & 0xff);
  }

  private writeInt(value: number): void {
    this.bytes.push(INT, (value >>> 24) & 0xff, (value >>> 16) & 0xff, (value >>> 8) & 0xff, value & 0xff);
  }

  private writeFloat(value: number): void {
    const buffer = new ArrayBuffer(4);
    new DataView(buffer).setFloat32(0, value);
    this.bytes.push(FLOAT, ...new Uint8Array(buffer));
  }

  private writeString(value: string): void {
    const encoded = new TextEncoder().encode(value);
    this.bytes.push(STRING, (encoded.length >> 8) & 0xff, encoded.length & 0xff, ...encoded);
  }
}

export const DEMO_PACKAGE = 'com.codemx.anrdemo';

export function demoHierarchyPayload(
  rootClassName = 'com.codemx.ui.RealRootLayout',
  childClassName = 'com.codemx.ui.RealTitleView',
): Uint8Array {
  const encoder = new EncodedHierarchyBuilder();
  encoder.property('window:left', 10);
  encoder.property('window:top', 20);
  encoder.map(() => {
    encoder.property('meta:__name__', rootClassName);
    encoder.property('id', 'NO_ID');
    encoder.property('layout:left', 0);
    encoder.property('layout:top', 0);
    encoder.property('layout:right', 1080);
    encoder.property('layout:bottom', 2400);
    encoder.property('scrolling:scrollX', 0);
    encoder.property('scrolling:scrollY', 0);
    encoder.float('drawing:translationX', 0);
    encoder.float('drawing:translationY', 0);
    encoder.float('drawing:translationZ', 2);
    encoder.float('drawing:elevation', 8);
    encoder.float('drawing:rotation', 5);
    encoder.float('drawing:rotationX', 1);
    encoder.float('drawing:rotationY', 2);
    encoder.float('drawing:scaleX', 1);
    encoder.float('drawing:scaleY', 1);
    encoder.float('drawing:pivotX', 540);
    encoder.float('drawing:pivotY', 1200);
    encoder.float('drawing:alpha', 1);
    encoder.property('drawing:clipBounds', 'Rect(0, 0 - 1080, 2300)');
    encoder.property('drawing:opaque', false);
    encoder.property('drawing:willNotDraw', false);
    encoder.property('drawing:hardwareAccelerated', true);
    encoder.property('drawing:layerType', 2);
    encoder.property('misc:visibility', 0);
    encoder.property('misc:enabled', true);
    encoder.property('misc:clickable', true);
    encoder.property('misc:longClickable', true);
    encoder.property('misc:selected', false);
    encoder.property('accessibility:getContentDescription()', 'Root container');
    encoder.property('focus:isFocusable', true);
    encoder.property('focus:isFocused', false);
    encoder.property('padding:paddingLeft', 16);
    encoder.property('padding:paddingTop', 24);
    encoder.property('padding:paddingRight', 16);
    encoder.property('padding:paddingBottom', 24);
    encoder.property('measurement:minWidth', 0);
    encoder.property('measurement:minHeight', 0);
    encoder.property('measurement:measuredWidth', 1080);
    encoder.property('measurement:measuredHeight', 2400);
    encoder.property('drawing:clipChildren', true);
    encoder.property('drawing:clipToPadding', false);
    encoder.nestedMap('layoutParams', () => {
      encoder.property('class', 'android.widget.FrameLayout.LayoutParams');
      encoder.property('width', -1);
      encoder.property('height', -2);
      encoder.property('leftMargin', 8);
      encoder.property('topMargin', 12);
      encoder.property('rightMargin', 8);
      encoder.property('bottomMargin', 12);
    });
    encoder.property('meta:__childCount__', 1);
    encoder.nestedMap('meta:__child__0', () => {
      encoder.property('meta:__name__', childClassName);
      encoder.property('id', DEMO_PACKAGE + ':id/title');
      encoder.property('text:mText', 'Title');
      encoder.property('layout:left', 40);
      encoder.property('layout:top', 80);
      encoder.property('layout:right', 600);
      encoder.property('layout:bottom', 160);
      encoder.property('scrolling:scrollX', 0);
      encoder.property('scrolling:scrollY', 0);
      encoder.float('drawing:translationX', 0);
      encoder.float('drawing:translationY', 0);
      encoder.float('drawing:alpha', 1);
      encoder.property('misc:visibility', 0);
      encoder.property('meta:__childCount__', 0);
    });
  });
  encoder.writePropertyIndex();
  return encoder.toBytes();
}

/** One app window, named the way the Kotlin fixture names it. */
export function demoWindowEntries(): ArchiveEntry[] {
  return [{ name: DEMO_PACKAGE + '/' + DEMO_PACKAGE + '.MainActivity', data: demoHierarchyPayload() }];
}

/** One app window with the window token the device prefixes to dump entries. */
export function demoDeviceWindowEntries(token = '42177c9'): ArchiveEntry[] {
  return [
    { name: token + ' ' + DEMO_PACKAGE + '/' + DEMO_PACKAGE + '.MainActivity', data: demoHierarchyPayload() },
  ];
}

export function demoTwoWindowEntries(): ArchiveEntry[] {
  return [
    { name: '42177c9 ' + DEMO_PACKAGE + '/' + DEMO_PACKAGE + '.MainActivity', data: demoHierarchyPayload() },
    { name: '51aa71 ' + DEMO_PACKAGE + '/' + DEMO_PACKAGE + '.ConfirmDialog', data: demoHierarchyPayload() },
    { name: '9911 other.app/other.app.MainActivity', data: demoHierarchyPayload() },
  ];
}

function uint16(value: number): number[] {
  return [value & 0xff, (value >>> 8) & 0xff];
}

function uint32(value: number): number[] {
  return [value & 0xff, (value >>> 8) & 0xff, (value >>> 16) & 0xff, (value >>> 24) & 0xff];
}

/** Writes the ZIP the device produces; entries default to method 0 (stored). */
export function zipArchive(entries: readonly ArchiveEntry[]): Uint8Array {
  const encoder = new TextEncoder();
  const locals: number[] = [];
  const directory: number[] = [];
  let offset = 0;
  for (const entry of entries) {
    const name = encoder.encode(entry.name);
    const method = entry.method ?? 0;
    const uncompressedSize = entry.uncompressedSize ?? entry.data.length;
    const header = [
      0x50, 0x4b, 0x03, 0x04,
      20, 0,
      0, 0,
      ...uint16(method),
      0, 0, 0, 0,
      0, 0, 0, 0,
      ...uint32(entry.data.length),
      ...uint32(uncompressedSize),
      ...uint16(name.length),
      0, 0,
    ];
    locals.push(...header, ...name, ...entry.data);
    directory.push(
      0x50, 0x4b, 0x01, 0x02,
      20, 0,
      20, 0,
      0, 0,
      ...uint16(method),
      0, 0, 0, 0,
      0, 0, 0, 0,
      ...uint32(entry.data.length),
      ...uint32(uncompressedSize),
      ...uint16(name.length),
      0, 0,
      0, 0,
      0, 0,
      0, 0,
      0, 0, 0, 0,
      ...uint32(offset),
      ...name,
    );
    offset += header.length + name.length + entry.data.length;
  }
  const bytes = [...locals, ...directory];
  bytes.push(
    0x50, 0x4b, 0x05, 0x06,
    0, 0,
    0, 0,
    ...uint16(entries.length),
    ...uint16(entries.length),
    ...uint32(directory.length),
    ...uint32(offset),
    0, 0,
  );
  return Uint8Array.from(bytes);
}
