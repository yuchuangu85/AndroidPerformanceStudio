import { lstat } from 'node:fs/promises';
import { sha256File } from '@aps/contracts/node';
import { openReadOnlyDatabase } from '@aps/storage';
import {
  DEFAULT_STARTUP_EXPERIMENT,
  createStartupSession,
  type MetricEvidence,
  type StartupRun,
  type StartupSession,
  type StartupSource,
  type StartupType,
} from '@aps/startup-profiler';
import type { DatabaseSync } from 'node:sqlite';

const STARTUP_TYPES = new Set<StartupType>(['COLD', 'WARM', 'HOT', 'UNKNOWN']);
const STARTUP_SOURCES = new Set<StartupSource>(['AM_START', 'AGENT', 'EVENT_LOG', 'PERFETTO']);
/** Bounds sync SQLite materialization on Electron's main-process import path. */
export const MAX_KOTLIN_STARTUP_SQLITE_BYTES = 64 * 1024 * 1024;
const MAX_KOTLIN_STARTUP_SQLITE_SESSIONS = 1_000;
const MAX_KOTLIN_STARTUP_SQLITE_RUNS = 100_000;
const REQUIRED_SESSION_COLUMNS = [
  'id',
  'device_serial',
  'package_name',
  'component_name',
  'requested_type',
  'compilation_mode',
  'warmup_runs',
  'measured_runs',
  'created_at',
] as const;
const REQUIRED_RUN_COLUMNS = [
  'id',
  'session_id',
  'iteration',
  'requested_type',
  'observed_type',
  'status',
  'launch_state',
  'activity',
  'this_time_ms',
  'total_time_ms',
  'wait_time_ms',
  'displayed_time_ms',
  'fully_drawn_time_ms',
  'complete',
  'warnings',
  'am_start_output',
  'event_log_output',
] as const;

/** Visible on every imported run because the SQLite source records evidence Electron does not model. */
export const SQLITE_STARTUP_IMPORT_LIMITATION_WARNING =
  'Imported from Kotlin Startup SQLite. Milestones, phases, compilation, environment, and trace evidence are not modeled by Electron; retain the source database for those details.';
const CONFIDENCE_LIMITATION_WARNING =
  'Kotlin Startup SQLite does not retain metric evidence confidence; Electron marks retained metric evidence as INFERRED.';

type SqliteRow = Record<string, unknown>;

interface SourceDatabaseIdentity {
  readonly device: number;
  readonly inode: number;
  readonly size: number;
  readonly modifiedAtMilliseconds: number;
}

export interface StartupSqliteImportOptions {
  /** Must generate a local, opaque Electron identifier for every source session. */
  readonly idForSourceSession: (sourceSessionId: string) => string;
  readonly sourceFileName: string;
}

/**
 * Maps Kotlin's relational Startup store into Electron's local session model.
 * The selected database is opened read-only and its original byte hash is
 * retained with every imported session for source provenance.
 */
export async function importKotlinStartupSqlite(
  path: string,
  options: StartupSqliteImportOptions,
): Promise<StartupSession[]> {
  // A primary-database hash is meaningful only when it is the entire readable
  // SQLite state. WAL/journal sidecars can contain committed rows not present
  // in the main database, so callers must close Kotlin before importing.
  const initialIdentity = await sourceDatabaseIdentity(path);
  if (initialIdentity.size > MAX_KOTLIN_STARTUP_SQLITE_BYTES) {
    throw new TypeError('Startup SQLite exceeds the 64 MiB import limit');
  }
  await requireNoSqliteSidecars(path);
  const sourceDatabaseSha256 = await sha256File(path);
  await requireStableSourceDatabase(path, initialIdentity, sourceDatabaseSha256);

  const database = openReadOnlyDatabase(path);
  let sessions: StartupSession[];
  try {
    requireSchema(database, 'startup_sessions', REQUIRED_SESSION_COLUMNS);
    const runColumns = requireSchema(database, 'startup_runs', REQUIRED_RUN_COLUMNS);
    sessions = mapSessions(database, runColumns, options, sourceDatabaseSha256);
  } finally {
    database.close();
  }

  await requireNoSqliteSidecars(path);
  await requireStableSourceDatabase(path, initialIdentity, sourceDatabaseSha256);
  return sessions;
}

async function sourceDatabaseIdentity(path: string): Promise<SourceDatabaseIdentity> {
  const metadata = await lstat(path).catch((error: unknown) => {
    throw new TypeError('Startup SQLite cannot be opened: ' + describe(error), { cause: error });
  });
  if (!metadata.isFile()) throw new TypeError('Selected startup database is not a regular file');
  return {
    device: metadata.dev,
    inode: metadata.ino,
    size: metadata.size,
    modifiedAtMilliseconds: metadata.mtimeMs,
  };
}

