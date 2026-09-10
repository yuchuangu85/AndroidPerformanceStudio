import { describe, expect, it } from 'vitest';
import { JsonSettingsStore, type SettingsStoreIo } from './store.js';

function memoryIo(initial: Record<string, string> = {}): SettingsStoreIo & { files: Map<string, string> } {
  const files = new Map(Object.entries(initial));
  return {
    files,
    readFile: async (path) => {
      const value = files.get(path);
      if (value === undefined) throw new Error('ENOENT');
      return value;
    },
    writeFile: async (path, contents) => {
      files.set(path, contents);
    },
    mkdir: async () => undefined,
  };
}

describe('JsonSettingsStore', () => {
  it('returns defaults when the file is missing or malformed', async () => {
    const store = new JsonSettingsStore('/data/settings.json', memoryIo());
    expect(await store.load()).toEqual({ theme: 'system', language: 'system' });
    const malformed = new JsonSettingsStore('/data/settings.json', memoryIo({ '/data/settings.json': '{oops' }));
    expect(await malformed.load()).toEqual({ theme: 'system', language: 'system' });
  });

  it('round-trips settings and survives unknown values', async () => {
    const io = memoryIo();
    const store = new JsonSettingsStore('/data/settings.json', io);
    expect(await store.save({ theme: 'dark', language: 'english', androidSdkPath: '/sdk' })).toBe(true);
    expect(await store.load()).toEqual({ theme: 'dark', language: 'english', androidSdkPath: '/sdk' });

    io.files.set('/data/settings.json', JSON.stringify({ theme: 'neon', language: 42 }));
    expect(await store.load()).toEqual({ theme: 'system', language: 'system' });
  });

  it('reports write failures instead of throwing', async () => {
    const store = new JsonSettingsStore('/data/settings.json', {
      readFile: async () => '',
      writeFile: async () => {
        throw new Error('EACCES');
      },
      mkdir: async () => undefined,
    });
    expect(await store.save({ theme: 'light', language: 'system' })).toBe(false);
  });
});
