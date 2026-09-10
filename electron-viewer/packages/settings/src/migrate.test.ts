import { describe, expect, it } from 'vitest';
import type { PreferenceNode } from './legacy-linux-xml.js';
import {
  migrateApplicationSettings,
  migrateLayoutInspectorPreferences,
  migrateSimpleperfPreferences,
  type LegacyPreferenceSource,
} from './migrate.js';

function source(nodes: Record<string, Record<string, string>>): LegacyPreferenceSource {
  return {
    readNode: async (nodePath: string): Promise<PreferenceNode> => new Map(Object.entries(nodes[nodePath] ?? {})),
  };
}

describe('migrateApplicationSettings', () => {
  it('maps legacy keys onto application settings', async () => {
    const result = await migrateApplicationSettings(
      source({
        'com/androidperformancestudio/desktop': {
          'application.theme': 'dark',
          'application.language': 'simplified_chinese',
          'application.androidSdkPath': '  /Users/dev/Library/Android/sdk  ',
        },
      }),
    );
    expect(result.settings).toEqual({
      theme: 'dark',
      language: 'simplified_chinese',
      androidSdkPath: '/Users/dev/Library/Android/sdk',
    });
    expect(result.migratedKeys).toHaveLength(3);
    expect(result.missingKeys).toEqual([]);
  });

  it('falls back to system defaults and reports missing keys', async () => {
    const result = await migrateApplicationSettings(source({}));
    expect(result.settings).toEqual({ theme: 'system', language: 'system' });
    expect(result.missingKeys).toEqual([
      'application.theme',
      'application.language',
      'application.androidSdkPath',
    ]);
  });

  it('ignores unknown enum values', async () => {
    const result = await migrateApplicationSettings(
      source({
        'com/androidperformancestudio/desktop': {
          'application.theme': 'neon',
          'application.language': 'klingon',
        },
      }),
    );
    expect(result.settings.theme).toBe('system');
    expect(result.settings.language).toBe('system');
  });
});

describe('feature preference migration', () => {
  it('collects only keys that are present', async () => {
    const layout = await migrateLayoutInspectorPreferences(
      source({
        'com/androidperformancestudio/desktop': {
          'view.showHierarchyIds': 'true',
          'canvas.bounds.selected': '#FF00FF00',
        },
      }),
    );
    expect(layout.values['view.showHierarchyIds']).toBe('true');
    expect(layout.values['canvas.bounds.selected']).toBe('#FF00FF00');
    expect(layout.missingKeys.length).toBeGreaterThan(0);

    const simpleperf = await migrateSimpleperfPreferences(
      source({ 'com/androidperformancestudio/desktop': { 'simpleperf.engine': 'LOCAL' } }),
    );
    expect(simpleperf.values['simpleperf.engine']).toBe('LOCAL');
    expect(simpleperf.missingKeys).toEqual(['simpleperf.tooltipMode']);
  });
});
