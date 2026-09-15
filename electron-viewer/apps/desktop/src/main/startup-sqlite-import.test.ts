import { createHash } from 'node:crypto';
import { access, mkdtemp, readFile, rm, truncate, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MAX_KOTLIN_STARTUP_SQLITE_BYTES,
  SQLITE_STARTUP_IMPORT_LIMITATION_WARNING,
  importKotlinStartupSqlite,
} from './startup-sqlite-import.js';

const directories: string[] = [];

async function databaseFile(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-startup-sqlite-'));
  directories.push(directory);
  return join(directory, 'startup.sqlite');
}

async function sha256(path: string): Promise<string> {
  return createHash('sha256').update(await readFile(path)).digest('hex');
}

async function exists(path: string): Promise<boolean> {
  return access(path).then(() => true).catch(() => false);
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

function createSchema(database: DatabaseSync, includeRuns = true): void {
  database.exec(`
    CREATE TABLE startup_sessions (
      id TEXT PRIMARY KEY, device_serial TEXT NOT NULL, package_name TEXT NOT NULL,
      component_name TEXT NOT NULL, requested_type TEXT NOT NULL, compilation_mode TEXT NOT NULL,
      warmup_runs INTEGER NOT NULL, measured_runs INTEGER NOT NULL, created_at TEXT NOT NULL
    );
  `);
  if (!includeRuns) return;
  database.exec(`
    CREATE TABLE startup_runs (
      id TEXT PRIMARY KEY, session_id TEXT NOT NULL, iteration INTEGER NOT NULL,
      requested_type TEXT NOT NULL, observed_type TEXT NOT NULL, status TEXT, launch_state TEXT,
      activity TEXT, this_time_ms INTEGER, total_time_ms INTEGER, wait_time_ms INTEGER,
      displayed_time_ms INTEGER, fully_drawn_time_ms INTEGER, complete INTEGER NOT NULL,
      warnings TEXT NOT NULL, am_start_output TEXT NOT NULL, event_log_output TEXT,
      ttid_source TEXT, ttid_unavailable_reason TEXT, ttfd_source TEXT, ttfd_unavailable_reason TEXT
    );
  `);
}

describe('importKotlinStartupSqlite', () => {
  it('imports all sessions read-only without promoting retained metric evidence to exact', async () => {
    const path = await databaseFile();
    const database = new DatabaseSync(path);
    createSchema(database);
    database.prepare(`
      INSERT INTO startup_sessions
      (id, device_serial, package_name, component_name, requested_type, compilation_mode, warmup_runs, measured_runs, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run('source-session', 'device-local-pseudonym', 'dev.example', 'dev.example/.MainActivity', 'COLD', 'SPEED', 1, 2, '2026-09-15T00:00:00Z');
    database.prepare(`
      INSERT INTO startup_runs
      (id, session_id, iteration, requested_type, observed_type, status, launch_state, activity,
       this_time_ms, total_time_ms, wait_time_ms, displayed_time_ms, fully_drawn_time_ms, complete,
       warnings, am_start_output, event_log_output, ttid_source, ttid_unavailable_reason, ttfd_source, ttfd_unavailable_reason)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      'warmup', 'source-session', 1, 'COLD', 'COLD', 'Status: ok', 'COLD', 'dev.example/.MainActivity',
      2, 100, 120, 110, null, 1, '', 'am start warmup', null,
      'EVENT_LOG', null, null, 'The app did not call reportFullyDrawn()',
    );
    database.prepare(`
      INSERT INTO startup_runs
      (id, session_id, iteration, requested_type, observed_type, status, launch_state, activity,
       this_time_ms, total_time_ms, wait_time_ms, displayed_time_ms, fully_drawn_time_ms, complete,
       warnings, am_start_output, event_log_output, ttid_source, ttid_unavailable_reason, ttfd_source, ttfd_unavailable_reason)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      'measured', 'source-session', 2, 'COLD', 'WARM', 'Status: ok', 'WARM', 'dev.example/.MainActivity',
      4, 140, 155, 145, 190, 1, 'first warning\nsecond warning', 'am start measured', 'event log',
      'EVENT_LOG', null, 'AGENT', null,
    );
    database.close();

    const before = await sha256(path);
    const sessions = await importKotlinStartupSqlite(path, {
      idForSourceSession: (sourceId) => 'sqlite-import-' + sourceId,
      sourceFileName: 'startup.sqlite',
    });

    expect(await sha256(path)).toBe(before);
    expect(await exists(path + '-journal')).toBe(false);
    expect(await exists(path + '-wal')).toBe(false);
    expect(await exists(path + '-shm')).toBe(false);
    expect(sessions).toHaveLength(1);
    expect(sessions[0]).toMatchObject({
      id: 'sqlite-import-source-session',
      deviceSerial: 'IMPORTED',
      origin: 'IMPORTED',
      sourceDeviceLocalId: 'device-local-pseudonym',
      sourceDatabaseSha256: before,
      sourceFileName: 'startup.sqlite',
      packageName: 'dev.example',
      componentName: 'dev.example/.MainActivity',
      config: { requestedType: 'COLD', warmupRuns: 1, measuredRuns: 2 },
    });
    expect(sessions[0]?.runs).toMatchObject([
      {
        id: 'warmup',
        measured: false,
        platform: { totalTimeMs: 100, displayedTimeMs: 110, complete: true },
        warnings: [
          'Imported from Kotlin Startup SQLite. Milestones, phases, compilation, environment, and trace evidence are not modeled by Electron; retain the source database for those details.',
          'Kotlin Startup SQLite does not retain metric evidence confidence; Electron marks retained metric evidence as INFERRED.',
        ],
        ttidEvidence: { source: 'EVENT_LOG', confidence: 'INFERRED' },
        ttfdEvidence: { confidence: 'UNAVAILABLE', unavailableReason: 'The app did not call reportFullyDrawn()' },
      },
      {
        id: 'measured',
        measured: true,
        platform: { totalTimeMs: 140, fullyDrawnTimeMs: 190, complete: true },
        ttidEvidence: { source: 'EVENT_LOG', confidence: 'INFERRED' },
        ttfdEvidence: { source: 'AGENT', confidence: 'INFERRED' },
      },
    ]);
    expect(sessions[0]?.runs[1]?.warnings).toEqual([
      'first warning',
      'second warning',
      SQLITE_STARTUP_IMPORT_LIMITATION_WARNING,
      'Kotlin Startup SQLite does not retain metric evidence confidence; Electron marks retained metric evidence as INFERRED.',
    ]);
    expect(sessions[0]?.statistics.measuredRuns).toBe(1);
  });

  it('rejects an oversized selected database before synchronously materializing it', async () => {
    const path = await databaseFile();
    await writeFile(path, '');
    await truncate(path, MAX_KOTLIN_STARTUP_SQLITE_BYTES + 1);

    await expect(importKotlinStartupSqlite(path, {
      idForSourceSession: (sourceId) => sourceId,
      sourceFileName: 'oversized.sqlite',
    })).rejects.toThrow('64 MiB import limit');
  });

  it('rejects an active WAL source so its main-database hash cannot misrepresent imported rows', async () => {
    const path = await databaseFile();
    const database = new DatabaseSync(path);
    database.exec('PRAGMA journal_mode = WAL');
    createSchema(database);
    database.prepare(`
      INSERT INTO startup_sessions
      (id, device_serial, package_name, component_name, requested_type, compilation_mode, warmup_runs, measured_runs, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run('session', 'pseudonym', 'dev.example', 'dev.example/.MainActivity', 'COLD', 'CURRENT', 0, 1, '2026-09-15T00:00:00Z');

    expect(await exists(path + '-wal')).toBe(true);
    await expect(importKotlinStartupSqlite(path, {
      idForSourceSession: (sourceId) => sourceId,
      sourceFileName: 'active.sqlite',
    })).rejects.toThrow('active sidecar files');
    database.close();
  });

  it('rejects a database missing the required Kotlin startup schema without changing it', async () => {
    const path = await databaseFile();
    const database = new DatabaseSync(path);
    createSchema(database, false);
    database.close();
    const before = await sha256(path);

    await expect(importKotlinStartupSqlite(path, {
      idForSourceSession: (sourceId) => sourceId,
      sourceFileName: 'incomplete.db',
    })).rejects.toThrow('missing required table: startup_runs');

    expect(await sha256(path)).toBe(before);
  });

  it('rejects a schema missing a required startup_runs column without changing the source', async () => {
    const path = await databaseFile();
    const database = new DatabaseSync(path);
    createSchema(database);
    database.exec('ALTER TABLE startup_runs DROP COLUMN complete');
    database.close();
    const before = await sha256(path);

    await expect(importKotlinStartupSqlite(path, {
      idForSourceSession: (sourceId) => sourceId,
      sourceFileName: 'missing-column.sqlite',
    })).rejects.toThrow('missing required column: startup_runs.complete');

    expect(await sha256(path)).toBe(before);
  });
});
