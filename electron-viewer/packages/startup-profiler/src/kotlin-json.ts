import { DEFAULT_STARTUP_EXPERIMENT, type StartupExperimentConfig } from './experiment.js';
import {
  UNAVAILABLE_EVIDENCE,
  type EvidenceConfidence,
  type MetricEvidence,
  type StartupRun,
  type StartupSource,
  type StartupType,
} from './model.js';
import { createStartupSession, type StartupSession, type StartupSessionStatistics } from './session.js';

/** Kotlin's public StartupJsonExporter / StartupJsonImporter contract. */
export const KOTLIN_STARTUP_JSON_SCHEMA_VERSION = 1;
/** Keeps malformed or hostile file-picker input out of the main process. */
export const MAX_KOTLIN_STARTUP_JSON_BYTES = 16 * 1024 * 1024;

const STARTUP_TYPES = new Set<StartupType>(['COLD', 'WARM', 'HOT', 'UNKNOWN']);
const STARTUP_SOURCES = new Set<StartupSource>(['AM_START', 'AGENT', 'EVENT_LOG', 'PERFETTO']);
const EVIDENCE_CONFIDENCES = new Set<EvidenceConfidence>(['EXACT', 'ESTIMATED', 'INFERRED', 'UNAVAILABLE']);

export interface KotlinStartupJsonImportOptions {
  readonly id: string;
  readonly capturedAtEpochMillis: number;
  readonly sourceFileName?: string;
}

/**
 * Reads the documented Kotlin StartupJsonExporter v1 shape into Electron's
 * session model. Kotlin exports only the pseudonymous deviceLocalId, so this
 * deliberately does not fabricate a live ADB serial for imported evidence.
 */
export function importKotlinStartupJson(text: string, options: KotlinStartupJsonImportOptions): StartupSession {
  const document = parseDocument(text);
  const runs = document.runs.map((run) => toStartupRun(run, options.id));
  // Session-wide provenance is valid only when every run supplies the same
  // context. A subset of contextual runs must not label the whole session.
  const contexts = document.runs.map((run) => run.context);
  const sharedContexts = contexts.every((context): context is KotlinRunContext => context !== undefined)
    ? contexts
    : [];
  const packageName = common(sharedContexts.map((context) => context.packageName));
  const componentName = common(sharedContexts.map((context) => context.componentName));
  const sourceDeviceLocalId = common(sharedContexts.map((context) => context.deviceLocalId));
  const requestedType = runs[0]?.requestedType ?? 'COLD';
  const config: StartupExperimentConfig = {
    ...DEFAULT_STARTUP_EXPERIMENT,
    requestedType,
    warmupRuns: 0,
    measuredRuns: runs.length,
  };

  return {
    ...createStartupSession({
      id: options.id,
      // A stable, non-device sentinel is never used for capture. The origin
      // fields below explicitly distinguish it from a captured device serial.
      deviceSerial: 'IMPORTED',
      ...(packageName !== undefined ? { packageName } : {}),
      ...(componentName !== undefined ? { componentName } : {}),
      config,
      createdAtEpochMillis: options.capturedAtEpochMillis,
      runs,
    }),
    origin: 'IMPORTED',
    ...(sourceDeviceLocalId !== undefined ? { sourceDeviceLocalId } : {}),
    ...(options.sourceFileName !== undefined ? { sourceFileName: options.sourceFileName } : {}),
    // Do not reconstruct an imported report: Kotlin v1 can contain rich
    // evidence that Electron intentionally does not turn into its own model.
    kotlinJsonV1: text,
  };
}

/**
 * Emits the minimum complete v1 document accepted by Kotlin's
 * StartupJsonImporter. It intentionally omits context: Kotlin requires a
 * pseudonymous deviceLocalId there, and Electron must not export an ADB serial
 * as if it were that pseudonym.
 */
