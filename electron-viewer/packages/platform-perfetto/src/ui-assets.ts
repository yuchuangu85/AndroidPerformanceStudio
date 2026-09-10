import { posix } from 'node:path';

const MIME_TYPES: Readonly<Record<string, string>> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.wasm': 'application/wasm',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.map': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
};

export function contentTypeForPath(path: string): string {
  const lower = path.toLowerCase();
  const dot = lower.lastIndexOf('.');
  if (dot === -1) return 'application/octet-stream';
  return MIME_TYPES[lower.slice(dot)] ?? 'application/octet-stream';
}

export interface PerfettoUiAssetProbe {
  readonly isDirectory: (path: string) => boolean;
  readonly isFile: (path: string) => boolean;
}

export function isPerfettoUiAssetsDirectory(directory: string, probe: PerfettoUiAssetProbe): boolean {
  return probe.isDirectory(directory) && probe.isFile(posix.join(directory, 'index.html'));
}

/**
 * Port of PerfettoUiServer.tryFindUiAssetsDir: packaged resources first, then an
 * explicit override, then the repository build output, then the downloaded cache.
 */
export function findPerfettoUiAssetsDirectory(
  candidates: readonly (string | undefined)[],
  probe: PerfettoUiAssetProbe,
): string | undefined {
  return candidates.find(
    (candidate) => candidate !== undefined && candidate.length > 0 && isPerfettoUiAssetsDirectory(candidate, probe),
  );
}

/**
 * Resolves a request path inside the UI root. Absolute paths, parent traversal,
 * and encoded separators are rejected so the handler cannot escape the root.
 */
export function resolveAssetRequest(root: string, requestPath: string): string | undefined {
  let decoded: string;
  try {
    decoded = decodeURIComponent(requestPath.split('?')[0]?.split('#')[0] ?? '');
  } catch {
    return undefined;
  }
  if (decoded.includes('\u0000')) return undefined;
  const withoutLeading = decoded.replace(/^\/+/, '');
  if (withoutLeading.length === 0) return posix.join(root, 'index.html');
  const normalized = posix.normalize(withoutLeading);
  if (normalized.startsWith('..') || posix.isAbsolute(normalized)) return undefined;
  if (normalized.split('/').includes('..')) return undefined;
  return posix.join(root, normalized);
}
