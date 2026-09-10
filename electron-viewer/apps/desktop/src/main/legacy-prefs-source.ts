import { readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { runHostProcessText } from '@aps/platform-host';
import {
  parseLinuxPreferencesXml,
  parseMacOsPreferencesPlist,
  parseWindowsRegistryQuery,
  type LegacyPreferenceSource,
  type PreferenceNode,
  type PreferenceNodes,
} from '@aps/settings';

export interface LegacySourceDependencies {
  readonly platform: NodeJS.Platform;
  readonly userHome: string;
  readonly readFile: (path: string) => Promise<string>;
  readonly runText: (
    executable: string,
    args: readonly string[],
  ) => Promise<{ exitCode: number; stdout: string; stderr: string }>;
}

const EMPTY_NODE: PreferenceNode = new Map<string, string>();

function nodeAt(nodes: PreferenceNodes, nodePath: string): PreferenceNode {
  return nodes.get('/' + nodePath) ?? EMPTY_NODE;
}

/** Reads java.util.prefs from whichever backing store the host platform uses. */
export function createLegacyPreferenceSource(dependencies: LegacySourceDependencies): LegacyPreferenceSource {
  return {
    readNode: async (nodePath: string): Promise<PreferenceNode> => {
      if (dependencies.platform === 'darwin') return readMacOs(dependencies, nodePath);
      if (dependencies.platform === 'win32') return readWindows(dependencies, nodePath);
      return readLinux(dependencies, nodePath);
    },
  };
}

async function readLinux(dependencies: LegacySourceDependencies, nodePath: string): Promise<PreferenceNode> {
  const file = join(dependencies.userHome, '.java', '.userPrefs', ...nodePath.split('/'), 'prefs.xml');
  try {
    return nodeAt(parseLinuxPreferencesXml(await dependencies.readFile(file)), nodePath);
  } catch {
    return EMPTY_NODE;
  }
}

async function readMacOs(dependencies: LegacySourceDependencies, nodePath: string): Promise<PreferenceNode> {
  const plist = join(dependencies.userHome, 'Library', 'Preferences', 'com.apple.java.util.prefs.plist');
  let xml: string;
  const converted = await dependencies.runText('plutil', ['-convert', 'xml1', '-o', '-', plist]);
  if (converted.exitCode === 0 && converted.stdout.trim().length > 0) {
    xml = converted.stdout;
  } else {
    try {
      xml = await dependencies.readFile(plist);
    } catch {
      return EMPTY_NODE;
    }
  }
  try {
    return nodeAt(parseMacOsPreferencesPlist(xml), nodePath);
  } catch {
    return EMPTY_NODE;
  }
}

async function readWindows(dependencies: LegacySourceDependencies, nodePath: string): Promise<PreferenceNode> {
  const key = 'HKCU\\Software\\JavaSoft\\Prefs\\' + nodePath.split('/').join('\\');
  const result = await dependencies.runText('reg', ['query', key]);
  if (result.exitCode !== 0) return EMPTY_NODE;
  return parseWindowsRegistryQuery(result.stdout);
}

export function defaultLegacySourceDependencies(
  platform: NodeJS.Platform = process.platform,
  userHome: string = homedir(),
): LegacySourceDependencies {
  return {
    platform,
    userHome,
    readFile: async (path) => readFile(path, 'utf8'),
    runText: async (executable, args) => {
      const result = await runHostProcessText({ executable, args: [...args], timeoutMs: 10_000 });
      return { exitCode: result.exitCode, stdout: result.stdout, stderr: result.stderr };
    },
  };
}
