import { createHash } from 'node:crypto';
import { lstat, mkdir, mkdtemp, readFile, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  SessionPackageCodec,
  SessionPackageFormatError,
  SESSION_PACKAGE_MANIFEST,
  sessionPackageLimitsFor,
} from './session-package-codec.js';

const KOTLIN_FIXTURE = fileURLToPath(
  new URL('../../../../../desktop-viewer/simpleperf-viewer/test-fixtures/src/main/resources/sessions/golden.apsession.zip', import.meta.url),
);
const ELECTRON_FIXTURE = fileURLToPath(
  new URL('../../../../../desktop-viewer/simpleperf-viewer/export-adapters/src/test/resources/electron-session-package.apsession.zip', import.meta.url),
);
const ELECTRON_FIXTURE_OUTPUT = process.env['APS_WRITE_ELECTRON_SESSION_PACKAGE_FIXTURE'];

const ELECTRON_FIXTURE_FILES = new Map<string, Buffer>([
  ['capture-artifact.json', Buffer.from('{"schema":1,"producer":"electron"}\n', 'utf8')],
  ['evidence/opaque.bin', Buffer.from([0, 255, 3, 4, 5, 6])],
  ['session.properties', Buffer.from('source=electron-cross-runtime-fixture\n', 'utf8')],
]);

async function temporary(name: string): Promise<string> {
  return join(await mkdtemp(join(tmpdir(), 'aps-session-package-')), name);
}

function crc32(bytes: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ ((crc & 1) === 0 ? 0 : 0xedb88320);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function storedZip(entries: readonly { readonly name: string; readonly bytes: Buffer }[]): Buffer {
  const localParts: Buffer[] = [];
  const centralParts: Buffer[] = [];
  let offset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.name, 'utf8');
    const crc = crc32(entry.bytes);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6);
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(entry.bytes.length, 18); local.writeUInt32LE(entry.bytes.length, 22); local.writeUInt16LE(name.length, 26);
    localParts.push(local, name, entry.bytes);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0x800, 8);
    central.writeUInt32LE(crc, 16); central.writeUInt32LE(entry.bytes.length, 20); central.writeUInt32LE(entry.bytes.length, 24); central.writeUInt16LE(name.length, 28); central.writeUInt32LE(offset, 42);
    centralParts.push(central, name);
    offset += local.length + name.length + entry.bytes.length;
  }
  const centralSize = centralParts.reduce((sum, part) => sum + part.length, 0);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(centralSize, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...localParts, ...centralParts, end]);
}

function manifest(entries: readonly { readonly name: string; readonly bytes: Buffer }[]): Buffer {
  return Buffer.from(`schema=1\n${entries.map((entry) => `${createHash('sha256').update(entry.bytes).digest('hex')}  ${entry.name}\n`).join('')}`, 'utf8');
}

async function writeSource(root: string, files: ReadonlyMap<string, Buffer>): Promise<void> {
  for (const [path, bytes] of files) {
    const target = join(root, ...path.split('/'));
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, bytes);
  }
}