export function exportKotlinStartupJson(session: StartupSession): string {
  if (session.kotlinJsonV1 !== undefined) {
    // Revalidate persisted wire text before writing it back out. This preserves
    // all Kotlin v1 evidence without ever exposing a captured Electron serial.
    parseDocument(session.kotlinJsonV1);
    return session.kotlinJsonV1;
  }

  const warnings = [...new Set(session.runs.flatMap((run) => run.warnings))];
  const document = {
    schemaVersion: KOTLIN_STARTUP_JSON_SCHEMA_VERSION,
    summary: {
      totalTime: statisticsDocument(session.statistics.totalTimeMs),
      firstFrame: statisticsDocument(session.statistics.ttidMs),
      fullyDrawn: statisticsDocument(session.statistics.ttfdMs),
      agentFirstFrame: emptyStatisticsDocument(session.statistics.measuredRuns),
    },
    warnings,
    runs: session.runs.map((run) => ({
      iteration: run.iteration,
      runId: run.id,
      requestedType: run.requestedType,
      observedType: run.observedType,
      totalTimeMs: run.platform.totalTimeMs ?? null,
      thisTimeMs: run.platform.thisTimeMs ?? null,
      waitTimeMs: run.platform.waitTimeMs ?? null,
      displayedTimeMs: run.platform.displayedTimeMs ?? null,
      fullyDrawnTimeMs: run.platform.fullyDrawnTimeMs ?? null,
      agentAvailable: false,
      diagnostics: [],
      metricEvidence: {
        ttid: metricEvidenceDocument(run.ttidEvidence),
        ttfd: metricEvidenceDocument(run.ttfdEvidence),
        agentFirstFrame: metricEvidenceDocument(UNAVAILABLE_EVIDENCE),
      },
      warnings: [...run.warnings],
      milestones: [],
      phases: [],
      rawEvidence: {
        amStartOutput: run.amStartOutput,
        eventLogOutput: run.eventLogOutput ?? null,
        compilationOutput: null,
      },
    })),
  };
  return JSON.stringify(document, null, 2) + '\n';
}

interface KotlinRunContext {
  readonly deviceLocalId: string;
  readonly packageName: string;
  readonly componentName: string;
}

interface KotlinMetricEvidence {
  readonly source?: StartupSource;
  readonly confidence: EvidenceConfidence;
  readonly unavailableReason?: string;
}

interface KotlinStartupRun {
  readonly iteration: number;
  readonly runId: string;
  readonly requestedType: StartupType;
  readonly observedType: StartupType;
  readonly totalTimeMs?: number;
  readonly thisTimeMs?: number;
  readonly waitTimeMs?: number;
  readonly displayedTimeMs?: number;
  readonly fullyDrawnTimeMs?: number;
  readonly warnings: readonly string[];
  readonly rawEvidence: {
    readonly amStartOutput: string;
    readonly eventLogOutput?: string;
  };
  readonly context?: KotlinRunContext;
  readonly metricEvidence?: {
    readonly ttid?: KotlinMetricEvidence;
    readonly ttfd?: KotlinMetricEvidence;
  };
}

interface KotlinStartupDocument {
  readonly runs: readonly KotlinStartupRun[];
}

function parseDocument(text: string): KotlinStartupDocument {
  if (new TextEncoder().encode(text).byteLength > MAX_KOTLIN_STARTUP_JSON_BYTES) {
    throw new TypeError('Startup JSON exceeds the 16 MiB import limit');
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new TypeError('Startup JSON is not valid JSON');
  }
  const document = record(parsed, 'Startup JSON');
  if (document['schemaVersion'] !== KOTLIN_STARTUP_JSON_SCHEMA_VERSION) {
    throw new TypeError('Unsupported Startup Profiler schema version');
  }
  const rawRuns = array(document['runs'], 'Startup JSON runs');
  if (rawRuns.length === 0 || rawRuns.length > 100) {
    throw new TypeError('Startup JSON must contain between 1 and 100 runs');
  }
  const runIds = new Set<string>();
  const runs = rawRuns.map((value, index) => parseRun(value, index));
  for (const run of runs) {
    if (runIds.has(run.runId)) throw new TypeError('Startup JSON contains duplicate run IDs');
    runIds.add(run.runId);
  }
  return { runs };
}

