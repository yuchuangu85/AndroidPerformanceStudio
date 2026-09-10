import { analyzeFrames, type FrameAnalysisResult } from './analysis.js';
import type { FrameSample } from './model.js';

export interface FrameSession {
  readonly id: string;
  readonly packageName: string;
  readonly capturedAtEpochMillis: number;
  readonly frames: readonly FrameSample[];
  readonly warnings: readonly string[];
}

export function createFrameSession(options: {
  readonly id: string;
  readonly packageName: string;
  readonly capturedAtEpochMillis: number;
  readonly frames: readonly FrameSample[];
  readonly warnings?: readonly string[];
}): FrameSession {
  return {
    id: options.id,
    packageName: options.packageName,
    capturedAtEpochMillis: options.capturedAtEpochMillis,
    frames: options.frames,
    warnings: options.warnings ?? [],
  };
}

/** Session analysis is derived on demand so stored sessions stay raw evidence. */
export function analyzeSession(session: FrameSession): FrameAnalysisResult {
  return analyzeFrames(session.frames);
}
