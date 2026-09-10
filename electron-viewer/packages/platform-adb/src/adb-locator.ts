import { statSync } from 'node:fs';
import { homedir } from 'node:os';
import { delimiter, join, normalize } from 'node:path';
import { AdbNotFoundError, AdbNotExecutableError } from './adb-errors.js';

export interface AdbLocatorConfiguration {
  readonly executablePath?: string;
  readonly androidSdkPath?: string;
}

export type AdbLocationSource =
  | 'EXPLICIT_EXECUTABLE'
  | 'EXPLICIT_SDK'
  | 'ADB_ENVIRONMENT'
  | 'ANDROID_HOME'
  | 'ANDROID_SDK_ROOT'
  | 'PATH'
  | 'DEFAULT_SDK';

export interface AdbLocation {
  readonly executable: string;
  readonly source: AdbLocationSource;
}

export interface AdbLocatorDependencies {
  readonly env: Readonly<Record<string, string | undefined>>;
  readonly userHome: string;
  readonly platform: NodeJS.Platform;
  readonly pathSeparator: string;
  readonly isUsableExecutable: (path: string) => boolean;
  readonly join: (...parts: string[]) => string;
  readonly normalize: (path: string) => string;
}

function defaultIsUsableExecutable(path: string): boolean {
  try {
    const stats = statSync(path);
    if (!stats.isFile()) return false;
    return process.platform === 'win32' || (stats.mode & 0o111) !== 0;
  } catch {
    return false;
  }
}

export function defaultAdbLocatorDependencies(): AdbLocatorDependencies {
  return {
    env: process.env,
    userHome: homedir(),
    platform: process.platform,
    pathSeparator: delimiter,
    isUsableExecutable: defaultIsUsableExecutable,
    join,
    normalize,
  };
}

function isWindows(platform: NodeJS.Platform): boolean {
  return platform === 'win32';
}

function isMac(platform: NodeJS.Platform): boolean {
  return platform === 'darwin';
}

export class AdbExecutableLocator {
  private readonly deps: AdbLocatorDependencies;

  constructor(dependencies: AdbLocatorDependencies = defaultAdbLocatorDependencies()) {
    this.deps = dependencies;
  }

  locate(configuration: AdbLocatorConfiguration = {}): AdbLocation {
    if (configuration.executablePath !== undefined) {
      return this.locateConfiguredExecutable(configuration.executablePath);
    }
    if (configuration.androidSdkPath !== undefined) {
      return this.locateConfiguredSdk(configuration.androidSdkPath);
    }
    for (const candidate of this.candidates()) {
      if (this.deps.isUsableExecutable(candidate.executable)) return candidate;
    }
    throw new AdbNotFoundError();
  }

  private get executableName(): string {
    return isWindows(this.deps.platform) ? 'adb.exe' : 'adb';
  }

  private locateConfiguredExecutable(executable: string): AdbLocation {
    if (!this.deps.isUsableExecutable(executable)) throw new AdbNotExecutableError(executable);
    return { executable, source: 'EXPLICIT_EXECUTABLE' };
  }

  private locateConfiguredSdk(sdk: string): AdbLocation {
    const executable = this.deps.join(sdk, 'platform-tools', this.executableName);
    if (!this.deps.isUsableExecutable(executable)) throw new AdbNotExecutableError(executable);
    return { executable, source: 'EXPLICIT_SDK' };
  }

  private environmentValue(name: string): string | undefined {
    if (isWindows(this.deps.platform)) {
      const entry = Object.entries(this.deps.env).find(([key]) => key.toLowerCase() === name.toLowerCase());
      return entry?.[1]?.trim().length ? entry[1] : undefined;
    }
    const value = this.deps.env[name];
    return value !== undefined && value.trim().length > 0 ? value : undefined;
  }

  private expandPath(value: string): string {
    const unquoted = value.trim().replace(/^"(.*)"$/, '$1');
    if (!isWindows(this.deps.platform)) return unquoted;
    return unquoted.replace(/%([^%]+)%/g, (match, name: string) => this.environmentValue(name) ?? match);
  }

  private sdkCandidate(name: string, source: AdbLocationSource): AdbLocation | undefined {
    const root = this.environmentValue(name);
    if (root === undefined) return undefined;
    return {
      executable: this.deps.join(this.expandPath(root), 'platform-tools', this.executableName),
      source,
    };
  }

  private defaultSdkDirectories(): string[] {
    if (isWindows(this.deps.platform)) {
      const localAppData = this.environmentValue('LOCALAPPDATA');
      return [localAppData !== undefined ? this.deps.join(localAppData, 'Android/Sdk') : this.deps.join(this.deps.userHome, 'AppData/Local/Android/Sdk')];
    }
    if (isMac(this.deps.platform)) {
      return [this.deps.join(this.deps.userHome, 'Library/Android/sdk')];
    }
    return [this.deps.join(this.deps.userHome, 'Android/Sdk')];
  }

  private candidates(): AdbLocation[] {
    const seen = new Set<string>();
    const result: AdbLocation[] = [];
    const emit = (location: AdbLocation | undefined): void => {
      if (location === undefined) return;
      const key = this.deps.normalize(location.executable);
      if (seen.has(key)) return;
      seen.add(key);
      result.push(location);
    };
    const adbEnv = this.environmentValue('ADB');
    if (adbEnv !== undefined) emit({ executable: this.expandPath(adbEnv), source: 'ADB_ENVIRONMENT' });
    const adbPath = this.environmentValue('ADB_PATH');
    if (adbPath !== undefined) emit({ executable: this.expandPath(adbPath), source: 'ADB_ENVIRONMENT' });
    emit(this.sdkCandidate('ANDROID_HOME', 'ANDROID_HOME'));
    emit(this.sdkCandidate('ANDROID_SDK_ROOT', 'ANDROID_SDK_ROOT'));
    const pathValue = this.environmentValue('PATH');
    if (pathValue !== undefined) {
      for (const entry of pathValue.split(this.deps.pathSeparator)) {
        if (entry.trim().length === 0) continue;
        emit({ executable: this.deps.join(this.expandPath(entry), this.executableName), source: 'PATH' });
      }
    }
    for (const directory of this.defaultSdkDirectories()) {
      emit({
        executable: this.deps.join(directory, 'platform-tools', this.executableName),
        source: 'DEFAULT_SDK',
      });
    }
    return result;
  }
}
