import { describe, expect, it } from 'vitest';
import { resolveDark, resolvedTheme } from './theme.js';

describe('theme resolution', () => {
  it('follows the system for the system preference', () => {
    expect(resolveDark('system', true)).toBe(true);
    expect(resolveDark('system', false)).toBe(false);
  });

  it('honours explicit preferences', () => {
    expect(resolveDark('dark', false)).toBe(true);
    expect(resolveDark('light', true)).toBe(false);
    expect(resolvedTheme('light', true)).toBe('light');
  });
});
