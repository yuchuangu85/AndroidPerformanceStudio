import { DatabaseSync } from 'node:sqlite';

export interface SqliteTableSummary {
  readonly name: string;
  readonly rows: number;
}

export interface SqliteDatabaseSummary {
  readonly userVersion: number;
  readonly tables: readonly SqliteTableSummary[];
}

/**
 * Opens an existing SQLite database read-only. The migrated profiler stores are
 * read through this during the transition, so a bug here must never mutate them.
 */
export function openReadOnlyDatabase(path: string): DatabaseSync {
  return new DatabaseSync(path, { readOnly: true });
}

export function readUserVersion(database: DatabaseSync): number {
  const row = database.prepare('PRAGMA user_version').get() as { user_version?: number } | undefined;
  return Number(row?.user_version ?? 0);
}

export function listTables(database: DatabaseSync): SqliteTableSummary[] {
  const rows = database
    .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
    .all() as { name: string }[];
  return rows.map((row) => ({
    name: row.name,
    rows: countRows(database, row.name),
  }));
}

function countRows(database: DatabaseSync, table: string): number {
  // Table names come from sqlite_master and are quoted to avoid injection.
  const quoted = '"' + table.replace(/"/g, '""') + '"';
  const row = database.prepare('SELECT COUNT(*) AS count FROM ' + quoted).get() as { count?: number } | undefined;
  return Number(row?.count ?? 0);
}

export function summarizeDatabase(path: string): SqliteDatabaseSummary {
  const database = openReadOnlyDatabase(path);
  try {
    return { userVersion: readUserVersion(database), tables: listTables(database) };
  } finally {
    database.close();
  }
}