async function requireNoSqliteSidecars(path: string): Promise<void> {
  const active = await Promise.all(['-wal', '-shm', '-journal'].map(async (suffix) => {
    try {
      await lstat(path + suffix);
      return suffix;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return undefined;
      throw error;
    }
  }));
  const present = active.filter((suffix): suffix is string => suffix !== undefined);
  if (present.length > 0) {
    throw new TypeError('Startup SQLite has active sidecar files (' + present.join(', ') + '); close the Kotlin app and retry');
  }
}

async function requireStableSourceDatabase(
  path: string,
  expectedIdentity: SourceDatabaseIdentity,
  expectedSha256: string,
): Promise<void> {
  const currentIdentity = await sourceDatabaseIdentity(path);
  if (
    currentIdentity.device !== expectedIdentity.device ||
    currentIdentity.inode !== expectedIdentity.inode ||
    currentIdentity.size !== expectedIdentity.size ||
    currentIdentity.modifiedAtMilliseconds !== expectedIdentity.modifiedAtMilliseconds
  ) {
    throw new TypeError('Startup SQLite changed or was replaced while it was being read');
  }
  if (await sha256File(path) !== expectedSha256) {
    throw new TypeError('Startup SQLite changed while it was being read');
  }
}

function mapSessions(
  database: DatabaseSync,
  runColumns: ReadonlySet<string>,
  options: StartupSqliteImportOptions,
  sourceDatabaseSha256: string,
): StartupSession[] {
  requireRowLimit(database, 'startup_sessions', MAX_KOTLIN_STARTUP_SQLITE_SESSIONS);
  requireRowLimit(database, 'startup_runs', MAX_KOTLIN_STARTUP_SQLITE_RUNS);
  const sourceSessions = database.prepare(`
    SELECT "id", "device_serial", "package_name", "component_name", "requested_type", "compilation_mode",
           "warmup_runs", "measured_runs", "created_at"
    FROM "startup_sessions"
    ORDER BY "created_at" DESC, "id" ASC
  `).all() as SqliteRow[];
  if (sourceSessions.length === 0) throw new TypeError('Kotlin Startup SQLite does not contain any startup sessions');

  const sourceRuns = database.prepare(`
    SELECT "id", "session_id", "iteration", "requested_type", "observed_type", "status", "launch_state", "activity",
           "this_time_ms", "total_time_ms", "wait_time_ms", "displayed_time_ms", "fully_drawn_time_ms", "complete",
           "warnings", "am_start_output", "event_log_output",
           ${optionalColumn(runColumns, 'ttid_source')},
           ${optionalColumn(runColumns, 'ttid_unavailable_reason')},
           ${optionalColumn(runColumns, 'ttfd_source')},
           ${optionalColumn(runColumns, 'ttfd_unavailable_reason')}
    FROM "startup_runs"
    ORDER BY "session_id" ASC, "iteration" ASC, "id" ASC
  `).all() as SqliteRow[];

  // Do not ignore corrupt/orphaned rows just because foreign keys were disabled
  // by the process that created the selected database.
  const runsBySourceSession = new Map<string, SqliteRow[]>();
  const sourceRunIds = new Set<string>();
  for (const row of sourceRuns) {
    const id = requiredString(row, 'id', 'startup run ID');
    if (sourceRunIds.has(id)) throw new TypeError('Kotlin Startup SQLite contains duplicate startup run ID: ' + id);
    sourceRunIds.add(id);
    const sessionId = requiredString(row, 'session_id', 'startup run session ID');
    const runs = runsBySourceSession.get(sessionId) ?? [];
    runs.push(row);
    runsBySourceSession.set(sessionId, runs);
  }

  const sessions: StartupSession[] = [];
  const sourceSessionIds = new Set<string>();
  for (const row of sourceSessions) {
    const sourceId = requiredString(row, 'id', 'startup session ID');
    if (sourceSessionIds.has(sourceId)) throw new TypeError('Kotlin Startup SQLite contains duplicate startup session ID: ' + sourceId);
    sourceSessionIds.add(sourceId);

    const requestedType = startupType(row, 'requested_type', 'startup session requested type');
    // This column is not yet represented by Electron's StartupExperimentConfig,
    // but validate it rather than accepting an unrelated database as Kotlin data.
    compilationMode(row);
    const warmupRuns = boundedInteger(row, 'warmup_runs', 0, 100, 'startup session warmup runs');
    const measuredRuns = boundedInteger(row, 'measured_runs', 1, 100, 'startup session measured runs');
    const createdAtEpochMillis = isoEpochMillis(row, 'created_at');
    const importedId = options.idForSourceSession(sourceId);
    if (importedId.length === 0) throw new TypeError('Generated Electron startup session ID must not be empty');

    const runs = (runsBySourceSession.get(sourceId) ?? []).map((run) =>
      toStartupRun(run, importedId, warmupRuns),
    );
    const session = createStartupSession({
      id: importedId,
      deviceSerial: 'IMPORTED',
      packageName: requiredString(row, 'package_name', 'startup session package name'),
      componentName: requiredString(row, 'component_name', 'startup session component name'),
      config: {
        ...DEFAULT_STARTUP_EXPERIMENT,
        requestedType,
        warmupRuns,
        measuredRuns,
      },
      createdAtEpochMillis,
      runs,
    });
    sessions.push({
      ...session,
      origin: 'IMPORTED',
      // Kotlin stores a pseudonym in this column. It must never become an ADB serial.
      sourceDeviceLocalId: requiredString(row, 'device_serial', 'startup session device pseudonym'),
      sourceDatabaseSha256,
      sourceFileName: options.sourceFileName,
    });
  }

  for (const sourceSessionId of runsBySourceSession.keys()) {
    if (!sourceSessionIds.has(sourceSessionId)) {
      throw new TypeError('Kotlin Startup SQLite contains a run without its source session: ' + sourceSessionId);
    }
  }
  return sessions;
}

