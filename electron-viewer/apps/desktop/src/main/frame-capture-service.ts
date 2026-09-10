import { fail, ok, type StudioResult } from '@aps/contracts';
import { createFrameSession, parseGfxInfoFrameStats, type FrameSession } from '@aps/frame-profiler';

export interface FrameCaptureAdb {
  shell(args: readonly string[], options: { readonly timeoutMs: number }): Promise<{ stdout: string }>;
}

export interface FrameCaptureDependencies {
  readonly adb: FrameCaptureAdb;
  readonly now: () => number;
  readonly newSessionId: () => string;
}

const SHELL_TIMEOUT_MS = 30_000;

/**
 * Captures FrameMetrics through dumpsys gfxinfo. Resetting the device counters
 * first keeps the capture scoped to the window the user is about to exercise.
 */
export async function captureFrameSession(
  dependencies: FrameCaptureDependencies,
  options: { readonly serial: string; readonly packageName: string; readonly resetDeviceStats?: boolean },
): Promise<StudioResult<FrameSession>> {
  const { adb } = dependencies;
  if (options.packageName.trim().length === 0) {
    return fail('DATA_VALIDATION', 'FRAME_PACKAGE_REQUIRED', 'A package name is required');
  }
  if (options.resetDeviceStats !== false) {
    try {
      await adb.shell(['dumpsys', 'gfxinfo', options.packageName, 'reset'], { timeoutMs: SHELL_TIMEOUT_MS });
    } catch (error) {
      return fail('PROCESS_EXIT', 'FRAME_RESET_FAILED', describe(error, options.serial));
    }
  }
  let output: string;
  try {
    output = (await adb.shell(['dumpsys', 'gfxinfo', options.packageName, 'framestats'], { timeoutMs: SHELL_TIMEOUT_MS }))
      .stdout;
  } catch (error) {
    return fail('PROCESS_EXIT', 'FRAME_DUMP_FAILED', describe(error, options.serial));
  }
  const parsed = parseGfxInfoFrameStats(output, dependencies.newSessionId(), options.packageName);
  if (parsed.frames.length === 0) {
    return fail(
      'DATA_VALIDATION',
      'FRAME_NO_FRAMES',
      parsed.warnings.join(' ') || 'gfxinfo returned no usable frames',
    );
  }
  return ok(
    createFrameSession({
      id: dependencies.newSessionId(),
      packageName: options.packageName,
      capturedAtEpochMillis: dependencies.now(),
      frames: parsed.frames,
      warnings: parsed.warnings,
    }),
  );
}

function describe(error: unknown, serial: string): string {
  const message = error instanceof Error ? error.message : 'frame capture failed';
  return message + ' (device ' + serial + ')';
}
