import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { captureHeapDump, MAX_HEAP_DUMP_BYTES, type MemoryCaptureAdb } from './memory-capture-service.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-memory-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

const ID_SIZE = 4;

class Writer {
  private readonly chunks: Buffer[] = [];
  u1(value: number): this {
    this.chunks.push(Buffer.from([value & 0xff]));
    return this;
  }
  u2(value: number): this {
    const buffer = Buffer.alloc(2);
    buffer.writeUInt16BE(value, 0);
    this.chunks.push(buffer);
    return this;
  }
  u4(value: number): this {
    const buffer = Buffer.alloc(4);
    buffer.writeUInt32BE(value, 0);
    this.chunks.push(buffer);
    return this;
  }
  u8(value: number): this {
    const buffer = Buffer.alloc(8);
    buffer.writeBigUInt64BE(BigInt(value), 0);
    this.chunks.push(buffer);
    return this;
  }
  id(value: number): this {
    return this.u4(value);
  }
  /** HPROF strings are length-delimited, not NUL terminated. */
  utf8z(value: string): this {
    this.chunks.push(Buffer.from(value, 'utf8'));
    return this;
  }
  raw(value: Buffer): this {
    this.chunks.push(value);
    return this;
  }
  bytes(): Buffer {
    return Buffer.concat(this.chunks);
  }
}

function record(tag: number, body: Buffer): Buffer {
  const head = Buffer.alloc(9);
  head.writeUInt8(tag, 0);
  head.writeUInt32BE(0, 1);
  head.writeUInt32BE(body.length, 5);
  return Buffer.concat([head, body]);
}

function sampleDump(): Buffer {
  const header = Buffer.concat([
    Buffer.from('JAVA PROFILE 1.0.3', 'utf8'),
    Buffer.from([0]),
    new Writer().u4(ID_SIZE).u8(0).bytes(),
  ]);
  const objectField = { nameId: 2n, type: 2 };
  void objectField;
  const heap = new Writer()
    .u1(0x20)
    .id(0x100)
    .u4(0)
    .id(0)
    .id(0)
    .id(0)
    .id(0)
    .id(0)
    .id(0)
    .u4(ID_SIZE)
    .u2(0)
    .u2(0)
    .u2(1)
    .id(2)
    .u1(2)
    .u1(0xff)
    .id(0x200)
    .u1(0x21)
    .id(0x200)
    .u4(0)
    .id(0x100)
    .u4(ID_SIZE)
    .id(0x300)
    .u1(0x21)
    .id(0x300)
    .u4(0)
    .id(0x100)
    .u4(ID_SIZE)
    .id(0)
    .bytes();
  return Buffer.concat([
    header,
    record(0x01, new Writer().id(1).utf8z('com.example.Node').bytes()),
    record(0x01, new Writer().id(2).utf8z('next').bytes()),
    record(0x02, new Writer().u4(1).id(0x100).u4(0).id(1).bytes()),
    record(0x1c, heap),
    record(0x2c, Buffer.alloc(0)),
  ]);
}

interface Harness {
  readonly adb: MemoryCaptureAdb;
  readonly commands: string[][];
  readonly pulled: Array<{ remote: string; local: string }>;
  readonly dependencies: Parameters<typeof captureHeapDump>[0];
}

async function harness(overrides: { dumpFails?: boolean; pullFails?: boolean; deviceSize?: number } = {}): Promise<Harness> {
  const directory = await temporaryDirectory();
  const dump = sampleDump();
  const commands: string[][] = [];
  const pulled: Array<{ remote: string; local: string }> = [];
  const adb: MemoryCaptureAdb = {
    shell: async (args) => {
      commands.push([...args]);
      if (args[0] === 'am' && overrides.dumpFails === true) throw new Error('am dumpheap failed');
      return { stdout: '' };
    },
    pull: async (remote, local) => {
      if (overrides.pullFails === true) throw new Error('pull failed');
      pulled.push({ remote, local });
      await writeFile(local, dump);
    },
  };
  const localRoot = join(directory, 'local');
  await mkdir(localRoot, { recursive: true });
  return {
    adb,
    commands,
    pulled,
    dependencies: {
      adb,
      sizeOf: async () => overrides.deviceSize ?? dump.length,
      readFile: async (path) => new Uint8Array(await (await import('node:fs/promises')).readFile(path)),
      removeFile: async (path) => {
        await rm(path, { force: true });
      },
      temporaryPath: (name) => join(localRoot, name),
      now: () => 99,
      newId: () => 'session-1',
    },
  };
}

describe('captureHeapDump', () => {
  it('dumps, pulls, analyzes, and cleans up both sides', async () => {
    const test = await harness();
    const result = await captureHeapDump(test.dependencies, {
      serial: 'emulator-5554',
      packageName: 'com.example.app',
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.id).toBe('session-1');
    expect(result.value.capturedAtEpochMillis).toBe(99);
    expect(result.value.packageName).toBe('com.example.app');
    expect(result.value.summary.instanceCount).toBe(2);
    expect(result.value.suspects[0]?.objectId).toBe('0x300');
    expect(test.commands[0]).toEqual(['am', 'dumpheap', 'com.example.app', '/data/local/tmp/aps-session-1.hprof']);
    expect(test.pulled[0]?.remote).toBe('/data/local/tmp/aps-session-1.hprof');
    expect(test.commands).toContainEqual(['rm', '-f', '/data/local/tmp/aps-session-1.hprof']);
  });

  it('reports each failure stage with a stable code and still cleans the device', async () => {
    const dump = await harness({ dumpFails: true });
    const dumpResult = await captureHeapDump(dump.dependencies, { serial: 'SER', packageName: 'com.example.app' });
    expect(dumpResult.ok).toBe(false);
    if (!dumpResult.ok) expect(dumpResult.error.code).toBe('MEMORY_DUMP_FAILED');
    expect(dump.commands).toContainEqual(['rm', '-f', '/data/local/tmp/aps-session-1.hprof']);

    const pull = await harness({ pullFails: true });
    const pullResult = await captureHeapDump(pull.dependencies, { serial: 'SER', packageName: 'com.example.app' });
    expect(pullResult.ok).toBe(false);
    if (!pullResult.ok) expect(pullResult.error.code).toBe('MEMORY_PULL_FAILED');

    const oversized = await harness({ deviceSize: MAX_HEAP_DUMP_BYTES + 1 });
    const oversizedResult = await captureHeapDump(oversized.dependencies, {
      serial: 'SER',
      packageName: 'com.example.app',
    });
    expect(oversizedResult.ok).toBe(false);
    if (!oversizedResult.ok) expect(oversizedResult.error.code).toBe('MEMORY_DUMP_TOO_LARGE');

    const noPackage = await harness();
    const noPackageResult = await captureHeapDump(noPackage.dependencies, { serial: 'SER', packageName: '  ' });
    expect(noPackageResult.ok).toBe(false);
    if (!noPackageResult.ok) expect(noPackageResult.error.code).toBe('MEMORY_PACKAGE_REQUIRED');
  });
});
