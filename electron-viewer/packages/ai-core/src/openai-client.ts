/**
 * Port of ai-core OpenAiResponsesClient.kt: the structured Responses API call,
 * the transport seam the tests use, and the failure classification the UI shows.
 */
import { parseJsonRecord } from './json.js';

export const AI_HTTP_SUCCESS_MIN = 200;
export const AI_HTTP_SUCCESS_MAX_EXCLUSIVE = 300;
export const AI_CONNECT_TIMEOUT_MS = 15_000;
export const AI_REQUEST_TIMEOUT_MS = 120_000;
export const OPENAI_DEFAULT_ENDPOINT = 'https://api.openai.com/v1/responses';

export type AiHttpMethod = 'GET' | 'POST';

export interface AiHttpRequest {
  readonly url: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly body?: string;
  readonly method?: AiHttpMethod;
}

export interface AiHttpResponse {
  readonly statusCode: number;
  readonly body: string;
}

export interface AiHttpTransport {
  execute(request: AiHttpRequest): Promise<AiHttpResponse>;
}

export interface StructuredAiRequest {
  readonly instructions: string;
  readonly input: string;
  readonly schemaName: string;
  readonly schemaJson: string;
}

export interface AiTextResponse {
  readonly model: string;
  readonly outputText: string;
}

export const AI_REQUEST_FAILURE_KINDS = [
  'AUTHENTICATION',
  'RATE_LIMIT',
  'TIMEOUT',
  'NETWORK',
  'HTTP',
] as const;
export type AiRequestFailureKind = (typeof AI_REQUEST_FAILURE_KINDS)[number];

export class AiRequestException extends Error {
  readonly kind: AiRequestFailureKind;
  readonly statusCode: number | undefined;

  constructor(kind: AiRequestFailureKind, options: { readonly statusCode?: number; readonly cause?: unknown } = {}) {
    const detail = kind.toLowerCase();
    super(
      options.statusCode === undefined
        ? 'AI request failed: ' + detail
        : 'AI request failed (' + String(options.statusCode) + '): ' + detail,
      options.cause === undefined ? undefined : { cause: options.cause },
    );
    this.name = 'AiRequestException';
    this.kind = kind;
    this.statusCode = options.statusCode;
  }
}

/** The status codes the Kotlin client maps to a specific failure kind. */
export function classifyHttpStatus(statusCode: number): AiRequestFailureKind {
  if (statusCode === 401 || statusCode === 403) return 'AUTHENTICATION';
  if (statusCode === 408 || statusCode === 504) return 'TIMEOUT';
  if (statusCode === 429) return 'RATE_LIMIT';
  return 'HTTP';
}

export function isSuccessStatus(statusCode: number): boolean {
  return statusCode >= AI_HTTP_SUCCESS_MIN && statusCode < AI_HTTP_SUCCESS_MAX_EXCLUSIVE;
}

/** Timeouts are distinguishable from other transport failures by their name. */
export function classifyTransportFailure(error: unknown): AiRequestFailureKind {
  const name = error instanceof Error ? error.name : '';
  return name === 'TimeoutError' || name === 'AbortError' ? 'TIMEOUT' : 'NETWORK';
}

/**
 * Runs a transport call and turns its failures into AiRequestException, the way
 * the Kotlin extension does for HttpTimeoutException and IOException. An
 * exception that is already classified passes through unchanged.
 */
export async function executeClassified(
  transport: AiHttpTransport,
  request: AiHttpRequest,
): Promise<AiHttpResponse> {
  try {
    return await transport.execute(request);
  } catch (error) {
    if (error instanceof AiRequestException) throw error;
    throw new AiRequestException(classifyTransportFailure(error), { cause: error });
  }
}

export interface OpenAiResponsesClientOptions {
  readonly apiKey: string;
  readonly model: string;
  readonly endpoint?: string;
  readonly transport?: AiHttpTransport;
}

export class OpenAiResponsesClient {
  private readonly apiKey: string;
  private readonly model: string;
  private readonly endpoint: string;
  private readonly transport: AiHttpTransport;

  constructor(options: OpenAiResponsesClientOptions) {
    this.apiKey = options.apiKey;
    this.model = options.model;
    this.endpoint = options.endpoint ?? OPENAI_DEFAULT_ENDPOINT;
    this.transport = options.transport ?? fetchAiTransport();
  }

  async execute(request: StructuredAiRequest): Promise<AiTextResponse> {
    if (this.apiKey.trim().length === 0) throw new Error('An API key is required for AI requests');
    const response = await executeClassified(this.transport, {
      url: this.endpoint,
      headers: {
        Authorization: 'Bearer ' + this.apiKey,
        'Content-Type': 'application/json',
      },
      body: buildRequestBody(this.model, request),
    });
    if (!isSuccessStatus(response.statusCode)) {
      throw new AiRequestException(classifyHttpStatus(response.statusCode), { statusCode: response.statusCode });
    }
    return { model: this.model, outputText: extractOutputText(response.body) };
  }
}

export function buildRequestBody(model: string, request: StructuredAiRequest): string {
  return JSON.stringify({
    model,
    instructions: request.instructions,
    input: request.input,
    text: {
      format: {
        type: 'json_schema',
        name: request.schemaName,
        strict: true,
        schema: JSON.parse(request.schemaJson) as unknown,
      },
    },
  });
}

/** The Responses API returns text either flat or nested inside output items. */
export function extractOutputText(body: string): string {
  const root = parseJsonRecord(body, 'AI response');
  if (typeof root['output_text'] === 'string') return root['output_text'];
  const output = root['output'];
  if (Array.isArray(output)) {
    for (const item of output) {
      if (typeof item !== 'object' || item === null) continue;
      const content = (item as Record<string, unknown>)['content'];
      if (!Array.isArray(content)) continue;
      for (const part of content) {
        if (typeof part !== 'object' || part === null) continue;
        const entry = part as Record<string, unknown>;
        if (entry['type'] === 'output_text' && typeof entry['text'] === 'string') return entry['text'];
      }
    }
  }
  throw new Error('AI response did not contain output text');
}

export interface FetchAiTransportOptions {
  /** Total request budget; the Kotlin client uses 120 s. */
  readonly timeoutMs?: number;
  /** Injected in tests; defaults to the global fetch. */
  readonly fetch?: typeof globalThis.fetch;
}

/**
 * fetch based transport. The JDK client sets a separate 15 s connect timeout;
 * fetch cannot express that, so the whole request shares one deadline.
 */
export function fetchAiTransport(options: FetchAiTransportOptions = {}): AiHttpTransport {
  const implementation = options.fetch ?? globalThis.fetch;
  const timeoutMs = options.timeoutMs ?? AI_REQUEST_TIMEOUT_MS;
  return {
    async execute(request: AiHttpRequest): Promise<AiHttpResponse> {
      const method = request.method ?? 'POST';
      const response = await implementation(request.url, {
        method,
        headers: { ...request.headers },
        signal: AbortSignal.timeout(timeoutMs),
        ...(method === 'GET' ? {} : { body: request.body ?? '' }),
      });
      return { statusCode: response.status, body: await response.text() };
    },
  };
}

