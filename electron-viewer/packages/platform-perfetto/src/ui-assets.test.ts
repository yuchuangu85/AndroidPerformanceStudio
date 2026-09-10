import { describe, expect, it } from 'vitest';
import {
  contentTypeForPath,
  findPerfettoUiAssetsDirectory,
  isPerfettoUiAssetsDirectory,
  resolveAssetRequest,
  type PerfettoUiAssetProbe,
} from './ui-assets.js';

function probe(directories: readonly string[], files: readonly string[]): PerfettoUiAssetProbe {
  return { isDirectory: (path) => directories.includes(path), isFile: (path) => files.includes(path) };
}

describe('Perfetto UI assets', () => {
  it('requires an index.html to accept a directory', () => {
    expect(isPerfettoUiAssetsDirectory('/ui', probe(['/ui'], ['/ui/index.html']))).toBe(true);
    expect(isPerfettoUiAssetsDirectory('/ui', probe(['/ui'], []))).toBe(false);
  });

  it('uses the first usable candidate in precedence order', () => {
    const found = findPerfettoUiAssetsDirectory(
      ['/packaged/perfetto-ui', undefined, '/repo/dist'],
      probe(['/repo/dist'], ['/repo/dist/index.html']),
    );
    expect(found).toBe('/repo/dist');
    expect(findPerfettoUiAssetsDirectory(['/missing'], probe([], []))).toBeUndefined();
  });

  it('resolves asset requests inside the root', () => {
    expect(resolveAssetRequest('/ui', '/')).toBe('/ui/index.html');
    expect(resolveAssetRequest('/ui', 'index.html')).toBe('/ui/index.html');
    expect(resolveAssetRequest('/ui', '/assets/app.js?v=1')).toBe('/ui/assets/app.js');
    expect(resolveAssetRequest('/ui', 'a/b/c.css')).toBe('/ui/a/b/c.css');
  });

  it('rejects traversal and encoded separators', () => {
    expect(resolveAssetRequest('/ui', '/../../etc/passwd')).toBeUndefined();
    expect(resolveAssetRequest('/ui', '..%2f..%2fetc/passwd')).toBeUndefined();
    expect(resolveAssetRequest('/ui', '/a/../../b')).toBeUndefined();
    expect(resolveAssetRequest('/ui', '/%ZZ')).toBeUndefined();
  });

  it('maps content types', () => {
    expect(contentTypeForPath('/ui/index.html')).toBe('text/html; charset=utf-8');
    expect(contentTypeForPath('/ui/app.js')).toBe('text/javascript; charset=utf-8');
    expect(contentTypeForPath('/ui/trace.wasm')).toBe('application/wasm');
    expect(contentTypeForPath('/ui/unknown.bin')).toBe('application/octet-stream');
  });
});
