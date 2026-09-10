import { createHash } from 'node:crypto';
import {
  NETWORK_REDACTION_POLICY_VERSION,
  type CallOutcome,
  type ConnectionUse,
  type EvidenceCompleteness,
  type HttpCall,
  type HttpExchange,
  type NetworkCaptureResult,
  type NetworkPhase,
  type NetworkPhaseKind,
  type NetworkSession,
  type TimingAvailability,
} from './model.js';
import { redactHeaders, redactUrl } from './redactor.js';

export const MAX_HAR_BYTES = 512 * 1024 * 1024;

const SUPPORTED_VERSIONS = new Set(['1.1', '1.2']);

interface HarTiming {
  readonly field: string;
  readonly kind: NetworkPhaseKind;
  readonly parent?: NetworkPhaseKind;
}

const HAR_TIMINGS: readonly HarTiming[] = [
  { field: 'blocked', kind: 'DISPATCHER_QUEUE' },
  { field: 'dns', kind: 'DNS' },
  { field: 'connect', kind: 'CONNECT' },
  { field: 'ssl', kind: 'TLS', parent: 'CONNECT' },
  { field: 'send', kind: 'REQUEST_BODY' },
  { field: 'wait', kind: 'SERVER_WAIT' },
  { field: 'receive', kind: 'RESPONSE_BODY' },
];

