import { describe, expect, it } from 'vitest';
import { parseGfxInfoFrameStats } from './gfxinfo.js';
import { MAX_SAFE_FRAME_VALUE } from './model.js';

const COLUMNS = [
  'Flags',
  'IntendedVsync',
  'Vsync',
  'OldestInputEvent',
  'NewestInputEvent',
  'HandleInputStart',
  'AnimationStart',
  'PerformTraversalsStart',
  'DrawStart',
  'FrameDeadline',
  'FrameInterval',
  'SyncQueued',
  'SyncStart',
  'IssueDrawCommandsStart',
  'SwapBuffers',
  'FrameCompleted',
  'GpuCompleted',
  'SwapBuffersCompleted',
  'DisplayPresentTime',
  'DequeueBufferDuration',
  'QueueBufferDuration',
  '',
];

function row(values: Record<string, number>): string {
  return COLUMNS.map((column) => String(values[column] ?? 0)).join(',');
}

const BASE = {
  IntendedVsync: 1_000_000_000,
  Vsync: 1_000_000_000,
  HandleInputStart: 1_001_000_000,
  AnimationStart: 1_002_000_000,
  PerformTraversalsStart: 1_004_000_000,
  DrawStart: 1_010_000_000,
  FrameDeadline: 1_016_666_666,
  FrameInterval: 16_666_666,
  SyncQueued: 1_012_000_000,
  SyncStart: 1_013_000_000,
  IssueDrawCommandsStart: 1_014_000_000,
  SwapBuffers: 1_015_000_000,
  FrameCompleted: 1_016_000_000,
  GpuCompleted: 1_016_500_000,
  SwapBuffersCompleted: 1_016_000_000,
  DisplayPresentTime: 1_016_200_000,
};

const OUTPUT = [
  'Applications Graphics Acceleration Info:',
  'Window: com.example.app/com.example.app.MainActivity',
  '---PROFILEDATA---',
  COLUMNS.join(','),
  row({ ...BASE, Flags: 0 }),
  row({ ...BASE, Flags: 0, IntendedVsync: 1_016_666_666, Vsync: 1_016_666_666, FrameDeadline: 1_033_333_332, FrameCompleted: 1_040_000_000, SwapBuffers: 1_038_000_000, GpuCompleted: 1_039_000_000, SwapBuffersCompleted: 1_040_000_000, DisplayPresentTime: 1_040_100_000 }),
  '---PROFILEDATA---',
].join('\n');

describe('parseGfxInfoFrameStats', () => {
  it('parses frames with stages, budget, and window id', () => {
    const result = parseGfxInfoFrameStats(OUTPUT, 'session-1', 'com.example.app');
    expect(result.warnings).toEqual([]);
    expect(result.frames).toHaveLength(2);
    const first = result.frames[0];
    expect(first?.windowId).toBe('com.example.app/com.example.app.MainActivity');
    expect(first?.eligibleForJank).toBe(true);
    expect(first?.expectedDurationSource).toBe('PLATFORM_DEADLINE');
    expect(first?.expectedDurationNs).toBe(16_666_666);
    expect(first?.totalDurationNs).toBe(16_000_000);
    expect(first?.stages.inputNs).toBe(1_000_000);
    expect(first?.stages.animationNs).toBe(2_000_000);
    expect(first?.stages.layoutMeasureNs).toBe(6_000_000);
    expect(first?.stages.drawNs).toBe(2_000_000);
    expect(first?.stages.syncNs).toBe(1_000_000);
    expect(first?.stages.commandIssueNs).toBe(1_000_000);
    expect(first?.stages.swapBuffersNs).toBe(1_000_000);
    expect(first?.stages.gpuNs).toBe(1_500_000);
    expect(first?.frameId).toBe(0);
    expect(result.frames[1]?.frameId).toBe(1);
  });

  it('marks frames with a non-zero Flags value as ineligible', () => {
    const output = OUTPUT.replace(row({ ...BASE, Flags: 0 }), row({ ...BASE, Flags: 1 }));
    const result = parseGfxInfoFrameStats(output, 'session-1');
    expect(result.frames[0]?.eligibleForJank).toBe(false);
    expect(result.frames[0]?.states['gfxinfo.flags']).toBe('1');
  });

  it('warns when no header is present', () => {
    const result = parseGfxInfoFrameStats('Window: x\nno data here', 'session-1');
    expect(result.frames).toEqual([]);
    expect(result.warnings).toContain('No gfxinfo framestats header was found.');
  });

  it('skips malformed rows and reports them', () => {
    const output = OUTPUT.replace(row({ ...BASE, Flags: 0 }), '1,2,3');
    const result = parseGfxInfoFrameStats(output, 'session-1');
    expect(result.frames).toHaveLength(1);
    expect(result.warnings.some((warning) => warning.includes('malformed frame row'))).toBe(true);
  });

  it('skips rows beyond the safe integer range', () => {
    const output = OUTPUT.replace(
      row({ ...BASE, Flags: 0 }),
      row({ ...BASE, Flags: 0, IntendedVsync: MAX_SAFE_FRAME_VALUE + 2 }),
    );
    const result = parseGfxInfoFrameStats(output, 'session-1');
    expect(result.frames).toHaveLength(1);
    expect(result.warnings.some((warning) => warning.includes('safe integer range'))).toBe(true);
  });

  it('infers a frame budget from the observed vsync interval', () => {
    const withoutDeadline = COLUMNS.filter((column) => column !== 'FrameDeadline' && column !== 'FrameInterval' && column !== '');
    const first = { ...BASE };
    const second = { ...BASE, IntendedVsync: 1_016_666_666, Vsync: 1_016_666_666 };
    const output = [
      '---PROFILEDATA---',
      withoutDeadline.join(','),
      withoutDeadline.map((column) => String((first as Record<string, number>)[column] ?? 0)).join(','),
      withoutDeadline.map((column) => String((second as Record<string, number>)[column] ?? 0)).join(','),
      '---PROFILEDATA---',
    ].join('\n');
    const result = parseGfxInfoFrameStats(output, 'session-1');
    expect(result.frames).toHaveLength(2);
    expect(result.frames[0]?.expectedDurationSource).toBe('INFERRED_VSYNC');
    expect(result.frames[0]?.expectedDurationNs).toBe(16_666_666);
  });
});
