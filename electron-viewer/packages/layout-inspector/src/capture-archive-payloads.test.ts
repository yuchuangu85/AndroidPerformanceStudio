import { describe, expect, it } from 'vitest';
import {
  CaptureArchivePayloadError,
  parseArchivedAiAnalysisReport,
  parseArchivedAnalysisReport,
  parseArchivedTimelineHistory,
} from './capture-archive-payloads.js';

describe('Kotlin CaptureArchive optional payloads', () => {
  it('decodes Kotlin-shaped analysis, AI provenance, and timeline history payloads', () => {
    expect(parseArchivedAnalysisReport(JSON.stringify({
      metrics: { nodeCount: 3, maxDepth: 2, widestLevel: 2 },
      findings: [{ ruleId: 'layout.deep-hierarchy', severity: 'WARNING', nodeId: 'root', message: 'Deep', arguments: { depth: '12' } }],
    }))).toMatchObject({ metrics: { nodeCount: 3 }, findings: [{ severity: 'WARNING' }] });

    expect(parseArchivedAiAnalysisReport(JSON.stringify({
      model: 'gpt-test', summary: 'Review complete',
      findings: [{ ruleId: 'ai.layout', severity: 'ERROR', nodeId: 'root', title: 'Problem', message: 'Why', recommendation: 'Fix it', confidence: 0.9 }],
      provenance: {
        sessionId: 'session-1', provider: 'openai', scope: 'layout', promptVersion: 'v1', payloadPolicyVersion: 'p1',
        sourceCandidates: [{ id: 'candidate-1', relativePath: 'app/Main.kt', resolutionConfidence: 'STRONG', startLine: 7, contentHash: 'abc' }],
      },
    }))).toMatchObject({ model: 'gpt-test', findings: [{ sourceCandidateIds: [] }], provenance: { sourceCandidates: [{ startLine: 7 }] } });

    // Kotlin's `encodeDefaults = true` writes a nullable provenance as null.
    expect(parseArchivedAiAnalysisReport(JSON.stringify({
      model: 'gpt-test', summary: 'No provenance was captured', findings: [], provenance: null,
    }))).toEqual({ model: 'gpt-test', summary: 'No provenance was captured', findings: [] });

    expect(parseArchivedTimelineHistory(JSON.stringify({
      frames: [{
        index: 2, capturedAtEpochMillis: 2_000,
        diffFromPrevious: {
          previousCapturedAtEpochMillis: 1_000, currentCapturedAtEpochMillis: 2_000,
          addedNodes: 1, removedNodes: 2, boundsChangedNodes: 3,
          changes: [{ type: 'CHANGED', windowId: 'window:1', nodeId: 'root', nodeKey: 'window:1/root', className: 'View', changedProperties: ['bounds'] }],
        },
      }],
    }))).toMatchObject({ frames: [{ index: 2, diffFromPrevious: { addedNodes: 1, changes: [{ type: 'CHANGED' }] } }] });
  });

  it('rejects malformed optional payloads before they cross the archive service boundary', () => {
    expect(() => parseArchivedAnalysisReport('{"metrics":{}}')).toThrow(CaptureArchivePayloadError);
    expect(() => parseArchivedAiAnalysisReport('{"model":"m","summary":"s","findings":[{"severity":"BAD"}]}')).toThrow(/invalid|must be a string/);
    expect(() => parseArchivedTimelineHistory('{"frames":[{"index":1,"capturedAtEpochMillis":2,"diffFromPrevious":{"previousCapturedAtEpochMillis":1,"currentCapturedAtEpochMillis":2,"addedNodes":0,"removedNodes":0,"boundsChangedNodes":0,"changes":[{"type":"MOVED"}]}}]}')).toThrow(/invalid|must be a string/);
  });
});
