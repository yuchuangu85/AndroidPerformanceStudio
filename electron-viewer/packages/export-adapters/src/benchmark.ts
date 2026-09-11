/**
 * Port of the benchmark report exporter (BenchmarkReportExporter.kt): JSON, CSV,
 * Markdown, JUnit XML, and SARIF, which is what a CI job consumes.
 */
import type { MetricComparison, RegressionReport } from '@aps/benchmark-regression';
import { textFile } from './csv.js';

const MISSING_MARKDOWN = '—';

export function benchmarkReportJson(report: RegressionReport): string {
  return JSON.stringify({
    schemaVersion: 1,
    baselineRunId: report.baselineRunId,
    currentRunId: report.currentRunId,
    createdAt: new Date(report.createdAtEpochMillis).toISOString(),
    regressionCount: report.regressionCount,
    comparisons: report.comparisons.map((comparison) => ({
      case: comparison.caseIdentity,
      metric: comparison.metricName,
      unit: comparison.unit,
      ...(comparison.baselineValue !== undefined ? { baseline: comparison.baselineValue } : {}),
      ...(comparison.currentValue !== undefined ? { current: comparison.currentValue } : {}),
      ...(comparison.absoluteDelta !== undefined ? { absoluteDelta: comparison.absoluteDelta } : {}),
      ...(comparison.relativeDeltaPercent !== undefined
        ? { relativeDeltaPercent: comparison.relativeDeltaPercent }
        : {}),
      classification: comparison.classification,
      confidence: comparison.confidence,
    })),
  });
}

export const BENCHMARK_CSV_HEADER =
  'case,metric,unit,baseline,current,absolute_delta,relative_delta_percent,classification,confidence';

export function benchmarkReportCsv(report: RegressionReport): string {
  const lines = [BENCHMARK_CSV_HEADER];
  for (const comparison of report.comparisons) {
    lines.push(
      [
        comparison.caseIdentity,
        comparison.metricName,
        comparison.unit,
        comparison.baselineValue,
        comparison.currentValue,
        comparison.absoluteDelta,
        comparison.relativeDeltaPercent,
        comparison.classification,
        comparison.confidence,
      ]
        .map((value) => quoted(value === undefined || value === null ? '' : String(value)))
        .join(','),
    );
  }
  return textFile(lines);
}

export function benchmarkReportMarkdown(report: RegressionReport): string {
  const lines: string[] = [
    '# Benchmark Regression Report',
    '',
    'Regressions: **' + String(report.regressionCount) + '**',
    '',
    '| Case | Metric | Baseline | Current | Delta | Result |',
    '| --- | --- | ---: | ---: | ---: | --- |',
  ];
  for (const comparison of report.comparisons) {
    lines.push(
      '| ' +
        [
          comparison.caseIdentity,
          comparison.metricName + ' (' + comparison.unit + ')',
          comparison.baselineValue === undefined ? MISSING_MARKDOWN : String(comparison.baselineValue),
          comparison.currentValue === undefined ? MISSING_MARKDOWN : String(comparison.currentValue),
          comparison.relativeDeltaPercent === undefined
            ? MISSING_MARKDOWN
            : comparison.relativeDeltaPercent.toFixed(2) + '%',
          comparison.classification,
        ].join(' | ') +
        ' |',
    );
  }
  return textFile(lines);
}

export function benchmarkReportJunit(report: RegressionReport): string {
  let text =
    '<testsuite name="AndroidPerformanceStudio Benchmark" tests="' +
    String(report.comparisons.length) +
    '" failures="' +
    String(report.regressionCount) +
    '">';
  for (const comparison of report.comparisons) {
    text +=
      '<testcase classname="' +
      xml(comparison.caseIdentity) +
      '" name="' +
      xml(comparison.metricName) +
      '">';
    if (comparison.classification === 'REGRESSED') {
      text += '<failure message="Regression">' + xml(JSON.stringify(comparison)) + '</failure>';
    }
    text += '</testcase>';
  }
  return text + '</testsuite>';
}

export function benchmarkReportSarif(report: RegressionReport): string {
  return JSON.stringify({
    version: '2.1.0',
    runs: [
      {
        tool: { driver: { name: 'AndroidPerformanceStudio Benchmark Regression' } },
        results: report.comparisons
          .filter((comparison) => comparison.classification === 'REGRESSED')
          .map((comparison) => ({
            ruleId: 'benchmark-regression',
            level: 'error',
            message: {
              text:
                comparison.caseIdentity +
                ' ' +
                comparison.metricName +
                ' regressed by ' +
                String(comparison.relativeDeltaPercent) +
                '%',
            },
          })),
      },
    ],
  });
}

/** JUnit attribute and text escaping; comparison bodies are JSON text. */
export function xml(value: string): string {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;');
}

function quoted(value: string): string {
  return '"' + value.replaceAll('"', '""') + '"';
}

export type BenchmarkComparison = MetricComparison;
