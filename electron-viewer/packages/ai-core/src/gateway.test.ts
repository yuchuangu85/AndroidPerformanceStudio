import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { afterEach, describe, expect, it } from 'vitest';
import { InMemoryCredentialStore } from './credentials.js';
import { OpenAiAnalysisGateway, MAX_TITLE_LENGTH, payloadText } from './gateway.js';
import { OpenAiResponsesClient, type AiHttpTransport } from './openai-client.js';
import { SqliteAnalysisSessionRepository } from './session-repository.js';

const directories: string[] = [];

afterEach(() => {
  while (directories.length > 0) rmSync(directories.pop() as string, { recursive: true, force: true });
});

function temporaryDirectory(): string {
  const directory = mkdtempSync(join(tmpdir(), 'ai-core-test-'));
  directories.push(directory);
  return directory;
}

/** The Kotlin test builds the same envelope around the model output. */
function gatewayReturning(output: string): OpenAiAnalysisGateway {
  const transport: AiHttpTransport = {
    execute: async () => ({ statusCode: 200, body: JSON.stringify({ output_text: output }) }),
  };
  return new OpenAiAnalysisGateway({
    client: new OpenAiResponsesClient({ apiKey: 'api-secret', model: 'test-model', transport }),
  });
}

function request() {
  return {
    sessionId: 'session',
    originProfiler: 'LAYOUT_INSPECTOR' as const,
    scope: { kind: 'CURRENT_SELECTION' as const, description: 'node' },
    evidence: [{ id: 'evidence-1', kind: 'layout', summary: 'summary', structuredPayload: '{}' }],
    sourceCandidates: [
      { id: 'candidate-1', relativePath: 'A.kt', symbol: 'A', resolutionConfidence: 'EXACT', reasons: ['type'], sourceSnippet: null },
    ],
    promptVersion: 'v1',
    payloadPolicyVersion: 'minimal-v1',
  };
}

/** One finding with the given overrides, as the model would return it. */
function finding(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'finding-1',
    severity: 'INFO',
    title: 'x',
    explanation: 'x',
    recommendation: 'x',
    analysisConfidence: 1,
    performanceEvidenceIds: ['evidence-1'],
    sourceCandidateIds: [],
    ...overrides,
  };
}

function modelOutput(summary: string, findings: readonly unknown[]): string {
  return JSON.stringify({ summary, findings });
}

describe('OpenAiAnalysisGateway', () => {
  it('accepts only evidence-bound structured findings', async () => {
    const gateway = gatewayReturning(
      modelOutput('A verified hotspot was found', [
        finding({
          severity: 'WARNING',
          title: 'Hot path',
          explanation: 'The selected call is expensive',
          recommendation: 'Reduce repeated work',
          analysisConfidence: 0.8,
          sourceCandidateIds: ['candidate-1'],
        }),
      ]),
    );

    const result = await gateway.analyze(request());

    expect(result.model).toBe('test-model');
    expect(result.findings[0]?.sourceCandidateIds).toEqual(['candidate-1']);
    expect(result.findings[0]?.analysisConfidence).toBe(0.8);
  });

  it('rejects unknown source candidate ids without partial acceptance', async () => {
    const gateway = gatewayReturning(modelOutput('bad', [finding({ sourceCandidateIds: ['invented'] })]));
    await expect(gateway.analyze(request())).rejects.toThrow(/unknown source candidate/);
  });

  it('rejects duplicate source candidate ids', async () => {
    const gateway = gatewayReturning(modelOutput('bad', [finding({ sourceCandidateIds: ['candidate-1', 'candidate-1'] })]));
    await expect(gateway.analyze(request())).rejects.toThrow(/duplicate source candidate/);
  });

  it('rejects locally oversized finding text', async () => {
    const gateway = gatewayReturning(modelOutput('bad', [finding({ title: 'x'.repeat(MAX_TITLE_LENGTH + 1) })]));
    await expect(gateway.analyze(request())).rejects.toThrow(/title/);
  });

  it('rejects out of range analysis confidence', async () => {
    const gateway = gatewayReturning(modelOutput('bad', [finding({ analysisConfidence: 2 })]));
    await expect(gateway.analyze(request())).rejects.toThrow(/confidence was out of range/);
  });

  it('rejects a finding that cites no evidence', async () => {
    const gateway = gatewayReturning(modelOutput('bad', [finding({ performanceEvidenceIds: [] })]));
    await expect(gateway.analyze(request())).rejects.toThrow(/unknown performance evidence/);
  });

  it('rejects duplicate finding ids', async () => {
    const gateway = gatewayReturning(modelOutput('bad', [finding(), finding({ title: 'y' })]));
    await expect(gateway.analyze(request())).rejects.toThrow(/duplicate finding IDs/);
  });

  it('requires evidence before it calls the model', async () => {
    const gateway = gatewayReturning(modelOutput('x', []));
    await expect(gateway.analyze({ ...request(), evidence: [] })).rejects.toThrow(/requires performance evidence/);
  });

  it('never sends the local content hash to the model', () => {
    const text = payloadText({
      ...request(),
      sourceCandidates: [
        {
          id: 'candidate-1',
          relativePath: 'A.kt',
          symbol: 'A',
          resolutionConfidence: 'EXACT',
          reasons: ['type'],
          sourceSnippet: 'val x = 1',
          startLine: 3,
          endLine: 4,
          contentHash: 'deadbeef',
          indexVersion: 7,
          indexComplete: true,
        },
      ],
    });
    const parsed = JSON.parse(text) as { sourceCandidates: Record<string, unknown>[] };
    const candidate = parsed.sourceCandidates[0] as Record<string, unknown>;
    expect(candidate['contentHash']).toBeUndefined();
    expect(candidate['startLine']).toBe(3);
    expect(candidate['indexVersion']).toBe(7);
    expect(candidate['indexComplete']).toBe(true);
    expect(Object.keys(parsed)).toContain('payloadPolicyVersion');
  });
});

