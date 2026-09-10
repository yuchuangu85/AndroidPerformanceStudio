import { resolveTraceProcessorTool } from '@aps/platform-perfetto';
import type { TraceProcessorStatus } from '../shared/ipc.js';

export async function resolveTraceProcessorStatus(): Promise<TraceProcessorStatus> {
  const resolved = await resolveTraceProcessorTool();
  if (!resolved.ok) {
    return { available: false, error: resolved.error.code + ': ' + resolved.error.message };
  }
  return { available: true, path: resolved.value.path, version: resolved.value.version };
}
