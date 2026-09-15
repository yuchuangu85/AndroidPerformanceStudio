import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { composeCoordinate, ComposeInspectorArtifactError, ComposeInspectorArtifactResolver } from './compose-inspector-artifact.js';

function varint(value: number): number[] { const out: number[] = []; do { const byte = value & 0x7f; value >>>= 7; out.push(value === 0 ? byte : byte | 0x80); } while (value !== 0); return out; }
function aar(jar: Buffer): Buffer {
  const name = Buffer.from('inspector.jar');
  const local = Buffer.alloc(30 + name.length + jar.length);
  local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0, 8); local.writeUInt32LE(jar.length, 18); local.writeUInt32LE(jar.length, 22); local.writeUInt16LE(name.length, 26); name.copy(local, 30); jar.copy(local, 30 + name.length);
  const central = Buffer.alloc(46 + name.length);
  central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0, 8); central.writeUInt32LE(jar.length, 20); central.writeUInt32LE(jar.length, 24); central.writeUInt16LE(name.length, 28); name.copy(central, 46);
  const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(1, 8); end.writeUInt16LE(1, 10); end.writeUInt32LE(central.length, 12); end.writeUInt32LE(local.length, 16);
  return Buffer.concat([local, central, end]);
}

describe('ComposeInspectorArtifactResolver', () => {
  it('maps the Kotlin coordinates across the ui-android boundary', () => {
    expect(composeCoordinate('1.4.3')).toEqual({ group: 'androidx.compose.ui', artifact: 'ui' });
    expect(composeCoordinate('1.5.0')).toEqual({ group: 'androidx.compose.ui', artifact: 'ui-android' });
    expect(() => composeCoordinate('latest')).toThrow(ComposeInspectorArtifactError);
  });

  it('extracts an exact inspector.jar from a local AAR and reuses its verified cache', async () => {
    const root = await mkdtemp(join(tmpdir(), 'aps-compose-artifact-'));
    try {
      const source = join(root, 'ui-android-1.10.4.aar');
      await writeFile(source, aar(Buffer.from('dex payload')));
      const resolver = new ComposeInspectorArtifactResolver({ cacheDirectory: join(root, 'cache') });
      const resolved = await resolver.resolve('1.10.4', source);
      expect(await readFile(resolved.jarPath, 'utf8')).toBe('dex payload');
      expect(resolved.identity).toMatchObject({ artifact: 'ui-android', version: '1.10.4', source: 'explicit-local' });
      expect((await resolver.resolve('1.10.4')).identity.source).toBe('aps-cache');
    } finally { await rm(root, { recursive: true, force: true }); }
  });

  it('rejects wrong archives rather than caching arbitrary AAR bytes', async () => {
    const root = await mkdtemp(join(tmpdir(), 'aps-compose-artifact-'));
    try {
      const source = join(root, 'ui-1.4.0.aar');
      await writeFile(source, Buffer.from(varint(1)));
      const resolver = new ComposeInspectorArtifactResolver({ cacheDirectory: join(root, 'cache'), repositories: [] });
      await expect(resolver.resolve('1.4.0', source)).rejects.toThrow('AAR ZIP');
    } finally { await rm(root, { recursive: true, force: true }); }
  });
});
