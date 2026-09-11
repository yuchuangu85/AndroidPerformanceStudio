/**
 * Port of ai-core CredentialStore.kt.
 *
 * The Kotlin app keeps the API key in the macOS Keychain and in memory
 * everywhere else. The Electron build keeps the same interface so the main
 * process can back it with safeStorage (see ./node.js) or with memory.
 */
export interface CredentialStore {
  read(key: string): string | undefined;
  write(key: string, value: string): void;
  delete(key: string): void;
}

export class InMemoryCredentialStore implements CredentialStore {
  private readonly values = new Map<string, string>();

  read(key: string): string | undefined {
    return this.values.get(key);
  }

  write(key: string, value: string): void {
    if (key.trim().length === 0 || value.trim().length === 0) {
      throw new Error('Credential key and value must not be blank');
    }
    this.values.set(key, value);
  }

  delete(key: string): void {
    this.values.delete(key);
  }
}
