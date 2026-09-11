import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { SafeStorageCredentialStore, isPersistentBackend, type SafeStorageLike } from './safe-storage-credentials.js';

const directories: string[] = [];

afterEach(() => {
  while (directories.length > 0) rmSync(directories.pop() as string, { recursive: true, force: true });
});

function temporaryFile(): string {
  const directory = mkdtempSync(join(tmpdir(), 'ai-credentials-'));
  directories.push(directory);
  return join(directory, 'credentials.json');
}

/** A stand-in for Electron safeStorage; base64 is enough to spot plaintext. */
function fakeSafeStorage(available = true, backend?: string): SafeStorageLike {
  return {
    isEncryptionAvailable: () => available,
    encryptString: (plainText) => Buffer.from('enc:' + plainText, 'utf8'),
    decryptString: (encrypted) => {
      const text = encrypted.toString('utf8');
      if (!text.startsWith('enc:')) throw new Error('bad key');
      return text.slice(4);
    },
    ...(backend === undefined ? {} : { getSelectedStorageBackend: () => backend }),
  };
}

describe('SafeStorageCredentialStore', () => {
  it('persists an encrypted value across instances', () => {
    const filePath = temporaryFile();
    new SafeStorageCredentialStore({ safeStorage: fakeSafeStorage(), filePath }).write('openai', 'sk-secret');

    const onDisk = readFileSync(filePath, 'utf8');
    expect(onDisk).not.toContain('sk-secret');
    expect(new SafeStorageCredentialStore({ safeStorage: fakeSafeStorage(), filePath }).read('openai')).toBe('sk-secret');
  });

  it('deletes a stored value from memory and from disk', () => {
    const filePath = temporaryFile();
    const store = new SafeStorageCredentialStore({ safeStorage: fakeSafeStorage(), filePath });
    store.write('openai', 'sk-secret');
    store.delete('openai');
    expect(store.read('openai')).toBeUndefined();
    expect(readFileSync(filePath, 'utf8')).not.toContain('openai');
  });

  it('keeps the value in memory only when no OS key is available', () => {
    const filePath = temporaryFile();
    const store = new SafeStorageCredentialStore({ safeStorage: fakeSafeStorage(false), filePath });
    store.write('openai', 'sk-secret');
    expect(store.isPersistent).toBe(false);
    expect(store.read('openai')).toBe('sk-secret');
    expect(new SafeStorageCredentialStore({ safeStorage: fakeSafeStorage(false), filePath }).read('openai')).toBeUndefined();
  });

  it('treats the basic_text backend as no key at all', () => {
    expect(isPersistentBackend(fakeSafeStorage(true, 'basic_text'))).toBe(false);
    expect(isPersistentBackend(fakeSafeStorage(true, 'keychain'))).toBe(true);
  });

  it('starts even when the credential file is corrupt', () => {
    const filePath = temporaryFile();
    writeFileSync(filePath, 'not json');
    const store = new SafeStorageCredentialStore({ safeStorage: fakeSafeStorage(), filePath });
    expect(store.read('openai')).toBeUndefined();
    store.write('openai', 'sk-secret');
    expect(store.read('openai')).toBe('sk-secret');
  });

  it('forgets a value it cannot decrypt instead of failing every read', () => {
    const filePath = temporaryFile();
    writeFileSync(filePath, JSON.stringify({ version: 1, credentials: { openai: Buffer.from('other', 'utf8').toString('base64') } }));
    expect(new SafeStorageCredentialStore({ safeStorage: fakeSafeStorage(), filePath }).read('openai')).toBeUndefined();
  });
});
