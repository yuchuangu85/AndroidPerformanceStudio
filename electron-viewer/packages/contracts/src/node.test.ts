import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import {
  deviceLocalId,
  deviceLocalIdFromRawSerial,
  loadOrCreateDeviceIdentitySalt,
  sha256Bytes,
  sha256File,
} from './node';

const temporaryDirectories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-contracts-'));
  temporaryDirectories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

describe('sha256', () => {
  it('matches the known empty-input vector', async () => {
    expect(await sha256Bytes(new Uint8Array())).toBe(
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    );
  });

  it('hashes a file identically to its bytes', async () => {
    const directory = await temporaryDirectory();
    const file = join(directory, 'artifact.bin');
    await writeFile(file, 'hello');
    expect(await sha256File(file)).toBe(await sha256Bytes(Buffer.from('hello', 'utf8')));
  });

  it('rejects paths that are not regular files', async () => {
    const directory = await temporaryDirectory();
    await expect(sha256File(directory)).rejects.toThrow();
  });
});

describe('device identity', () => {
  it('creates a salt once and reuses it', async () => {
    const directory = await temporaryDirectory();
    const saltFile = join(directory, 'nested', 'device-identity.salt');
    const first = await loadOrCreateDeviceIdentitySalt(saltFile);
    expect(first.length).toBeGreaterThanOrEqual(16);
    const second = await loadOrCreateDeviceIdentitySalt(saltFile);
    expect(Buffer.from(second).equals(Buffer.from(first))).toBe(true);
  });

  it('derives a deterministic installation-local id', () => {
    const salt = new Uint8Array(16).fill(7);
    const first = deviceLocalIdFromRawSerial('SERIAL-1', salt);
    expect(first).toBe(deviceLocalIdFromRawSerial('SERIAL-1', salt));
    expect(first).toMatch(/^[0-9a-f]{64}$/);
    expect(deviceLocalIdFromRawSerial('SERIAL-2', salt)).not.toBe(first);
  });

  it('passes an already persisted local id through unchanged', () => {
    const salt = new Uint8Array(16).fill(7);
    const persisted = 'c'.repeat(64);
    expect(deviceLocalId(persisted, salt)).toBe(persisted);
  });

  it('rejects a too-short application salt', () => {
    expect(() => deviceLocalIdFromRawSerial('SERIAL-1', new Uint8Array(4))).toThrow();
  });
});
