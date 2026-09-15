import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { ComposeAgentBundleError, loadComposeAgentBundle } from './compose-agent-bundle.js';

const roots: string[] = [];
afterEach(async () => { await Promise.all(roots.splice(0).map(async (root) => (await import('node:fs/promises')).rm(root, { recursive: true, force: true }))); });
const sha = (data: string) => createHash('sha256').update(data).digest('hex');
async function fixture(): Promise<string> {
  const root = await (await import('node:fs/promises')).mkdtemp('/tmp/aps-compose-agent-'); roots.push(root);
  const files: Record<string, string> = {
    'agent/arm64-v8a/lib_ui_inspector_agent.so': 'native', 'lib_ui_inspector_service.jar': 'service',
    'lib_ui_inspector_payload.jar': 'payload', 'view-inspector.jar': 'view',
  };
  for (const [file, content] of Object.entries(files)) { await mkdir(join(root, file, '..'), { recursive: true }); await writeFile(join(root, file), content); }
  await writeFile(join(root, 'manifest.properties'), [
    `agent.arm64-v8a.sha256=${sha('native')}`, `service.sha256=${sha('service')}`,
    `payload.sha256=${sha('payload')}`, `view.sha256=${sha('view')}`,
  ].join('\n'));
  return root;
}
describe('Compose agent bundle', () => {
  it('loads a complete, hash-verified ABI-specific bundle', async () => {
    const root = await fixture(); const bundle = await loadComposeAgentBundle(root, 'arm64-v8a');
    expect(bundle.nativeAgentPath).toBe(join(root, 'agent/arm64-v8a/lib_ui_inspector_agent.so'));
    expect(bundle.fingerprint).toHaveLength(64);
  });
  it('rejects checksum mismatch, missing ABI, malformed manifest, and unsafe ABI', async () => {
    const root = await fixture(); await writeFile(join(root, 'view-inspector.jar'), 'tampered');
    await expect(loadComposeAgentBundle(root, 'arm64-v8a')).rejects.toBeInstanceOf(ComposeAgentBundleError);
    await expect(loadComposeAgentBundle(root, 'x86_64')).rejects.toThrow(/Missing or invalid agent bundle checksum/);
    await expect(loadComposeAgentBundle(root, '../arm64-v8a')).rejects.toThrow(/Invalid/);
    await writeFile(join(root, 'manifest.properties'), 'broken');
    await expect(loadComposeAgentBundle(root, 'arm64-v8a')).rejects.toThrow(/Malformed/);
  });
});
