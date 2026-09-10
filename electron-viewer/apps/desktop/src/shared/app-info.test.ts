import { describe, expect, it } from 'vitest';
import { APPLICATION_NAME, buildAppInfo } from './app-info';

describe('buildAppInfo', () => {
  it('reports the application name and contract version', () => {
    const info = buildAppInfo('1.2.3', 'darwin');
    expect(info.name).toBe(APPLICATION_NAME);
    expect(info.version).toBe('1.2.3');
    expect(info.contractVersion).toBe(1);
    expect(info.platform).toBe('darwin');
  });

  it('falls back to development for a blank version', () => {
    expect(buildAppInfo('   ', 'linux').version).toBe('development');
  });
});
