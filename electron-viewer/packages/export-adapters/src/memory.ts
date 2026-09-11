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
