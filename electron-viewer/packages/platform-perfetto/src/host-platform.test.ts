import { describe, expect, it } from 'vitest';
import { detectHostPlatform } from './host-platform.js';

describe('detectHostPlatform', () => {
  it('maps supported platforms to manifest keys', () => {
    expect(detectHostPlatform('darwin', 'arm64')?.resourceDirectory).toBe('macos-arm64');
    expect(detectHostPlatform('darwin', 'x64')?.resourceDirectory).toBe('macos-x64');
    expect(detectHostPlatform('linux', 'x64')?.resourceDirectory).toBe('linux-x64');
    expect(detectHostPlatform('win32', 'x64')?.resourceDirectory).toBe('windows-x64');
  });

  it('returns undefined for unsupported platforms or architectures', () => {
    expect(detectHostPlatform('freebsd', 'x64')).toBeUndefined();
    expect(detectHostPlatform('linux', 'ia32')).toBeUndefined();
  });
});
