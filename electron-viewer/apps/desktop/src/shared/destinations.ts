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
] as const;

export type AppDestination = (typeof DESTINATIONS)[number];

/** Home intentionally surfaces nine of the destinations; GPU, benchmark, and
 * method recording have no card, matching AppHomePage. */
export const HOME_DESTINATIONS: readonly AppDestination[] = [
  'LAYOUT_INSPECTOR',
  'SIMPLEPERF',
  'PERFETTO',
  'MEMORY_PROFILER',
  'FRAME_PROFILER',
  'STARTUP_PROFILER',
  'BATTERY_PROFILER',
  'NETWORK_PROFILER',
  'SOURCE_WORKSPACES',
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
} as const satisfies Record<AppDestination, string>;

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
