import { fail, ok, type StudioResult } from '@aps/contracts';
import {
  createStartupSession,
  evidence,
  parseAmStartOutput,
  parseStartupEventLog,
  planStartupRuns,
  requiresForceStop,
  UNAVAILABLE_EVIDENCE,
  validateStartupExperimentConfig,
  type StartupExperimentConfig,
  type StartupRun,
  type StartupSession,
} from '@aps/startup-profiler';

export interface StartupCaptureAdb {
  shell(args: readonly string[], options: { readonly timeoutMs: number }): Promise<{ stdout: string }>;
}

export interface StartupCaptureDependencies {
  readonly adb: StartupCaptureAdb;
  readonly now: () => number;
  readonly newId: () => string;
}

export interface StartupExperimentOptions {
  readonly serial: string;
  readonly packageName: string;
  readonly componentName?: string;
  readonly config: StartupExperimentConfig;
}

const HELPER_TIMEOUT_MS = 30_000;

/**
 * Runs a startup experiment: optional warmups, then measured runs. Each run
 * resets the process for cold mode, clears the event log, launches the activity,
 * and reads both the am start metrics and the TTID/TTFD events.
 */
export async function runStartupExperiment(
  dependencies: StartupCaptureDependencies,
  options: StartupExperimentOptions,
): Promise<StudioResult<StartupSession>> {
  const errors = validateStartupExperimentConfig(options.config);
  if (errors.length > 0) {
    return fail('DATA_VALIDATION', 'STARTUP_CONFIG_INVALID', errors.join('; '));
  }
  if (options.packageName.trim().length === 0) {
    return fail('DATA_VALIDATION', 'STARTUP_PACKAGE_REQUIRED', 'A package name is required');
  }
  const sessionId = dependencies.newId();
  const runs: StartupRun[] = [];
  for (const entry of planStartupRuns(options.config)) {
    const run = await executeRun(dependencies, options, sessionId, entry.iteration, entry.measured);
    if (!run.ok) return run;
    runs.push(run.value);
  }
  return ok(
    createStartupSession({
      id: sessionId,
      deviceSerial: options.serial,
      packageName: options.packageName,
      ...(options.componentName !== undefined ? { componentName: options.componentName } : {}),
      config: options.config,
      createdAtEpochMillis: dependencies.now(),
      runs,
    }),
  );
}

async function executeRun(
  dependencies: StartupCaptureDependencies,
  options: StartupExperimentOptions,
  sessionId: string,
  iteration: number,
  measured: boolean,
): Promise<StudioResult<StartupRun>> {
  const { adb } = dependencies;
  if (requiresForceStop(options.config.requestedType)) {
    try {
      await adb.shell(['am', 'force-stop', options.packageName], { timeoutMs: HELPER_TIMEOUT_MS });
    } catch (error) {
      return fail('PROCESS_EXIT', 'STARTUP_FORCE_STOP_FAILED', describe(error, options.serial, iteration));
    }
  }
  // Clearing the event log is best effort: a device may deny it.
  await adb.shell(['logcat', '-c'], { timeoutMs: HELPER_TIMEOUT_MS }).catch(() => undefined);

  const startArguments =
    options.componentName !== undefined && options.componentName.length > 0
      ? ['am', 'start', '-W', '-n', options.componentName]
      : ['am', 'start', '-W', '-p', options.packageName];
  let amStartOutput: string;
  try {
    amStartOutput = (
      await adb.shell(startArguments, { timeoutMs: options.config.timeoutSeconds * 1000 })
    ).stdout;
  } catch (error) {
    return fail('PROCESS_EXIT', 'STARTUP_RUN_FAILED', describe(error, options.serial, iteration));
  }

  const eventLogOutput = await adb
    .shell(['logcat', '-d', '-b', 'events', '-v', 'brief'], { timeoutMs: HELPER_TIMEOUT_MS })
    .then((result) => result.stdout)
    .catch(() => undefined);

  const parsed = parseAmStartOutput(amStartOutput);
  const events = eventLogOutput === undefined ? undefined : parseStartupEventLog(eventLogOutput, options.packageName);
  const warnings = [...parsed.warnings, ...(events?.warnings ?? [])];
  const metrics = {
    ...parsed.metrics,
    ...(events?.displayedTimeMs !== undefined ? { displayedTimeMs: events.displayedTimeMs } : {}),
    ...(events?.fullyDrawnTimeMs !== undefined ? { fullyDrawnTimeMs: events.fullyDrawnTimeMs } : {}),
  };
  const observedType = observedOf(parsed.metrics.launchState);
  return ok({
    id: sessionId + '-' + String(iteration),
    sessionId,
    iteration,
    measured,
    requestedType: options.config.requestedType,
    observedType,
    platform: metrics,
    warnings,
    amStartOutput,
    ...(eventLogOutput !== undefined ? { eventLogOutput } : {}),
    ttidEvidence:
      events?.displayedTimeMs === undefined
        ? UNAVAILABLE_EVIDENCE
        : evidence('EVENT_LOG', 'EXACT'),
    ttfdEvidence:
      events?.fullyDrawnTimeMs === undefined
        ? UNAVAILABLE_EVIDENCE
        : evidence('EVENT_LOG', 'EXACT'),
  });
}

function observedOf(launchState: string | undefined): StartupRun['observedType'] {
  const normalized = launchState?.trim().toUpperCase();
  if (normalized === 'COLD' || normalized === 'WARM' || normalized === 'HOT') return normalized;
  return 'UNKNOWN';
}

function describe(error: unknown, serial: string, iteration: number): string {
  const message = error instanceof Error ? error.message : 'startup run failed';
  return message + ' (device ' + serial + ', run ' + String(iteration) + ')';
}
