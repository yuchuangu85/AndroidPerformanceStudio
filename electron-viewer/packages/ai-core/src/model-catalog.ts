/**
 * Port of ai-core OpenAiModelCatalog.kt.
 *
 * The /v1/models listing has no capability field, so the catalog filters by name
 * and keeps only the families that can answer a Responses request with text.
 */
import { parseJsonRecord } from './json.js';
import {
  OPENAI_DEFAULT_ENDPOINT,
  executeClassified,
  isSuccessStatus,
  classifyHttpStatus,
  AiRequestException,
  fetchAiTransport,
  type AiHttpTransport,
} from './openai-client.js';

export interface OpenAiModelCatalogOptions {
  readonly apiKey: string;
  readonly responsesEndpoint?: string;
  readonly transport?: AiHttpTransport;
}

export class OpenAiModelCatalog {
  private readonly apiKey: string;
  private readonly modelsEndpoint: string;
  private readonly transport: AiHttpTransport;

  constructor(options: OpenAiModelCatalogOptions) {
    this.apiKey = options.apiKey;
    this.modelsEndpoint = modelCatalogEndpoint(options.responsesEndpoint ?? OPENAI_DEFAULT_ENDPOINT);
    this.transport = options.transport ?? fetchAiTransport();
  }

  async listModels(): Promise<string[]> {
    if (this.apiKey.trim().length === 0) throw new Error('An API key is required to list AI models');
    const response = await executeClassified(this.transport, {
      url: this.modelsEndpoint,
      headers: { Authorization: 'Bearer ' + this.apiKey },
      method: 'GET',
    });
    if (!isSuccessStatus(response.statusCode)) {
      throw new AiRequestException(classifyHttpStatus(response.statusCode), { statusCode: response.statusCode });
    }
    const root = parseJsonRecord(response.body, 'AI model list');
    const data = root['data'];
    if (!Array.isArray(data)) throw new Error('AI model list did not contain a data array');
    const models = new Set<string>();
    for (const entry of data) {
      if (typeof entry !== 'object' || entry === null) continue;
      const id = (entry as Record<string, unknown>)['id'];
      if (typeof id === 'string' && isLikelyResponsesTextModel(id)) models.add(id);
    }
    return [...models].sort();
  }
}

/** The models endpoint is the responses endpoint with its last segment swapped. */
export function modelCatalogEndpoint(responsesEndpoint: string): string {
  // trimEnd('/') is Kotlin: JavaScript's trimEnd only strips whitespace.
  const parsed = new URL(responsesEndpoint.replace(/\/+$/, ''));
  if (parsed.protocol !== 'https:' && parsed.hostname !== 'localhost') {
    throw new Error('AI endpoint must use HTTPS (or localhost for development)');
  }
  return new URL('models', parsed).toString();
}

const INCOMPATIBLE_MODEL_TOKENS = [
  'audio',
  'image',
  'realtime',
  'transcribe',
  'tts',
  'search',
  'embedding',
  'moderation',
  'chatgpt',
  'deep-research',
];

/**
 * /v1/models has no capability field; replace this name filter if the API adds
 * one. The Kotlin regex o\d(?:-|$) is anchored, so "o3" and "o3-mini" pass and
 * "o3mini" does not.
 */
export function isLikelyResponsesTextModel(modelId: string): boolean {
  const normalized = (modelId.startsWith('ft:') ? modelId.slice(3) : modelId).toLowerCase();
  const textFamily = normalized.startsWith('gpt-') || /^o\d(?:-|$)/.test(normalized);
  const incompatible = INCOMPATIBLE_MODEL_TOKENS.some((token) => normalized.includes(token));
  return textFamily && !incompatible;
}
