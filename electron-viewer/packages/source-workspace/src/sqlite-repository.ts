/**
 * Port of SqliteSourceWorkspaceRepository.kt: the same tables, columns, and
 * defaults, so an existing source-workspaces.db written by the Kotlin app stays
 * readable and a database written here stays readable there.
 *
 * created_at is ISO-8601 text in both implementations; the TypeScript model
 * carries epoch milliseconds and converts on the way in and out. Columns the
 * TypeScript model dropped (the range start and end columns) are written with
 * the Kotlin defaults of 1 so a row never loses information that was never read.
 */
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import {
  RESOLUTION_CONFIDENCES,
  SOURCE_LANGUAGES,
  SOURCE_PROVIDER_KINDS,
  SOURCE_SYMBOL_KINDS,
  SOURCE_WORKSPACE_PHASES,
  type ResolutionCandidate,
  type SourceFile,
  type SourceProviderConfig,
  type SourceSnapshot,
  type SourceSymbol,
  type SourceWorkspace,
} from './model.js';
import type { SourceWorkspaceRepository } from './repository.js';

export class SqliteSourceWorkspaceRepository implements SourceWorkspaceRepository {
  private readonly database: DatabaseSync;

  constructor(databasePath: string) {
    const directory = dirname(databasePath);
    if (directory.length > 0 && directory !== '.') mkdirSync(directory, { recursive: true });
    this.database = new DatabaseSync(databasePath);
    this.database.exec('PRAGMA foreign_keys = ON');
    this.database.exec('PRAGMA journal_mode = WAL');
    this.createSchema();
  }

  saveWorkspace(workspace: SourceWorkspace): void {
    const config = workspace.config;
    this.database
      .prepare(
        'INSERT INTO source_workspace(' +
          'id, display_name, provider_kind, local_root, github_owner, github_repository, ' +
          'provider_ref, credential_key, aosp_project, active_snapshot_id, phase, progress, message, ' +
          'allow_ai_source_upload' +
          ') VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ' +
          'ON CONFLICT(id) DO UPDATE SET ' +
          'display_name=excluded.display_name, provider_kind=excluded.provider_kind, ' +
          'local_root=excluded.local_root, github_owner=excluded.github_owner, ' +
          'github_repository=excluded.github_repository, provider_ref=excluded.provider_ref, ' +
          'credential_key=excluded.credential_key, aosp_project=excluded.aosp_project, ' +
          'active_snapshot_id=excluded.active_snapshot_id, phase=excluded.phase, ' +
          'progress=excluded.progress, message=excluded.message, ' +
          'allow_ai_source_upload=excluded.allow_ai_source_upload',
      )
      .run(
        workspace.id,
        workspace.displayName,
        config.kind,
        config.kind === 'LOCAL' ? config.root : null,
        config.kind === 'GITHUB' ? config.owner : null,
        config.kind === 'GITHUB' ? config.repository : null,
        config.kind === 'LOCAL' ? null : config.ref,
        config.kind === 'GITHUB' ? (config.credentialKey ?? null) : null,
        config.kind === 'AOSP' ? config.project : null,
        workspace.activeSnapshotId ?? null,
        workspace.phase,
        workspace.progress,
        workspace.message ?? null,
        workspace.allowAiSourceUpload ? 1 : 0,
      );
  }

  workspace(id: string): SourceWorkspace | undefined {
    const row = this.database.prepare('SELECT * FROM source_workspace WHERE id = ?').get(id);
    return row === undefined ? undefined : toWorkspace(row as Record<string, unknown>);
  }

  workspaces(): SourceWorkspace[] {
    const rows = this.database
      .prepare('SELECT * FROM source_workspace ORDER BY display_name')
      .all() as Record<string, unknown>[];
    return rows.map(toWorkspace);
  }

  deleteWorkspace(id: string): void {
    this.database.prepare('DELETE FROM source_workspace WHERE id = ?').run(id);
  }