function toStartupRun(row: SqliteRow, sessionId: string, warmupRuns: number): StartupRun {
  const iteration = boundedInteger(row, 'iteration', 1, Number.MAX_SAFE_INTEGER, 'startup iteration');
  const displayedTimeMs = optionalDuration(row, 'displayed_time_ms');
  const fullyDrawnTimeMs = optionalDuration(row, 'fully_drawn_time_ms');
  return {
    id: requiredString(row, 'id', 'startup run ID'),
    sessionId,
    iteration,
    measured: iteration > warmupRuns,
    requestedType: startupType(row, 'requested_type', 'startup run requested type'),
    observedType: startupType(row, 'observed_type', 'startup run observed type'),
    platform: {
      ...optionalProperty('status', optionalString(row, 'status')),
      ...optionalProperty('launchState', optionalString(row, 'launch_state')),
      ...optionalProperty('activity', optionalString(row, 'activity')),
      ...optionalProperty('thisTimeMs', optionalDuration(row, 'this_time_ms')),
      ...optionalProperty('totalTimeMs', optionalDuration(row, 'total_time_ms')),
      ...optionalProperty('waitTimeMs', optionalDuration(row, 'wait_time_ms')),
      ...optionalProperty('displayedTimeMs', displayedTimeMs),
      ...optionalProperty('fullyDrawnTimeMs', fullyDrawnTimeMs),
      complete: sqliteBoolean(row, 'complete', 'startup complete'),
    },
    warnings: [...warnings(row), SQLITE_STARTUP_IMPORT_LIMITATION_WARNING, CONFIDENCE_LIMITATION_WARNING],
    amStartOutput: requiredString(row, 'am_start_output', 'startup am start output'),
    ...optionalProperty('eventLogOutput', optionalString(row, 'event_log_output')),
    ttidEvidence: metricEvidence(displayedTimeMs, row, 'ttid_source', 'ttid_unavailable_reason', 'TTID'),
    ttfdEvidence: metricEvidence(fullyDrawnTimeMs, row, 'ttfd_source', 'ttfd_unavailable_reason', 'TTFD'),
  };
}

function metricEvidence(
  value: number | undefined,
  row: SqliteRow,
  sourceColumn: string,
  reasonColumn: string,
  label: string,
): MetricEvidence {
  const source = optionalStartupSource(row, sourceColumn, label + ' source');
  const unavailableReason = optionalString(row, reasonColumn);
  if (value === undefined) {
    return {
      confidence: 'UNAVAILABLE',
      unavailableReason: unavailableReason ?? 'No ' + label + ' value is available in the Kotlin Startup SQLite source.',
    };
  }
  return {
    ...(source !== undefined ? { source } : {}),
    confidence: 'INFERRED',
    ...(unavailableReason !== undefined ? { unavailableReason } : {}),
  };
}

function requireRowLimit(database: DatabaseSync, table: string, maximum: number): void {
  const row = database.prepare('SELECT COUNT(*) AS count FROM ' + quotedIdentifier(table)).get() as { count?: unknown } | undefined;
  const count = sqliteInteger(row?.count);
  if (count === undefined || count > maximum) {
    throw new TypeError('Kotlin Startup SQLite has too many ' + table + ' rows (maximum ' + String(maximum) + ')');
  }
}