describe('SqliteAnalysisSessionRepository', () => {
  it('versions and restores findings without credential data', () => {
    const directory = temporaryDirectory();
    const repository = new SqliteAnalysisSessionRepository(join(directory, 'analysis.db'));
    try {
      const session = sessionRow();
      repository.saveSession(session);
      repository.saveRequest({
        ...request(),
        sessionId: session.id,
        evidence: [
          { id: 'evidence', kind: 'layout', summary: 'safe summary', structuredPayload: '{"private":"source-body"}' },
        ],
      });
      repository.saveResult({
        sessionId: session.id,
        model: 'test-model',
        summary: 'summary',
        findings: [
          {
            id: 'finding',
            severity: 'WARNING',
            title: 'title',
            explanation: 'explanation',
            recommendation: 'recommendation',
            analysisConfidence: 0.75,
            performanceEvidenceIds: ['evidence'],
            sourceCandidateIds: ['candidate'],
          },
        ],
      });

      expect(repository.session(session.id)?.status).toBe('SUCCEEDED');
      expect(repository.session(session.id)?.createdAt).toBe(session.createdAt);
      expect(repository.findings(session.id)[0]?.sourceCandidateIds).toEqual(['candidate']);
      expect(repository.evidence(session.id)[0]?.summary).toBe('safe summary');
      expect(repository.evidence(session.id)[0]?.payloadHash).toHaveLength(64);
      const bytes = readFileSync(join(directory, 'analysis.db'), 'latin1');
      expect(bytes).not.toContain('api-secret');
      expect(bytes).not.toContain('source-body');
    } finally {
      repository.close();
    }
  });

  it('reads and migrates a database written with the legacy finding key', () => {
    const directory = temporaryDirectory();
    const path = join(directory, 'analysis.db');
    seedLegacyDatabase(path);

    const repository = new SqliteAnalysisSessionRepository(path);
    try {
      expect(repository.session('legacy-session')?.provider).toBeNull();
      expect(repository.session('legacy-session')?.model).toBe('gpt-test');
      expect(repository.findings('legacy-session')[0]?.title).toBe('legacy title');
      expect(repository.candidates('legacy-session')[0]?.startLine).toBe(12);
      // The migration makes the finding key (session_id, id), so the same
      // finding id can exist in a second session.
      repository.saveSession({ ...sessionRow(), id: 'second-session' });
      repository.saveResult({
        sessionId: 'second-session',
        model: 'test-model',
        summary: 'summary',
        findings: [
          {
            id: 'legacy-finding',
            severity: 'INFO',
            title: 'other',
            explanation: 'x',
            recommendation: 'x',
            analysisConfidence: 1,
            performanceEvidenceIds: [],
            sourceCandidateIds: [],
          },
        ],
      });
      expect(repository.findings('second-session')).toHaveLength(1);
      expect(repository.findings('legacy-session')).toHaveLength(1);
    } finally {
      repository.close();
    }
  });
});

