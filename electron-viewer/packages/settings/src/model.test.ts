import { describe, expect, it } from 'vitest';
import {
  ACCENT_COLOR_PRESETS,
  accentColorOf,
  parseAccentPreference,
  DEFAULT_APPLICATION_UI_SETTINGS,
  DEFAULT_LAYOUT_INSPECTOR_SETTINGS,
  DISPLAY_SCALE_PERCENTS,
  mergeApplicationUiSettings,
  normalizeApplicationUiSettings,
  parseArgbColor,
  parseDisplayScalePercent,
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

  it('offers the theme colours and keeps the shell blue when none is chosen', () => {
    // The palette the General settings paint, key and colour alike.
    expect(ACCENT_COLOR_PRESETS.map((preset) => [preset.key, preset.color])).toEqual([
      ['default', '#007AFF'],
      ['banana-red', '#D4042D'],
      ['warm-sun-orange', '#DB7A0E'],
      ['cornflower-blue', '#5A92E5'],
      ['jade-green', '#5E8034'],
      ['merlot-pink', '#EB6D98'],
      ['azure', '#41B5C2'],
      ['lemon-yellow', '#FACA2E'],
      ['royal-purple', '#722169'],
    ]);
    expect(DEFAULT_APPLICATION_UI_SETTINGS.accentColor).toBe('default');
    expect(parseAccentPreference('royal-purple')).toBe('royal-purple');
    expect(parseAccentPreference('BANANA-RED')).toBe('default');
    expect(parseAccentPreference(undefined)).toBe('default');
    expect(accentColorOf('azure')).toBe('#41B5C2');
    expect(normalizeApplicationUiSettings({ accentColor: 'jade-green' }).accentColor).toBe('jade-green');
    expect(mergeApplicationUiSettings(DEFAULT_APPLICATION_UI_SETTINGS, { accentColor: 'merlot-pink' }).accentColor).toBe(
      'merlot-pink',
    );
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

describe('display scale', () => {
  it('defaults to the default size and offers the documented steps', () => {
    expect(DEFAULT_APPLICATION_UI_SETTINGS.displayScalePercent).toBe(100);
    expect(DISPLAY_SCALE_PERCENTS).toEqual([80, 90, 100, 110, 125, 150]);
  });

  it('clamps a stored size instead of leaving the shell unusable', () => {
    expect(parseDisplayScalePercent(125)).toBe(125);
    expect(parseDisplayScalePercent(10)).toBe(75);
    expect(parseDisplayScalePercent(1000)).toBe(200);
    expect(parseDisplayScalePercent(112.6)).toBe(113);
    expect(parseDisplayScalePercent('125')).toBe(100);
    expect(parseDisplayScalePercent(undefined)).toBe(100);
    expect(parseDisplayScalePercent(Number.NaN)).toBe(100);
  });

  it('changes one field and keeps the sections the caller never read', () => {
    const merged = mergeApplicationUiSettings(DEFAULT_APPLICATION_UI_SETTINGS, {
      displayScalePercent: 150,
    });
    expect(merged.displayScalePercent).toBe(150);
    expect(merged.layoutInspector).toEqual(DEFAULT_LAYOUT_INSPECTOR_SETTINGS);
    expect(normalizeApplicationUiSettings({ displayScalePercent: 5 }).displayScalePercent).toBe(75);
  });
});
