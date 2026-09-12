import { describe, expect, it } from 'vitest';
import type { PreferenceNode } from './legacy-linux-xml.js';
import {
  MIGRATED_PREFERENCE_KEYS,
  migrateApplicationSettings,
  migrateKeys,
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
    expect(result.settings.theme).toBe('dark');
    expect(result.settings.language).toBe('simplified_chinese');
    expect(result.settings.androidSdkPath).toBe('/Users/dev/Library/Android/sdk');
    expect(result.migratedKeys).toHaveLength(3);
    expect(result.missingKeys).toHaveLength(MIGRATED_PREFERENCE_KEYS.length - 3);
  });

  it('falls back to system defaults and reports missing keys', async () => {
    const result = await migrateApplicationSettings(source({}));
    expect(result.settings.theme).toBe('system');
    expect(result.settings.language).toBe('system');
    expect(result.settings.layoutInspector).toEqual({
      hideInvisibleHierarchyViews: false,
      hideInvisibleFindings: false,
      hideHierarchyIndices: false,
      showHierarchyIds: true,
      showHierarchyLayerVisibilityButtons: false,
      showVisibleViewBounds: true,
      canvasHitTestOrder: 'smallest-area',
      canvasBorderColors: { normal: '#FF7DD3FC', hovered: '#FFF59E0B', selected: '#FFEF4444' },
    });
    expect(result.missingKeys).toEqual([...MIGRATED_PREFERENCE_KEYS]);
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

  it('carries the Layout Inspector and simpleperf preferences over', async () => {
    const result = await migrateApplicationSettings(
      source({
        'com/androidperformancestudio/desktop': {
          'view.hideInvisibleHierarchyViews': 'true',
          'view.showHierarchyIds': 'false',
          'view.canvasHitTestOrder.zOrder': 'true',
          'canvas.bounds.selected': '#FF00FF00',
          'simpleperf.tooltipMode': 'FIXED',
        },
      }),
    );
    expect(result.settings.layoutInspector.hideInvisibleHierarchyViews).toBe(true);
    expect(result.settings.layoutInspector.showHierarchyIds).toBe(false);
    expect(result.settings.layoutInspector.canvasHitTestOrder).toBe('z-order');
    expect(result.settings.layoutInspector.canvasBorderColors.selected).toBe('#FF00FF00');
    expect(result.settings.layoutInspector.canvasBorderColors.normal).toBe('#FF7DD3FC');
    expect(result.settings.simpleperf.flameTooltipMode).toBe('fixed');
    expect(result.migratedKeys).toHaveLength(5);
  });
});

describe('migrateKeys', () => {
  it('collects only keys that are present', async () => {
    const layout = await migrateKeys(
      source({ 'com/androidperformancestudio/desktop': { 'view.showHierarchyIds': 'true' } }),
      ['view.showHierarchyIds', 'view.hideHierarchyIndices'],
    );
    expect(layout.values['view.showHierarchyIds']).toBe('true');
    expect(layout.migratedKeys).toEqual(['view.showHierarchyIds']);
    expect(layout.missingKeys).toEqual(['view.hideHierarchyIndices']);
  });
});