  saveSnapshot(snapshot: SourceSnapshot, files: readonly SourceFile[], symbols: readonly SourceSymbol[]): void {
    this.transaction(() => {
      this.database
        .prepare(
          'INSERT OR REPLACE INTO source_snapshot(' +
            'id, workspace_id, immutable_revision, dirty_digest, manifest_hash, ' +
            'created_at, index_version, index_complete' +
            ') VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
        )
        .run(
          snapshot.id,
          snapshot.workspaceId,
          snapshot.immutableRevision,
          snapshot.dirtyContentDigest ?? null,
          snapshot.manifestHash,
          new Date(snapshot.createdAtEpochMillis).toISOString(),
          snapshot.indexVersion,
          snapshot.indexComplete ? 1 : 0,
        );
      this.database.prepare('DELETE FROM source_file WHERE snapshot_id = ?').run(snapshot.id);
      this.database.prepare('DELETE FROM source_symbol WHERE snapshot_id = ?').run(snapshot.id);
      const insertFile = this.database.prepare(
        'INSERT INTO source_file(snapshot_id, relative_path, language, content_hash, size_bytes) ' +
          'VALUES (?, ?, ?, ?, ?)',
      );
      for (const file of files) {
        insertFile.run(file.snapshotId, file.relativePath, file.language, file.contentHash, file.sizeBytes);
      }
      const insertSymbol = this.database.prepare(
        'INSERT INTO source_symbol(snapshot_id, relative_path, kind, qualified_name, signature, ' +
          'start_line, end_line) VALUES (?, ?, ?, ?, ?, ?, ?)',
      );
      for (const symbol of symbols) {
        insertSymbol.run(
          symbol.snapshotId,
          symbol.relativePath,
          symbol.kind,
          symbol.qualifiedName,
          symbol.signature ?? null,
          symbol.startLine,
          symbol.endLine,
        );
      }
    });
  }

  snapshot(snapshotId: string): SourceSnapshot | undefined {
    const row = this.database.prepare('SELECT * FROM source_snapshot WHERE id = ?').get(snapshotId);
    if (row === undefined) return undefined;
    const record = row as Record<string, unknown>;
    const dirty = nullableString(record['dirty_digest']);
    return {
      id: snapshotId,
      workspaceId: requiredString(record, 'workspace_id'),
      immutableRevision: requiredString(record, 'immutable_revision'),
      ...(dirty !== undefined ? { dirtyContentDigest: dirty } : {}),
      manifestHash: requiredString(record, 'manifest_hash'),
      createdAtEpochMillis: Date.parse(requiredString(record, 'created_at')),
      indexVersion: requiredNumber(record, 'index_version'),
      indexComplete: requiredNumber(record, 'index_complete') !== 0,
    };
  }

  files(snapshotId: string): readonly SourceFile[] {
    const rows = this.database
      .prepare('SELECT * FROM source_file WHERE snapshot_id = ? ORDER BY relative_path')
      .all(snapshotId) as Record<string, unknown>[];
    return rows.map((row) => ({
      snapshotId,
      relativePath: requiredString(row, 'relative_path'),
      language: enumOf(requiredString(row, 'language'), SOURCE_LANGUAGES),
      contentHash: requiredString(row, 'content_hash'),
      sizeBytes: requiredNumber(row, 'size_bytes'),
    }));
  }

  symbols(snapshotId: string): readonly SourceSymbol[] {
    const rows = this.database
      .prepare('SELECT * FROM source_symbol WHERE snapshot_id = ? ORDER BY relative_path, start_line')
      .all(snapshotId) as Record<string, unknown>[];
    return rows.map((row) => {
      const signature = nullableString(row['signature']);
      return {
        snapshotId,
        relativePath: requiredString(row, 'relative_path'),
        kind: enumOf(requiredString(row, 'kind'), SOURCE_SYMBOL_KINDS),
        qualifiedName: requiredString(row, 'qualified_name'),
        ...(signature !== undefined ? { signature } : {}),
        startLine: requiredNumber(row, 'start_line'),
        endLine: requiredNumber(row, 'end_line'),
      };
    });
  }

