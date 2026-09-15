import {
  analyzeLayout,
  decodeLayoutSnapshot,
  encodeLayoutSnapshot,
  normalizedToCurrentProtocol,
  parseArchivedAiAnalysisReport,
  parseArchivedAnalysisReport,
  parseArchivedTimelineHistory,
  type LayoutSnapshot,
} from '@aps/layout-inspector';
import type { LayoutCaptureStore } from './layout-capture-store.js';
import { CaptureArchiveCodec, CaptureArchiveFormatError } from './capture-archive-codec.js';

export interface CaptureArchiveServiceOptions {
  readonly store: LayoutCaptureStore;
  readonly producerVersion: () => string;
  readonly snapshotSizeMultiplier?: number;
}

export interface ImportedCaptureArchive {
  readonly id: string;
  readonly archiveVersion: number;
  readonly snapshot: LayoutSnapshot;
  readonly screenshotBase64?: string;
  readonly composeInspectionJson?: string;
  /** Kotlin-compatible `report/analysis-report.json`, preserved verbatim. */
  readonly analysisReportJson?: string;
  /** Kotlin-compatible `report/ai-analysis-report.json`, including provenance. */
  readonly aiAnalysisReportJson?: string;
  /** Kotlin-compatible `timeline/history.json`, containing summaries rather than per-frame snapshots. */
  readonly timelineHistoryJson?: string;
}

export class CaptureArchiveService {
  private readonly codec: CaptureArchiveCodec;
  constructor(private readonly options: CaptureArchiveServiceOptions) {
    this.codec = new CaptureArchiveCodec({ snapshotSizeMultiplier: options.snapshotSizeMultiplier });
  }

  async export(sourceId: string, target: string): Promise<{ path: string; rawArtifactsIncluded: boolean }> {
    const snapshot = await this.options.store.loadSnapshot(sourceId);
    if (snapshot === undefined) throw new CaptureArchiveFormatError('Layout capture not found');
    const normalized = normalizedToCurrentProtocol(snapshot);
    const screenshot = await this.options.store.loadScreenshot(sourceId);
    const composeInspectionJson = await this.options.store.loadComposeInspection(sourceId);
    const rawArtifacts = await this.options.store.loadRawArtifacts(sourceId);
    const archivePayloads = await this.options.store.loadArchivePayloads(sourceId);
    validateOptionalPayloads(archivePayloads);
    // Native Electron captures calculate the same static report as the renderer.
    // Imported reports stay byte-for-byte intact so a later export does not rewrite
    // a historical Kotlin report with whatever the current implementation derives.
    const analysisReportJson = archivePayloads.analysisReportJson ?? JSON.stringify(analyzeLayout(normalized.root));
    return await this.codec.write(
      target,
      {
        producerVersion: this.options.producerVersion(),
        packageName: normalized.packageName,
        capturedAtEpochMillis: normalized.capturedAtEpochMillis,
        protocolMajor: normalized.protocolVersion.major,
        protocolMinor: normalized.protocolVersion.minor,
      },
      {
        snapshotJson: encodeLayoutSnapshot(normalized),
        ...(screenshot !== undefined && screenshot.length > 0 ? { screenshotPng: screenshot } : {}),
        ...(rawArtifacts !== undefined ? { rawArtifacts } : {}),
        analysisReportJson,
        ...(archivePayloads.aiAnalysisReportJson !== undefined ? { aiAnalysisReportJson: archivePayloads.aiAnalysisReportJson } : {}),
        ...(archivePayloads.timelineHistoryJson !== undefined ? { timelineHistoryJson: archivePayloads.timelineHistoryJson } : {}),
        ...(composeInspectionJson !== undefined ? { composeInspectionJson } : {}),
      },
    );
  }

