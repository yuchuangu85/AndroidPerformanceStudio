/**
 * Port of the memory export adapters (MemoryExportAdapters.kt).
 *
 * The class histogram CSV keeps the Kotlin header. Retained sizes are written
 * empty unless the caller passes them: the TypeScript histogram entry carries
 * shallow size only, and retained size comes from the dominator analysis. The
 * raw and converted HPROF copies are not here, because the TypeScript memory
 * session does not keep the source file path the Kotlin heap dump has.
 */
export interface HistogramExportEntry {
  readonly className: string;
  readonly instanceCount: number;
  readonly shallowBytes: number;
  readonly retainedBytes?: number;
}

export const MEMORY_HISTOGRAM_HEADER = 'className,instanceCount,shallowSizeBytes,retainedSizeBytes';

export function classHistogramCsv(entries: readonly HistogramExportEntry[]): string {
  const lines = [MEMORY_HISTOGRAM_HEADER];
  for (const entry of entries) {
    lines.push(
      [
        memoryCsvValue(entry.className),
        memoryCsvValue(String(entry.instanceCount)),
        memoryCsvValue(String(entry.shallowBytes)),
        entry.retainedBytes === undefined ? '' : memoryCsvValue(String(entry.retainedBytes)),
      ].join(','),
    );
  }
  return lines.map((line) => line + '\n').join('');
}

/** The memory exporter also quotes carriage returns, unlike the frame one. */
export function memoryCsvValue(value: string): string {
  const needsQuoting = [',', '"', '\n', '\r'].some((character) => value.includes(character));
  if (!needsQuoting) return value;
  return '"' + value.replaceAll('"', '""') + '"';
}
/**
 * The deep reports as CSV. Kept beside the histogram so one export bundle can
 * carry every table the panel shows.
 */
export interface LeakSuspectExportEntry {
  readonly className: string;
  readonly reason: string;
  readonly retainedBytes: number;
  readonly instanceCount: number;
  readonly requiresManualVerification: boolean;
  readonly activityOrFragmentLeak: boolean;
  readonly referenceChain: readonly string[];
}

export const MEMORY_LEAK_SUSPECT_HEADER =
  'className,reason,retainedSizeBytes,instanceCount,requiresManualVerification,activityOrFragmentLeak,referenceChain';

export function leakSuspectsCsv(entries: readonly LeakSuspectExportEntry[]): string {
  const lines = [MEMORY_LEAK_SUSPECT_HEADER];
  for (const entry of entries) {
    lines.push(
      [
        memoryCsvValue(entry.className),
        memoryCsvValue(entry.reason),
        memoryCsvValue(String(entry.retainedBytes)),
        memoryCsvValue(String(entry.instanceCount)),
        memoryCsvValue(String(entry.requiresManualVerification)),
        memoryCsvValue(String(entry.activityOrFragmentLeak)),
        memoryCsvValue(entry.referenceChain.join(' -> ')),
      ].join(','),
    );
  }
  return lines.map((line) => line + '\n').join('');
}

export interface ActivityLeakExportEntry {
  readonly className: string;
  readonly liveInstanceCount: number;
  readonly destroyedInstanceCount: number;
  readonly retainedBytes: number;
}

export const MEMORY_ACTIVITY_LEAK_HEADER =
  'className,liveInstanceCount,destroyedInstanceCount,retainedSizeBytes';

export function activityLeaksCsv(entries: readonly ActivityLeakExportEntry[]): string {
  const lines = [MEMORY_ACTIVITY_LEAK_HEADER];
  for (const entry of entries) {
    lines.push(
      [
        memoryCsvValue(entry.className),
        memoryCsvValue(String(entry.liveInstanceCount)),
        memoryCsvValue(String(entry.destroyedInstanceCount)),
        memoryCsvValue(String(entry.retainedBytes)),
      ].join(','),
    );
  }
  return lines.map((line) => line + '\n').join('');
}

/** Port of BitmapDumpExportAdapters.kt: one row per exported PNG. */
export interface BitmapDumpExportEntry {
  readonly recordIndex: number;
  readonly fileName: string;
  readonly width: number;
  readonly height: number;
  readonly pngBytes: number;
  readonly estimatedMemoryBytes: number;
  readonly duplicateCount: number;
  readonly sha256: string;
}

export const BITMAP_DUMP_HEADER =
  'recordIndex,fileName,width,height,pngBytes,estimatedBitmapBytes,duplicateCount,sha256';

export function bitmapDumpCsv(entries: readonly BitmapDumpExportEntry[]): string {
  const lines = [BITMAP_DUMP_HEADER];
  for (const entry of entries) {
    lines.push(
      [
        memoryCsvValue(String(entry.recordIndex)),
        memoryCsvValue(entry.fileName),
        memoryCsvValue(String(entry.width)),
        memoryCsvValue(String(entry.height)),
        memoryCsvValue(String(entry.pngBytes)),
        memoryCsvValue(String(entry.estimatedMemoryBytes)),
        memoryCsvValue(String(entry.duplicateCount)),
        memoryCsvValue(entry.sha256),
      ].join(','),
    );
  }
  return lines.map((line) => line + '\n').join('');
}