function requireSchema(
  database: DatabaseSync,
  table: string,
  requiredColumns: readonly string[],
): ReadonlySet<string> {
  const exists = database.prepare(
    "SELECT 1 AS found FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
  ).get(table) as { found?: number } | undefined;
  if (exists?.found !== 1) throw new TypeError('Kotlin Startup SQLite is missing required table: ' + table);

  const columns = new Set(
    (database.prepare('PRAGMA table_info(' + quotedIdentifier(table) + ')').all() as SqliteRow[])
      .map((column) => column['name'])
      .filter((name): name is string => typeof name === 'string'),
  );
  for (const requiredColumn of requiredColumns) {
    if (!columns.has(requiredColumn)) {
      throw new TypeError('Kotlin Startup SQLite is missing required column: ' + table + '.' + requiredColumn);
    }
  }
  return columns;
}

function optionalColumn(
  columns: ReadonlySet<string>,
  name: 'ttid_source' | 'ttid_unavailable_reason' | 'ttfd_source' | 'ttfd_unavailable_reason',
): string {
  return columns.has(name)
    ? quotedIdentifier(name)
    : 'NULL AS ' + quotedIdentifier(name);
}

function quotedIdentifier(identifier: string): string {
  return '"' + identifier.replaceAll('"', '""') + '"';
}

function requiredString(row: SqliteRow, name: string, label: string): string {
  const value = row[name];
  if (typeof value !== 'string' || value.length === 0) throw new TypeError('Kotlin Startup SQLite has invalid ' + label);
  return value;
}

function optionalString(row: SqliteRow, name: string): string | undefined {
  const value = row[name];
  if (value === null || value === undefined) return undefined;
  if (typeof value !== 'string') throw new TypeError('Kotlin Startup SQLite has invalid ' + name);
  return value;
}

function boundedInteger(row: SqliteRow, name: string, minimum: number, maximum: number, label: string): number {
  const value = sqliteInteger(row[name]);
  if (value === undefined || value < minimum || value > maximum) {
    throw new TypeError('Kotlin Startup SQLite has invalid ' + label);
  }
  return value;
}

function optionalDuration(row: SqliteRow, name: string): number | undefined {
  const value = row[name];
  if (value === null || value === undefined) return undefined;
  const integer = sqliteInteger(value);
  if (integer === undefined || integer < 0) throw new TypeError('Kotlin Startup SQLite has invalid ' + name);
  return integer;
}

function sqliteInteger(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isSafeInteger(value)) return value;
  if (typeof value === 'bigint' && value >= BigInt(Number.MIN_SAFE_INTEGER) && value <= BigInt(Number.MAX_SAFE_INTEGER)) {
    return Number(value);
  }
  return undefined;
}

function sqliteBoolean(row: SqliteRow, name: string, label: string): boolean {
  const value = sqliteInteger(row[name]);
  if (value === 0) return false;
  if (value === 1) return true;
  throw new TypeError('Kotlin Startup SQLite has invalid ' + label);
}

function startupType(row: SqliteRow, name: string, label: string): StartupType {
  const value = requiredString(row, name, label);
  if (!STARTUP_TYPES.has(value as StartupType)) throw new TypeError('Kotlin Startup SQLite has invalid ' + label);
  return value as StartupType;
}

function optionalStartupSource(row: SqliteRow, name: string, label: string): StartupSource | undefined {
  const value = optionalString(row, name);
  if (value === undefined) return undefined;
  if (!STARTUP_SOURCES.has(value as StartupSource)) throw new TypeError('Kotlin Startup SQLite has invalid ' + label);
  return value as StartupSource;
}

function compilationMode(row: SqliteRow): void {
  const value = requiredString(row, 'compilation_mode', 'startup session compilation mode');
  if (!['CURRENT', 'RESET', 'VERIFY', 'SPEED_PROFILE', 'SPEED'].includes(value)) {
    throw new TypeError('Kotlin Startup SQLite has invalid startup session compilation mode');
  }
}

function isoEpochMillis(row: SqliteRow, name: string): number {
  const value = requiredString(row, name, 'startup session created time');
  const epochMillis = Date.parse(value);
  if (!Number.isSafeInteger(epochMillis)) throw new TypeError('Kotlin Startup SQLite has invalid startup session created time');
  return epochMillis;
}

function warnings(row: SqliteRow): string[] {
  const stored = optionalString(row, 'warnings');
  if (stored === undefined) throw new TypeError('Kotlin Startup SQLite has invalid startup warnings');
  return stored.length === 0 ? [] : stored.split('\n').filter((warning) => warning.length > 0);
}

function optionalProperty<Key extends string, Value>(key: Key, value: Value | undefined): Partial<Record<Key, Value>> {
  return value === undefined ? {} : { [key]: value } as Partial<Record<Key, Value>>;
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : 'unknown error';
}
