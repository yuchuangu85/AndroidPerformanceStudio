export type HostOperatingSystem = 'MACOS' | 'LINUX' | 'WINDOWS';
export type CpuArchitecture = 'X64' | 'ARM64';

export interface HostPlatform {
  readonly operatingSystem: HostOperatingSystem;
  readonly architecture: CpuArchitecture;
  /** Manifest key, for example macos-arm64. */
  readonly resourceDirectory: string;
}

export function detectHostPlatform(
  platform: NodeJS.Platform = process.platform,
  architecture: string = process.arch,
): HostPlatform | undefined {
  const operatingSystem: HostOperatingSystem | undefined =
    platform === 'darwin' ? 'MACOS' : platform === 'linux' ? 'LINUX' : platform === 'win32' ? 'WINDOWS' : undefined;
  const cpu: CpuArchitecture | undefined =
    architecture === 'x64' ? 'X64' : architecture === 'arm64' ? 'ARM64' : undefined;
  if (operatingSystem === undefined || cpu === undefined) return undefined;
  const osToken = operatingSystem === 'MACOS' ? 'macos' : operatingSystem === 'WINDOWS' ? 'windows' : 'linux';
  const archToken = cpu === 'X64' ? 'x64' : 'arm64';
  return { operatingSystem, architecture: cpu, resourceDirectory: osToken + '-' + archToken };
}
