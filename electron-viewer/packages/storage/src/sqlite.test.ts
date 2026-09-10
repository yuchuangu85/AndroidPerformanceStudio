import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { afterEach, describe, expect, it } from 'vitest';
import { listTables, openReadOnlyDatabase, readUserVersion, summarizeDatabase } from './sqlite.js';

const directories: string[] = [];

async function databaseFile(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-storage-'));
  directories.push(directory);
  return join(directory, 'sessions.sqlite');
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

describe('read-only SQLite access', () => {
  it('summarizes tables and the schema user_version', async () => {
    const path = await databaseFile();
    const writable = new DatabaseSync(path);
    writable.exec('PRAGMA user_version = 3');
    writable.exec('CREATE TABLE frame_session (id TEXT PRIMARY KEY, state TEXT)');
    writable.exec("INSERT INTO frame_session (id, state) VALUES ('a', 'ok'), ('b', 'ok')");
    writable.exec('CREATE TABLE frame_sample (id TEXT)');
    writable.close();

    const summary = summarizeDatabase(path);
    expect(summary.userVersion).toBe(3);
    expect(summary.tables).toEqual([
      { name: 'frame_sample', rows: 0 },
      { name: 'frame_session', rows: 2 },
    ]);
  });

  it('rejects writes through the read-only handle', async () => {
    const path = await databaseFile();
    const writable = new DatabaseSync(path);
    writable.exec('CREATE TABLE t (id TEXT)');
    writable.close();

    const readOnly = openReadOnlyDatabase(path);
    try {
      expect(() => readOnly.exec("INSERT INTO t (id) VALUES ('x')")).toThrow();
      expect(readUserVersion(readOnly)).toBe(0);
      expect(listTables(readOnly)).toEqual([{ name: 't', rows: 0 }]);
    } finally {
      readOnly.close();
    }
  });
});
