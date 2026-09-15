import { describe, expect, it } from 'vitest';
import type { PreferenceNode } from '@aps/settings';
import { loadApplicationSettings } from './settings-service.js';

function memoryIo() {
  const files = new Map<string, string>();
  return {
    files,
    io: {
      readFile: async (path: string): Promise<string> => {
        const value = files.get(path);
        if (value === undefined) throw new Error('ENOENT');
        return value;
      },
      writeFile: async (path: string, contents: string): Promise<void> => {
        files.set(path, contents);
      },
      mkdir: async (): Promise<void> => undefined,
    },
  };
}

describe('loadApplicationSettings', () => {
  it('persists the migrated Kotlin archive snapshot-size multiplier and reloads it', async () => {
    const { files, io } = memoryIo();
    let legacyReads = 0;
    const legacySource = {
      readNode: async (): Promise<PreferenceNode> => {
        legacyReads += 1;
        return new Map([['archive.snapshotSizeMultiplier', '3']]);
      },
    };

    const first = await loadApplicationSettings({
      userDataDirectory: '/data',
      io,
      legacySource,
    });
    expect(first.migration).toEqual({ source: 'migrated', migratedKeys: ['archive.snapshotSizeMultiplier'] });
    expect(first.settings.layoutInspector.snapshotSizeMultiplier).toBe(3);
    expect(JSON.parse(files.get('/data/settings.json') ?? '{}').layoutInspector.snapshotSizeMultiplier).toBe(3);

    const second = await loadApplicationSettings({
      userDataDirectory: '/data',
      io,
      legacySource,
    });
    expect(second.migration).toEqual({ source: 'stored', migratedKeys: [] });
    expect(second.settings.layoutInspector.snapshotSizeMultiplier).toBe(3);
    expect(legacyReads).toBe(1);
  });
});
