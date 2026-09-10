const DATA_SOURCE_NAME = /^[A-Za-z0-9_.-]+$/;
export const MINIMUM_PERFETTO_BUFFER_SIZE_KB = 1_024;

export interface PerfettoDataSource {
  readonly name: string;
  readonly config?: string;
}

export interface PerfettoCaptureDocument {
  readonly durationMillis: number;
  readonly bufferSizeKb: number;
  readonly dataSources: readonly PerfettoDataSource[];
  readonly flushPeriodMillis?: number;
}

/** Serializes only the data sources requested by a feature adapter. */
export function composePerfettoConfig(document: PerfettoCaptureDocument): string {
  if (!(document.durationMillis > 0)) throw new Error('durationMillis must be positive');
  if (document.bufferSizeKb < MINIMUM_PERFETTO_BUFFER_SIZE_KB) {
    throw new Error('bufferSizeKb must be at least ' + MINIMUM_PERFETTO_BUFFER_SIZE_KB);
  }
  if (document.dataSources.length === 0) throw new Error('at least one feature-owned data source is required');
  if (document.flushPeriodMillis !== undefined && !(document.flushPeriodMillis > 0)) {
    throw new Error('flushPeriodMillis must be positive');
  }
  const lines: string[] = [];
  lines.push('buffers: {');
  lines.push('  size_kb: ' + document.bufferSizeKb);
  lines.push('  fill_policy: RING_BUFFER');
  lines.push('}');
  lines.push('duration_ms: ' + document.durationMillis);
  if (document.flushPeriodMillis !== undefined) {
    lines.push('flush_period_ms: ' + document.flushPeriodMillis);
  }
  for (const source of document.dataSources) {
    if (!DATA_SOURCE_NAME.test(source.name)) throw new Error('data source name must be a Perfetto identifier');
    lines.push('data_sources: {');
    lines.push('  config {');
    lines.push('    name: "' + source.name + '"');
    for (const line of (source.config ?? '').split('\n')) {
      lines.push('    ' + line);
    }
    lines.push('  }');
    lines.push('}');
  }
  return lines.join('\n') + '\n';
}
