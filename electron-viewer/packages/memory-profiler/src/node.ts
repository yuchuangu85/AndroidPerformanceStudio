/**
 * Node-only half of the memory profiler: the file readers, the bitmap payload
 * extraction, and the Trace Processor adapters. Kept out of the main entry so a
 * renderer type check never sees node builtins.
 */
import { readFileSync } from 'node:fs';
import {
  javaHeapReadFailure,
  parseJavaHeapTrace,
  type JavaHeapParseResult,
} from './java-heap-trace.js';
import {
  parseNativeHeapTrace,
  parseNativeHeapTraceStrict,
  type NativeHeapAnalysis,
} from './native-heap-trace.js';

export * from './bitmap-dump.js';
export * from './bitmap-model.js';
export * from './native-heap-trace.js';
export * from './java-heap-trace.js';
export * from './heap-graph-bridge.js';
export * from './java-heap-adapter.js';
export * from './native-heap-adapter.js';
export * from './trace-query-runner.js';

export function parseJavaHeapTraceFile(path: string): JavaHeapParseResult {
  try {
    return parseJavaHeapTrace(readFileSync(path));
  } catch (error) {
    return javaHeapReadFailure(error);
  }
}

export function parseNativeHeapTraceFile(path: string, strict = false): NativeHeapAnalysis {
  const bytes = readFileSync(path);
  return strict ? parseNativeHeapTraceStrict(bytes) : parseNativeHeapTrace(bytes);
}
