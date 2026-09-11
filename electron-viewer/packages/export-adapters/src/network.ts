/**
 * Port of the network export adapters (NetworkExporter.kt).
 *
 * The HAR export and the CSV are complete. The JSON report and the raw bundle
 * are not: they need the analysis summary (call counts, phase summaries,
 * connection reuse, cache hit rate) and the raw event stream, neither of which
 * the TypeScript network profiler produces yet. The HAR export is the one that
 * matters for interop, and it keeps the _aps block that marks the file partial.
 *
 * Two formatting notes: timestamps come from epoch milliseconds, so they carry
 * millisecond rather than nanosecond precision, and a whole millisecond prints
 * as 12 where Kotlin printed 12.0. Both are numerically identical.
 */
import { callDurationNs, phaseDurationNs, type HttpCall, type NetworkCaptureResult } from '@aps/network-profiler';
import type { HttpExchange, NetworkPhase } from '@aps/network-profiler';

export function networkPartialHar(result: NetworkCaptureResult): string {
  return JSON.stringify({
    log: {
      version: '1.2',
      creator: { name: 'AndroidPerformanceStudio', version: '1' },
      entries: result.calls.map((call) => harEntry(result, call)),
    },
  });
}

function harEntry(result: NetworkCaptureResult, call: HttpCall): Record<string, unknown> {
  const exchange = lastExchange(call);
  const durationMs = durationOf(call);
  return {
    startedDateTime: startedDateTime(result, call),
    time: durationMs ?? -1,
    request: {
      method: call.method,
      url: call.redactedUrl,
      httpVersion: exchange?.protocol ?? '',
      headers: headerList(exchange?.requestHeaders),
      queryString: [],
      headersSize: -1,
      bodySize: exchange?.requestBytes ?? -1,
    },
    response: {
      status: exchange?.statusCode ?? 0,
      statusText: '',
      httpVersion: exchange?.protocol ?? '',
      headers: headerList(exchange?.responseHeaders),
      content: {
        size: exchange?.decodedResponseBytes ?? exchange?.responseBytes ?? -1,
        mimeType: '',
      },
      redirectURL: '',
      headersSize: -1,
      bodySize: exchange?.responseBytes ?? -1,
    },
    cache: {},
    timings: harTimings(exchange?.phases ?? []),
    _aps: {
      partial: true,
      source: call.source,
      bodiesCaptured: false,
      redactionPolicyVersion: result.session.redactionPolicyVersion,
    },
  };
}

/** HAR timings in milliseconds; a phase that was not observed stays -1. */
export function harTimings(phases: readonly NetworkPhase[]): Record<string, number> {
  const value = (kind: NetworkPhase['kind']): number => {
    const phase = phases.find((candidate) => candidate.kind === kind);
    if (phase === undefined) return -1;
    const durationNs = phaseDurationNs(phase);
    return durationNs === undefined ? -1 : durationNs / 1_000_000;
  };
  return {
    blocked: value('DISPATCHER_QUEUE'),
    dns: value('DNS'),
    connect: value('CONNECT'),
    ssl: value('TLS'),
    send: value('REQUEST_BODY'),
    wait: value('SERVER_WAIT'),
    receive: value('RESPONSE_BODY'),
  };
}

export const NETWORK_CSV_HEADER =
  'call_id,instrumentation_id,method,url,outcome,status,protocol,connection_use,duration_ms,request_bytes,response_bytes';

export function networkCsv(result: NetworkCaptureResult): string {
  const lines = [NETWORK_CSV_HEADER];
  for (const call of result.calls) {
    const exchange = lastExchange(call);
    lines.push(
      [
        call.callId,
        call.instrumentationId,
        call.method,
        call.redactedUrl,
        call.outcome,
        exchange?.statusCode,
        exchange?.protocol,
        exchange?.connectionUse,
        durationOf(call),
        exchange?.requestBytes,
        exchange?.responseBytes,
      ].map((value) => quoted(value === undefined || value === null ? '' : String(value))).join(','),
    );
  }
  return lines.map((line) => line + '\n').join('');
}

function lastExchange(call: HttpCall): HttpExchange | undefined {
  return call.exchanges.length === 0 ? undefined : call.exchanges[call.exchanges.length - 1];
}

function durationOf(call: HttpCall): number | undefined {
  const durationNs = callDurationNs(call);
  return durationNs === undefined ? undefined : durationNs / 1_000_000;
}

function startedDateTime(result: NetworkCaptureResult, call: HttpCall): string {
  return new Date(result.session.startedAtEpochMillis + call.startedNs / 1_000_000).toISOString();
}

function headerList(headers: Readonly<Record<string, string>> | undefined): { name: string; value: string }[] {
  if (headers === undefined) return [];
  return Object.entries(headers).map(([name, value]) => ({ name, value }));
}

function quoted(value: string): string {
  return '"' + value.replaceAll('"', '""') + '"';
}
