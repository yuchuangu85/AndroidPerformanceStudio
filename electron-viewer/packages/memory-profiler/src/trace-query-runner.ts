/**
 * The slice of TraceAnalysisContext the adapters need. Declared structurally so
 * the memory package does not pull the whole Perfetto context (and its node
 * builtins) into a renderer type check.
 */
import type { StudioResult } from '@aps/contracts';
import type { TraceQuery } from '@aps/platform-perfetto';

export interface TraceQueryRunner {
  query<T>(query: TraceQuery<T>): Promise<StudioResult<T[]>>;
}