  async import(source: string): Promise<ImportedCaptureArchive> {
    const document = await this.codec.read(source);
    const snapshot = normalizedToCurrentProtocol(decodeLayoutSnapshot(document.payload.snapshotJson));
    if (
      document.metadata.packageName !== snapshot.packageName ||
      document.metadata.capturedAtEpochMillis !== snapshot.capturedAtEpochMillis ||
      document.metadata.protocolMajor !== snapshot.protocolVersion.major ||
      document.metadata.protocolMinor !== snapshot.protocolVersion.minor
    ) throw new CaptureArchiveFormatError('Archive metadata does not match the layout snapshot');
    const screenshot = document.payload.screenshotPng;
    if (screenshot !== undefined) validatePng(screenshot, snapshot.display.widthPx, snapshot.display.heightPx);
    validateOptionalPayloads(document.payload);
    const record = await this.options.store.add(
      snapshot,
      screenshot ?? Buffer.alloc(0),
      document.payload.composeInspectionJson,
      document.payload.rawArtifacts,
      optionalPayloadsOf(document.payload),
    );
    return {
      id: record.id,
      archiveVersion: document.archiveVersion,
      snapshot,
      ...(screenshot !== undefined && screenshot.length <= 32 * 1024 * 1024 ? { screenshotBase64: screenshot.toString('base64') } : {}),
      ...(document.payload.composeInspectionJson !== undefined ? { composeInspectionJson: document.payload.composeInspectionJson } : {}),
      ...optionalPayloadsOf(document.payload),
    };
  }
}

function validatePng(bytes: Buffer, expectedWidth: number, expectedHeight: number): void {
  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  if (bytes.length < 33 || !bytes.subarray(0, 8).equals(signature) || bytes.toString('ascii', 12, 16) !== 'IHDR') {
    throw new CaptureArchiveFormatError('Screenshot is not a valid PNG');
  }
  const width = bytes.readUInt32BE(16);
  const height = bytes.readUInt32BE(20);
  if (width === 0 || height === 0 || width > 16_384 || height > 16_384 || width * height > 64 * 1024 * 1024) {
    throw new CaptureArchiveFormatError('Screenshot dimensions are too large');
  }
  if (width !== expectedWidth || height !== expectedHeight) {
    throw new CaptureArchiveFormatError(`Screenshot dimensions ${width}x${height} do not match layout ${expectedWidth}x${expectedHeight}`);
  }
  // A complete PNG ends with an IEND chunk. This catches valid-looking truncated headers without an image decoder.
  if (bytes.toString('ascii', bytes.length - 8, bytes.length - 4) !== 'IEND') throw new CaptureArchiveFormatError('Screenshot is not a valid PNG');
}


function optionalPayloadsOf(payload: {
  readonly analysisReportJson?: string;
  readonly aiAnalysisReportJson?: string;
  readonly timelineHistoryJson?: string;
}): {
  readonly analysisReportJson?: string;
  readonly aiAnalysisReportJson?: string;
  readonly timelineHistoryJson?: string;
} {
  return {
    ...(payload.analysisReportJson !== undefined ? { analysisReportJson: payload.analysisReportJson } : {}),
    ...(payload.aiAnalysisReportJson !== undefined ? { aiAnalysisReportJson: payload.aiAnalysisReportJson } : {}),
    ...(payload.timelineHistoryJson !== undefined ? { timelineHistoryJson: payload.timelineHistoryJson } : {}),
  };
}

function validateOptionalPayloads(payload: {
  readonly analysisReportJson?: string;
  readonly aiAnalysisReportJson?: string;
  readonly timelineHistoryJson?: string;
}): void {
  try {
    if (payload.analysisReportJson !== undefined) parseArchivedAnalysisReport(payload.analysisReportJson);
    if (payload.aiAnalysisReportJson !== undefined) parseArchivedAiAnalysisReport(payload.aiAnalysisReportJson);
    if (payload.timelineHistoryJson !== undefined) parseArchivedTimelineHistory(payload.timelineHistoryJson);
  } catch (error) {
    throw new CaptureArchiveFormatError(error instanceof Error ? error.message : 'Archive optional payload is invalid');
  }
}