describe('InMemoryCredentialStore', () => {
  it('supports replace and delete', () => {
    const store = new InMemoryCredentialStore();
    store.write('openai', 'first');
    store.write('openai', 'second');
    expect(store.read('openai')).toBe('second');
    store.delete('openai');
    expect(store.read('openai')).toBeUndefined();
  });
});

function sessionRow() {
  return {
    id: 'session',
    originProfiler: 'SIMPLEPERF' as const,
    scope: { kind: 'CURRENT_SELECTION' as const, description: 'renderFrame' },
    model: null,
    promptVersion: 'v1',
    payloadPolicyVersion: 'minimal-v1',
    sourceSnapshotIds: ['snapshot'],
    buildEvidenceBundleIds: [],
    status: 'RUNNING' as const,
    createdAt: '2026-01-01T00:00:00Z',
  };
}

/** The schema the Kotlin app writes: no provider column, finding keyed by id. */
// One statement per entry: node:sqlite exec is happiest with a single statement.
function seedLegacyDatabase(path: string): void {
  const database = new DatabaseSync(path);
  try {
    for (const statement of LEGACY_SCHEMA) database.exec(statement);
    database
      .prepare(
        'INSERT INTO analysis_session(id, origin_profiler, scope_kind, scope_description, model, ' +
          'prompt_version, payload_policy_version, source_snapshot_ids, build_evidence_ids, status, ' +
          'created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
      )
      .run(
        'legacy-session',
        'SIMPLEPERF',
        'REPORT_SUMMARY',
        'report',
        'gpt-test',
        'v1',
        'minimal-v1',
        '[]',
        '[]',
        'SUCCEEDED',
        '2026-01-01T00:00:00Z',
      );
    database
      .prepare(
        'INSERT INTO analysis_finding(id, session_id, severity, title, explanation, recommendation, ' +
          'analysis_confidence, evidence_ids, candidate_ids) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
      )
      .run('legacy-finding', 'legacy-session', 'WARNING', 'legacy title', 'x', 'x', 0.5, '[]', '[]');
    database
      .prepare(
        'INSERT INTO analysis_candidate(session_id, id, relative_path, start_line, end_line, ' +
          'resolution_confidence, content_hash) VALUES (?, ?, ?, ?, ?, ?, ?)',
      )
      .run('legacy-session', 'candidate', 'A.kt', 12, 18, 'EXACT', null);
  } finally {
    database.close();
  }
}

/** Each entry is one complete statement: exec runs exactly one at a time. */
const LEGACY_SCHEMA = [
  [
    'CREATE TABLE analysis_session(',
    '  id TEXT PRIMARY KEY, origin_profiler TEXT NOT NULL, scope_kind TEXT NOT NULL,',
    '  scope_description TEXT NOT NULL, model TEXT, prompt_version TEXT NOT NULL,',
    '  payload_policy_version TEXT NOT NULL, source_snapshot_ids TEXT NOT NULL,',
    '  build_evidence_ids TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL,',
    '  parent_session_id TEXT, summary TEXT, error_message TEXT',
    ')',
  ].join('\n'),
  [
    'CREATE TABLE analysis_finding(',
    '  id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES analysis_session(id) ON DELETE CASCADE,',
    '  severity TEXT NOT NULL, title TEXT NOT NULL, explanation TEXT NOT NULL,',
    '  recommendation TEXT NOT NULL, analysis_confidence REAL NOT NULL,',
    '  evidence_ids TEXT NOT NULL, candidate_ids TEXT NOT NULL',
    ')',
  ].join('\n'),
  [
    'CREATE TABLE analysis_evidence(',
    '  session_id TEXT NOT NULL REFERENCES analysis_session(id) ON DELETE CASCADE,',
    '  id TEXT NOT NULL, kind TEXT NOT NULL, summary TEXT NOT NULL,',
    '  payload_hash TEXT NOT NULL, PRIMARY KEY(session_id, id)',
    ')',
  ].join('\n'),
  [
    'CREATE TABLE analysis_candidate(',
    '  session_id TEXT NOT NULL REFERENCES analysis_session(id) ON DELETE CASCADE,',
    '  id TEXT NOT NULL, relative_path TEXT NOT NULL, start_line INTEGER, end_line INTEGER,',
    '  resolution_confidence TEXT NOT NULL, content_hash TEXT, PRIMARY KEY(session_id, id)',
    ')',
  ].join('\n'),
];
