import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { DEFAULT_SETTINGS } from '../../../shared/settings-contract';
import { GeneralSettings } from './GeneralSettings';

function render(overrides: Partial<typeof DEFAULT_SETTINGS> = {}): string {
  return renderToStaticMarkup(
    createElement(GeneralSettings, {
      language: 'zh' as const,
      settings: { ...DEFAULT_SETTINGS, ...overrides },
      onPatch: () => {},
    }),
  );
}

/**
 * General is the one settings page the reference draws with DropdownSelector
 * rather than a stack of choice chips, so the three rows are asserted here: a
 * dropdown each, and one option per value the reference offers.
 */
describe('general settings', () => {
  it('chooses language, theme and display size with a dropdown', () => {
    const markup = render();
    expect(markup.match(/<select/g) ?? []).toHaveLength(3);
    // The control that came before was one full-width button per option.
    expect(markup).not.toContain('settings__choice');
    expect(markup).toContain('settings__select');
  });

  it('offers the reference options with the stored one selected', () => {
    const markup = render({ language: 'english', theme: 'dark', displayScalePercent: 110 });
    expect(markup).toContain('<option value="system">跟随系统</option>');
    expect(markup).toContain('<option value="simplified_chinese">简体中文</option>');
    expect(markup).toContain('<option value="english" selected="">English</option>');
    expect(markup).toContain('<option value="light">浅色</option>');
    expect(markup).toContain('<option value="dark" selected="">深色</option>');
    expect(markup).toContain('<option value="80">80%</option>');
    expect(markup).toContain('<option value="110" selected="">110%</option>');
    expect(markup).toContain('<option value="150">150%</option>');
  });

  it('paints the theme colours and marks the chosen one', () => {
    const markup = render({ accentColor: 'azure' });
    expect(markup.match(/settings__accent"/g) ?? []).toHaveLength(8);
    expect(markup.match(/settings__accent--selected/g) ?? []).toHaveLength(1);
    expect(markup).toContain('background:#41B5C2');
    expect(markup).toContain('title="碧青"');
    expect(markup).toContain('title="蕉红"');
    expect(markup).toContain('title="默认"');
    expect(markup).toContain('aria-checked="true"');
  });

  it('labels each dropdown with the row it belongs to', () => {
    const markup = render();
    expect(markup).toContain('>语言</span>');
    expect(markup).toContain('>主题</span>');
    expect(markup).toContain('>显示大小</span>');
  });
});
