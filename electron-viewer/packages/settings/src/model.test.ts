import { describe, expect, it } from 'vitest';
import {
  DEFAULT_APPLICATION_UI_SETTINGS,
  mergeApplicationUiSettings,
  parseArgbColor,
  samplingTemplateDefaults,
} from './model.js';

describe('mergeApplicationUiSettings', () => {
  it('merges each section instead of replacing it', () => {
    const once = mergeApplicationUiSettings(DEFAULT_APPLICATION_UI_SETTINGS, {
      theme: 'dark',
      layoutInspector: { showHierarchyIds: false },
    });
    const twice = mergeApplicationUiSettings(once, {
      layoutInspector: { canvasBorderColors: { normal: '#FF010203' } },
      simpleperf: { captureDefaults: { frequencyHertz: 400 } },
    });
    expect(twice.theme).toBe('dark');
    expect(twice.layoutInspector.showHierarchyIds).toBe(false);
    expect(twice.layoutInspector.canvasBorderColors.normal).toBe('#FF010203');
    expect(twice.layoutInspector.canvasBorderColors.hovered).toBe('#FFF59E0B');
    expect(twice.simpleperf.captureDefaults.frequencyHertz).toBe(400);
    expect(twice.simpleperf.captureDefaults.event).toBe('cpu-clock');
  });

  it('clears the Android SDK path only when the patch says so', () => {
    const withPath = mergeApplicationUiSettings(DEFAULT_APPLICATION_UI_SETTINGS, {
      androidSdkPath: '/sdk',
    });
    expect(withPath.androidSdkPath).toBe('/sdk');
    const untouched = mergeApplicationUiSettings(withPath, { theme: 'light' });
    expect(untouched.androidSdkPath).toBe('/sdk');
    const cleared = mergeApplicationUiSettings(withPath, { androidSdkPath: null });
    expect(cleared.androidSdkPath).toBeUndefined();
  });

  it('rejects values the Kotlin stores would reject', () => {
    const merged = mergeApplicationUiSettings(DEFAULT_APPLICATION_UI_SETTINGS, {
      simpleperf: { captureDefaults: { event: '   ', durationSeconds: 0 } },
      layoutInspector: { canvasBorderColors: { selected: 'not-a-color' } },
    });
    expect(merged.simpleperf.captureDefaults.event).toBe('cpu-clock');
    expect(merged.simpleperf.captureDefaults.durationSeconds).toBe(1);
    expect(merged.layoutInspector.canvasBorderColors.selected).toBe('#FFEF4444');
  });
});

describe('parseArgbColor', () => {
  it('accepts the shapes CanvasArgb.parse accepts', () => {
    expect(parseArgbColor('#7dd3fc')).toBe('#FF7DD3FC');
    expect(parseArgbColor('807DD3FC')).toBe('#807DD3FC');
    expect(parseArgbColor('#GG0000')).toBeUndefined();
    expect(parseArgbColor(42)).toBeUndefined();
  });
});

describe('samplingTemplateDefaults', () => {
  it('ports the five SamplingTemplate presets', () => {
    expect(samplingTemplateDefaults('APP_CPU_BASIC', 'APP')).toMatchObject({
      event: 'cpu-clock',
      frequencyHertz: 1000,
      callGraph: 'DWARF',
      scope: 'USER',
    });
    expect(samplingTemplateDefaults('NATIVE_HOTSPOT', 'APP')).toMatchObject({
      event: 'cpu-cycles',
      frequencyHertz: 1000,
    });
    expect(samplingTemplateDefaults('LOW_OVERHEAD', 'APP')).toMatchObject({
      frequencyHertz: 100,
      callGraph: 'FRAME_POINTER',
    });
    expect(samplingTemplateDefaults('SYSTEM_PROCESS', 'SYSTEM_WIDE')).toMatchObject({
      frequencyHertz: 400,
      callGraph: 'FRAME_POINTER',
      scope: 'BOTH',
    });
  });
});