describe('SessionPackageCodec', () => {
  it('reads Kotlin’s checked-in generic session package and retains all regular files', async () => {
    const document = await new SessionPackageCodec().read(KOTLIN_FIXTURE);
    expect([...document.files.keys()]).toEqual([
      'README.txt',
      'call-tree.csv',
      'profile.sqlite',
      'report.json',
      'session.properties',
      'top-functions.csv',
    ]);
    expect(document.files.get('session.properties')?.toString('utf8')).toContain('source=generated-golden');
    expect(document.files.get('profile.sqlite')?.length).toBe(86_016);
  });

  it('retains explicit empty directories from a Kotlin-compatible package', async () => {
    const content = Buffer.from('contents');
    const archive = await temporary('empty-directory.apsession.zip');
    await writeFile(archive, storedZip([
      { name: SESSION_PACKAGE_MANIFEST, bytes: manifest([{ name: 'evidence.txt', bytes: content }]) },
      { name: 'evidence.txt', bytes: content },
      { name: 'empty/', bytes: Buffer.alloc(0) },
    ]));
    const codec = new SessionPackageCodec();
    expect((await codec.read(archive)).directories).toEqual(['empty/']);
    const imported = await codec.import(archive, await temporary('empty-directory-import'));
    expect((await lstat(join(imported.sessionDirectory, 'empty'))).isDirectory()).toBe(true);
  });

  it('exports a deterministic manifest, ignores stale manifests, and preserves unknown files on import', async () => {
    const root = await temporary('source');
    await mkdir(root, { recursive: true });
    await writeSource(root, ELECTRON_FIXTURE_FILES);
    await writeFile(join(root, SESSION_PACKAGE_MANIFEST), 'stale manifest must not be nested');
    const first = await temporary('one.apsession.zip');
    const second = await temporary('two.apsession.zip');
    const codec = new SessionPackageCodec();

    expect(await codec.export(root, first)).toEqual({ archive: first, fileCount: 3 });
    await codec.export(root, second);
    expect(await readFile(first)).toEqual(await readFile(second));

    const destination = await temporary('imports');
    const imported = await codec.import(first, destination);
    expect(imported.verifiedFiles).toBe(3);
    expect(await readFile(join(imported.sessionDirectory, 'evidence', 'opaque.bin'))).toEqual(ELECTRON_FIXTURE_FILES.get('evidence/opaque.bin'));
    expect(await lstat(join(imported.sessionDirectory, SESSION_PACKAGE_MANIFEST)).catch(() => undefined)).toBeUndefined();
  });

  it('uses Kotlin-compatible import naming and leaves a failed import without a partial destination', async () => {
    const root = await temporary('source');
    await mkdir(root, { recursive: true });
    await writeSource(root, ELECTRON_FIXTURE_FILES);
    const archive = await temporary('profile.apsession.zip');
    const codec = new SessionPackageCodec();
    await codec.export(root, archive);
    const destination = await temporary('destinations');

    expect((await codec.import(archive, destination)).sessionDirectory).toBe(join(destination, 'profile'));
    expect((await codec.import(archive, destination)).sessionDirectory).toBe(join(destination, 'profile-1'));
    await mkdir(join(destination, 'profile-2'));
    expect((await codec.import(archive, destination)).sessionDirectory).toBe(join(destination, 'profile-3'));

    const malformed = await temporary('malformed.apsession.zip');
    await writeFile(malformed, storedZip([{ name: '../escape.txt', bytes: Buffer.from('no') }]));
    await expect(codec.import(malformed, destination)).rejects.toBeInstanceOf(SessionPackageFormatError);
    expect((await readFile(join(destination, 'profile', 'capture-artifact.json'), 'utf8'))).toContain('electron');
  });

  it('rejects duplicate, undeclared, tampered, and over-limit entries before import', async () => {
    const content = Buffer.from('contents');
    const declared = [{ name: 'evidence.txt', bytes: content }];
    const manifestBytes = manifest(declared);
    const codec = new SessionPackageCodec({ maxEntryBytes: 1024, maxTotalBytes: 4_096, maxEntries: 3 });

    const duplicate = await temporary('duplicate.apsession.zip');
    await writeFile(duplicate, storedZip([
      { name: SESSION_PACKAGE_MANIFEST, bytes: manifestBytes },
      ...declared,
      ...declared,
    ]));
    await expect(codec.read(duplicate)).rejects.toThrow(/Duplicate/);

    const undeclared = await temporary('undeclared.apsession.zip');
    await writeFile(undeclared, storedZip([
      { name: SESSION_PACKAGE_MANIFEST, bytes: manifestBytes },
      ...declared,
      { name: 'undeclared.txt', bytes: Buffer.from('x') },
    ]));
    await expect(codec.read(undeclared)).rejects.toThrow(/file list/);

    const tampered = await temporary('tampered.apsession.zip');
    await writeFile(tampered, storedZip([
      { name: SESSION_PACKAGE_MANIFEST, bytes: manifestBytes },
      { name: 'evidence.txt', bytes: Buffer.from('changed') },
    ]));
    await expect(codec.read(tampered)).rejects.toThrow(/checksum/);

    const oversized = await temporary('oversized.apsession.zip');
    await writeFile(oversized, storedZip([
      { name: SESSION_PACKAGE_MANIFEST, bytes: Buffer.from('schema=1\n') },
      { name: 'too-large.txt', bytes: Buffer.from('123456789') },
    ]));
    await expect(new SessionPackageCodec({ maxEntryBytes: 8, maxTotalBytes: 32, maxEntries: 3 }).read(oversized)).rejects.toThrow(/size limit/);
  });

  it('rejects source symlinks and preserves an existing destination on failed replacement', async () => {
    const source = await temporary('source');
    await mkdir(source, { recursive: true });
    const secret = await temporary('secret.txt');
    await writeFile(secret, 'secret');
    const link = join(source, 'leak.txt');
    const linked = await symlink(secret, link).then(() => true).catch(() => false);
    if (linked) await expect(new SessionPackageCodec().export(source, await temporary('blocked.apsession.zip'))).rejects.toThrow(/symbolic link/);

    const safeSource = await temporary('safe-source');
    await mkdir(safeSource, { recursive: true });
    await writeSource(safeSource, ELECTRON_FIXTURE_FILES);
    const existing = await temporary('existing.apsession.zip');
    await writeFile(existing, 'existing archive');
    const failing = new SessionPackageCodec({}, async () => { throw new Error('move failed'); });
    await expect(failing.export(safeSource, existing)).rejects.toThrow('move failed');
    expect(await readFile(existing, 'utf8')).toBe('existing archive');
  });

  it('keeps the committed Electron fixture byte-identical to the current writer for Kotlin’s reader', async () => {
    const source = await temporary('fixture-source');
    await mkdir(source, { recursive: true });
    await writeSource(source, ELECTRON_FIXTURE_FILES);
    const generated = await temporary('electron-session-package.apsession.zip');
    await new SessionPackageCodec().export(source, generated);
    if (ELECTRON_FIXTURE_OUTPUT !== undefined) {
      await mkdir(dirname(ELECTRON_FIXTURE_OUTPUT), { recursive: true });
      await writeFile(ELECTRON_FIXTURE_OUTPUT, await readFile(generated));
    } else {
      expect(await readFile(generated)).toEqual(await readFile(ELECTRON_FIXTURE));
    }
  });

  it('derives bounded defaults and rejects invalid limit configuration', () => {
    expect(sessionPackageLimitsFor()).toMatchObject({
      maxEntryBytes: 4 * 1024 * 1024 * 1024,
      maxTotalBytes: 8 * 1024 * 1024 * 1024,
      maxEntries: 100_000,
    });
    expect(() => sessionPackageLimitsFor({ maxEntries: 0 })).toThrow(/positive safe integer/);
  });
});
