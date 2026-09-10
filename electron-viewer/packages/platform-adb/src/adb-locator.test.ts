import { describe, expect, it } from 'vitest';
import { AdbNotExecutableError, AdbNotFoundError } from './adb-errors.js';
import { AdbExecutableLocator, type AdbLocatorDependencies } from './adb-locator.js';

interface Options {
  readonly env?: Record<string, string | undefined>;
  readonly userHome?: string;
  readonly platform?: NodeJS.Platform;
  readonly pathSeparator?: string;
  readonly usable?: readonly string[];
}

function locator(options: Options = {}): AdbExecutableLocator {
  const usable = new Set(options.usable ?? []);
  const dependencies: AdbLocatorDependencies = {
    env: options.env ?? {},
    userHome: options.userHome ?? '/home/user',
    platform: options.platform ?? 'linux',
    pathSeparator: options.pathSeparator ?? ':',
    isUsableExecutable: (path) => usable.has(path),
    join: (...parts) => parts.join('/'),
    normalize: (path) => path,
  };
  return new AdbExecutableLocator(dependencies);
}

describe('AdbExecutableLocator', () => {
  it('prefers an explicit executable over everything else', () => {
    const result = locator({ env: { ADB: '/env/adb' }, usable: ['/explicit/adb', '/env/adb'] }).locate({
      executablePath: '/explicit/adb',
    });
    expect(result).toEqual({ executable: '/explicit/adb', source: 'EXPLICIT_EXECUTABLE' });
  });

  it('rejects an unusable explicit executable', () => {
    expect(() => locator().locate({ executablePath: '/missing/adb' })).toThrow(AdbNotExecutableError);
  });

  it('resolves platform-tools under an explicit SDK', () => {
    const result = locator({ usable: ['/sdk/platform-tools/adb'] }).locate({ androidSdkPath: '/sdk' });
    expect(result).toEqual({ executable: '/sdk/platform-tools/adb', source: 'EXPLICIT_SDK' });
  });

  it('follows the documented precedence: ADB, ADB_PATH, ANDROID_HOME, ANDROID_SDK_ROOT, PATH, default', () => {
    const env = {
      ADB: '/adb-env',
      ANDROID_HOME: '/home-sdk',
      ANDROID_SDK_ROOT: '/root-sdk',
      PATH: '/path-one:/path-two',
    };
    expect(locator({ env, usable: ['/adb-env', '/home-sdk/platform-tools/adb'] }).locate()).toEqual({
      executable: '/adb-env',
      source: 'ADB_ENVIRONMENT',
    });
    expect(locator({ env, usable: ['/home-sdk/platform-tools/adb', '/root-sdk/platform-tools/adb'] }).locate()).toEqual({
      executable: '/home-sdk/platform-tools/adb',
      source: 'ANDROID_HOME',
    });
    expect(locator({ env, usable: ['/root-sdk/platform-tools/adb'] }).locate()).toEqual({
      executable: '/root-sdk/platform-tools/adb',
      source: 'ANDROID_SDK_ROOT',
    });
    expect(locator({ env, usable: ['/path-two/adb'] }).locate()).toEqual({
      executable: '/path-two/adb',
      source: 'PATH',
    });
    expect(locator({ env, userHome: '/home/user', usable: ['/home/user/Android/Sdk/platform-tools/adb'] }).locate()).toEqual({
      executable: '/home/user/Android/Sdk/platform-tools/adb',
      source: 'DEFAULT_SDK',
    });
  });

  it('uses the macOS default SDK directory', () => {
    const result = locator({
      platform: 'darwin',
      userHome: '/Users/dev',
      usable: ['/Users/dev/Library/Android/sdk/platform-tools/adb'],
    }).locate();
    expect(result.source).toBe('DEFAULT_SDK');
  });

  it('handles Windows executable names, case-insensitive variables, and %VAR% expansion', () => {
    const result = locator({
      platform: 'win32',
      env: { 'android_home': '%USERPROFILE%\\Sdk', USERPROFILE: 'C:\\Users\\dev' },
      pathSeparator: ';',
      usable: ['C:\\Users\\dev\\Sdk/platform-tools/adb.exe'],
    }).locate();
    expect(result).toEqual({ executable: 'C:\\Users\\dev\\Sdk/platform-tools/adb.exe', source: 'ANDROID_HOME' });
  });

  it('throws when nothing is found', () => {
    expect(() => locator({ env: { PATH: '/empty' } }).locate()).toThrow(AdbNotFoundError);
  });
});
