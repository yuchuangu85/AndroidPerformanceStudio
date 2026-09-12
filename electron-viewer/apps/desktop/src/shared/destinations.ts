import type { ShellStringKey } from './i18n.js';

/** Single navigation source of truth, ported from AppDestination/AppNavigator. */
export const DESTINATIONS = [
  'HOME',
  'SOURCE_WORKSPACES',
  'LAYOUT_INSPECTOR',
  'SIMPLEPERF',
  'PERFETTO',
  'MEMORY_PROFILER',
  'FRAME_PROFILER',
  'STARTUP_PROFILER',
  'BATTERY_PROFILER',
  'NETWORK_PROFILER',
  'GPU_INSPECTOR',
  'BENCHMARK_REGRESSION',
  'METHOD_RECORDING',
  'AI_ANALYSIS',
] as const;

export type AppDestination = (typeof DESTINATIONS)[number];

/** The destinations the home grid draws: everything but the home page itself. */
export type FeatureDestination = Exclude<AppDestination, 'HOME'>;

/**
 * Every destination except home gets a card, because the home grid is the
 * shell's only navigation surface. The reference home page draws nine cards and
 * leaves GPU, benchmark, and method recording without any entry point at all;
 * the first nine keep its order, and the three it forgot (plus AI analysis)
 * follow so no feature is unreachable.
 */
export const HOME_DESTINATIONS: readonly FeatureDestination[] = [
  'LAYOUT_INSPECTOR',
  'SIMPLEPERF',
  'PERFETTO',
  'MEMORY_PROFILER',
  'FRAME_PROFILER',
  'STARTUP_PROFILER',
  'BATTERY_PROFILER',
  'NETWORK_PROFILER',
  'SOURCE_WORKSPACES',
  'GPU_INSPECTOR',
  'BENCHMARK_REGRESSION',
  'METHOD_RECORDING',
  'AI_ANALYSIS',
];

export const DESTINATION_TITLE_KEYS = {
  HOME: 'app.name',
  SOURCE_WORKSPACES: 'destination.sourceWorkspaces',
  LAYOUT_INSPECTOR: 'destination.layoutInspector',
  SIMPLEPERF: 'destination.cpuProfiler',
  PERFETTO: 'destination.traceAnalyzer',
  MEMORY_PROFILER: 'destination.memoryProfiler',
  FRAME_PROFILER: 'destination.frameProfiler',
  STARTUP_PROFILER: 'destination.startupProfiler',
  BATTERY_PROFILER: 'destination.batteryProfiler',
  NETWORK_PROFILER: 'destination.networkProfiler',
  GPU_INSPECTOR: 'destination.gpuInspector',
  BENCHMARK_REGRESSION: 'destination.benchmarkRegression',
  METHOD_RECORDING: 'destination.methodRecording',
  AI_ANALYSIS: 'destination.aiAnalysis',
} as const satisfies Record<AppDestination, string>;

/**
 * One description per card, printed under the card's icon and title. Complete by
 * construction: a new destination cannot be added without its own summary.
 */
export const DESTINATION_SUMMARY_KEYS = {
  SOURCE_WORKSPACES: 'destination.sourceWorkspaces.summary',
  LAYOUT_INSPECTOR: 'destination.layoutInspector.summary',
  SIMPLEPERF: 'destination.cpuProfiler.summary',
  PERFETTO: 'destination.traceAnalyzer.summary',
  MEMORY_PROFILER: 'destination.memoryProfiler.summary',
  FRAME_PROFILER: 'destination.frameProfiler.summary',
  STARTUP_PROFILER: 'destination.startupProfiler.summary',
  BATTERY_PROFILER: 'destination.batteryProfiler.summary',
  NETWORK_PROFILER: 'destination.networkProfiler.summary',
  GPU_INSPECTOR: 'destination.gpuInspector.summary',
  BENCHMARK_REGRESSION: 'destination.benchmarkRegression.summary',
  METHOD_RECORDING: 'destination.methodRecording.summary',
  AI_ANALYSIS: 'destination.aiAnalysis.summary',
} as const satisfies Record<FeatureDestination, ShellStringKey>;

export function shouldMaximizeWindow(destination: AppDestination): boolean {
  return destination !== 'HOME';
}

export interface NavigationState {
  readonly current: AppDestination;
  /** Visited destinations stay mounted so their state survives navigation. */
  readonly retained: readonly AppDestination[];
}

export const INITIAL_NAVIGATION_STATE: NavigationState = { current: 'HOME', retained: ['HOME'] };

export function activateDestination(state: NavigationState, destination: AppDestination): NavigationState {
  const retained = state.retained.includes(destination) ? state.retained : [...state.retained, destination];
  return { current: destination, retained };
}
