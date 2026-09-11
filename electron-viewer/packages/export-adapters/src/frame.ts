/**
 * Port of the frame export adapters (FrameCsvExporter.kt, FrameJsonExporter.kt).
 *
 * The column set, key order, and indentation are the Kotlin ones, so a file
 * written by either build reads the same. The TypeScript frame model does not
 * carry processId, refreshRateHz, frameTimelineVsyncId, the platform jank rule
 * id and version, or the per frame droppedBeforeSample flag: those are written
 * as null, or as false for the flag the Kotlin model always has, rather than
 * dropped from the schema. The deadline miss cluster has no activityName yet.
 */
import { resolvedDurationNs, type FrameSample } from '@aps/frame-profiler';
import type { AnalyzedFrame, FrameAnalysisResult } from '@aps/frame-profiler';
import { csvRow, textFile } from './csv.js';
import { jsonString, jsonValue, jsonObjectOf } from './json.js';

const FRAME_CSV_HEADER =
  'frame_id,source,intended_vsync_ns,duration_ns,expected_duration_ns,expected_source,' +
  'deadline_verdict,platform_jank_signal,platform_jank_rule_id,platform_jank_rule_version,' +
  'severity,missed_vsync_count,largest_reported_stage,' +
  'platform_jank_types,frame_timeline_vsync_id';

export function frameCsv(result: FrameAnalysisResult): string {
  const lines = [FRAME_CSV_HEADER];
  for (const frame of result.frames) {
    const sample = frame.sample;
    lines.push(
      csvRow([
        sample.frameId,
        sample.source,
        sample.intendedVsyncNs,
        resolvedDurationNs(sample),
        sample.expectedDurationNs,
        sample.expectedDurationSource,
        frame.deadlineVerdict,
        sample.platformJank,
        null,
        null,
        frame.severity,
        frame.missedVsyncCount,
        frame.largestReportedStage,
        frame.platformJankTypes.join('|'),
        null,
      ]),
    );
  }
  return textFile(lines);
}

export function frameJson(result: FrameAnalysisResult): string {
  const lines: string[] = [
    '{',
    '  "schemaVersion": 3,',
    '  "summary": {',
    '    "totalFrames": ' + String(result.summary.totalFrames) + ',',
    '    "deadlineClassifiedFrames": ' + String(result.summary.deadlineClassifiedFrames) + ',',
    '    "deadlineMissFrames": ' + String(result.summary.deadlineMissFrames) + ',',
    '    "deadlineUnknownFrames": ' + String(result.summary.deadlineUnknownFrames) + ',',
    '    "deadlineMissRate": ' + jsonValue(result.summary.deadlineMissRate) + ',',
    '    "platformClassifiedFrames": ' + String(result.summary.platformClassifiedFrames) + ',',
    '    "platformJankFrames": ' + String(result.summary.platformJankFrames) + ',',
    '    "platformUnknownFrames": ' + String(result.summary.platformUnknownFrames) + ',',
    '    "platformJankRate": ' + jsonValue(result.summary.platformJankRate) + ',',
    '    "p50DurationNs": ' + jsonValue(result.summary.p50DurationNs) + ',',
    '    "p95DurationNs": ' + jsonValue(result.summary.p95DurationNs) + ',',
    '    "p99DurationNs": ' + jsonValue(result.summary.p99DurationNs) + ',',
    '    "worstDurationNs": ' + jsonValue(result.summary.worstDurationNs),
    '  },',
    '  "frames": [',
  ];
  result.frames.forEach((frame, index) => {
    lines.push(...frameJsonLines(frame), index === result.frames.length - 1 ? '    }' : '    },');
  });
  lines.push('  ],', '  "clusters": [');
  result.clusters.forEach((cluster, index) => {
    lines.push('    {');
    lines.push('      "id": ' + String(cluster.id) + ',');
    lines.push('      "firstFrameId": ' + String(cluster.firstFrameId) + ',');
    lines.push('      "lastFrameId": ' + String(cluster.lastFrameId) + ',');
    lines.push('      "deadlineMissFrameIds": [' + cluster.deadlineMissFrameIds.map(String).join(', ') + '],');
    lines.push('      "durationNs": ' + String(cluster.durationNs) + ',');
    lines.push('      "worstSeverity": ' + jsonString(cluster.worstSeverity) + ',');
    lines.push('      "windowId": ' + jsonValue(cluster.windowId) + ',');
    lines.push('      "activityName": ' + jsonValue(null) + ',');
    lines.push('      "dominantReportedStage": ' + jsonValue(cluster.dominantReportedStage));
    lines.push(index === result.clusters.length - 1 ? '    }' : '    },');
  });
  lines.push('  ]', '}');
  return textFile(lines);
}

function frameJsonLines(frame: AnalyzedFrame): string[] {
  const sample: FrameSample = frame.sample;
  return [
    '    {',
    '      "frameId": ' + String(sample.frameId) + ',',
    '      "source": ' + jsonString(sample.source) + ',',
    '      "packageName": ' + jsonValue(sample.packageName) + ',',
    '      "processId": ' + jsonValue(null) + ',',
    '      "activityName": ' + jsonValue(sample.activityName) + ',',
    '      "windowId": ' + jsonValue(sample.windowId) + ',',
    '      "intendedVsyncNs": ' + jsonValue(sample.intendedVsyncNs) + ',',
    '      "actualVsyncNs": ' + jsonValue(sample.actualVsyncNs) + ',',
    '      "frameCompletedNs": ' + jsonValue(sample.frameCompletedNs) + ',',
    '      "presentNs": ' + jsonValue(sample.presentNs) + ',',
    '      "durationNs": ' + jsonValue(resolvedDurationNs(sample)) + ',',
    '      "totalDurationNs": ' + jsonValue(sample.totalDurationNs) + ',',
    '      "expectedDurationNs": ' + jsonValue(sample.expectedDurationNs) + ',',
    '      "expectedDurationSource": ' + jsonString(sample.expectedDurationSource) + ',',
    '      "refreshRateHz": ' + jsonValue(null) + ',',
    '      "frameTimelineVsyncId": ' + jsonValue(null) + ',',
    '      "deadlineVerdict": ' + jsonString(frame.deadlineVerdict) + ',',
    '      "severity": ' + jsonString(frame.severity) + ',',
    '      "missedVsyncCount": ' + jsonValue(frame.missedVsyncCount) + ',',
    '      "largestReportedStage": ' + jsonValue(frame.largestReportedStage) + ',',
    '      "platformJankTypes": ' + '[' + frame.platformJankTypes.map(jsonString).join(', ') + ']' + ',',
    '      "platformJank": ' + jsonValue(sample.platformJank) + ',',
    '      "platformJankRuleId": ' + jsonValue(null) + ',',
    '      "platformJankRuleVersion": ' + jsonValue(null) + ',',
    '      "eligibleForJank": ' + String(sample.eligibleForJank) + ',',
    '      "droppedBeforeSample": false,',
    '      "states": ' + jsonObjectOf(sample.states) + ',',
    '      "stagesNs": ' + jsonObjectOf(stageMap(sample)),
  ];
}

/** Only the stages the sample carries, in the model order. */
function stageMap(sample: FrameSample): Record<string, number> {
  return { ...sample.stages };
}
