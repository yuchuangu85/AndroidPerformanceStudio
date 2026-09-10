import { describe, expect, it } from 'vitest';
import { locateAgi, safeLaunchArguments, type AgiLocatorDependencies } from './toolchain.js';

function dependencies(overrides: Partial<AgiLocatorDependencies> = {}): AgiLocatorDependencies {
  return {
    platform: 'darwin',
    env: {},
    userHome: '/Users/dev',
    isExecutableFile: () => false,
    join: (...parts) => parts.join('/'),
    run: async () => ({ exitCode: 0, stdout: '', stderr: '', timedOut: false }),
    ...overrides,
  };
}

describe('locateAgi', () => {
  it('reports an unsupported toolchain when nothing is found', async () => {
    const capability = await locateAgi(dependencies());
    expect(capability.launchMode).toBe('UNSUPPORTED');
    expect(capability.launchSupported).toBe(false);
    expect(capability.warnings[0]).toContain('was not found');
  });

  it('prefers the configured path and detects automation arguments', async () => {
    const capability = await locateAgi(
      dependencies({
        isExecutableFile: (path) => path === '/custom/agi',
        run: async (_executable, args) =>
          args[0] === '--version'
            ? { exitCode: 0, stdout: 'AGI 4.2.0\n', stderr: '', timedOut: false }
            : { exitCode: 0, stdout: 'usage: --device --package --capture', stderr: '', timedOut: false },
      }),
      '/custom/agi',
    );
    expect(capability.executable).toBe('/custom/agi');
    expect(capability.version).toBe('AGI 4.2.0');
    expect(capability.launchMode).toBe('VERIFIED_CLI');
    expect(capability.supportedArguments).toEqual(['--device', '--package', '--capture']);
    expect(capability.artifactOpenSupported).toBe(true);
    expect(capability.warnings).toEqual([]);
  });

  it('falls back to GUI-only mode when no automation arguments are advertised', async () => {
    const capability = await locateAgi(
      dependencies({
        platform: 'linux',
        isExecutableFile: (path) => path === '/opt/android-gpu-inspector/agi',
        run: async () => ({ exitCode: 1, stdout: '', stderr: '', timedOut: false }),
      }),
    );
    expect(capability.launchMode).toBe('GUI_ONLY');
    expect(capability.launchSupported).toBe(true);
    expect(capability.warnings.some((warning) => warning.includes('GUI-only'))).toBe(true);
    expect(capability.warnings.some((warning) => warning.includes('could not be determined'))).toBe(true);
  });

  it('warns when the version probe times out and when the executable is not an AGI binary', async () => {
    const capability = await locateAgi(
      dependencies({
        platform: 'linux',
        env: { PATH: '/usr/bin' },
        isExecutableFile: (path) => path === '/usr/bin/gapic',
        run: async (_executable, args) =>
          args[0] === '--version'
            ? { exitCode: -1, stdout: '', stderr: '', timedOut: true }
            : { exitCode: 0, stdout: '--device', stderr: '', timedOut: false },
      }),
    );
    expect(capability.executable).toBe('/usr/bin/gapic');
    expect(capability.warnings.some((warning) => warning.includes('timed out'))).toBe(true);
    expect(capability.artifactOpenSupported).toBe(true);
  });

  it('warns that artifact opening is unverified for a non-AGI executable name', async () => {
    const agi = await locateAgi(
      dependencies({
        platform: 'win32',
        env: { PATH: 'C:\\Tools' },
        isExecutableFile: (path) => path === 'C:\\Tools/agi.exe',
        run: async () => ({ exitCode: 0, stdout: '--device', stderr: '', timedOut: false }),
      }),
    );
    expect(agi.executable).toBe('C:\\Tools/agi.exe');
    expect(agi.artifactOpenSupported).toBe(true);

    // PATH lookup only ever yields agi/gapic names, so an unverified executable
    // has to be configured explicitly.
    const other = await locateAgi(
      dependencies({
        platform: 'win32',
        isExecutableFile: (path) => path === 'C:\\Tools\\other.exe',
        run: async () => ({ exitCode: 0, stdout: '', stderr: '', timedOut: false }),
      }),
      'C:\\Tools\\other.exe',
    );
    expect(other.executable).toBe('C:\\Tools\\other.exe');
    expect(other.artifactOpenSupported).toBe(false);
    expect(other.warnings.some((warning) => warning.includes('not verified'))).toBe(true);
  });
});

describe('safeLaunchArguments', () => {
  it('forwards only supported automation flags', () => {
    const capability = {
      launchSupported: true,
      artifactOpenSupported: true,
      launchMode: 'VERIFIED_CLI' as const,
      supportedArguments: ['--device', '--package'],
      warnings: [],
    };
    expect(safeLaunchArguments(capability, ['--device=emulator-5554', '--package=com.x', '--evil', 'plain'])).toEqual([
      '--device=emulator-5554',
      '--package=com.x',
    ]);
  });

  it('forwards nothing in GUI-only mode', () => {
    const capability = {
      launchSupported: true,
      artifactOpenSupported: false,
      launchMode: 'GUI_ONLY' as const,
      supportedArguments: [],
      warnings: [],
    };
    expect(safeLaunchArguments(capability, ['--device=x'])).toEqual([]);
  });
});
