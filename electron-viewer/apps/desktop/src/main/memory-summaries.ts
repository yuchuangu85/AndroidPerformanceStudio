/** Index summaries for the bitmap and native heap stores. */
import type { BitmapDumpSession, NativeHeapCaptureRecord } from '../shared/ipc.js';
import type { BitmapSessionSummary, NativeHeapSessionSummary } from '../shared/ipc.js';

export function summarizeBitmapSession(session: BitmapDumpSession): BitmapSessionSummary {
  return {
    id: session.id,
    packageName: session.packageName,
    deviceSerial: session.deviceSerial,
    capturedAtEpochMillis: session.capturedAtEpochMillis,
    exportedImageCount: session.summary.exportedImageCount,
    uniqueImageCount: session.summary.uniqueImageCount,
    duplicateGroupCount: session.summary.duplicateGroupCount,
    estimatedBitmapBytes: session.summary.estimatedBitmapBytes,
  };
}

export function summarizeNativeHeapSession(record: NativeHeapCaptureRecord): NativeHeapSessionSummary {
  return {
    id: record.id,
    packageName: record.packageName,
    deviceSerial: record.deviceSerial,
    capturedAtEpochMillis: record.capturedAtEpochMillis,
    fileSizeBytes: record.fileSizeBytes,
    totalAllocatedBytes: record.analysis.totalAllocatedBytes,
    totalFreedBytes: record.analysis.totalFreedBytes,
    sampleCount: record.analysis.sampleCount,
  };
}
