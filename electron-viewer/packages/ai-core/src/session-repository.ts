/**
 * Port of ai-core AnalysisSessionRepository.kt.
 *
 * Writes the same analysis-sessions.db schema the Kotlin app writes, including
 * the provider column and the finding primary key migration, so sessions created
 * by either build stay readable by the other. Evidence payload bodies are never
 * stored: only their SHA-256, which is what keeps a captured source body out of
 * the database.
 */
import { createHash } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import {
  ANALYSIS_SESSION_STATUSES,
  ANALYSIS_SEVERITIES,
  PROFILER_KINDS,
  ANALYSIS_SCOPE_KINDS,
  type AnalysisFinding,
  type AnalysisRequest,
  type AnalysisResult,
  type AnalysisSession,
  type AnalysisSessionId,
} from './contracts.js';

export interface AnalysisEvidenceSummary {
  readonly id: string;
  readonly kind: string;
  readonly summary: string;
  readonly payloadHash: string;
}

export interface AnalysisCandidateSummary {
  readonly id: string;
  readonly relativePath: string;
  readonly startLine: number | null;
  readonly endLine: number | null;
  readonly resolutionConfidence: string;
  readonly contentHash: string | null;
}

export interface AnalysisSessionRepository {
  saveSession(session: AnalysisSession): void;
  saveRequest(request: AnalysisRequest): void;
  saveResult(result: AnalysisResult): void;
  session(id: AnalysisSessionId): AnalysisSession | undefined;
  /** Newest first; the Kotlin repository has no equivalent, the UI needs it. */
  listSessions(limit: number): AnalysisSession[];
  findings(id: AnalysisSessionId): AnalysisFinding[];
  evidence(id: AnalysisSessionId): AnalysisEvidenceSummary[];
  candidates(id: AnalysisSessionId): AnalysisCandidateSummary[];
}

export class SqliteAnalysisSessionRepository implements AnalysisSessionRepository {
  private readonly database: DatabaseSync;

  constructor(databasePath: string) {
    const directory = dirname(databasePath);
    if (directory.length > 0 && directory !== '.') mkdirSync(directory, { recursive: true });
    this.database = new DatabaseSync(databasePath);
    this.database.exec('PRAGMA journal_mode = WAL');
    this.database.exec('PRAGMA foreign_keys = ON');
    this.createSchema();
    this.ensureColumn('analysis_session', 'provider', 'TEXT');
    this.migrateLegacyFindingPrimaryKey();
  }

  saveSession(session: AnalysisSession): void {
    this.database
      .prepare(
        'INSERT INTO analysis_session(' +
          'id, origin_profiler, scope_kind, scope_description, model, provider, prompt_version, ' +
          'payload_policy_version, source_snapshot_ids, build_evidence_ids, status, ' +
          'created_at, parent_session_id, summary, error_message' +
          ') VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ' +
          'ON CONFLICT(id) DO UPDATE SET model=excluded.model, provider=excluded.provider, ' +
          'status=excluded.status, summary=excluded.summary, error_message=excluded.error_message',
      )
      .run(
        session.id,
        session.originProfiler,
        session.scope.kind,
        session.scope.description,
        session.model,
        session.provider ?? null,
        session.promptVersion,
        session.payloadPolicyVersion,
        JSON.stringify(session.sourceSnapshotIds),
        JSON.stringify(session.buildEvidenceBundleIds),
        session.status,
        session.createdAt,
        session.parentSessionId ?? null,
        session.summary ?? null,
        session.errorMessage ?? null,
      );
  }

  saveRequest(request: AnalysisRequest): void {
    this.transaction(() => {
      this.database.prepare('DELETE FROM analysis_evidence WHERE session_id = ?').run(request.sessionId);
      const insertEvidence = this.database.prepare(
        'INSERT INTO analysis_evidence(session_id, id, kind, summary, payload_hash) VALUES (?, ?, ?, ?, ?)',
      );
      for (const item of request.evidence) {
        insertEvidence.run(request.sessionId, item.id, item.kind, item.summary, sha256(item.structuredPayload));
      }
      this.database.prepare('DELETE FROM analysis_candidate WHERE session_id = ?').run(request.sessionId);
      const insertCandidate = this.database.prepare(
        'INSERT INTO analysis_candidate(' +
          'session_id, id, relative_path, start_line, end_line, resolution_confidence, content_hash' +
          ') VALUES (?, ?, ?, ?, ?, ?, ?)',
      );
      for (const candidate of request.sourceCandidates) {
        insertCandidate.run(
          request.sessionId,
          candidate.id,
          candidate.relativePath,
          candidate.startLine ?? null,
          candidate.endLine ?? null,
          candidate.resolutionConfidence,
          candidate.contentHash ?? null,
        );
      }
    });
  }

