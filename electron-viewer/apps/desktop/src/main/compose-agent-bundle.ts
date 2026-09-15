import { createHash } from 'node:crypto';
import { readFile, stat } from 'node:fs/promises';
import { resolve } from 'node:path';

const MIB = 1024 * 1024;
const SHA256 = /^[a-f0-9]{64}$/;

export interface ComposeAgentBundle {
  readonly root: string;
  readonly abi: string;
  readonly nativeAgentPath: string;
  readonly serviceJarPath: string;
  readonly payloadJarPath: string;
  readonly viewInspectorJarPath: string;
  /** SHA-256 over the four verified artifact digests, matching Kotlin's bundle fingerprint. */
  readonly fingerprint: string;
}

export class ComposeAgentBundleError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ComposeAgentBundleError';
  }
}

/**
 * Loads only the exact bundle produced by tools/build-compose-agent-bundle.sh.
 * This is intentionally a local verification boundary: it never downloads or
 * executes agent code, and rejects symlink/path escapes before any ADB action.
 */
export async function loadComposeAgentBundle(root: string, abi: string): Promise<ComposeAgentBundle> {
  if (!/^[A-Za-z0-9_-]+$/.test(abi)) throw new ComposeAgentBundleError('Invalid Compose agent ABI');
  const absoluteRoot = resolve(root);
  const manifestPath = safeChild(absoluteRoot, 'manifest.properties');
  const manifest = parseProperties(await readUtf8(manifestPath, 'Compose agent manifest'));
  const nativeAgentPath = await verify(manifest, absoluteRoot, `agent/${abi}/lib_ui_inspector_agent.so`, `agent.${abi}.sha256`, 32 * MIB);
  const serviceJarPath = await verify(manifest, absoluteRoot, 'lib_ui_inspector_service.jar', 'service.sha256', 32 * MIB);
  const payloadJarPath = await verify(manifest, absoluteRoot, 'lib_ui_inspector_payload.jar', 'payload.sha256', 64 * MIB);
  const viewInspectorJarPath = await verify(manifest, absoluteRoot, 'view-inspector.jar', 'view.sha256', 64 * MIB);
  const fingerprint = hash([nativeAgentPath, serviceJarPath, payloadJarPath, viewInspectorJarPath].map((path) => manifestDigest(manifest, path, absoluteRoot)).join(''));
  return { root: absoluteRoot, abi, nativeAgentPath, serviceJarPath, payloadJarPath, viewInspectorJarPath, fingerprint };
}

async function verify(manifest: ReadonlyMap<string, string>, root: string, relative: string, key: string, limit: number): Promise<string> {
  const expected = manifest.get(key);
  if (expected === undefined || !SHA256.test(expected)) throw new ComposeAgentBundleError('Missing or invalid agent bundle checksum: ' + key);
  const path = safeChild(root, relative);
  let metadata;
  try { metadata = await stat(path); } catch { throw new ComposeAgentBundleError('Missing agent bundle artifact: ' + relative); }
  if (!metadata.isFile() || metadata.size < 1 || metadata.size > limit) throw new ComposeAgentBundleError('Invalid agent bundle artifact size: ' + relative);
  const actual = hash(await readFile(path));
  if (actual !== expected) throw new ComposeAgentBundleError('Agent bundle checksum mismatch: ' + relative);
  return path;
}

function manifestDigest(manifest: ReadonlyMap<string, string>, path: string, root: string): string {
  const relative = path.slice(root.length + 1).replaceAll('\\', '/');
  const key = relative.startsWith('agent/') ? `agent.${relative.split('/')[1]}.sha256` :
    relative === 'lib_ui_inspector_service.jar' ? 'service.sha256' :
    relative === 'lib_ui_inspector_payload.jar' ? 'payload.sha256' : 'view.sha256';
  const value = manifest.get(key);
  if (value === undefined) throw new ComposeAgentBundleError('Missing agent bundle checksum: ' + key);
  return value;
}

function safeChild(root: string, relative: string): string {
  const child = resolve(root, relative);
  if (!child.startsWith(root + '/')) throw new ComposeAgentBundleError('Unsafe agent bundle path');
  return child;
}

function parseProperties(content: string): ReadonlyMap<string, string> {
  const values = new Map<string, string>();
  for (const raw of content.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.length === 0 || line.startsWith('#') || line.startsWith('!')) continue;
    const separator = line.search(/[=:]/);
    if (separator < 1) throw new ComposeAgentBundleError('Malformed agent bundle manifest');
    const key = line.slice(0, separator).trim();
    const value = line.slice(separator + 1).trim().toLowerCase();
    if (values.has(key)) throw new ComposeAgentBundleError('Duplicate agent bundle manifest key: ' + key);
    values.set(key, value);
  }
  return values;
}

async function readUtf8(path: string, label: string): Promise<string> {
  try { return await readFile(path, 'utf8'); } catch { throw new ComposeAgentBundleError(label + ' is unavailable'); }
}
function hash(value: Uint8Array | string): string { return createHash('sha256').update(value).digest('hex'); }
