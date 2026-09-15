import { copyFile, mkdir, mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import type { LayoutSnapshot } from '@aps/layout-inspector';
import { LayoutCaptureStore } from './layout-capture-store.js';
import { CaptureArchiveService } from './capture-archive-service.js';
import { CaptureArchiveCodec } from './capture-archive-codec.js';

const ONE_PIXEL_PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64');

const KOTLIN_CAPTURE_ARCHIVE_FIXTURE = fileURLToPath(
  new URL('./fixtures/kotlin-capture.apinspect', import.meta.url),
);

const ELECTRON_CAPTURE_ARCHIVE_FIXTURE_PATH = process.env['APS_ELECTRON_CAPTURE_ARCHIVE_FIXTURE_PATH'];

function snapshot(time: number): LayoutSnapshot {
  const root = { type: 'view' as const, id: 'root', className: 'android.view.View', bounds: { left: 0, top: 0, right: 1, bottom: 1 }, visible: true, alpha: 1, children: [], attributes: { rawProperties: {} } };
  return {
    protocolVersion: { major: 1, minor: 1 }, packageName: 'com.example', capturedAtEpochMillis: time,
    display: { widthPx: 1, heightPx: 1, density: 1 }, capabilities: { viewHierarchy: true, composeSemantics: false, screenshots: true, timeline: false },
    root, windows: [{ id: 'window:1', title: 'Example', type: 'ACTIVITY', bounds: root.bounds, root }], defaultWindowId: 'window:1',
  };
}

function countNodes(): number { return 1; }

async function service(directory: string): Promise<{ service: CaptureArchiveService; store: LayoutCaptureStore }> {
  const store = new LayoutCaptureStore(join(directory, 'captures'), { countNodes });
  return { service: new CaptureArchiveService({ store, producerVersion: () => '0.4.6' }), store };
}

describe('CaptureArchiveService', () => {
  it('exports a stored capture and imports it into another store', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'aps-archive-service-'));
    const source = await service(join(directory, 'source'));
    const rawArtifacts = { zip: Buffer.from([0x50, 0x4b, 3, 4]), text: 'VISIBLE WINDOW VIEW DUMP\n' };
    const archivePayloads = {
      analysisReportJson: '{"metrics":{"nodeCount":1,"maxDepth":1,"widestLevel":1},"findings":[]}',
      aiAnalysisReportJson: '{"model":"gpt-test","summary":"archive report","findings":[]}',
      timelineHistoryJson: '{"frames":[{"index":0,"capturedAtEpochMillis":1234}]}',
    };
    await source.store.add(snapshot(1234), ONE_PIXEL_PNG, '{"compose":true}', rawArtifacts, archivePayloads);
    const archive = join(directory, 'capture.apinspect');
    await source.service.export('1234', archive);

    const destination = await service(join(directory, 'destination'));
    const imported = await destination.service.import(archive);
    expect(imported.archiveVersion).toBe(2);
    expect(imported.snapshot.packageName).toBe('com.example');
    expect(await destination.store.loadScreenshot('1234')).toEqual(ONE_PIXEL_PNG);
    expect(await destination.store.loadComposeInspection('1234')).toBe('{"compose":true}');
    expect(await destination.store.loadRawArtifacts('1234')).toEqual(rawArtifacts);
    expect(await destination.store.loadArchivePayloads('1234')).toEqual(archivePayloads);
    expect(imported.composeInspectionJson).toBe('{"compose":true}');
    expect(imported.analysisReportJson).toBe(archivePayloads.analysisReportJson);
    expect(imported.aiAnalysisReportJson).toBe(archivePayloads.aiAnalysisReportJson);
    expect(imported.timelineHistoryJson).toBe(archivePayloads.timelineHistoryJson);
  });

  /**
   * The resulting fixture is consumed by Kotlin's CaptureArchiveService.
   * Regenerate it with:
   *   APS_ELECTRON_CAPTURE_ARCHIVE_FIXTURE_PATH=../desktop-viewer/layout-inspector/presentation/src/test/resources/electron-capture.apinspect \
   *     corepack pnpm@12.3.4 --dir electron-viewer exec vitest run \
   *       apps/desktop/src/main/capture-archive-service.test.ts -t "Electron-created v2 archive"
   */
  it('writes a full Electron-created v2 archive for Kotlin', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'aps-electron-archive-'));
    const archive = electronCaptureArchiveFixturePath(directory);
    await mkdir(dirname(archive), { recursive: true });
    const source = await service(join(directory, 'source'));
    const capturedAtEpochMillis = 1_750_000_001_000;
    const rawArtifacts = { zip: Buffer.from([0x50, 0x4b, 3, 4]), text: 'Electron visible-window hierarchy\n' };
    const archivePayloads = {
      analysisReportJson: '{"metrics":{"nodeCount":1,"maxDepth":1,"widestLevel":1},"findings":[{"ruleId":"layout.flat","severity":"INFO","nodeId":"root","message":"Electron archive finding","arguments":{}}]}',
      aiAnalysisReportJson: '{"model":"electron-archive-model","summary":"Electron archive AI summary","findings":[{"ruleId":"ai.layout","severity":"WARNING","nodeId":"root","title":"Electron archive AI finding","message":"Review the hierarchy.","recommendation":"Keep the hierarchy shallow.","confidence":0.8,"performanceEvidenceIds":[],"sourceCandidateIds":[]}],"provenance":null}',
      timelineHistoryJson: `{"frames":[{"index":1,"capturedAtEpochMillis":${capturedAtEpochMillis},"diffFromPrevious":{"previousCapturedAtEpochMillis":${capturedAtEpochMillis - 1_000},"currentCapturedAtEpochMillis":${capturedAtEpochMillis},"addedNodes":1,"removedNodes":0,"boundsChangedNodes":1,"changes":[{"type":"CHANGED","windowId":"window:1","nodeId":"root","nodeKey":"window:1/root","className":"android.view.View","changedProperties":["bounds"]}]}}]}`,
    };
    const composeInspectionJson = JSON.stringify({
      schemaVersion: 1,
      packageName: 'com.example',
      capturedAtEpochMillis,
      frame: { frameId: `com.example:${capturedAtEpochMillis}:1`, generation: 1, mode: 'FULL', capabilities: [], roots: [] },
    });
    await source.store.add(snapshot(capturedAtEpochMillis), ONE_PIXEL_PNG, composeInspectionJson, rawArtifacts, archivePayloads);
    await source.service.export(String(capturedAtEpochMillis), archive);

    const document = await new CaptureArchiveCodec().read(archive);
    expect(document).toMatchObject({
      archiveVersion: 2,
      metadata: {
        producerVersion: '0.4.6', packageName: 'com.example', capturedAtEpochMillis,
        protocolMajor: 1, protocolMinor: 1,
      },
      payload: { screenshotPng: ONE_PIXEL_PNG, composeInspectionJson, rawArtifacts, ...archivePayloads },
    });
  });