  saveResult(result: AnalysisResult): void {
    this.transaction(() => {
      this.database.prepare('DELETE FROM analysis_finding WHERE session_id = ?').run(result.sessionId);
      const insertFinding = this.database.prepare(
        'INSERT INTO analysis_finding(' +
          'id, session_id, severity, title, explanation, recommendation, ' +
          'analysis_confidence, evidence_ids, candidate_ids' +
          ') VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
      );
      for (const finding of result.findings) {
        insertFinding.run(
          finding.id,
          result.sessionId,
          finding.severity,
          finding.title,
          finding.explanation,
          finding.recommendation,
          finding.analysisConfidence,
          JSON.stringify(finding.performanceEvidenceIds),
          JSON.stringify(finding.sourceCandidateIds),
        );
      }
      this.database
        .prepare('UPDATE analysis_session SET model = ?, status = ?, summary = ? WHERE id = ?')
        .run(result.model, 'SUCCEEDED', result.summary, result.sessionId);
    });
  }

  session(id: AnalysisSessionId): AnalysisSession | undefined {
    const row = this.database.prepare('SELECT * FROM analysis_session WHERE id = ?').get(id);
    if (row === undefined) return undefined;
    const record = row as Record<string, unknown>;
    const parentSessionId = nullableString(record['parent_session_id']);
    const summary = nullableString(record['summary']);
    const errorMessage = nullableString(record['error_message']);
    return {
      id,
      originProfiler: enumField(record, 'origin_profiler', PROFILER_KINDS),
      scope: {
        kind: enumField(record, 'scope_kind', ANALYSIS_SCOPE_KINDS),
        description: requiredString(record, 'scope_description'),
      },
      model: nullableString(record['model']) ?? null,
      promptVersion: requiredString(record, 'prompt_version'),
      payloadPolicyVersion: requiredString(record, 'payload_policy_version'),
      sourceSnapshotIds: parseStringArray(requiredString(record, 'source_snapshot_ids')),
      buildEvidenceBundleIds: parseStringArray(requiredString(record, 'build_evidence_ids')),
      status: enumField(record, 'status', ANALYSIS_SESSION_STATUSES),
      createdAt: requiredString(record, 'created_at'),
      parentSessionId: parentSessionId ?? null,
      summary: summary ?? null,
      errorMessage: errorMessage ?? null,
      provider: nullableString(record['provider']) ?? null,
    };
  }

  listSessions(limit: number): AnalysisSession[] {
    const rows = this.database
      .prepare('SELECT id FROM analysis_session ORDER BY created_at DESC LIMIT ?')
      .all(limit) as Record<string, unknown>[];
    const sessions: AnalysisSession[] = [];
    for (const row of rows) {
      const session = this.session(requiredString(row, 'id'));
      if (session !== undefined) sessions.push(session);
    }
    return sessions;
  }

  findings(id: AnalysisSessionId): AnalysisFinding[] {
    const rows = this.database
      .prepare('SELECT * FROM analysis_finding WHERE session_id = ? ORDER BY rowid')
      .all(id) as Record<string, unknown>[];
    return rows.map((row) => ({
      id: requiredString(row, 'id'),
      severity: enumField(row, 'severity', ANALYSIS_SEVERITIES),
      title: requiredString(row, 'title'),
      explanation: requiredString(row, 'explanation'),
      recommendation: requiredString(row, 'recommendation'),
      analysisConfidence: requiredNumber(row, 'analysis_confidence'),
      performanceEvidenceIds: parseStringArray(requiredString(row, 'evidence_ids')),
      sourceCandidateIds: parseStringArray(requiredString(row, 'candidate_ids')),
    }));
  }

  evidence(id: AnalysisSessionId): AnalysisEvidenceSummary[] {
    const rows = this.database
      .prepare('SELECT id, kind, summary, payload_hash FROM analysis_evidence WHERE session_id = ? ORDER BY rowid')
      .all(id) as Record<string, unknown>[];
    return rows.map((row) => ({
      id: requiredString(row, 'id'),
      kind: requiredString(row, 'kind'),
      summary: requiredString(row, 'summary'),
      payloadHash: requiredString(row, 'payload_hash'),
    }));
  }

