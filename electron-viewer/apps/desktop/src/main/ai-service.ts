/**
 * Wires ai-core to the app: the credential store, the sessions database the
 * Kotlin app also writes, the gateway, and the evidence the layout inspector
 * produces. Analysis always persists the session first and then the request, so
 * a failure leaves a FAILED row instead of nothing.
 */
import { mkdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import {
  OpenAiAnalysisGateway,
  OpenAiModelCatalog,
  OpenAiResponsesClient,
  SqliteAnalysisSessionRepository,
  createAnalysisSessionId,
  fetchAiTransport,
  type AiHttpTransport,
  type AnalysisFinding,
  type AnalysisResult,
  type AnalysisSession,
  type CredentialStore,
  type PerformanceEvidence,
} from '@aps/ai-core/node';

export const AI_CREDENTIAL_KEY = 'openai:api-key';
export const AI_DEFAULT_MODEL = 'gpt-5';
export const AI_PROMPT_VERSION = 'layout-v1';
export const AI_PAYLOAD_POLICY_VERSION = 'minimal-v1';
export const AI_MAX_PAYLOAD_BYTES = 200_000;
export const AI_SESSION_HISTORY_LIMIT = 50;

/** The Kotlin app keeps its sessions in the same file, so both can read them. */
export function aiSessionsDatabasePath(): string {
  return join(homedir(), '.android-performance-studio', 'analysis-sessions.db');
}

export function aiCredentialFilePath(): string {
  return join(homedir(), '.android-performance-studio', 'ai-credentials.json');
}

export interface AiSettingsSnapshot {
  readonly configured: boolean;
  readonly persistent: boolean;
  readonly model: string;
}

export interface AiLayoutEvidenceInput {
  readonly captureId: string;
  readonly selectedNodeId?: string;
  /** Node classes and resources the capture holds, for the model summary. */
  readonly highlights: readonly string[];
  readonly payload: unknown;
}

/**
 * One evidence item per analysis, exactly like the Kotlin builder: the id names
 * the selected node when there is one, and the payload is the capture itself.
 */
export function layoutPerformanceEvidence(input: AiLayoutEvidenceInput): PerformanceEvidence {
  const summary =
    input.highlights.length > 0
      ? input.highlights.slice(0, 3).join(' · ')
      : 'Layout report summary';
  return {
    id: input.selectedNodeId === undefined ? 'layout-report' : 'layout:' + input.selectedNodeId,
    kind: 'layout-snapshot',
    summary,
    structuredPayload: JSON.stringify(input.payload),
  };
}

export interface AiAnalysisRequest {
  readonly evidence: readonly PerformanceEvidence[];
  readonly scopeDescription: string;
  readonly model?: string;
}

export interface AiAnalysisOutcome {
  readonly ok: boolean;
  readonly sessionId: string;
  readonly model?: string;
  readonly summary?: string;
  readonly findings?: readonly AnalysisFinding[];
  readonly error?: string;
}

export interface AiServiceDependencies {
  readonly credentials: CredentialStore;
  readonly persistent: boolean;
  readonly repository: SqliteAnalysisSessionRepository;
  readonly transport: AiHttpTransport;
  readonly now: () => number;
  readonly endpoint?: string;
}

export class AiAnalysisService {
  private readonly dependencies: AiServiceDependencies;

  constructor(dependencies: AiServiceDependencies) {
    this.dependencies = dependencies;
  }

  status(): AiSettingsSnapshot {
    return {
      configured: this.apiKey() !== undefined,
      persistent: this.dependencies.persistent,
      model: this.model(),
    };
  }

  saveCredential(value: string): AiSettingsSnapshot {
    this.dependencies.credentials.write(AI_CREDENTIAL_KEY, value.trim());
    return this.status();
  }

  clearCredential(): AiSettingsSnapshot {
    this.dependencies.credentials.delete(AI_CREDENTIAL_KEY);
    return this.status();
  }

  async models(): Promise<readonly string[]> {
    const apiKey = this.requireKey();
    const catalog = new OpenAiModelCatalog({
      apiKey,
      transport: this.dependencies.transport,
      ...(this.dependencies.endpoint !== undefined ? { responsesEndpoint: this.dependencies.endpoint } : {}),
    });
    return catalog.listModels();
  }

  async analyze(request: AiAnalysisRequest): Promise<AiAnalysisOutcome> {
    const sessionId = createAnalysisSessionId();
    const model = request.model ?? this.model();
    const session: AnalysisSession = {
      id: sessionId,
      originProfiler: 'LAYOUT_INSPECTOR',
      scope: { kind: 'REPORT_SUMMARY', description: request.scopeDescription },
      model,
      promptVersion: AI_PROMPT_VERSION,
      payloadPolicyVersion: AI_PAYLOAD_POLICY_VERSION,
      sourceSnapshotIds: [],
      buildEvidenceBundleIds: [],
      status: 'RUNNING',
      createdAt: new Date(this.dependencies.now()).toISOString(),
      provider: 'OpenAI Responses',
    };
    this.dependencies.repository.saveSession(session);
    try {
      const apiKey = this.requireKey();
      const analysisRequest = {
        sessionId,
        originProfiler: 'LAYOUT_INSPECTOR' as const,
        scope: session.scope,
        evidence: request.evidence,
        sourceCandidates: [],
        promptVersion: AI_PROMPT_VERSION,
        payloadPolicyVersion: AI_PAYLOAD_POLICY_VERSION,
      };
      const payloadBytes = Buffer.byteLength(JSON.stringify(request.evidence), 'utf8');
      if (payloadBytes > AI_MAX_PAYLOAD_BYTES) {
        throw new Error('AI payload is ' + String(payloadBytes) + ' bytes; limit is ' + String(AI_MAX_PAYLOAD_BYTES));
      }
      this.dependencies.repository.saveRequest(analysisRequest);
      const gateway = new OpenAiAnalysisGateway({
        client: new OpenAiResponsesClient({
          apiKey,
          model,
          transport: this.dependencies.transport,
          ...(this.dependencies.endpoint !== undefined ? { endpoint: this.dependencies.endpoint } : {}),
        }),
      });
      const result: AnalysisResult = await gateway.analyze(analysisRequest);
      this.dependencies.repository.saveResult(result);
      return { ok: true, sessionId, model: result.model, summary: result.summary, findings: result.findings };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this.dependencies.repository.saveSession({
        ...session,
        status: 'FAILED',
        errorMessage: error instanceof Error ? error.name : 'AnalysisFailure',
      });
      return { ok: false, sessionId, error: message };
    }
  }

  sessions(): readonly AnalysisSession[] {
    return this.dependencies.repository.listSessions(AI_SESSION_HISTORY_LIMIT);
  }

  findings(sessionId: string): readonly AnalysisFinding[] {
    return this.dependencies.repository.findings(sessionId);
  }

  private apiKey(): string | undefined {
    const value = this.dependencies.credentials.read(AI_CREDENTIAL_KEY);
    return value !== undefined && value.trim().length > 0 ? value : undefined;
  }

  private requireKey(): string {
    const apiKey = this.apiKey();
    if (apiKey === undefined) {
      throw new Error('Configure an OpenAI API key before running AI analysis');
    }
    return apiKey;
  }

  private model(): string {
    return this.dependencies.credentials.read(AI_MODEL_KEY) ?? AI_DEFAULT_MODEL;
  }
}

export const AI_MODEL_KEY = 'openai:model';

/** The app creates the sessions directory before the repository opens it. */
export function ensureAiDirectory(): void {
  mkdirSync(join(homedir(), '.android-performance-studio'), { recursive: true });
}

export { fetchAiTransport };
