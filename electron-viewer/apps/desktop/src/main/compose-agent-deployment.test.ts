import { createHash } from 'node:crypto';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { ComposeDeploymentAdb } from './compose-agent-deployment.js';
import { deployComposeAgent } from './compose-agent-deployment.js';

const bundle = {
  abi: 'arm64-v8a', fingerprint: 'a'.repeat(64), nativeAgentPath: '/n', serviceJarPath: '/s',
  payloadJarPath: '/p', viewInspectorJarPath: '/v', root: '/b',
};

function harness(overrides: {
  readonly api?: string;
  readonly abi?: string;
  readonly appData?: string;
  readonly pids?: string;
  readonly sockets?: readonly string[];
  readonly pushFailsAt?: number;
} = {}): { readonly adb: ComposeDeploymentAdb; readonly calls: string[][] } {
  const calls: string[][] = [];
  let socketReads = 0;
  let pushCount = 0;
  const adb: ComposeDeploymentAdb = {
    shell: async (args) => {
      calls.push([...args]);
      if (args[0] === 'getprop') return { stdout: args[1] === 'ro.build.version.sdk' ? (overrides.api ?? '34') + '\n' : (overrides.abi ?? 'arm64-v8a') + '\n' };
      if (args[0] === 'run-as' && args[2] === 'pwd') return { stdout: (overrides.appData ?? '/data/user/0/dev.sample') + '\n' };
      if (args[0] === 'pidof') return { stdout: (overrides.pids ?? '123') + '\n' };
      if (args[0] === 'cat' && args[1] === '/proc/net/unix') return { stdout: (overrides.sockets?.[socketReads++] ?? '@ui_inspector_123') + '\n' };
      return { stdout: '' };
    },
    push: async (localPath, remotePath) => {
      calls.push(['push', localPath, remotePath]);
      pushCount += 1;
      if (pushCount === overrides.pushFailsAt) throw new Error('push failed');
    },
  };
  return { adb, calls };
}

describe('deployComposeAgent', () => {
  it('stages verified files in run-as storage, attaches the exact PID, waits for its socket, and cleans up', async () => {
    const test = harness({ sockets: ['', '@ui_inspector_123'] });
    const deployed = await deployComposeAgent(test.adb, 'dev.sample', bundle, { sleep: async () => undefined, sessionToken: 'b'.repeat(64) });

    expect(deployed).toMatchObject({
      pid: 123,
      privateDirectory: '/data/user/0/dev.sample/code_cache/aps-ui-inspector-aaaaaaaaaaaaaaaa',
      viewInspectorPath: '/data/user/0/dev.sample/code_cache/aps-ui-inspector-aaaaaaaaaaaaaaaa/view-inspector.jar',
      sessionToken: 'b'.repeat(64),
    });
    expect(test.calls).toContainEqual([
      'cmd', 'activity', 'attach-agent', '123',
      `/data/user/0/dev.sample/code_cache/aps-ui-inspector-aaaaaaaaaaaaaaaa/lib_ui_inspector_agent.so=/data/user/0/dev.sample/code_cache/aps-ui-inspector-aaaaaaaaaaaaaaaa/lib_ui_inspector_service.jar;/data/user/0/dev.sample/code_cache/aps-ui-inspector-aaaaaaaaaaaaaaaa/lib_ui_inspector_payload.jar;123;${'b'.repeat(64)}`,
    ]);
    expect(test.calls.filter((call) => call[0] === 'push')).toHaveLength(4);

    await deployed.cleanup();
    await deployed.cleanup();
    expect(test.calls.filter((call) => call.join(' ') === 'run-as dev.sample rmdir /data/user/0/dev.sample/code_cache/aps-ui-inspector-aaaaaaaaaaaaaaaa')).toHaveLength(1);
  });

  it.each([
    ['requires Android API 29', { api: '28' }, 'Android API 29'],
    ['rejects a mismatched ABI', { abi: 'x86_64' }, 'ABI mismatch'],
    ['rejects a non-debuggable app', { appData: '/unexpected/path' }, 'not debuggable'],
    ['rejects no running process', { pids: '' }, 'not running'],
    ['rejects multiple processes', { pids: '123 456' }, 'multiple processes'],
  ])('%s', async (_name, overrides, message) => {
    await expect(deployComposeAgent(harness(overrides).adb, 'dev.sample', bundle)).rejects.toThrow(message);
  });

  it('refuses to inject over an existing agent socket', async () => {
    const test = harness({ sockets: ['@ui_inspector_123'] });
    await expect(deployComposeAgent(test.adb, 'dev.sample', bundle)).rejects.toThrow('already attached');
    expect(test.calls.some((call) => call[0] === 'push')).toBe(false);
  });

  it('stages a checksum-verified version-specific Compose inspector and cleans it with the session', async () => {
    const root = await mkdtemp(join(tmpdir(), 'aps-compose-deployment-'));
    try {
      const localPath = join(root, 'inspector.jar');
      const bytes = Buffer.from('dex inspector');
      await writeFile(localPath, bytes);
      const deployed = await deployComposeAgent(harness({ sockets: ['', '@ui_inspector_123'] }).adb, 'dev.sample', bundle, { sessionToken: 'c'.repeat(64) });
      const destination = await deployed.deployComposeInspector(localPath, createHash('sha256').update(bytes).digest('hex'));
      expect(destination).toMatch(/compose-[a-f0-9]{16}\.jar$/);
      await deployed.cleanup();
    } finally { await rm(root, { recursive: true, force: true }); }
  });

  it('cleans staged and private files when deployment fails', async () => {
    const test = harness({ sockets: [''], pushFailsAt: 2 });
    await expect(deployComposeAgent(test.adb, 'dev.sample', bundle)).rejects.toThrow('push failed');
    expect(test.calls).toContainEqual(['rm', '-f', '/data/local/tmp/aps-ui-inspector-aaaaaaaaaaaaaaaa-lib_ui_inspector_agent.so']);
    expect(test.calls).toContainEqual(['rm', '-f', '/data/local/tmp/aps-ui-inspector-aaaaaaaaaaaaaaaa-lib_ui_inspector_service.jar']);
    expect(test.calls.some((call) => call.slice(0, 4).join(' ') === 'run-as dev.sample rm -f' && call.includes('/data/user/0/dev.sample/code_cache/aps-ui-inspector-aaaaaaaaaaaaaaaa/lib_ui_inspector_agent.so'))).toBe(true);
  });

  it('fails when the injected agent does not publish its socket', async () => {
    await expect(deployComposeAgent(harness({ sockets: Array(11).fill('') }).adb, 'dev.sample', bundle, { sleep: async () => undefined }))
      .rejects.toThrow('Timed out waiting');
  });
});
