import { describe, expect, it } from 'vitest';
import { OpenAiModelCatalog, isLikelyResponsesTextModel, modelCatalogEndpoint } from './model-catalog.js';
import type { AiHttpRequest } from './openai-client.js';

describe('OpenAiModelCatalog', () => {
  it('loads only likely Responses text models from the configured endpoint', async () => {
    let captured: AiHttpRequest | undefined;
    const catalog = new OpenAiModelCatalog({
      apiKey: 'secret',
      responsesEndpoint: 'https://example.test/openai/v1/responses',
      transport: {
        execute: async (request) => {
          captured = request;
          return {
            statusCode: 200,
            body: JSON.stringify({
              data: [{ id: 'text-embedding-3-small' }, { id: 'gpt-5' }, { id: 'gpt-image-1' }, { id: 'o3' }],
            }),
          };
        },
      },
    });

    expect(await catalog.listModels()).toEqual(['gpt-5', 'o3']);
    expect(captured?.url).toBe('https://example.test/openai/v1/models');
    expect(captured?.method).toBe('GET');
    expect(captured?.headers['Authorization']).toBe('Bearer secret');
  });

  it('deduplicates and sorts the identifiers it keeps', async () => {
    const catalog = new OpenAiModelCatalog({
      apiKey: 'secret',
      transport: {
        execute: async () => ({ statusCode: 200, body: JSON.stringify({ data: [{ id: 'o3' }, { id: 'gpt-5' }, { id: 'o3' }] }) }),
      },
    });
    expect(await catalog.listModels()).toEqual(['gpt-5', 'o3']);
  });

  it('requires an api key', async () => {
    const catalog = new OpenAiModelCatalog({ apiKey: ' ', transport: { execute: async () => ({ statusCode: 200, body: '{}' }) } });
    await expect(catalog.listModels()).rejects.toThrow(/API key is required/);
  });

  it('rejects an unsuccessful response', async () => {
    const catalog = new OpenAiModelCatalog({ apiKey: 'secret', transport: { execute: async () => ({ statusCode: 500, body: '' }) } });
    await expect(catalog.listModels()).rejects.toMatchObject({ kind: 'HTTP', statusCode: 500 });
  });
});

describe('modelCatalogEndpoint', () => {
  it('swaps the last path segment and strips a trailing slash', () => {
    expect(modelCatalogEndpoint('https://example.test/openai/v1/responses')).toBe('https://example.test/openai/v1/models');
    expect(modelCatalogEndpoint('https://example.test/v1/responses/')).toBe('https://example.test/v1/models');
    expect(modelCatalogEndpoint('http://localhost:8080/v1/responses')).toBe('http://localhost:8080/v1/models');
  });

  it('rejects a plain http endpoint that is not localhost', () => {
    expect(() => modelCatalogEndpoint('http://example.test/v1/responses')).toThrow(/must use HTTPS/);
  });
});

describe('isLikelyResponsesTextModel', () => {
  it('keeps text families and drops the specialised ones', () => {
    for (const id of ['gpt-5', 'gpt-4o-mini', 'o3', 'o3-mini', 'o4-mini', 'ft:gpt-4o-mini-2024-07-18']) {
      expect(isLikelyResponsesTextModel(id), id).toBe(true);
    }
    for (const id of ['text-embedding-3-small', 'gpt-image-1', 'gpt-4o-realtime-preview', 'whisper-1', 'o3mini', 'gpt-4o-audio-preview', 'omni-moderation-latest', 'chatgpt-4o-latest']) {
      expect(isLikelyResponsesTextModel(id), id).toBe(false);
    }
  });
});
