import { describe, expect, it } from 'vitest';
import { mergeApplicationUiSettings } from './model.js';
import { DEFAULT_APPLICATION_UI_SETTINGS, JsonSettingsStore, type SettingsStoreIo } from './index.js';

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
    expect(await store.load()).toEqual(DEFAULT_APPLICATION_UI_SETTINGS);
    const malformed = new JsonSettingsStore('/data/settings.json', memoryIo({ '/data/settings.json': '{oops' }));
    expect(await malformed.load()).toEqual(DEFAULT_APPLICATION_UI_SETTINGS);
  });

  it('round-trips every settings section', async () => {
    const io = memoryIo();
    const store = new JsonSettingsStore('/data/settings.json', io);
    const saved = mergeApplicationUiSettings(DEFAULT_APPLICATION_UI_SETTINGS, {
      theme: 'dark',
      language: 'english',
      androidSdkPath: '/sdk',
      layoutInspector: { showHierarchyIds: false, canvasBorderColors: { selected: '#FF00FF00' } },
      simpleperf: { flameTooltipMode: 'fixed', captureDefaults: { frequencyHertz: 400 } },
    });
    expect(await store.save(saved)).toBe(true);
    expect(await store.load()).toEqual(saved);
    expect(saved.layoutInspector.canvasBorderColors.normal).toBe('#FF7DD3FC');
  });

  it('survives unknown values and partial sections', async () => {
    const io = memoryIo();
    const store = new JsonSettingsStore('/data/settings.json', io);
    io.files.set(
      '/data/settings.json',
      JSON.stringify({
        theme: 'neon',
        language: 42,
        layoutInspector: { canvasBorderColors: { normal: '#7dd3fc' } },
        simpleperf: { captureDefaults: { frequencyHertz: 99999999 } },
      }),
    );
    const loaded = await store.load();
    expect(loaded.theme).toBe('system');
    expect(loaded.language).toBe('system');
    expect(loaded.layoutInspector.canvasBorderColors.normal).toBe('#FF7DD3FC');
    expect(loaded.simpleperf.captureDefaults.frequencyHertz).toBe(100000);
  });

  it('reports write failures instead of throwing', async () => {
    const store = new JsonSettingsStore('/data/settings.json', {
      readFile: async () => '',
      writeFile: async () => {
        throw new Error('EACCES');
      },
      mkdir: async () => undefined,
    });
    expect(await store.save(DEFAULT_APPLICATION_UI_SETTINGS)).toBe(false);
  });
});
