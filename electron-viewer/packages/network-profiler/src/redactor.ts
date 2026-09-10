import { NETWORK_REDACTION_POLICY_VERSION } from './model.js';

const REDACTED_VALUE = '<redacted>';
const REDACTED_PATH = '/<redacted-path>';
const INVALID_URL = 'redacted://invalid-url';

/** Sensitive query keys are always redacted, even when allowlisted. */
export const SENSITIVE_QUERY_KEYS: ReadonlySet<string> = new Set([
  'token',
  'access_token',
  'refresh_token',
  'id_token',
  'auth',
  'authorization',
  'apikey',
  'api_key',
  'key',
  'password',
  'passwd',
  'secret',
  'client_secret',
  'session',
  'sessionid',
  'jsessionid',
  'credential',
  'signature',
  'sig',
]);

export const SENSITIVE_HEADERS: ReadonlySet<string> = new Set([
  'authorization',
  'proxy-authorization',
  'cookie',
  'set-cookie',
  'x-api-key',
  'x-auth-token',
  'x-csrf-token',
]);

export interface RedactionResult {
  readonly value: string;
  readonly warnings: readonly string[];
}

function isSensitiveKey(key: string): boolean {
  return [...SENSITIVE_QUERY_KEYS].some((candidate) => candidate.toLowerCase() === key.toLowerCase());
}

function minimizePath(path: string): string {
  if (path.length === 0) return '';
  if (path === '/') return '/';
  return REDACTED_PATH;
}

/**
 * WHATWG URL normalizes an absent path to "/", while java.net.URI keeps it empty.
 * Read the raw string so the ported behaviour matches the Kotlin original.
 */
function explicitPathOf(raw: string): string {
  const schemeEnd = raw.indexOf('//');
  if (schemeEnd === -1) return '';
  const afterAuthority = raw.slice(schemeEnd + 2);
  const authorityEnd = afterAuthority.search(/[/?#]/);
  if (authorityEnd === -1) return '';
  const remainder = afterAuthority.slice(authorityEnd);
  if (!remainder.startsWith('/')) return '';
  const end = remainder.search(/[?#]/);
  return end === -1 ? remainder : remainder.slice(0, end);
}

/**
 * Port of NetworkUrlRedactor: deny-by-default, path minimized, fragment removed,
 * userinfo replaced, and only allowlisted query values preserved.
 */
export function redactUrl(raw: string, queryKeyAllowlist: ReadonlySet<string> = new Set()): RedactionResult {
  const warnings: string[] = [];
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return { value: INVALID_URL, warnings };
  }
  if (url.hostname.length === 0) return { value: INVALID_URL, warnings };
  const parts: string[] = [url.protocol + '//'];
  if (url.username.length > 0 || url.password.length > 0) parts.push(REDACTED_VALUE + '@');
  parts.push(url.hostname);
  if (url.port.length > 0) parts.push(':' + url.port);
  parts.push(minimizePath(explicitPathOf(raw)));
  if (url.search.length > 1) {
    const parameters = url.search.slice(1).split('&').map((parameter) => {
      const separator = parameter.indexOf('=');
      const key = separator === -1 ? parameter : parameter.slice(0, separator);
      const sensitive = isSensitiveKey(key);
      if (sensitive && queryKeyAllowlist.has(key)) {
        warnings.push('Query key "' + key + '" is in the allowlist but is classified as sensitive and was redacted.');
      }
      const value = sensitive || !queryKeyAllowlist.has(key)
        ? REDACTED_VALUE
        : separator === -1
          ? ''
          : parameter.slice(separator + 1);
      return key + '=' + value;
    });
    parts.push('?' + parameters.join('&'));
  }
  return { value: parts.join(''), warnings };
}

/** Header values for credential-bearing headers are replaced, names are kept. */
export function redactHeaders(
  pairs: Iterable<readonly [string, string]>,
): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [name, value] of pairs) {
    result[name] = SENSITIVE_HEADERS.has(name.toLowerCase()) ? REDACTED_VALUE : value;
  }
  return result;
}

export const REDACTION_POLICY_VERSION = NETWORK_REDACTION_POLICY_VERSION;