function millisecondsToNs(value: number): number {
  return Math.round(value * 1_000_000);
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function stringOf(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function numberOf(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function headerPairs(element: unknown): Array<[string, string]> {
  const pairs: Array<[string, string]> = [];
  for (const entry of asArray(element)) {
    const header = asRecord(entry);
    const name = stringOf(header?.['name']);
    if (name === undefined) continue;
    pairs.push([name, stringOf(header?.['value']) ?? '']);
  }
  return pairs;
}

function fingerprint(bytes: string): string {
  return createHash('sha256').update(bytes, 'utf8').digest('hex');
}

export interface HarParseResult {
  readonly result: NetworkCaptureResult;
  readonly warnings: readonly string[];
}

/**
 * Port of HarParser. Every HAR timing is INFERRED because HAR timings are
 * wall-clock values produced by different tools with different semantics.
 */
export function parseHar(text: string, sourceFingerprint = fingerprint(text)): HarParseResult {
  const root = asRecord(JSON.parse(text));
  const log = asRecord(root?.['log']);
  if (log === undefined) throw new Error('HAR log object is missing');
  const version = stringOf(log['version']);
  if (version === undefined) throw new Error('HAR log.version is missing');
  if (!SUPPORTED_VERSIONS.has(version)) throw new Error('Unsupported HAR version: ' + version);
  const entryElements = log['entries'];
  if (!Array.isArray(entryElements)) throw new Error('HAR entries array is missing');

  const starts = entryElements
    .map((entry) => stringOf(asRecord(entry)?.['startedDateTime']))
    .filter((value): value is string => value !== undefined)
    .map((value) => Date.parse(value))
    .filter((value) => Number.isFinite(value));
  const base = starts.length > 0 ? Math.min(...starts) : 0;

  const warnings: string[] = [];
  const calls: HttpCall[] = [];
  let invalidTimingCount = 0;
  entryElements.forEach((element, index) => {
    const entry = asRecord(element);
    if (entry === undefined) {
      warnings.push('Entry #' + index + ' skipped: entry is not an object');
      return;
    }
    const parsed = parseEntry(entry, index, base, warnings);
    if (parsed === undefined) return;
    invalidTimingCount += parsed.invalidTimingCount;
    calls.push(parsed.call);
  });
  if (calls.length === 0) throw new Error('HAR contains no valid entries');
  if (invalidTimingCount > 0) warnings.push('HAR contains ' + invalidTimingCount + ' invalid timing values.');

  const skipped = entryElements.length - calls.length;
  const completeness: EvidenceCompleteness =
    skipped === 0 && invalidTimingCount === 0 ? 'COMPLETE' : 'PARTIAL';
  const creator = asRecord(log['creator']);
  const producerName = stringOf(creator?.['name']) ?? 'unknown HAR producer';
  const producerVersion = stringOf(creator?.['version']);
  const endedNs = calls.reduce<number | undefined>((latest, call) => {
    if (call.endedNs === undefined) return latest;
    return latest === undefined || call.endedNs > latest ? call.endedNs : latest;
  }, undefined);

  const session: NetworkSession = {
    id: sourceFingerprint.slice(0, 32),
    startedAtEpochMillis: base,
    ...(endedNs !== undefined ? { endedAtEpochMillis: base + Math.round(endedNs / 1_000_000) } : {}),
    coverage: {
      processIds: [],
      observedLibraries: [producerName],
      observedInstrumentationIds: [],
      instrumentationMode: 'HAR_IMPORT',
      supportedEventKinds: ['HAR ' + version],
      knownLimitations: ['HAR does not identify unrecorded application traffic'],
      windowStartedNs: 0,
      ...(endedNs !== undefined ? { windowEndedNs: endedNs } : {}),
    },
    completeness: { status: completeness, droppedEvents: 0, sequenceGaps: 0, unpairedEvents: 0, skippedRecords: skipped },
    sourceTimeDomain: 'HAR_WALL_CLOCK',
    status: completeness === 'COMPLETE' ? 'COMPLETE' : 'PARTIAL',
    redactionPolicyVersion: NETWORK_REDACTION_POLICY_VERSION,
    sourceFormatVersion: version,
    sourceProducer: [producerName, producerVersion].filter((value): value is string => value !== undefined).join(' '),
    sourceFingerprint,
    warnings,
  };
  return { result: { session, calls }, warnings };
}

interface ParsedEntry {
  readonly call: HttpCall;
  readonly invalidTimingCount: number;
}

function parseEntry(
  entry: Record<string, unknown>,
  index: number,
  base: number,
  warnings: string[],
): ParsedEntry | undefined {
  const request = asRecord(entry['request']);
  if (request === undefined) {
    warnings.push('Entry #' + index + ' skipped: request is missing');
    return undefined;
  }
  const response = asRecord(entry['response']) ?? {};
  const startedText = stringOf(entry['startedDateTime']);
  const startedMillis = startedText === undefined ? base : Date.parse(startedText);
  // startedMillis and base are epoch milliseconds; millisecondsToNs expects ms.
  const startedNs = Number.isFinite(startedMillis) ? Math.max(0, millisecondsToNs(startedMillis - base)) : 0;
  const totalMs = numberOf(entry['time']);
  const validTotal = totalMs !== undefined && totalMs >= 0 ? totalMs : undefined;
  const timings = asRecord(entry['timings']) ?? {};
  const rawUrl = stringOf(request['url']) ?? 'redacted://unknown';
  const isPlaintext = rawUrl.toLowerCase().startsWith('http://');

  let invalidTimingCount = 0;
  const phases: NetworkPhase[] = HAR_TIMINGS.map((mapping) => {
    const raw = numberOf(timings[mapping.field]);
    let availability: TimingAvailability;
    if (raw === undefined) availability = 'UNAVAILABLE';
    else if (raw >= 0) availability = 'VALUE';
    else if (raw === -1 && mapping.kind === 'TLS' && isPlaintext) availability = 'NOT_APPLICABLE';
    else if (raw === -1) availability = 'UNAVAILABLE';
    else {
      availability = 'INVALID';
      invalidTimingCount += 1;
    }
    return {
      kind: mapping.kind,
      confidence: availability === 'VALUE' ? 'INFERRED' : 'UNKNOWN',
      ...(raw !== undefined && raw >= 0 ? { reportedDurationNs: millisecondsToNs(raw) } : {}),
      availability,
      ...(mapping.parent !== undefined ? { parentKind: mapping.parent } : {}),
    };
  });
  phases.push({
    kind: 'TOTAL',
    startNs: startedNs,
    ...(validTotal !== undefined ? { endNs: startedNs + millisecondsToNs(validTotal) } : {}),
    confidence: validTotal === undefined ? 'UNKNOWN' : 'INFERRED',
    ...(validTotal !== undefined ? { reportedDurationNs: millisecondsToNs(validTotal) } : {}),
    availability: validTotal === undefined ? 'UNAVAILABLE' : 'VALUE',
  });

  const statusValue = numberOf(response['status']);
  const status = statusValue !== undefined && statusValue > 0 ? Math.trunc(statusValue) : undefined;
  const failureText = stringOf(entry['_error']);
  const failure = failureText === undefined ? undefined : { type: 'HAR_ERROR', message: '<redacted>' };
  const endedNs = validTotal === undefined ? undefined : startedNs + millisecondsToNs(validTotal);

  const dns = numberOf(timings['dns']);
  const connect = numberOf(timings['connect']);
  const ssl = numberOf(timings['ssl']);
  const availableTimings = [dns, connect, ssl].filter((value) => value !== undefined && value >= 0);
  const connectionUse: ConnectionUse = availableTimings.length === 0 ? 'REUSED' : 'NEW';

  const knownTimingNames = new Set(HAR_TIMINGS.map((mapping) => mapping.field));
  const sourceAttributes: Record<string, string> = {};
  for (const [name, value] of Object.entries(timings)) {
    if (knownTimingNames.has(name)) continue;
    const numeric = numberOf(value);
    if (numeric === undefined) continue;
    sourceAttributes['har.timings.' + name] = String(numeric);
  }

  const content = asRecord(response['content']);
  const bodySize = numberOf(response['bodySize']);
  const decodedSize = numberOf(content?.['size']);
  const requestBodySize = numberOf(request['bodySize']);
  const redacted = redactUrl(rawUrl);

  const outcome: CallOutcome =
    failure !== undefined ? 'FAILED' : endedNs === undefined ? 'INCOMPLETE' : 'COMPLETED';

  const exchange: HttpExchange = {
    exchangeIndex: 0,
    ...(stringOf(entry['connection']) !== undefined ? { connectionId: stringOf(entry['connection']) as string } : {}),
    connectionUse,
    ...(stringOf(response['httpVersion']) !== undefined ? { protocol: stringOf(response['httpVersion']) as string } : {}),
    ...(status !== undefined ? { statusCode: status } : {}),
    ...(requestBodySize !== undefined && requestBodySize >= 0 ? { requestBytes: requestBodySize } : {}),
    ...(bodySize !== undefined && bodySize >= 0 ? { responseBytes: bodySize } : {}),
    ...(decodedSize !== undefined && decodedSize >= 0 ? { decodedResponseBytes: decodedSize } : {}),
    phases,
    cacheDisposition: 'UNKNOWN',
    ...(failure !== undefined ? { failure } : {}),
    requestHeaders: redactHeaders(headerPairs(request['headers'])),
    responseHeaders: redactHeaders(headerPairs(response['headers'])),
    sourceAttributes,
  };

  return {
    call: {
      callId: stringOf(entry['_requestId']) ?? 'har-' + String(index),
      method: stringOf(request['method']) ?? 'UNKNOWN',
      redactedUrl: redacted.value,
      startedNs,
      ...(endedNs !== undefined ? { endedNs } : {}),
      exchanges: [exchange],
      outcome,
      source: 'HAR_IMPORT',
    },
    invalidTimingCount,
  };
}
