import { isAbsolute, normalize, resolve } from 'node:path';
import {
  medianOf,
  type BenchmarkBuild,
  type BenchmarkCase,
  type BenchmarkDevice,
  type BenchmarkMetric,
  type BenchmarkRun,
  type MetricDirection,
} from './model.js';

export const MAX_BENCHMARK_JSON_BYTES = 64 * 1024 * 1024;

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

function asArray(value: unknown): unknown[] | undefined {
  return Array.isArray(value) ? value : undefined;
}

function stringOf(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function numberOf(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function booleanOf(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined;
}

function recordString(record: Record<string, unknown>, name: string): string | undefined {
  return stringOf(record[name]);
}

function recordNumber(record: Record<string, unknown>, name: string): number | undefined {
  return numberOf(record[name]);
}

function recordObject(record: Record<string, unknown>, name: string): Record<string, unknown> | undefined {
  return asRecord(record[name]);
}

export function inferUnit(name: string): string {
  const lower = name.toLowerCase();
  if (name.toLowerCase().endsWith('ms') || lower.includes('millisecond')) return 'ms';
  if (name.toLowerCase().endsWith('ns') || lower.includes('nanosecond')) return 'ns';
  if (lower.includes('byte') || name.toLowerCase().endsWith('kb')) return 'bytes';
  if (lower.includes('percent') || name.toLowerCase().endsWith('pct')) return '%';
  return 'unit';
}

export function inferDirection(name: string): MetricDirection {
  const lower = name.toLowerCase();
  const higher = ['throughput', 'fps', 'framespersecond'];
  const lower_ = ['time', 'duration', 'latency', 'overrun', 'jank', 'memory', 'byte', 'power', 'energy'];
  if (higher.some((token) => lower.includes(token))) return 'HIGHER_IS_BETTER';
  if (lower_.some((token) => lower.includes(token))) return 'LOWER_IS_BETTER';
  return 'UNKNOWN';
}

function numericSample(value: unknown): number | undefined {
  const primitive = numberOf(value);
  if (primitive !== undefined) return primitive;
  const record = asRecord(value);
  if (record === undefined) return undefined;
  return recordNumber(record, 'value') ?? recordNumber(record, 'measurement') ?? recordNumber(record, 'ns');
}

function parseMetric(name: string, element: unknown): BenchmarkMetric | undefined {
  const primitive = numberOf(element);
  if (primitive !== undefined) {
    return {
      name,
      unit: inferUnit(name),
      direction: inferDirection(name),
      samples: [primitive],
      minimum: primitive,
      median: primitive,
      maximum: primitive,
      confidence: 'PARTIAL',
      sourceFields: {},
    };
  }
  const metric = asRecord(element);
  if (metric === undefined) return undefined;
  const samplesElement =
    asArray(metric['runs']) ?? asArray(metric['values']) ?? asArray(metric['samples']) ?? asArray(metric['measurements']);
  const samples = (samplesElement ?? [])
    .map(numericSample)
    .filter((value): value is number => value !== undefined);
  const minimum = recordNumber(metric, 'minimum') ?? recordNumber(metric, 'min') ?? (samples.length > 0 ? Math.min(...samples) : undefined);
  const median = recordNumber(metric, 'median') ?? recordNumber(metric, 'p50') ?? medianOf(samples);
  const maximum = recordNumber(metric, 'maximum') ?? recordNumber(metric, 'max') ?? (samples.length > 0 ? Math.max(...samples) : undefined);
  if (minimum === undefined && median === undefined && maximum === undefined && samples.length === 0) return undefined;
  const sourceFields: Record<string, string> = {};
  for (const [key, value] of Object.entries(metric)) sourceFields[key] = String(value);
  return {
    name,
    unit: recordString(metric, 'unit') ?? inferUnit(name),
    direction: inferDirection(name),
    samples,
    ...(minimum !== undefined ? { minimum } : {}),
    ...(median !== undefined ? { median } : {}),
    ...(maximum !== undefined ? { maximum } : {}),
    confidence: samples.length > 0 ? 'EXACT' : 'PARTIAL',
    sourceFields,
  };
}

function resolveArtifact(baseDirectory: string | undefined, raw: string): string {
  if (isAbsolute(raw) || baseDirectory === undefined) return normalize(raw);
  return resolve(baseDirectory, raw);
}

function parseCase(value: Record<string, unknown>, baseDirectory: string | undefined): BenchmarkCase {
  const params = recordObject(value, 'params') ?? {};
  const rawName = recordString(value, 'name') ?? recordString(value, 'testName') ?? recordString(value, 'benchmarkName') ?? 'unknown';
  const lastDot = rawName.lastIndexOf('.');
  const className = recordString(value, 'className') ?? (lastDot === -1 ? 'UnknownBenchmark' : rawName.slice(0, lastDot));
  const testName = recordString(value, 'testName') ?? (lastDot === -1 ? rawName : rawName.slice(lastDot + 1));
  const metricsObject = recordObject(value, 'metrics') ?? recordObject(value, 'measurements') ?? {};
  const metrics: BenchmarkMetric[] = [];
  for (const [name, metric] of Object.entries(metricsObject)) {
    const parsed = parseMetric(name, metric);
    if (parsed !== undefined) metrics.push(parsed);
  }
  if (metrics.length === 0) throw new Error('No metrics found for ' + rawName);

  const traces = new Set<string>();
  for (const path of asArray(value['tracePaths']) ?? []) {
    const text = stringOf(path);
    if (text !== undefined) traces.add(resolveArtifact(baseDirectory, text));
  }
  const singleTrace = recordString(value, 'tracePath');
  if (singleTrace !== undefined) traces.add(resolveArtifact(baseDirectory, singleTrace));
  for (const output of asArray(value['profilerOutputs']) ?? []) {
    const record = asRecord(output);
    const path = record === undefined ? stringOf(output) : recordString(record, 'filePath');
    if (path === undefined) continue;
    if (path.endsWith('.trace') || path.endsWith('.perfetto-trace') || path.endsWith('.pftrace')) {
      traces.add(resolveArtifact(baseDirectory, path));
    }
  }

  const packageName = recordString(value, 'packageName') ?? recordString(value, 'targetPackage');
  const compilationMode = recordString(value, 'compilationMode') ?? recordString(params, 'compilationMode');
  const startupMode = recordString(value, 'startupMode') ?? recordString(params, 'startupMode');
  const iterationCount = recordNumber(value, 'repeatIterations') ?? recordNumber(value, 'iterationCount');
  return {
    className,
    testName,
    ...(packageName !== undefined ? { packageName } : {}),
    ...(compilationMode !== undefined ? { compilationMode } : {}),
    ...(startupMode !== undefined ? { startupMode } : {}),
    ...(iterationCount !== undefined ? { iterationCount } : {}),
    metrics,
    traceArtifacts: [...traces],
  };
}

function parseDevice(context: Record<string, unknown>, root: Record<string, unknown>): BenchmarkDevice {
  const model = recordString(context, 'deviceModel') ?? recordString(context, 'model') ?? recordString(root, 'deviceModel');
  const brand = recordString(context, 'deviceBrand') ?? recordString(context, 'brand');
  const apiLevel = recordNumber(context, 'apiLevel') ?? recordNumber(context, 'sdkVersion') ?? recordNumber(context, 'sdkInt');
  const osVersion = recordString(context, 'osVersion') ?? recordString(context, 'buildVersion');
  const abi = recordString(context, 'abi') ?? stringOf(asArray(context['supportedAbis'])?.[0]);
  const fingerprint = recordString(context, 'fingerprint') ?? recordString(context, 'buildFingerprint');
  const cpuCoreCount = recordNumber(context, 'cpuCoreCount');
  const physicalDevice = booleanOf(context['physicalDevice']) ?? booleanOf(context['isPhysicalDevice']);
  return {
    ...(model !== undefined ? { model } : {}),
    ...(brand !== undefined ? { brand } : {}),
    ...(apiLevel !== undefined ? { apiLevel } : {}),
    ...(osVersion !== undefined ? { osVersion } : {}),
    ...(abi !== undefined ? { abi } : {}),
    ...(fingerprint !== undefined ? { fingerprint } : {}),
    ...(cpuCoreCount !== undefined ? { cpuCoreCount } : {}),
    ...(physicalDevice !== undefined ? { physicalDevice } : {}),
  };
}

function parseBuild(context: Record<string, unknown>, root: Record<string, unknown>): BenchmarkBuild {
  const build = recordObject(root, 'build') ?? recordObject(context, 'build') ?? {};
  const targetPackage = recordString(build, 'targetPackage') ?? recordString(root, 'targetPackage');
  const versionName = recordString(build, 'versionName') ?? recordString(root, 'versionName');
  const versionCode = recordNumber(build, 'versionCode') ?? recordNumber(root, 'versionCode');
  const variant = recordString(build, 'variant') ?? recordString(root, 'variant');
  const gitCommit = recordString(build, 'gitCommit') ?? recordString(root, 'gitCommit');
  const gitBranch = recordString(build, 'gitBranch') ?? recordString(root, 'gitBranch');
  return {
    ...(targetPackage !== undefined ? { targetPackage } : {}),
    ...(versionName !== undefined ? { versionName } : {}),
    ...(versionCode !== undefined ? { versionCode } : {}),
    ...(variant !== undefined ? { variant } : {}),
    ...(gitCommit !== undefined ? { gitCommit } : {}),
    ...(gitBranch !== undefined ? { gitBranch } : {}),
  };
}

const KNOWN_TOP_LEVEL = new Set([
  'context',
  'device',
  'build',
  'benchmarks',
  'results',
  'tests',
  'version',
  'benchmarkDataVersion',
  'benchmarkLibraryVersion',
  'targetPackage',
  'versionName',
  'versionCode',
  'variant',
  'gitCommit',
  'gitBranch',
]);

export interface BenchmarkParseResult {
  readonly run: BenchmarkRun;
  readonly warnings: readonly string[];
}

/** Port of BenchmarkJsonParser for AndroidX Microbenchmark JSON output. */
export function parseBenchmarkJson(
  text: string,
  sourceFile: string,
  id: string,
  importedAtEpochMillis: number,
): BenchmarkParseResult {
  const root = asRecord(JSON.parse(text));
  if (root === undefined) throw new Error('Benchmark JSON root must be an object');
  const context = recordObject(root, 'context') ?? recordObject(root, 'device') ?? {};
  const warnings: string[] = [];
  const elements = asArray(root['benchmarks']) ?? asArray(root['results']) ?? asArray(root['tests']);
  if (elements === undefined) throw new Error('No benchmarks/results/tests array found');
  const baseDirectory = sourceFile.includes('/') ? sourceFile.slice(0, sourceFile.lastIndexOf('/')) : undefined;

  const cases: BenchmarkCase[] = [];
  elements.forEach((element, index) => {
    const record = asRecord(element);
    if (record === undefined) {
      warnings.push('Case #' + index + ' skipped: entry is not an object');
      return;
    }
    try {
      cases.push(parseCase(record, baseDirectory));
    } catch (error) {
      warnings.push('Case #' + index + ' skipped: ' + (error instanceof Error ? error.message : String(error)));
    }
  });
  if (cases.length === 0) throw new Error('No valid benchmark cases found');

  const unknown = Object.keys(root).filter((key) => !KNOWN_TOP_LEVEL.has(key));
  if (unknown.length > 0) {
    warnings.push('Preserved unknown top-level fields: ' + unknown.sort().join(', '));
  }

  const benchmarkDataVersion =
    recordNumber(root, 'version') ??
    recordNumber(root, 'benchmarkDataVersion') ??
    recordNumber(context, 'benchmarkDataVersion');
  const benchmarkLibraryVersion =
    recordString(root, 'benchmarkLibraryVersion') ?? recordString(context, 'benchmarkLibraryVersion');

  return {
    run: {
      id,
      sourceFile,
      ...(benchmarkDataVersion !== undefined ? { benchmarkDataVersion } : {}),
      ...(benchmarkLibraryVersion !== undefined ? { benchmarkLibraryVersion } : {}),
      device: parseDevice(context, root),
      build: parseBuild(context, root),
      importedAtEpochMillis,
      cases,
      warnings,
    },
    warnings,
  };
}
