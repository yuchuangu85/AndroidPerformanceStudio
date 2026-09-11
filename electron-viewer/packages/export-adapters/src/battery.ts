/**
 * Port of the battery export adapters (BatteryExporters.kt).
 *
 * The JSON document and the CSV header are the Kotlin ones. Two differences
 * the TypeScript model forces: the session has no device capability level, so
 * that key is only written when the caller supplies one, and createdAt is an
 * epoch millisecond value that is written as an ISO string like Kotlin did.
 * The raw bundle keeps the manifest, the parsed history lines, and the capture
 * conditions; the checkin, report, and battery dumps are not retained by the
 * TypeScript capture path, so those entries cannot be reproduced.
 */
import { totalNetworkBytes, type AttributionScope, type BatteryRunDelta } from '@aps/battery-profiler';
import type { BatteryAnalysisResult, BatteryExperimentResult, ResourceTimer } from '@aps/battery-profiler';

export interface BatteryJsonOptions {
  /** Already pseudonymised by the caller, see deviceLocalId in @aps/contracts/node. */
  readonly deviceLocalId: string;
  readonly capabilityLevel?: string;
}

export interface ZipEntry {
  readonly name: string;
  readonly content: string;
}

export function batteryJson(experiment: BatteryExperimentResult, options: BatteryJsonOptions): string {
  const session = experiment.session;
  const document: Record<string, unknown> = {
    schemaVersion: 1,
    sessionId: session.id,
    deviceLocalId: options.deviceLocalId,
    packageName: session.packageName,
    uid: session.uid,
    attributionScope: session.attributionScope,
    captureMode: session.config.mode,
  };
  if (options.capabilityLevel !== undefined) document['capabilityLevel'] = options.capabilityLevel;
  document['createdAt'] = new Date(session.createdAtEpochMillis).toISOString();
  document['runs'] = experiment.analysis.runs.map(batteryRunJson);
  document['warnings'] = [...experiment.analysis.warnings];
  return JSON.stringify(document);
}

function batteryRunJson(delta: BatteryRunDelta): Record<string, unknown> {
  return {
    runId: delta.runId,
    iteration: delta.iteration,
    durationMs: delta.durationMs,
    networkBytes: totalNetworkBytes(delta.network),
    wakelocks: delta.wakelocks.map(timerJson),
    alarms: delta.alarms.map(timerJson),
    jobs: delta.jobs.map(timerJson),
    sensors: delta.sensors.map(timerJson),
    energy: delta.energy.map((energy) => ({
      component: energy.component,
      ...(energy.energyMah !== undefined ? { energyMah: energy.energyMah } : {}),
      ...(energy.energyUws !== undefined ? { energyUws: energy.energyUws } : {}),
      source: energy.source,
      scope: energy.attributionScope,
      confidence: energy.confidence,
    })),
    warnings: [...delta.warnings],
  };
}

function timerJson(timer: ResourceTimer): Record<string, unknown> {
  return { name: timer.name, durationMs: timer.durationMs, count: timer.count, confidence: timer.confidence };
}

export const BATTERY_CSV_HEADER =
  'schema_version,run,resource_type,name,duration_ms,count,bytes,energy_mah,energy_uws,source,scope,confidence';

/** Always quoted, exactly like the Kotlin exporter. */
function quoted(value: string): string {
  return '"' + value.replaceAll('"', '""') + '"';
}

export function batteryCsv(analysis: BatteryAnalysisResult, attributionScope: AttributionScope = 'UID'): string {
  const lines = [BATTERY_CSV_HEADER];
  const writeTimers = (run: BatteryRunDelta, kind: string, timers: readonly ResourceTimer[]): void => {
    for (const timer of timers) {
      lines.push(
        [
          '1',
          String(run.iteration),
          kind,
          quoted(timer.name),
          String(timer.durationMs),
          String(timer.count),
          '',
          '',
          '',
          'UID_COUNTER',
          attributionScope,
          timer.confidence,
        ].join(','),
      );
    }
  };
  for (const run of analysis.runs) {
    writeTimers(run, 'wakelock', run.wakelocks);
    writeTimers(run, 'alarm', run.alarms);
    writeTimers(run, 'job', run.jobs);
    writeTimers(run, 'sensor', run.sensors);
    lines.push(
      [
        '1',
        String(run.iteration),
        'network',
        'total',
        '',
        '',
        String(totalNetworkBytes(run.network)),
        '',
        '',
        '',
        '',
      ].join(','),
    );
    for (const energy of run.energy) {
      lines.push(
        [
          '1',
          String(run.iteration),
          'energy',
          quoted(energy.component),
          '',
          '',
          '',
          energy.energyMah === undefined ? '' : String(energy.energyMah),
          energy.energyUws === undefined ? '' : String(energy.energyUws),
          energy.source,
          energy.attributionScope,
          energy.confidence,
        ].join(','),
      );
    }
  }
  return lines.map((line) => line + '\n').join('');
}

export function batteryRawBundleEntries(experiment: BatteryExperimentResult): ZipEntry[] {
  const session = experiment.session;
  const entries: ZipEntry[] = [
    {
      name: 'manifest.txt',
      content:
        'schemaVersion=1\n' +
        'sessionId=' + session.id + '\n' +
        'packageName=' + session.packageName + '\n' +
        'uid=' + String(session.uid) + '\n',
    },
  ];
  for (const run of experiment.runs) {
    const snapshots = [run.baseline, ...run.samples, run.finalSnapshot];
    for (const snapshot of snapshots) {
      const prefix = 'run-' + String(run.iteration) + '/snapshot-' + String(snapshot.sequence);
      if (snapshot.history.length > 0) {
        entries.push({
          name: prefix + '/history.txt',
          content: snapshot.history.map((event) => event.raw).join('\n') + '\n',
        });
      }
      const conditions = Object.entries(snapshot.conditions);
      if (conditions.length > 0) {
        entries.push({
          name: prefix + '/conditions.txt',
          content: conditions.map(([key, value]) => key + '=' + value).join('\n') + '\n',
        });
      }
    }
  }
  return entries;
}
