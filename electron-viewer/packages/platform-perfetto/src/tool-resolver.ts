import { statSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { fail, ok, type StudioResult } from '@aps/contracts';
import { sha256File as defaultSha256File } from '@aps/contracts/node';
import { runHostProcessText } from '@aps/platform-host';
import { detectHostPlatform, type HostPlatform } from './host-platform.js';
import { loadPinnedManifest, type TraceProcessorManifest } from './manifest.js';
import { PINNED_TRACE_PROCESSOR_VERSION } from './version.js';

export interface TraceProcessorTool {
  readonly path: string;
  readonly version: string;
  readonly sha256: string;
}

export interface VersionProbeResult {
  readonly exitCode: number;
  readonly stdout: string;
  readonly stderr: string;
}

export interface ToolResolverDependencies {
  readonly platform: HostPlatform | undefined;
  readonly manifest: TraceProcessorManifest;
  readonly applicationResourcesPath: string | undefined;
  readonly installedToolsPath: string;
  readonly overridePath: string | undefined;
  readonly isRegularFile: (path: string) => boolean;
  readonly isExecutable: (path: string) => boolean;
  readonly sha256File: (path: string) => Promise<string>;
  readonly probeVersion: (path: string) => Promise<VersionProbeResult>;
}

export function defaultToolResolverDependencies(): ToolResolverDependencies {
  const electronResourcesPath = (process as NodeJS.Process & { resourcesPath?: string }).resourcesPath;
  const overridePath = process.env['PERFETTO_TRACE_PROCESSOR_PATH'];
  return {
    platform: detectHostPlatform(),
    manifest: loadPinnedManifest(),
    applicationResourcesPath: process.env['APS_APPLICATION_RESOURCES_PATH'] ?? electronResourcesPath,
    installedToolsPath: join(homedir(), '.android-performance-studio', 'tools', 'perfetto', PINNED_TRACE_PROCESSOR_VERSION),
    overridePath: overridePath !== undefined && overridePath.trim().length > 0 ? overridePath : undefined,
    isRegularFile: (path) => {
      try {
        return statSync(path).isFile();
      } catch {
        return false;
      }
    },
    isExecutable: (path) => {
      try {
        const stats = statSync(path);
        return stats.isFile() && (process.platform === 'win32' || (stats.mode & 0o111) !== 0);
      } catch {
        return false;
      }
    },
    sha256File: defaultSha256File,
    probeVersion: async (path) => {
      const result = await runHostProcessText({ executable: path, args: ['--version'], timeoutMs: 5_000 });
      return { exitCode: result.exitCode, stdout: result.stdout, stderr: result.stderr };
    },
  };
}

const UNIX_BINARY = 'trace_processor_shell';
const WINDOWS_BINARY = 'trace_processor_shell.exe';

/**
 * Resolves the pinned Trace Processor. Matches TraceProcessorToolResolver:
 * a configured override is probed for the pinned version, while packaged and
 * installed binaries must match the manifest checksum.
 */
export async function resolveTraceProcessorTool(
  dependencies: ToolResolverDependencies = defaultToolResolverDependencies(),
): Promise<StudioResult<TraceProcessorTool>> {
  const { platform, manifest } = dependencies;
  if (platform === undefined) {
    return fail('UNSUPPORTED_PLATFORM', 'TRACE_PROCESSOR_HOST_UNSUPPORTED', 'The host platform is not supported');
  }
  if (dependencies.overridePath !== undefined) {
    return resolveOverride(dependencies, dependencies.overridePath);
  }
  const expected = manifest.checksums.get(platform.resourceDirectory);
  if (expected === undefined) {
    return fail(
      'UNSUPPORTED_PLATFORM',
      'TRACE_PROCESSOR_HOST_UNSUPPORTED',
      'The pinned Trace Processor is not available for ' + platform.resourceDirectory,
    );
  }
  const binaryName = platform.operatingSystem === 'WINDOWS' ? WINDOWS_BINARY : UNIX_BINARY;
  const packaged =
    dependencies.applicationResourcesPath === undefined
      ? undefined
      : join(dependencies.applicationResourcesPath, 'perfetto-tools', binaryName);
  if (packaged !== undefined && dependencies.isRegularFile(packaged)) {
    return verifyPinned(dependencies, packaged, expected);
  }
  const installed = join(dependencies.installedToolsPath, binaryName);
  if (dependencies.isRegularFile(installed)) {
    return verifyPinned(dependencies, installed, expected);
  }
  return fail(
    'CONFIGURATION',
    'TRACE_PROCESSOR_NOT_FOUND',
    'Pinned Trace Processor ' +
      PINNED_TRACE_PROCESSOR_VERSION +
      ' was not packaged or installed. Run scripts/install-trace-processor.sh or configure PERFETTO_TRACE_PROCESSOR_PATH explicitly.',
  );
}

async function verifyPinned(
  dependencies: ToolResolverDependencies,
  path: string,
  expectedSha256: string,
): Promise<StudioResult<TraceProcessorTool>> {
  if (!dependencies.isExecutable(path)) {
    return fail('CONFIGURATION', 'TRACE_PROCESSOR_NOT_EXECUTABLE', 'Trace Processor is not executable: ' + path);
  }
  const actual = await dependencies.sha256File(path);
  if (actual !== expectedSha256) {
    return fail('DATA_VALIDATION', 'TRACE_PROCESSOR_CHECKSUM_MISMATCH', 'Trace Processor checksum mismatch for ' + path);
  }
  return ok({ path, version: PINNED_TRACE_PROCESSOR_VERSION, sha256: actual });
}

async function resolveOverride(
  dependencies: ToolResolverDependencies,
  path: string,
): Promise<StudioResult<TraceProcessorTool>> {
  if (!dependencies.isExecutable(path)) {
    return fail('CONFIGURATION', 'TRACE_PROCESSOR_OVERRIDE_INVALID', 'Configured Trace Processor is not executable: ' + path);
  }
  let probe: VersionProbeResult;
  try {
    probe = await dependencies.probeVersion(path);
  } catch (error) {
    return fail(
      'PROCESS_START',
      'TRACE_PROCESSOR_OVERRIDE_PROBE_FAILED',
      error instanceof Error ? error.message : 'Configured Trace Processor could not be inspected',
    );
  }
  const reported = probe.stdout + '\n' + probe.stderr;
  if (probe.exitCode !== 0 || !reported.includes(PINNED_TRACE_PROCESSOR_VERSION)) {
    return fail(
      'CONFIGURATION',
      'TRACE_PROCESSOR_INCOMPATIBLE',
      'Configured Trace Processor must report ' + PINNED_TRACE_PROCESSOR_VERSION,
    );
  }
  return ok({ path, version: PINNED_TRACE_PROCESSOR_VERSION, sha256: await dependencies.sha256File(path) });
}
