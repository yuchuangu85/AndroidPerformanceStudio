import { mkdir, readFile, rename, unlink, writeFile } from 'node:fs/promises';
import { basename, dirname, resolve } from 'node:path';

const DEFAULT_MAXIMUM_ENTRIES = 10;

/** Durable newest-first history for main-process-owned file paths. */
export class RecentPathStore {
  private pending: Promise<void> = Promise.resolve();

  constructor(
    private readonly storageFile: string,
    private readonly maximumEntries = DEFAULT_MAXIMUM_ENTRIES,
  ) {
    if (!Number.isInteger(maximumEntries) || maximumEntries <= 0) {
      throw new Error('maximumEntries must be a positive integer');
    }
  }

  load(): Promise<string[]> {
    return this.enqueue(() => this.loadFromDisk());
  }

  record(path: string): Promise<string[]> {
    return this.enqueue(async () => {
      const normalized = normalizePath(path);
      const existing = await this.loadFromDisk();
      const updated = [normalized, ...existing.filter((entry) => entry !== normalized)].slice(
        0,
        this.maximumEntries,
      );
      await this.writeAtomically(updated);
      return updated;
    });
  }

  clear(): Promise<void> {
    return this.enqueue(async () => {
      try {
        await unlink(this.storageFile);
      } catch (error) {
        if (!isNodeError(error, 'ENOENT')) throw error;
      }
    });
  }

  private enqueue<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.pending.then(operation, operation);
    this.pending = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }

  private async loadFromDisk(): Promise<string[]> {
    try {
      const contents = await readFile(this.storageFile, 'utf8');
      const entries: string[] = [];
      for (const line of contents.split(/\r?\n/u)) {
        if (line.trim().length === 0 || line.includes('\0')) continue;
        const normalized = normalizePath(line);
        if (!entries.includes(normalized)) entries.push(normalized);
        if (entries.length === this.maximumEntries) break;
      }
      return entries;
    } catch {
      return [];
    }
  }

  private async writeAtomically(entries: readonly string[]): Promise<void> {
    const parent = dirname(this.storageFile);
    await mkdir(parent, { recursive: true });
    const temporaryFile = resolve(
      parent,
      `.${basename(this.storageFile)}.${process.pid}.${Date.now()}.${Math.random().toString(16).slice(2)}.tmp`,
    );
    try {
      await writeFile(temporaryFile, entries.join('\n') + '\n', { encoding: 'utf8', flag: 'wx' });
      await rename(temporaryFile, this.storageFile);
    } finally {
      await unlink(temporaryFile).catch(() => undefined);
    }
  }
}

function normalizePath(path: string): string {
  if (path.includes('\0')) throw new Error('path must not contain NUL');
  return resolve(path);
}

function isNodeError(error: unknown, code: string): boolean {
  return error instanceof Error && 'code' in error && error.code === code;
}