  candidates(id: AnalysisSessionId): AnalysisCandidateSummary[] {
    const rows = this.database
      .prepare('SELECT * FROM analysis_candidate WHERE session_id = ? ORDER BY rowid')
      .all(id) as Record<string, unknown>[];
    return rows.map((row) => ({
      id: requiredString(row, 'id'),
      relativePath: requiredString(row, 'relative_path'),
      startLine: nullableNumber(row['start_line']),
      endLine: nullableNumber(row['end_line']),
      resolutionConfidence: requiredString(row, 'resolution_confidence'),
      contentHash: nullableString(row['content_hash']) ?? null,
    }));
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
    this.database.exec(
      [
        'CREATE TABLE IF NOT EXISTS analysis_session(',
        '  id TEXT PRIMARY KEY, origin_profiler TEXT NOT NULL, scope_kind TEXT NOT NULL,',
        '  scope_description TEXT NOT NULL, model TEXT, provider TEXT, prompt_version TEXT NOT NULL,',
        '  payload_policy_version TEXT NOT NULL, source_snapshot_ids TEXT NOT NULL,',
        '  build_evidence_ids TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL,',
        '  parent_session_id TEXT, summary TEXT, error_message TEXT',
        ')',
      ].join('\n'),
    );
    this.database.exec(FINDING_TABLE_DDL);
    this.database.exec(
      [
        'CREATE TABLE IF NOT EXISTS analysis_evidence(',
        '  session_id TEXT NOT NULL REFERENCES analysis_session(id) ON DELETE CASCADE,',
        '  id TEXT NOT NULL, kind TEXT NOT NULL, summary TEXT NOT NULL,',
        '  payload_hash TEXT NOT NULL,',
        '  PRIMARY KEY(session_id, id)',
        ')',
      ].join('\n'),
    );
    this.database.exec(
      [
        'CREATE TABLE IF NOT EXISTS analysis_candidate(',
        '  session_id TEXT NOT NULL REFERENCES analysis_session(id) ON DELETE CASCADE,',
        '  id TEXT NOT NULL, relative_path TEXT NOT NULL, start_line INTEGER, end_line INTEGER,',
        '  resolution_confidence TEXT NOT NULL, content_hash TEXT,',
        '  PRIMARY KEY(session_id, id)',
        ')',
      ].join('\n'),
    );
  }

  private ensureColumn(table: string, column: string, definition: string): void {
    const columns = this.database.prepare('PRAGMA table_info(' + table + ')').all() as Record<string, unknown>[];
    if (columns.some((entry) => entry['name'] === column)) return;
    this.database.exec('ALTER TABLE ' + table + ' ADD COLUMN ' + column + ' ' + definition);
  }

  /**
   * Older databases keyed findings by id alone, so two sessions could not both
   * hold a finding called "finding-1". Rebuild the table in place when the
   * primary key does not include session_id.
   */
  private migrateLegacyFindingPrimaryKey(): void {
    const columns = this.database.prepare('PRAGMA table_info(analysis_finding)').all() as Record<string, unknown>[];
    const alreadyMigrated = columns.some((entry) => entry['name'] === 'session_id' && Number(entry['pk'] ?? 0) > 0);
    if (alreadyMigrated) return;
    this.transaction(() => {
      this.database.exec('ALTER TABLE analysis_finding RENAME TO analysis_finding_legacy');
      this.database.exec(FINDING_TABLE_DDL);
      this.database.exec('INSERT INTO analysis_finding SELECT * FROM analysis_finding_legacy');
      this.database.exec('DROP TABLE analysis_finding_legacy');
    });
  }
}

const FINDING_TABLE_DDL = [
  'CREATE TABLE IF NOT EXISTS analysis_finding(',
  '  id TEXT NOT NULL, session_id TEXT NOT NULL REFERENCES analysis_session(id) ON DELETE CASCADE,',
  '  severity TEXT NOT NULL, title TEXT NOT NULL, explanation TEXT NOT NULL,',
  '  recommendation TEXT NOT NULL, analysis_confidence REAL NOT NULL,',
  '  evidence_ids TEXT NOT NULL, candidate_ids TEXT NOT NULL,',
  '  PRIMARY KEY(session_id, id)',
  ')',
].join('\n');

export function sha256(text: string): string {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

function requiredString(record: Record<string, unknown>, key: string): string {
  const value = record[key];
  if (typeof value !== 'string') throw new Error('analysis row column ' + key + ' was not text');
  return value;
}

function nullableString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function requiredNumber(record: Record<string, unknown>, key: string): number {
  const value = record[key];
  if (typeof value !== 'number') throw new Error('analysis row column ' + key + ' was not numeric');
  return value;
}

function nullableNumber(value: unknown): number | null {
  if (value === null || value === undefined) return null;
  return typeof value === 'number' ? value : Number(value);
}

function enumField<T extends string>(
  record: Record<string, unknown>,
  key: string,
  allowed: readonly T[],
): T {
  const value = requiredString(record, key);
  const match = allowed.find((candidate) => candidate === value);
  if (match === undefined) throw new Error('analysis row column ' + key + ' held an unknown value ' + value);
  return match;
}

function parseStringArray(text: string): string[] {
  const parsed: unknown = JSON.parse(text);
  if (!Array.isArray(parsed)) return [];
  return parsed.filter((entry): entry is string => typeof entry === 'string');
}

