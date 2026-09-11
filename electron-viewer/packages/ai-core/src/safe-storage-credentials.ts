/**
 * Credential store backed by Electron safeStorage.
 *
 * The Kotlin app keeps the key in the macOS Keychain and in memory everywhere
 * else. safeStorage is the equivalent seam: on macOS it is the Keychain, on
 * Windows DPAPI. It reports isEncryptionAvailable() false (or a basic_text
 * backend) when no real key is available, and then this store degrades to
 * memory only rather than writing a key to disk in the clear.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import type { CredentialStore } from './credentials.js';

export interface SafeStorageLike {
  isEncryptionAvailable(): boolean;
  encryptString(plainText: string): Buffer;
  decryptString(encrypted: Buffer): string;
  /** Electron only; absent in the test double. */
  getSelectedStorageBackend?(): string;
}

export interface SafeStorageCredentialStoreOptions {
  readonly safeStorage: SafeStorageLike;
  /** File the encrypted values live in; created on first write. */
  readonly filePath: string;
}

export const CREDENTIAL_FILE_VERSION = 1;

export class SafeStorageCredentialStore implements CredentialStore {
  private readonly safeStorage: SafeStorageLike;
  private readonly filePath: string;
  private readonly persistent: boolean;
  /** Values that survive a restart, encrypted with the OS key. */
  private readonly encrypted = new Map<string, string>();
  /** Values that do not: the fallback when there is no OS key. */
  private readonly plain = new Map<string, string>();

  constructor(options: SafeStorageCredentialStoreOptions) {
    this.safeStorage = options.safeStorage;
    this.filePath = options.filePath;
    this.persistent = isPersistentBackend(options.safeStorage);
    if (this.persistent) this.load();
  }

  /** False when only memory is used, so the UI can say so. */
  get isPersistent(): boolean {
    return this.persistent;
  }

  read(key: string): string | undefined {
    if (!this.persistent) return this.plain.get(key);
    const stored = this.encrypted.get(key);
    if (stored === undefined) return undefined;
    try {
      return this.safeStorage.decryptString(Buffer.from(stored, 'base64'));
    } catch {
      // A value written on another machine or with another key is unusable;
      // forgetting it is better than failing every later read.
      this.encrypted.delete(key);
      return undefined;
    }
  }

  write(key: string, value: string): void {
    if (key.trim().length === 0 || value.trim().length === 0) {
      throw new Error('Credential key and value must not be blank');
    }
    if (!this.persistent) {
      // Without an OS key the value stays in memory, as on non macOS today.
      this.plain.set(key, value);
      return;
    }
    this.encrypted.set(key, this.safeStorage.encryptString(value).toString('base64'));
    this.persist();
  }

  delete(key: string): void {
    if (!this.persistent) {
      this.plain.delete(key);
      return;
    }
    if (!this.encrypted.delete(key)) return;
    this.persist();
  }

  private load(): void {
    let text: string;
    try {
      text = readFileSync(this.filePath, 'utf8');
    } catch {
      return;
    }
    try {
      const parsed: unknown = JSON.parse(text);
      if (typeof parsed !== 'object' || parsed === null) return;
      const values = (parsed as { credentials?: unknown }).credentials;
      if (typeof values !== 'object' || values === null) return;
      for (const [key, value] of Object.entries(values as Record<string, unknown>)) {
        if (typeof value === 'string') this.encrypted.set(key, value);
      }
    } catch {
      // A corrupt file must not stop the app from starting.
    }
  }

  private persist(): void {
    const credentials: Record<string, string> = {};
    for (const [key, value] of this.encrypted) credentials[key] = value;
    // The values are encrypted; the file is still created owner only.
    writeFileSync(this.filePath, JSON.stringify({ version: CREDENTIAL_FILE_VERSION, credentials }), {
      encoding: 'utf8',
      mode: 0o600,
    });
  }
}

/** Electron reports basic_text when no OS keychain is wired up. */
export function isPersistentBackend(safeStorage: SafeStorageLike): boolean {
  if (!safeStorage.isEncryptionAvailable()) return false;
  const backend = safeStorage.getSelectedStorageBackend?.();
  return backend === undefined || backend !== 'basic_text';
}