function parseRun(value: unknown, index: number): KotlinStartupRun {
  const run = record(value, `Startup JSON run ${String(index + 1)}`);
  const requestedType = enumValue(run['requestedType'], STARTUP_TYPES, 'requested startup type');
  const observedType = enumValue(run['observedType'], STARTUP_TYPES, 'observed startup type');
  const rawEvidence = record(run['rawEvidence'], 'Startup JSON raw evidence');
  const metricEvidence = run['metricEvidence'] === undefined
    ? undefined
    : parseMetricEvidenceContainer(run['metricEvidence']);
  const eventLogOutput = optionalStringValue(rawEvidence['eventLogOutput'], 'eventLogOutput');
  return {
    iteration: nonNegativeInteger(run['iteration'], 'startup iteration'),
    runId: nonEmptyString(run['runId'], 'startup run ID'),
    requestedType,
    observedType,
    ...optionalDuration(run['totalTimeMs'], 'totalTimeMs'),
    ...optionalDuration(run['thisTimeMs'], 'thisTimeMs'),
    ...optionalDuration(run['waitTimeMs'], 'waitTimeMs'),
    ...optionalDuration(run['displayedTimeMs'], 'displayedTimeMs'),
    ...optionalDuration(run['fullyDrawnTimeMs'], 'fullyDrawnTimeMs'),
    warnings: optionalStringArray(run['warnings'], 'startup warnings'),
    rawEvidence: {
      amStartOutput: stringValue(rawEvidence['amStartOutput'], 'amStartOutput'),
      ...(eventLogOutput !== undefined ? { eventLogOutput } : {}),
    },
    ...(run['context'] === undefined ? {} : { context: parseContext(run['context']) }),
    ...(metricEvidence === undefined ? {} : { metricEvidence }),
  };
}

function toStartupRun(run: KotlinStartupRun, sessionId: string): StartupRun {
  const ttidEvidence = run.metricEvidence?.ttid ?? legacyEvidence(
    run.displayedTimeMs,
    'No displayed event was observed.',
  );
  const ttfdEvidence = run.metricEvidence?.ttfd ?? legacyEvidence(
    run.fullyDrawnTimeMs,
    'The app did not call reportFullyDrawn() during this run.',
  );
  return {
    id: run.runId,
    sessionId,
    iteration: run.iteration,
    measured: true,
    requestedType: run.requestedType,
    observedType: run.observedType,
    platform: {
      complete: false,
      ...optionalProperty('thisTimeMs', run.thisTimeMs),
      ...optionalProperty('totalTimeMs', run.totalTimeMs),
      ...optionalProperty('waitTimeMs', run.waitTimeMs),
      ...optionalProperty('displayedTimeMs', run.displayedTimeMs),
      ...optionalProperty('fullyDrawnTimeMs', run.fullyDrawnTimeMs),
    },
    warnings: run.warnings,
    amStartOutput: run.rawEvidence.amStartOutput,
    ...optionalProperty('eventLogOutput', run.rawEvidence.eventLogOutput),
    ttidEvidence,
    ttfdEvidence,
  };
}

function parseContext(value: unknown): KotlinRunContext {
  const context = record(value, 'startup context');
  return {
    deviceLocalId: nonEmptyString(context['deviceLocalId'], 'startup deviceLocalId'),
    packageName: nonEmptyString(context['packageName'], 'startup packageName'),
    componentName: nonEmptyString(context['componentName'], 'startup componentName'),
  };
}

function parseMetricEvidenceContainer(value: unknown): { readonly ttid?: KotlinMetricEvidence; readonly ttfd?: KotlinMetricEvidence } {
  const container = record(value, 'startup metric evidence');
  return {
    ...(container['ttid'] === undefined || container['ttid'] === null ? {} : { ttid: parseMetricEvidence(container['ttid']) }),
    ...(container['ttfd'] === undefined || container['ttfd'] === null ? {} : { ttfd: parseMetricEvidence(container['ttfd']) }),
  };
}

function parseMetricEvidence(value: unknown): KotlinMetricEvidence {
  const evidence = record(value, 'startup metric evidence item');
  const confidence = enumValue(evidence['confidence'] ?? 'UNAVAILABLE', EVIDENCE_CONFIDENCES, 'startup evidence confidence');
  const source = evidence['source'];
  const unavailableReason = optionalStringValue(evidence['unavailableReason'], 'startup unavailable reason');
  return {
    ...(source === undefined || source === null ? {} : { source: enumValue(source, STARTUP_SOURCES, 'startup evidence source') }),
    confidence,
    ...(unavailableReason !== undefined ? { unavailableReason } : {}),
  };
}

