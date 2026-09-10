import { describe, expect, it } from 'vitest';
import { resolveLanguage, SHELL_STRINGS, translate } from './i18n.js';

describe('shell i18n', () => {
  it('resolves the language preference and zh locales', () => {
    expect(resolveLanguage('system', 'zh-CN')).toBe('zh');
    expect(resolveLanguage('system', 'en-US')).toBe('en');
    expect(resolveLanguage('english', 'zh-CN')).toBe('en');
    expect(resolveLanguage('simplified_chinese', 'en-US')).toBe('zh');
  });

  it('provides both locales for every shell string', () => {
    for (const [key, value] of Object.entries(SHELL_STRINGS)) {
      expect(value.en.length, key).toBeGreaterThan(0);
      expect(value.zh.length, key).toBeGreaterThan(0);
    }
  });

  it('translates destination titles', () => {
    expect(translate('destination.layoutInspector', 'zh')).toBe('布局检查器');
    expect(translate('destination.layoutInspector', 'en')).toBe('Layout Inspector');
  });
});
