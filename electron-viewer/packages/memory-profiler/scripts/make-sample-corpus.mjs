// Builds a sample golden corpus with the same shape the Kotlin exporter emits.
// Used to exercise the corpus consumer locally; CI generates the real corpus.
import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
// The parser's own subdirectory, matching the Kotlin exporter layout.
const out = join(here, '..', '..', '..', '.cache', 'golden-sample', 'hprof');
mkdirSync(out, { recursive: true });

const ID_SIZE = 4;

class Writer {
  constructor() {
    this.chunks = [];
  }
  u1(value) {
    this.chunks.push(Buffer.from([value & 0xff]));
    return this;
  }
  u2(value) {
    const buffer = Buffer.alloc(2);
    buffer.writeUInt16BE(value, 0);
    this.chunks.push(buffer);
    return this;
  }
  u4(value) {
    const buffer = Buffer.alloc(4);
    buffer.writeUInt32BE(value >>> 0, 0);
    this.chunks.push(buffer);
    return this;
  }
  u8(value) {
    const buffer = Buffer.alloc(8);
    buffer.writeBigUInt64BE(BigInt.asUintN(64, value), 0);
    this.chunks.push(buffer);
    return this;
  }
  id(value) {
    return this.u4(value);
  }
  utf8(value) {
    this.chunks.push(Buffer.from(value, 'utf8'));
    return this;
  }
  bytes() {
    return Buffer.concat(this.chunks);
  }
}

function record(tag, body) {
  const head = Buffer.alloc(9);
  head.writeUInt8(tag, 0);
  head.writeUInt32BE(0, 1);
  head.writeUInt32BE(body.length, 5);
  return Buffer.concat([head, body]);
}

const header = Buffer.concat([
  Buffer.from('JAVA PROFILE 1.0.3', 'utf8'),
  Buffer.from([0]),
  new Writer().u4(ID_SIZE).u8(0n).bytes(),
]);

const segment = new Writer();
segment.u1(0x20).id(0x100).u4(0).id(0).id(0).id(0).id(0).id(0).id(0);
// One object-reference field, so the instance size and payload are 4 bytes.
segment.u4(4).u2(0).u2(0).u2(1).id(2).u1(2);
segment.u1(0x01).id(0x200).id(0x900);
segment.u1(0x21).id(0x200).u4(0).id(0x100).u4(4).id(0);
segment.u1(0x23).id(0x300).u4(0).u4(4).u1(10);
for (let index = 0; index < 4; index += 1) segment.u4(index);
// HEAP_DUMP_END is a top-level record, not part of the segment body.
const body = segment.bytes();
const segmentHead = Buffer.alloc(9);
segmentHead.writeUInt8(0x1c, 0);
segmentHead.writeUInt32BE(0, 1);
segmentHead.writeUInt32BE(body.length, 5);

const bytes = Buffer.concat([
  header,
  record(0x01, new Writer().id(1).utf8('com.example.Golden').bytes()),
  record(0x01, new Writer().id(2).utf8('next').bytes()),
  record(0x02, new Writer().u4(0).id(0x100).u4(0).id(1).bytes()),
  segmentHead,
  body,
  record(0x2c, Buffer.alloc(0)),
]);

const inputFile = 'synthetic-basic.hprof';
writeFileSync(join(out, inputFile), bytes);
writeFileSync(
  join(out, 'synthetic-basic.json'),
  JSON.stringify(
    {
      parser: 'HPROF',
      case: 'synthetic-basic',
      inputFile,
      inputSha256: createHash('sha256').update(bytes).digest('hex'),
      expectations: {
        format: 'JAVA PROFILE 1.0.3',
        idSize: 4,
        classNames: ['com.example.Golden'],
        instanceCount: 1,
        instanceShallowSizes: [4],
        arrayShallowSizes: [16 + 4 * 4],
        heapNames: ['Default'],
        rootCount: 1,
        warningCount: 0,
      },
    },
    null,
    2,
  ),
);
console.log('wrote sample corpus to ' + out);
