import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { DEFAULT_STARTUP_EXPERIMENT, createStartupSession, type StartupSession } from '@aps/startup-profiler';
import { describe, expect, it } from 'vitest';
import { StartupImportedEvidence } from './StartupProfilerPanel';

function importedSession(): StartupSession {
  const session = createStartupSession({
    id: 'imported',
    deviceSerial: 'IMPORTED',
    packageName: 'dev.example',
    componentName: 'dev.example/.MainActivity',
    config: { ...DEFAULT_STARTUP_EXPERIMENT, measuredRuns: 1 },
    createdAtEpochMillis: 0,
    runs: [{
      id: 'run',
      sessionId: 'imported',
      iteration: 1,
      measured: true,
      requestedType: 'COLD',
      observedType: 'COLD',
      platform: { complete: true, totalTimeMs: 100, displayedTimeMs: 80 },
      warnings: ['Milestones are not modeled by Electron.'],
      amStartOutput: 'am start',
      ttidEvidence: { source: 'EVENT_LOG', confidence: 'INFERRED' },
      ttfdEvidence: { confidence: 'UNAVAILABLE', unavailableReason: 'No fully drawn event.' },
    }],
  });
  return {
    ...session,
    origin: 'IMPORTED',
    sourceFileName: 'startup.sqlite',
    sourceDeviceLocalId: 'pseudonymous-device',
    sourceDatabaseSha256: 'a'.repeat(64),
  };
}

describe('StartupImportedEvidence', () => {
  it('visibly distinguishes imported SQLite provenance, confidence, and limitations', () => {
    const markup = renderToStaticMarkup(createElement(StartupImportedEvidence, { session: importedSession(), language: 'en' }));

    expect(markup).toContain('Imported evidence');
    expect(markup).toContain('startup.sqlite');
    expect(markup).toContain('pseudonymous-device');
    expect(markup).toContain('a'.repeat(64));
    expect(markup).toContain('EVENT_LOG · INFERRED');
    expect(markup).toContain('NO_SOURCE · UNAVAILABLE · No fully drawn event.');
    expect(markup).toContain('Milestones are not modeled by Electron.');
  });

  it('does not render imported provenance for a captured session', () => {
    const session = { ...importedSession(), origin: 'CAPTURED' as const };
    expect(renderToStaticMarkup(createElement(StartupImportedEvidence, { session, language: 'en' }))).toBe('');
  });
});