function legacyEvidence(value: number | undefined, unavailableReason: string): MetricEvidence {
  return value === undefined
    ? { confidence: 'UNAVAILABLE', unavailableReason }
    : { source: 'EVENT_LOG', confidence: 'EXACT' };
}

function statisticsDocument(statistics: StartupSessionStatistics[keyof Pick<StartupSessionStatistics, 'totalTimeMs' | 'ttidMs' | 'ttfdMs'>]): Record<string, unknown> {
  return {
    count: statistics.count,
    missingCount: statistics.missingCount,
    minimumMs: statistics.minimumMs ?? null,
    maximumMs: statistics.maximumMs ?? null,
    medianMs: statistics.medianMs ?? null,
    meanMs: statistics.meanMs ?? null,
    p90Ms: statistics.p90Ms ?? null,
    p95Ms: statistics.p95Ms ?? null,
    standardDeviationMs: statistics.standardDeviationMs ?? null,
    medianAbsoluteDeviationMs: statistics.medianAbsoluteDeviationMs ?? null,
    p90LowResolution: statistics.p90LowResolution,
    p95LowResolution: statistics.p95LowResolution,
  };
}

function emptyStatisticsDocument(measuredRuns: number): Record<string, unknown> {
  return {
    count: 0,
    missingCount: measuredRuns,
    minimumMs: null,
    maximumMs: null,
    medianMs: null,
    meanMs: null,
    p90Ms: null,
    p95Ms: null,
    standardDeviationMs: null,
    medianAbsoluteDeviationMs: null,
    p90LowResolution: true,
    p95LowResolution: true,
  };
}

function metricEvidenceDocument(evidence: MetricEvidence): Record<string, unknown> {
  return {
    source: evidence.source ?? null,
    confidence: evidence.confidence,
    unavailableReason: evidence.unavailableReason ?? null,
  };
}

function common(values: readonly string[]): string | undefined {
  const first = values[0];
  return first !== undefined && values.every((value) => value === first) ? first : undefined;
}

function record(value: unknown, label: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError(`${label} must be an object`);
  return value as Record<string, unknown>;
}

function array(value: unknown, label: string): readonly unknown[] {
  if (!Array.isArray(value)) throw new TypeError(`${label} must be an array`);
  return value;
}

function stringValue(value: unknown, label: string): string {
  if (typeof value !== 'string') throw new TypeError(`${label} must be a string`);
  return value;
}

function nonEmptyString(value: unknown, label: string): string {
  const text = stringValue(value, label).trim();
  if (text.length === 0 || text.length > 16_384) throw new TypeError(`${label} has an invalid length`);
  return text;
}

function optionalStringValue(value: unknown, label: string): string | undefined {
  if (value === undefined || value === null) return undefined;
  return stringValue(value, label);
}

function optionalStringArray(value: unknown, label: string): readonly string[] {
  if (value === undefined || value === null) return [];
  const values = array(value, label);
  if (values.length > 1_000) throw new TypeError(`${label} contains too many entries`);
  return values.map((entry) => stringValue(entry, label));
}

function optionalDuration(value: unknown, label: string): { readonly [key: string]: number } {
  if (value === undefined || value === null) return {};
  return { [label]: nonNegativeInteger(value, label) };
}

function nonNegativeInteger(value: unknown, label: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new TypeError(`${label} must be a non-negative safe integer`);
  return value;
}

function enumValue<T extends string>(value: unknown, allowed: ReadonlySet<T>, label: string): T {
  if (typeof value !== 'string' || !allowed.has(value as T)) throw new TypeError(`${label} has an invalid value`);
  return value as T;
}

function optionalProperty<T extends string, U>(key: T, value: U | undefined): { readonly [key in T]?: U } {
  return value === undefined ? {} : { [key]: value } as { readonly [key in T]: U };
}
