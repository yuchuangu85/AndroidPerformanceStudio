/**
 * Model of an ART method trace (the .trace file written by am profile start and
 * stop, or Debug.startMethodTracing). Two on-disk layouts exist:
 *
 * - streaming versions 4/5, the default on modern Android: a 32 byte header
 *   followed by length prefixed packets,
 * - classic versions 2/3: a header, a text section with the method and thread
 *   tables, then fixed size records.
 *
 * Both are normalized into one event stream: per-thread, time-ordered method
 * enter/exit/unroll events on a monotonic nanosecond timeline.
 */

/** ART records either one clock or wall plus per-thread CPU time. */
export type ArtClockSource = 'SINGLE' | 'DUAL';

export const ART_TRACE_ACTIONS = ['ENTER', 'EXIT', 'UNROLL'] as const;
export type ArtTraceAction = (typeof ART_TRACE_ACTIONS)[number];

export interface ArtTraceHeader {
  readonly version: number;
  /** Monotonic clock at trace start, nanoseconds (v2/v3 converted from µs). */
  readonly startTimeNanos: bigint;
  readonly clockSource: ArtClockSource;
}

export interface ArtMethod {
  readonly methodId: bigint;
  readonly className: string;
  readonly methodName: string;
  readonly signature: string;
  readonly sourceFile: string;
}

export interface ArtThread {
  readonly threadId: number;
  readonly name: string;
}

export interface ArtTraceEvent {
  readonly threadId: number;
  readonly methodId: bigint;
  readonly action: ArtTraceAction;
  /** Monotonic time since trace start, nanoseconds. */
  readonly timeNanos: bigint;
  /** Per-thread CPU time since trace start, when the trace is dual clock. */
  readonly cpuNanos?: bigint;
}

export interface ArtTraceAnalysis {
  readonly header: ArtTraceHeader;
  readonly methods: ReadonlyMap<bigint, ArtMethod>;
  readonly threads: ReadonlyMap<number, ArtThread>;
  /** File order; each thread's own events are chronological. */
  readonly events: readonly ArtTraceEvent[];
  readonly startTimeNanos: bigint;
  readonly endTimeNanos: bigint;
  readonly warnings: readonly string[];
}

/** Qualified display name: dot separated class plus method, or the raw id. */
export function methodDisplayName(method: ArtMethod | undefined, methodId: bigint): string {
  if (method === undefined) return '0x' + methodId.toString(16);
  const dottedClass = method.className.replaceAll('/', '.').replace(/;$/, '');
  const qualified = method.methodName.length === 0 ? dottedClass : dottedClass + '.' + method.methodName;
  return qualified.length === 0 ? '0x' + methodId.toString(16) : qualified;
}
