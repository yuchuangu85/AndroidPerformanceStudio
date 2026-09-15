import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { DEFAULT_SETTINGS } from '../../../shared/settings-contract';
import { LayoutInspectorSettingsPage } from './LayoutInspectorSettings';

describe('Layout Inspector settings', () => {
  it('shows and selects the Kotlin-compatible archive snapshot limit', () => {
    const markup = renderToStaticMarkup(
      createElement(LayoutInspectorSettingsPage, {
        language: 'en',
        settings: {
          ...DEFAULT_SETTINGS,
          layoutInspector: { ...DEFAULT_SETTINGS.layoutInspector, snapshotSizeMultiplier: 3 },
        },
        onPatch: () => {},
      }),
    );

    expect(markup).toContain('Snapshot limit: 324 MiB');
    expect(markup).toContain('324 MiB');
  });
});
