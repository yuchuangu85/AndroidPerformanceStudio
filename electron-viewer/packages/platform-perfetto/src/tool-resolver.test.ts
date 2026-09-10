import { describe, expect, it } from 'vitest';
import type { HostPlatform } from './host-platform.js';
import { resolveTraceProcessorTool, type ToolResolverDependencies, type VersionProbeResult } from './tool-resolver.js';

const MAC: HostPlatform = { operatingSystem: 'MACOS', architecture: 'ARM64', resourceDirectory: 'macos-arm64' };
const SHA = 'a'.repeat(64);

interface Options {
  readonly platform?: HostPlatform;
  readonly files?: readonly string[];
  readonly executables?: readonly string[];
  readonly hashes?: Readonly<Record<string, string>>;
  readonly resources?: string;
  readonly installed?: string;
  readonly overridePath?: string;
  readonly probe?: VersionProbeResult;
}

function dependencies(options: Options = {}): ToolResolverDependencies {
  const files = new Set(options.files ?? []);
  const executables = new Set(options.executables ?? []);
  const hashes = options.hashes ?? {};
  return {
    platform: 'platform' in options ? options.platform : MAC,
    manifest: { version: 'v57.2', checksums: new Map([['macos-arm64', SHA]]) },
    applicationResourcesPath: options.resources,
    installedToolsPath: options.installed ?? '/home/.aps/tools/perfetto/v57.2',
    overridePath: options.overridePath,
    isRegularFile: (path) => files.has(path),
    isExecutable: (path) => executables.has(path),
    sha256File: async (path) => hashes[path] ?? SHA,
    probeVersion: async () => options.probe ?? { exitCode: 0, stdout: 'Trace Processor v57.2', stderr: '' },
  };
}

describe('resolveTraceProcessorTool', () => {
  it('accepts a packaged binary whose checksum matches the manifest', async () => {
    const path = '/app/perfetto-tools/trace_processor_shell';
    const result = await resolveTraceProcessorTool(
      dependencies({ resources: '/app', files: [path], executables: [path] }),
    );
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.path).toBe(path);
      expect(result.value.version).toBe('v57.2');
      expect(result.value.sha256).toBe(SHA);
    }
  });

  it('reports a checksum mismatch', async () => {
    const path = '/app/perfetto-tools/trace_processor_shell';
    const result = await resolveTraceProcessorTool(
      dependencies({ resources: '/app', files: [path], executables: [path], hashes: { [path]: 'b'.repeat(64) } }),
    );
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('TRACE_PROCESSOR_CHECKSUM_MISMATCH');
  });

  it('reports a missing binary', async () => {
    const result = await resolveTraceProcessorTool(dependencies({ resources: '/app' }));
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('TRACE_PROCESSOR_NOT_FOUND');
  });

  it('reports an unsupported host', async () => {
    const result = await resolveTraceProcessorTool(dependencies({ platform: undefined }));
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('TRACE_PROCESSOR_HOST_UNSUPPORTED');
  });

  it('probes a configured override for the pinned version', async () => {
    const path = '/custom/trace_processor_shell';
    const accepted = await resolveTraceProcessorTool(
      dependencies({ overridePath: path, executables: [path] }),
    );
    expect(accepted.ok).toBe(true);

    const rejected = await resolveTraceProcessorTool(
      dependencies({ overridePath: path, executables: [path], probe: { exitCode: 0, stdout: 'v9.9', stderr: '' } }),
    );
    expect(rejected.ok).toBe(false);
    if (!rejected.ok) expect(rejected.error.code).toBe('TRACE_PROCESSOR_INCOMPATIBLE');

    const notExecutable = await resolveTraceProcessorTool(dependencies({ overridePath: path }));
    expect(notExecutable.ok).toBe(false);
    if (!notExecutable.ok) expect(notExecutable.error.code).toBe('TRACE_PROCESSOR_OVERRIDE_INVALID');
  });
});
