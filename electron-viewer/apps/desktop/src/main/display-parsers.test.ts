import { describe, expect, it } from 'vitest';
import { parseDeviceDisplay, parseWmDensity, parseWmSize } from './display-parsers.js';

describe('wm output parsing', () => {
  it('prefers the physical size', () => {
    expect(parseWmSize('Physical size: 1080x1920\nOverride size: 720x1280\n')).toEqual({
      widthPx: 1080,
      heightPx: 1920,
    });
    expect(parseWmSize('Override size: 720x1280\n')).toEqual({ widthPx: 720, heightPx: 1280 });
  });

  it('rejects unusable sizes', () => {
    expect(parseWmSize('')).toBeUndefined();
    expect(parseWmSize('Physical size: 0x0')).toBeUndefined();
  });

  it('converts density to a scale', () => {
    expect(parseWmDensity('Physical density: 440\n')).toBe(2.75);
    expect(parseWmDensity('Physical density: 160')).toBe(1);
    expect(parseWmDensity('nope')).toBeUndefined();
  });

  it('combines size and density with a safe default', () => {
    expect(parseDeviceDisplay('Physical size: 1080x1920', 'Physical density: 440')).toEqual({
      widthPx: 1080,
      heightPx: 1920,
      density: 2.75,
    });
    expect(parseDeviceDisplay('Physical size: 1080x1920', 'nope')?.density).toBe(1);
    expect(parseDeviceDisplay('nope', 'Physical density: 440')).toBeUndefined();
  });
});
