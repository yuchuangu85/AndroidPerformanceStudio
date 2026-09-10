import manifestJson from './trace-processor-manifest.json';
import { detectHostPlatform, type HostPlatform } from './host-platform.js';
import { PINNED_TRACE_PROCESSOR_VERSION } from './version.js';

const SHA_256 = /^[0-9a-f]{64}$/;

export interface TraceProcessorArtifact {
  readonly url: string;
  readonly sha256: string;
}

export interface TraceProcessorManifest {
  readonly version: string;
  /** Keyed by host resource directory, for example macos-arm64. */
  readonly checksums: ReadonlyMap<string, string>;
}

interface ManifestJson {
  readonly version: string;
  readonly artifacts: Readonly<Record<string, TraceProcessorArtifact>>;
}

/** Loads and validates the manifest pinned alongside the Trace Processor schema. */
export function loadPinnedManifest(raw: ManifestJson = manifestJson): TraceProcessorManifest {
  if (raw.version !== PINNED_TRACE_PROCESSOR_VERSION) {
    throw new Error('Pinned Trace Processor manifest version does not match the query schema');
  }
  const checksums = new Map<string, string>();
  for (const [host, artifact] of Object.entries(raw.artifacts)) {
    if (!SHA_256.test(artifact.sha256)) {
      throw new Error('trace processor manifest contains an invalid checksum for ' + host);
    }
    if (resolveHostKey(host) === undefined) {
      throw new Error('Unsupported host in Trace Processor manifest: ' + host);
    }
    checksums.set(host, artifact.sha256);
  }
  return { version: raw.version, checksums };
}

/** Returns the host platform for a manifest key, or undefined when unknown. */
export function resolveHostKey(host: string): HostPlatform | undefined {
  const [os, arch] = host.split('-');
  const platform = os === 'macos' ? 'darwin' : os === 'linux' ? 'linux' : os === 'windows' ? 'win32' : undefined;
  const cpu = arch === 'x64' ? 'x64' : arch === 'arm64' ? 'arm64' : undefined;
  if (platform === undefined || cpu === undefined) return undefined;
  const detected = detectHostPlatform(platform, cpu);
  return detected;
}
