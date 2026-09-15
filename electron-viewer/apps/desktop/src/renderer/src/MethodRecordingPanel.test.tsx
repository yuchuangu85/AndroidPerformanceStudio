import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { MethodRecordingPanel } from './MethodRecordingPanel';

describe('MethodRecordingPanel', () => {
  it('renders a device-scoped process picker instead of editable package and PID fields', () => {
    const markup = renderToStaticMarkup(
      createElement(MethodRecordingPanel, {
        language: 'en',
        devices: [{ serial: 'emulator-5554', state: 'ONLINE', model: 'Pixel' }],
      }),
    );

    expect(markup).toContain('Process');
    expect(markup).toContain('No debuggable or profileable app processes found');
    expect(markup).toContain('Refresh processes');
    expect(markup).toContain('Stop recording');
    expect(markup).toContain('emulator-5554');
    expect(markup).not.toContain('com.example.app');
    expect(markup).not.toContain('Process id');
  });

  it('offers offline trace import without a connected device', () => {
    const markup = renderToStaticMarkup(createElement(MethodRecordingPanel, { language: 'en', devices: [] }));

    expect(markup).toContain('Import trace');
    expect(markup).toContain('Import an existing ART .trace without connecting to a device.');
  });
});
