import { createHash, randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import type { ComposeAgentBundle } from './compose-agent-bundle.js';

const TIMEOUT_MS = 30_000;
const SOCKET_PREFIX = 'ui_inspector_';
const SOCKET_POLL_ATTEMPTS = 10;
const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;

export interface ComposeDeploymentAdb {
  shell(args: readonly string[], options: { readonly timeoutMs: number }): Promise<{ readonly stdout: string }>;
  push(localPath: string, remotePath: string, options: { readonly timeoutMs: number }): Promise<unknown>;
}

export interface ComposeDeploymentOptions {
  /** Injectable only to make socket polling deterministic in tests. */
  readonly sleep?: (milliseconds: number) => Promise<void>;
  /** Supply only for deterministic tests; production sessions use a random 256-bit token. */
  readonly sessionToken?: string;
}

export interface DeployedComposeAgent {
  readonly pid: number;
  readonly privateDirectory: string;
  readonly nativeAgentPath: string;
  readonly servicePath: string;
  readonly payloadPath: string;
  readonly viewInspectorPath: string;
  readonly sessionToken: string;
  /** Copies a verified, version-matched Compose inspector JAR into this session's private directory. */
  deployComposeInspector(localPath: string, sha256: string): Promise<string>;
  /** Removes private copied artifacts when the capture session has ended. Safe to call repeatedly. */
  cleanup(): Promise<void>;
}

/**
 * Deploys a checksum-verified bundle only into a debuggable app's private directory,
 * attaches it to one exact PID, and waits until its localabstract socket is visible.
 */
export async function deployComposeAgent(
  adb: ComposeDeploymentAdb,
  packageName: string,
  bundle: ComposeAgentBundle,
  options: ComposeDeploymentOptions = {},
): Promise<DeployedComposeAgent> {
  validatePackageName(packageName);
  const sessionToken = options.sessionToken ?? randomBytes(32).toString('hex');
  if (!/^[a-f0-9]{64}$/.test(sessionToken)) throw new Error('Invalid UI Inspector session token');
  const api = Number((await adb.shell(['getprop', 'ro.build.version.sdk'], { timeoutMs: TIMEOUT_MS })).stdout.trim());
  if (!Number.isInteger(api) || api < 29) throw new Error('Compose inspection requires Android API 29 or newer');

  const abi = (await adb.shell(['getprop', 'ro.product.cpu.abi'], { timeoutMs: TIMEOUT_MS })).stdout.trim();
  if (abi !== bundle.abi) throw new Error(`Compose agent ABI mismatch: device ${abi}, bundle ${bundle.abi}`);

  const appData = (await adb.shell(['run-as', packageName, 'pwd'], { timeoutMs: TIMEOUT_MS })).stdout.trim();
  if (!isAppDataDirectory(appData, packageName)) {
    throw new Error('Target app is not debuggable or returned an unsafe data directory');
  }

  const pid = await targetPid(adb, packageName);
  if (await agentSocketExists(adb, pid)) {
    throw new Error(`A Layout Inspector is already attached to PID ${pid}`);
  }

  const suffix = bundle.fingerprint.slice(0, 16);
  const privateDirectory = `${appData}/code_cache/aps-ui-inspector-${suffix}`;
  const artifacts = [
    [bundle.nativeAgentPath, 'lib_ui_inspector_agent.so'],
    [bundle.serviceJarPath, 'lib_ui_inspector_service.jar'],
    [bundle.payloadJarPath, 'lib_ui_inspector_payload.jar'],
    [bundle.viewInspectorJarPath, 'view-inspector.jar'],
  ] as const;
  const staged: string[] = [];
  const privatePaths: string[] = [];

  try {
    await adb.shell(['run-as', packageName, 'mkdir', '-p', privateDirectory], { timeoutMs: TIMEOUT_MS });
    for (const [localPath, name] of artifacts) {
      const temporary = `/data/local/tmp/aps-ui-inspector-${suffix}-${name}`;
      const destination = `${privateDirectory}/${name}`;
      staged.push(temporary);
      privatePaths.push(destination);
      await adb.push(localPath, temporary, { timeoutMs: TIMEOUT_MS });
      await adb.shell(['run-as', packageName, 'cp', temporary, destination], { timeoutMs: TIMEOUT_MS });
      await adb.shell(['run-as', packageName, 'chmod', '444', destination], { timeoutMs: TIMEOUT_MS });
    }

    const [nativeAgentPath, servicePath, payloadPath, viewInspectorPath] = privatePaths as [string, string, string, string];
    await adb.shell(
      ['cmd', 'activity', 'attach-agent', String(pid), `${nativeAgentPath}=${servicePath};${payloadPath};${pid};${sessionToken}`],
      { timeoutMs: TIMEOUT_MS },
    );
    await waitForAgentSocket(adb, pid, options.sleep ?? delay);

    let cleaned = false;
    return {
      pid,
      privateDirectory,
      nativeAgentPath,
      servicePath,
      payloadPath,
      viewInspectorPath,
      sessionToken,
      deployComposeInspector: async (localPath, sha256) => {
        if (!/^[a-f0-9]{64}$/.test(sha256)) throw new Error('Invalid Compose inspector checksum');
        const bytes = await readFile(localPath);
        if (bytes.length < 1 || createHash('sha256').update(bytes).digest('hex') !== sha256) {
          throw new Error('Compose inspector checksum changed after resolution');
        }
        const temporary = `/data/local/tmp/aps-compose-${sha256.slice(0, 16)}.jar`;
        const destination = `${privateDirectory}/compose-${sha256.slice(0, 16)}.jar`;
        try {
          await adb.push(localPath, temporary, { timeoutMs: TIMEOUT_MS });
          await adb.shell(['run-as', packageName, 'cp', temporary, destination], { timeoutMs: TIMEOUT_MS });
          await adb.shell(['run-as', packageName, 'chmod', '444', destination], { timeoutMs: TIMEOUT_MS });
          privatePaths.push(destination);
          return destination;
        } finally {
          await adb.shell(['rm', '-f', temporary], { timeoutMs: TIMEOUT_MS }).catch(() => undefined);
        }
      },
      cleanup: async () => {
        if (cleaned) return;
        cleaned = true;
        await cleanupPrivateFiles(adb, packageName, privateDirectory, privatePaths);
      },
    };
  } catch (error) {
    await cleanupPrivateFiles(adb, packageName, privateDirectory, privatePaths);
    throw error;
  } finally {
    await Promise.all(staged.map((path) => adb.shell(['rm', '-f', path], { timeoutMs: TIMEOUT_MS }).catch(() => undefined)));
  }
}

async function targetPid(adb: ComposeDeploymentAdb, packageName: string): Promise<number> {
  const pids = (await adb.shell(['pidof', packageName], { timeoutMs: TIMEOUT_MS })).stdout
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .map(Number)
    .filter((pid) => Number.isInteger(pid) && pid > 0);
  if (pids.length !== 1) throw new Error(pids.length === 0 ? 'Target process is not running' : 'Target app has multiple processes');
  return pids[0] as number;
}

async function agentSocketExists(adb: ComposeDeploymentAdb, pid: number): Promise<boolean> {
  const sockets = await adb.shell(['cat', '/proc/net/unix'], { timeoutMs: TIMEOUT_MS });
  const name = `${SOCKET_PREFIX}${pid}`;
  return new RegExp(`(?:^|\\s)@?${name}(?:\\s|$)`, 'm').test(sockets.stdout);
}

async function waitForAgentSocket(
  adb: ComposeDeploymentAdb,
  pid: number,
  sleep: (milliseconds: number) => Promise<void>,
): Promise<void> {
  for (let attempt = 0; attempt < SOCKET_POLL_ATTEMPTS; attempt += 1) {
    if (await agentSocketExists(adb, pid)) return;
    if (attempt + 1 < SOCKET_POLL_ATTEMPTS) await sleep(100 * (2 ** Math.min(attempt, 3)));
  }
  throw new Error('Timed out waiting for Compose inspector agent socket');
}

async function cleanupPrivateFiles(
  adb: ComposeDeploymentAdb,
  packageName: string,
  privateDirectory: string,
  privatePaths: readonly string[],
): Promise<void> {
  await Promise.all([
    privatePaths.length === 0
      ? Promise.resolve()
      : adb.shell(['run-as', packageName, 'rm', '-f', ...privatePaths], { timeoutMs: TIMEOUT_MS }).catch(() => undefined),
    adb.shell(['run-as', packageName, 'rmdir', privateDirectory], { timeoutMs: TIMEOUT_MS }).catch(() => undefined),
  ]);
}

function validatePackageName(packageName: string): void {
  if (!PACKAGE_NAME.test(packageName)) throw new Error('Invalid target package name');
}

function isAppDataDirectory(path: string, packageName: string): boolean {
  return path === `/data/data/${packageName}` || /^\/data\/user(?:_de)?\/\d+\//.test(path) && path.endsWith(`/${packageName}`);
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