  saveCandidates(candidates: readonly ResolutionCandidate[]): void {
    const statement = this.database.prepare(
      'INSERT OR REPLACE INTO resolution_candidate(' +
        'id, evidence_id, workspace_id, snapshot_id, relative_path, start_line, start_column, ' +
        'end_line, end_column, content_hash, confidence, reasons, index_version, index_complete' +
        ') VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
    );
    for (const candidate of candidates) {
      const range = candidate.location.range;
      statement.run(
        candidate.id,
        candidate.evidenceId,
        candidate.location.workspaceId,
        candidate.location.snapshotId,
        candidate.location.relativePath,
        range?.startLine ?? null,
        range === undefined ? null : 1,
        range?.endLine ?? null,
        range === undefined ? null : 1,
        candidate.location.contentHash,
        candidate.confidence,
        JSON.stringify([...candidate.reasons]),
        candidate.indexVersion,
        candidate.indexComplete ? 1 : 0,
      );
    }
  }

  candidate(id: string): ResolutionCandidate | undefined {
    const row = this.database.prepare('SELECT * FROM resolution_candidate WHERE id = ?').get(id);
    if (row === undefined) return undefined;
    const record = row as Record<string, unknown>;
    const startLine = nullableNumber(record['start_line']);
    const endLine = nullableNumber(record['end_line']);
    return {
      id,
      evidenceId: requiredString(record, 'evidence_id'),
      location: {
        workspaceId: requiredString(record, 'workspace_id'),
        snapshotId: requiredString(record, 'snapshot_id'),
        relativePath: requiredString(record, 'relative_path'),
        ...(startLine !== null && endLine !== null ? { range: { startLine, endLine } } : {}),
        contentHash: requiredString(record, 'content_hash'),
      },
      confidence: enumOf(requiredString(record, 'confidence'), RESOLUTION_CONFIDENCES),
      reasons: parseReasons(requiredString(record, 'reasons')),
      indexVersion: requiredNumber(record, 'index_version'),
      indexComplete: requiredNumber(record, 'index_complete') !== 0,
    };
  }

  close(): void {
    this.database.close();
  }

  private transaction(body: () => void): void {
    this.database.exec('BEGIN');
    try {
      body();
      this.database.exec('COMMIT');
    } catch (error) {
      this.database.exec('ROLLBACK');
      throw error;
    }
  }

  private createSchema(): void {
    for (const statement of SCHEMA) this.database.exec(statement);
  }
}

