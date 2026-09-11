import { describe, expect, it } from 'vitest';
import {
  AiRequestException,
  OPENAI_DEFAULT_ENDPOINT,
  OpenAiResponsesClient,
  executeClassified,
  fetchAiTransport,
  type AiHttpRequest,
  type AiHttpTransport,
  type StructuredAiRequest,
} from './openai-client.js';

const STRUCTURED: StructuredAiRequest = {
  instructions: 'Return JSON',
  input: 'snapshot',
  schemaName: 'analysis',
  schemaJson: JSON.stringify({ type: 'object' }),
};

function clientWith(transport: AiHttpTransport): OpenAiResponsesClient {
  return new OpenAiResponsesClient({ apiKey: 'test-key', model: 'gpt-test', transport });
}

describe('OpenAiResponsesClient', () => {
  it('builds a structured request and extracts nested output text', async () => {
    let captured: AiHttpRequest | undefined;
    const client = clientWith({
      execute: async (request) => {
        captured = request;
        return {
          statusCode: 200,
          body: JSON.stringify({ output: [{ content: [{ type: 'output_text', text: '{"summary":"ok"}' }] }] }),
        };
      },
    });

    const response = await client.execute(STRUCTURED);

    expect(captured?.url).toBe(OPENAI_DEFAULT_ENDPOINT);
    expect(captured?.headers['Authorization']).toBe('Bearer test-key');
    expect(captured?.headers['Content-Type']).toBe('application/json');
    expect(captured?.body).toContain('"name":"analysis"');
    expect(captured?.body).toContain('"strict":true');
    expect(response.model).toBe('gpt-test');
    expect(response.outputText).toBe('{"summary":"ok"}');
  });

  it('prefers the flat output_text field', async () => {
    const client = clientWith({
      execute: async () => ({ statusCode: 200, body: JSON.stringify({ output_text: 'flat' }) }),
    });
    expect((await client.execute(STRUCTURED)).outputText).toBe('flat');
  });

  it('rejects unsuccessful responses with a classified failure', async () => {
    const client = clientWith({ execute: async () => ({ statusCode: 429, body: 'rate limited' }) });
    const failure = await client.execute(STRUCTURED).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(AiRequestException);
    expect((failure as AiRequestException).kind).toBe('RATE_LIMIT');
    expect((failure as AiRequestException).statusCode).toBe(429);
  });

  it('classifies transport timeout and network failures', async () => {
    const timeout = clientWith({
      execute: async () => { throw new DOMException('timed out', 'TimeoutError'); },
    });
    await expect(timeout.execute(STRUCTURED)).rejects.toMatchObject({ kind: 'TIMEOUT' });

    const offline = clientWith({
      execute: async () => { throw new TypeError('offline'); },
    });
    await expect(offline.execute(STRUCTURED)).rejects.toMatchObject({ kind: 'NETWORK' });
  });

  it('requires an api key before it builds a request', async () => {
    const client = new OpenAiResponsesClient({ apiKey: '  ', model: 'gpt-test', transport: { execute: async () => ({ statusCode: 200, body: '{}' }) } });
    await expect(client.execute(STRUCTURED)).rejects.toThrow(/API key is required/);
  });

  it('reports a response without output text', async () => {
    const client = clientWith({ execute: async () => ({ statusCode: 200, body: '{}' }) });
    await expect(client.execute(STRUCTURED)).rejects.toThrow(/did not contain output text/);
  });

  it('maps every documented status code', async () => {
    for (const [status, kind] of [[401, 'AUTHENTICATION'], [403, 'AUTHENTICATION'], [408, 'TIMEOUT'], [504, 'TIMEOUT'], [500, 'HTTP']] as const) {
      const client = clientWith({ execute: async () => ({ statusCode: status, body: '' }) });
      await expect(client.execute(STRUCTURED)).rejects.toMatchObject({ kind });
    }
  });
});

describe('fetchAiTransport', () => {
  it('forwards the request to fetch and returns status and body', async () => {
    const calls: { url: string; init: RequestInit }[] = [];
    const transport = fetchAiTransport({
      timeoutMs: 5_000,
      fetch: (async (url: string | URL | Request, init?: RequestInit) => {
        calls.push({ url: String(url), init: init ?? {} });
        return new Response('hello', { status: 201 });
      }) as typeof globalThis.fetch,
    });

    const response = await transport.execute({ url: 'https://example.test/v1/responses', headers: { A: 'b' }, body: '{}' });

    expect(response).toEqual({ statusCode: 201, body: 'hello' });
    expect(calls[0]?.init.method).toBe('POST');
    expect(calls[0]?.init.body).toBe('{}');
    // The GET request used by the model catalog must not carry a body.
    await transport.execute({ url: 'https://example.test/v1/models', headers: {}, method: 'GET' });
    expect(calls[1]?.init.method).toBe('GET');
    expect(calls[1]?.init.body).toBeUndefined();
  });

  it('classifies an aborted fetch through executeClassified', async () => {
    const transport = fetchAiTransport({
      fetch: (async () => {
        throw new DOMException('The operation was aborted', 'AbortError');
      }) as typeof globalThis.fetch,
    });
    await expect(executeClassified(transport, { url: 'https://example.test', headers: {} })).rejects.toMatchObject({
      kind: 'TIMEOUT',
    });
  });
});