function electronCaptureArchiveFixturePath(directory: string): string {
  if (ELECTRON_CAPTURE_ARCHIVE_FIXTURE_PATH !== undefined && ELECTRON_CAPTURE_ARCHIVE_FIXTURE_PATH.trim().length > 0) {
    return ELECTRON_CAPTURE_ARCHIVE_FIXTURE_PATH;
  }
  return join(directory, 'electron-capture.apinspect');
}

  /**
   * The archive is created by Kotlin's CaptureArchiveService and all of its
   * serializers. Regenerate it with:
   *   cd desktop-viewer && ./gradlew :layout-inspector:presentation:writeElectronInteropFixture \
   *     -PfixturePath=../electron-viewer/apps/desktop/src/main/fixtures/kotlin-capture.apinspect
   */
  it('imports a Kotlin-created v2 archive with every supported payload', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'aps-kotlin-archive-'));
    const archive = join(directory, 'kotlin-capture.apinspect');
    await copyFile(KOTLIN_CAPTURE_ARCHIVE_FIXTURE, archive);

    const document = await new CaptureArchiveCodec().read(archive);
    expect(document.archiveVersion).toBe(2);
    expect(document.metadata).toEqual({
      producerVersion: 'kotlin-capture-fixture-v1',
      packageName: 'com.androidperformancestudio.sample',
      capturedAtEpochMillis: 1_750_000_000_000,
      protocolMajor: 1,
      protocolMinor: 1,
    });
    expect(document.payload.screenshotPng).toEqual(ONE_PIXEL_PNG);
    expect(document.payload.rawArtifacts).toEqual({
      zip: Buffer.from([0x50, 0x4b, 3, 4]),
      text: 'Kotlin visible-window hierarchy\n',
    });
    expect(JSON.parse(document.payload.composeInspectionJson ?? '{}')).toMatchObject({
      schemaVersion: 1,
      packageName: 'com.androidperformancestudio.sample',
      capturedAtEpochMillis: 1_750_000_000_000,
      privacy: 'SAFE_REDACTED',
      frame: { roots: [{ viewId: 7, nodes: [{ id: 1, name: 'Content' }] }] },
    });
    expect(JSON.parse(document.payload.composeInspectionJson ?? '{}').frame.details['1'].parameters[0].value).toBe('<redacted>');
    expect(JSON.parse(document.payload.analysisReportJson ?? '{}')).toMatchObject({
      metrics: { nodeCount: 3, maxDepth: 2, widestLevel: 2 },
      findings: [{ ruleId: 'layout.deep-hierarchy', severity: 'WARNING', nodeId: 'root' }],
    });
    expect(JSON.parse(document.payload.aiAnalysisReportJson ?? '{}')).toMatchObject({
      model: 'kotlin-archive-model',
      summary: 'Kotlin archive AI summary',
      findings: [{ ruleId: 'ai.layout', severity: 'ERROR', nodeId: 'root', confidence: 0.9 }],
    });
    expect(JSON.parse(document.payload.timelineHistoryJson ?? '{}')).toMatchObject({
      frames: [{
        index: 1,
        capturedAtEpochMillis: 1_750_000_000_000,
        diffFromPrevious: { addedNodes: 1, removedNodes: 0, boundsChangedNodes: 2 },
      }],
    });

    const destination = await service(join(directory, 'destination'));
    const imported = await destination.service.import(archive);
    expect(imported).toMatchObject({
      archiveVersion: 2,
      snapshot: {
        packageName: 'com.androidperformancestudio.sample',
        capturedAtEpochMillis: 1_750_000_000_000,
        display: { widthPx: 1, heightPx: 1 },
      },
      screenshotBase64: ONE_PIXEL_PNG.toString('base64'),
      composeInspectionJson: document.payload.composeInspectionJson,
      analysisReportJson: document.payload.analysisReportJson,
      aiAnalysisReportJson: document.payload.aiAnalysisReportJson,
      timelineHistoryJson: document.payload.timelineHistoryJson,
    });
    expect(await destination.store.loadScreenshot('1750000000000')).toEqual(ONE_PIXEL_PNG);
    expect(await destination.store.loadComposeInspection('1750000000000')).toBe(document.payload.composeInspectionJson);
    expect(await destination.store.loadRawArtifacts('1750000000000')).toEqual(document.payload.rawArtifacts);
    expect(await destination.store.loadArchivePayloads('1750000000000')).toEqual({
      analysisReportJson: document.payload.analysisReportJson,
      aiAnalysisReportJson: document.payload.aiAnalysisReportJson,
      timelineHistoryJson: document.payload.timelineHistoryJson,
    });
  });

  it('fails closed before exporting a persisted optional payload that is not Kotlin-compatible', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'aps-archive-service-invalid-'));
    const source = await service(join(directory, 'source'));
    await source.store.add(snapshot(5678), ONE_PIXEL_PNG, undefined, undefined, {
      analysisReportJson: '{"metrics":{},"findings":[]}',
    });
    const archive = join(directory, 'invalid-optional.apinspect');
    await expect(source.service.export('5678', archive)).rejects.toThrow(/analysis metrics/);
  });
});