const SCHEMA = [
  [
    'CREATE TABLE IF NOT EXISTS source_workspace(',
    '  id TEXT PRIMARY KEY, display_name TEXT NOT NULL, provider_kind TEXT NOT NULL,',
    '  local_root TEXT, github_owner TEXT, github_repository TEXT, provider_ref TEXT,',
    '  credential_key TEXT, aosp_project TEXT, active_snapshot_id TEXT,',
    '  phase TEXT NOT NULL, progress REAL NOT NULL, message TEXT,',
    '  allow_ai_source_upload INTEGER NOT NULL DEFAULT 0',
    ')',
  ].join('\n'),
  [
    'CREATE TABLE IF NOT EXISTS source_snapshot(',
    '  id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL REFERENCES source_workspace(id) ON DELETE CASCADE,',
    '  immutable_revision TEXT NOT NULL, dirty_digest TEXT, manifest_hash TEXT NOT NULL,',
    '  created_at TEXT NOT NULL, index_version INTEGER NOT NULL, index_complete INTEGER NOT NULL',
    ')',
  ].join('\n'),
  [
    'CREATE TABLE IF NOT EXISTS source_file(',
    '  snapshot_id TEXT NOT NULL REFERENCES source_snapshot(id) ON DELETE CASCADE,',
    '  relative_path TEXT NOT NULL, language TEXT NOT NULL, content_hash TEXT NOT NULL,',
    '  size_bytes INTEGER NOT NULL, PRIMARY KEY(snapshot_id, relative_path)',
    ')',
  ].join('\n'),
  [
    'CREATE TABLE IF NOT EXISTS source_symbol(',
    '  snapshot_id TEXT NOT NULL REFERENCES source_snapshot(id) ON DELETE CASCADE,',
    '  relative_path TEXT NOT NULL, kind TEXT NOT NULL, qualified_name TEXT NOT NULL,',
    '  signature TEXT, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL',
    ')',
  ].join('\n'),
  'CREATE INDEX IF NOT EXISTS source_symbol_name ON source_symbol(snapshot_id, qualified_name)',
  [
    'CREATE TABLE IF NOT EXISTS resolution_candidate(',
    '  id TEXT PRIMARY KEY, evidence_id TEXT NOT NULL, workspace_id TEXT NOT NULL,',
    '  snapshot_id TEXT NOT NULL REFERENCES source_snapshot(id) ON DELETE CASCADE,',
    '  relative_path TEXT NOT NULL, start_line INTEGER, start_column INTEGER,',
    '  end_line INTEGER, end_column INTEGER, content_hash TEXT NOT NULL,',
    '  confidence TEXT NOT NULL, reasons TEXT NOT NULL, index_version INTEGER NOT NULL,',
    '  index_complete INTEGER NOT NULL',
    ')',
  ].join('\n'),
];

function toWorkspace(row: Record<string, unknown>): SourceWorkspace {
  const kind = enumOf(requiredString(row, 'provider_kind'), SOURCE_PROVIDER_KINDS);
  const config: SourceProviderConfig =
    kind === 'LOCAL'
      ? { kind: 'LOCAL', root: requiredString(row, 'local_root') }
      : kind === 'GITHUB'
        ? {
            kind: 'GITHUB',
            owner: requiredString(row, 'github_owner'),
            repository: requiredString(row, 'github_repository'),
            ref: requiredString(row, 'provider_ref'),
            credentialKey: nullableString(row['credential_key']) ?? null,
          }
        : { kind: 'AOSP', project: requiredString(row, 'aosp_project'), ref: requiredString(row, 'provider_ref') };
  const activeSnapshotId = nullableString(row['active_snapshot_id']);
  const message = nullableString(row['message']);
  return {
    id: requiredString(row, 'id'),
    displayName: requiredString(row, 'display_name'),
    config,
    ...(activeSnapshotId !== undefined ? { activeSnapshotId } : {}),
    phase: enumOf(requiredString(row, 'phase'), SOURCE_WORKSPACE_PHASES),
    progress: requiredNumber(row, 'progress'),
    ...(message !== undefined ? { message } : {}),
    allowAiSourceUpload: requiredNumber(row, 'allow_ai_source_upload') !== 0,
  };
}

function parseReasons(text: string): string[] {
  const parsed: unknown = JSON.parse(text);
  if (!Array.isArray(parsed)) return [];
  return parsed.filter((entry): entry is string => typeof entry === 'string');
}

function requiredString(record: Record<string, unknown>, key: string): string {
  const value = record[key];
  if (typeof value !== 'string') throw new Error('source workspace column ' + key + ' was not text');
  return value;
}

function nullableString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function requiredNumber(record: Record<string, unknown>, key: string): number {
  const value = record[key];
  if (typeof value === 'number') return value;
  if (typeof value === 'bigint') return Number(value);
  throw new Error('source workspace column ' + key + ' was not numeric');
}

function nullableNumber(value: unknown): number | null {
  if (value === null || value === undefined) return null;
  return typeof value === 'number' ? value : Number(value);
}

function enumOf<T extends string>(value: string, allowed: readonly T[]): T {
  const match = allowed.find((candidate) => candidate === value);
  if (match === undefined) throw new Error('source workspace held an unknown value ' + value);
  return match;
}
