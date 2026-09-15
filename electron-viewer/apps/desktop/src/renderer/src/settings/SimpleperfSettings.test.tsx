import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { DEFAULT_SETTINGS } from '../../../shared/settings-contract';
import { SimpleperfSettingsPage } from './SimpleperfSettings';

function render(section: Parameters<typeof SimpleperfSettingsPage>[0]['section']): string {
  return renderToStaticMarkup(
    createElement(SimpleperfSettingsPage, {
      language: 'en',
      settings: DEFAULT_SETTINGS,
      section,
      onPatch: () => {},
    }),
  );
}

describe('Simpleperf settings', () => {
  it('keeps call graph and event scope available in the Advanced Parameters section', () => {
    const markup = render('ADVANCED_PARAMETERS');

    expect(markup).toContain('Advanced parameters');
    expect(markup).toContain('Call graph');
    expect(markup).toContain('Scope');
    expect(markup).toContain('DWARF');
    expect(markup).toContain('Frame pointer');
    expect(markup).toContain('User and kernel');
    expect(markup).not.toContain('not migrated yet');
  });

  it('exposes both Firefox engines as persisted runtime choices', () => {
    const markup = render('SIMPLEPERF_ENGINE');

    expect(markup).toContain('Firefox Profiler (local)');
    expect(markup).toContain('Firefox Profiler');
    expect(markup).not.toContain('not migrated yet');
    expect(markup).not.toContain('disabled');
  });
});
