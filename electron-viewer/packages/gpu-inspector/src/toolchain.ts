import type { AgiCapability, AgiLaunchMode } from './model.js';

export interface AgiProcessResult {
  readonly exitCode: number;
  readonly stdout: string;
  readonly stderr: string;
  readonly timedOut: boolean;
}

export interface AgiLocatorDependencies {
  readonly platform: NodeJS.Platform;
  readonly env: Readonly<Record<string, string | undefined>>;
  readonly userHome: string;
  readonly isExecutableFile: (path: string) => boolean;
  readonly join: (...parts: string[]) => string;
  readonly run: (executable: string, args: readonly string[]) => Promise<AgiProcessResult>;
}

const AUTOMATION_ARGUMENTS = ['--device', '--package', '--activity', '--capture'];
const ARTIFACT_OPEN_EXECUTABLES = new Set(['agi', 'gapic']);

function executableNames(platform: NodeJS.Platform): string[] {
  return platform === 'win32' ? ['agi.exe', 'gapic.exe'] : ['agi', 'gapic'];
}

function defaultCandidates(dependencies: AgiLocatorDependencies): string[] {
  if (dependencies.platform === 'darwin') {
    return [
      '/Applications/Android GPU Inspector.app/Contents/MacOS/agi',
      dependencies.join(
        dependencies.userHome,
        'Applications',
        'Android GPU Inspector.app',
        'Contents',
        'MacOS',
        'agi',
      ),
    ];
  }
  if (dependencies.platform === 'win32') {
    const localAppData = dependencies.env['LOCALAPPDATA'] ?? '';
    const programFiles = dependencies.env['ProgramFiles'] ?? '';
    return [
      dependencies.join(localAppData, 'Android GPU Inspector', 'agi.exe'),
      dependencies.join(programFiles, 'Android GPU Inspector', 'agi.exe'),
    ];
  }
  return [
    '/opt/android-gpu-inspector/agi',
    '/opt/android-gpu-inspector/gapic',
    '/usr/local/bin/agi',
    '/usr/local/bin/gapic',
  ];
}

function executableStem(path: string): string {
  const name = path.slice(path.lastIndexOf('/') + 1).split('\\').pop() ?? path;
  const dot = name.lastIndexOf('.');
  return (dot === -1 ? name : name.slice(0, dot)).toLowerCase();
}

/**
 * Port of AgiLocator: an explicit path wins, then PATH, then per-OS defaults.
 * Only stable automation arguments detected in --help are ever forwarded.
 */
export async function locateAgi(
  dependencies: AgiLocatorDependencies,
  configuredPath?: string,
): Promise<AgiCapability> {
  const candidates: string[] = [];
  if (configuredPath !== undefined && configuredPath.length > 0) candidates.push(configuredPath);
  const pathValue = dependencies.env['PATH'] ?? '';
  const separator = dependencies.platform === 'win32' ? ';' : ':';
  for (const directory of pathValue.split(separator)) {
    if (directory.trim().length === 0) continue;
    for (const name of executableNames(dependencies.platform)) {
      candidates.push(dependencies.join(directory, name));
    }
  }
  candidates.push(...defaultCandidates(dependencies));

  const usable = [...new Set(candidates)].filter((candidate) => dependencies.isExecutableFile(candidate));
  const executable = usable[0];
  if (executable === undefined) {
    return {
      launchSupported: false,
      artifactOpenSupported: false,
      launchMode: 'UNSUPPORTED',
      supportedArguments: [],
      warnings: ['Android GPU Inspector executable was not found. Configure its local path.'],
    };
  }

  const versionResult = await dependencies.run(executable, ['--version']);
  const helpResult = await dependencies.run(executable, ['--help']);
  const version = (versionResult.stdout + versionResult.stderr)
    .split('\n')
    .find((line) => line.trim().length > 0)
    ?.trim();
  const help = helpResult.stdout + helpResult.stderr;
  const supportedArguments = AUTOMATION_ARGUMENTS.filter((argument) => help.includes(argument));
  const artifactOpenSupported = ARTIFACT_OPEN_EXECUTABLES.has(executableStem(executable));

  const warnings: string[] = [];
  if (versionResult.timedOut) {
    warnings.push('AGI version probe timed out; GUI launch remains available.');
  }
  if (versionResult.exitCode !== 0 && (version === undefined || version.length === 0)) {
    warnings.push('AGI version could not be determined.');
  }
  if (supportedArguments.length === 0) {
    warnings.push('No stable automation arguments were detected; AGI will be launched in GUI-only mode.');
  }
  if (!artifactOpenSupported) {
    warnings.push('Opening an artifact through this configured executable was not verified.');
  }

  const launchMode: AgiLaunchMode = supportedArguments.length === 0 ? 'GUI_ONLY' : 'VERIFIED_CLI';
  return {
    executable,
    ...(version !== undefined && version.length > 0 ? { version } : {}),
    launchSupported: true,
    artifactOpenSupported,
    launchMode,
    supportedArguments,
    warnings,
  };
}

/** Drops any argument that is not an explicitly supported automation flag. */
export function safeLaunchArguments(capability: AgiCapability, args: readonly string[]): string[] {
  if (capability.launchMode !== 'VERIFIED_CLI') return [];
  const supported = new Set(capability.supportedArguments);
  return args.filter((argument) => {
    if (!argument.startsWith('--')) return false;
    const name = argument.includes('=') ? argument.slice(0, argument.indexOf('=')) : argument;
    return supported.has(name);
  });
}
