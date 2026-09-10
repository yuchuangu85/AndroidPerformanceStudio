import { describe, expect, it } from 'vitest';
import { redactHeaders, redactUrl } from './redactor.js';

describe('redactUrl', () => {
  it('keeps scheme, host, and port but minimizes the path and redacts query values', () => {
    const result = redactUrl('https://api.example.com:8443/v1/users/12345?token=abc&page=2#frag');
    expect(result.value).toBe('https://api.example.com:8443/<redacted-path>?token=<redacted>&page=<redacted>');
  });

  it('redacts userinfo and drops the root path only when it is root', () => {
    expect(redactUrl('https://user:pass@example.com/').value).toBe('https://<redacted>@example.com/');
    expect(redactUrl('https://example.com').value).toBe('https://example.com');
  });

  it('preserves allowlisted query values but never sensitive ones', () => {
    const allowed = redactUrl('https://example.com/a?page=3&size=20', new Set(['page', 'size']));
    expect(allowed.value).toBe('https://example.com/<redacted-path>?page=3&size=20');

    const sensitive = redactUrl('https://example.com/a?token=abc', new Set(['token']));
    expect(sensitive.value).toBe('https://example.com/<redacted-path>?token=<redacted>');
    expect(sensitive.warnings).toHaveLength(1);
    expect(sensitive.warnings[0]).toContain('sensitive');
  });

  it('replaces malformed URLs with a stable marker', () => {
    expect(redactUrl('not a url').value).toBe('redacted://invalid-url');
    expect(redactUrl('').value).toBe('redacted://invalid-url');
  });
});

describe('redactHeaders', () => {
  it('redacts credential-bearing headers and keeps the rest', () => {
    const headers = redactHeaders([
      ['Authorization', 'Bearer secret'],
      ['Cookie', 'sid=1'],
      ['Set-Cookie', 'sid=1'],
      ['X-Api-Key', 'abc'],
      ['Accept', 'application/json'],
      ['Content-Type', 'text/plain'],
    ]);
    expect(headers['Authorization']).toBe('<redacted>');
    expect(headers['Cookie']).toBe('<redacted>');
    expect(headers['Set-Cookie']).toBe('<redacted>');
    expect(headers['X-Api-Key']).toBe('<redacted>');
    expect(headers['Accept']).toBe('application/json');
    expect(headers['Content-Type']).toBe('text/plain');
  });
});
