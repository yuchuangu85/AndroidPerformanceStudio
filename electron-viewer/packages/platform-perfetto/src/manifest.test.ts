import { describe, expect, it } from 'vitest';
import { loadPinnedManifest, resolveHostKey } from './manifest.js';

describe('TraceProcessorManifest', () => {
  it('loads the pinned v57.2 manifest with five host checksums', () => {
    const manifest = loadPinnedManifest();
    expect(manifest.version).toBe('v57.2');
    expect(manifest.checksums.size).toBe(5);
    expect(manifest.checksums.get('macos-arm64')).toMatch(/^[0-9a-f]{64}$/);
  });

  it('rejects a manifest for a different Trace Processor version', () => {
    expect(() => loadPinnedManifest({ version: 'v1.0', artifacts: {} })).toThrow();
  });

  it('rejects an invalid checksum', () => {
    expect(() =>
      loadPinnedManifest({ version: 'v57.2', artifacts: { 'linux-x64': { url: 'u', sha256: 'nope' } } }),
    ).toThrow();
  });

  it('resolves manifest keys to host platforms', () => {
    expect(resolveHostKey('windows-x64')?.operatingSystem).toBe('WINDOWS');
    expect(resolveHostKey('solaris-x64')).toBeUndefined();
  });
});
