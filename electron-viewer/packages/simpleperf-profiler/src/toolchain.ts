/**
 * Port of SamplingParameters.kt: the sampling request and the exact simpleperf
 * record argument vector it produces. The vector is built as an array, so no
 * shell quoting is ever needed to run it; quoting exists only for the preview
 * shown to the user.
 */
export const CALL_GRAPH_MODES = ['DWARF', 'FRAME_POINTER', 'NONE'] as const;
export type CallGraphMode = (typeof CALL_GRAPH_MODES)[number];

export const EVENT_SCOPES = ['USER', 'KERNEL', 'BOTH'] as const;
export type EventScope = (typeof EVENT_SCOPES)[number];

export type SamplingRate =
  | { readonly kind: 'FREQUENCY'; readonly hertz: number }
  | { readonly kind: 'PERIOD'; readonly events: bigint };

export type SimpleperfTarget =
  | { readonly kind: 'APP'; readonly packageName: string }
  | { readonly kind: 'PROCESS'; readonly pid: number; readonly appPackage?: string }
  | { readonly kind: 'PROCESS_NAME'; readonly name: string }
  | { readonly kind: 'THREAD'; readonly tid: number; readonly appPackage?: string }
  | { readonly kind: 'SYSTEM_WIDE' };

export interface SamplingParameters {
  readonly target: SimpleperfTarget;
  readonly event: string;
  readonly rate: SamplingRate;
  /** Undefined records until the user stops it. */
  readonly durationSeconds?: number;
  readonly callGraph: CallGraphMode;
  readonly scope: EventScope;
  readonly outputPath: string;
}

export const DEFAULT_FREQUENCY_HERTZ = 1000;
export const DEFAULT_DURATION_SECONDS = 10;
export const DEFAULT_OUTPUT_PATH = '/data/local/tmp/aps/perf.data';

export const DEFAULT_SAMPLING_PARAMETERS: SamplingParameters = {
  target: { kind: 'SYSTEM_WIDE' },
  event: 'cpu-clock',
  rate: { kind: 'FREQUENCY', hertz: DEFAULT_FREQUENCY_HERTZ },
  durationSeconds: DEFAULT_DURATION_SECONDS,
  callGraph: 'DWARF',
  scope: 'BOTH',
  outputPath: DEFAULT_OUTPUT_PATH,
};

/** Command tokens must never contain whitespace; simpleperf is exec'd directly. */
export function requireCommandToken(value: string, name: string): string {
  if (value.trim().length === 0 || /\s/.test(value)) {
    throw new Error(name + ' must be a non-blank command token');
  }
  return value;
}

export function samplingParameters(overrides: Partial<SamplingParameters> = {}): SamplingParameters {
  const parameters: SamplingParameters = { ...DEFAULT_SAMPLING_PARAMETERS, ...overrides };
  requireCommandToken(parameters.event, 'event');
  requireCommandToken(parameters.outputPath, 'outputPath');
  if (parameters.durationSeconds !== undefined && !(parameters.durationSeconds > 0)) {
    throw new Error('duration must be positive');
  }
  if (parameters.rate.kind === 'FREQUENCY' && parameters.rate.hertz <= 0) {
    throw new Error('frequency must be positive');
  }
  if (parameters.rate.kind === 'PERIOD' && parameters.rate.events <= 0n) {
    throw new Error('period must be positive');
  }
  switch (parameters.target.kind) {
    case 'APP':
      requireCommandToken(parameters.target.packageName, 'packageName');
      break;
    case 'PROCESS_NAME':
      requireCommandToken(parameters.target.name, 'processName');
      break;
    case 'PROCESS':
      if (parameters.target.pid <= 0) throw new Error('pid must be positive');
      if (parameters.target.appPackage !== undefined) {
        requireCommandToken(parameters.target.appPackage, 'appPackage');
      }
      break;
    case 'THREAD':
      if (parameters.target.tid <= 0) throw new Error('tid must be positive');
      if (parameters.target.appPackage !== undefined) {
        requireCommandToken(parameters.target.appPackage, 'appPackage');
      }
      break;
    default:
      break;
  }
  return parameters;
}

/** The arguments after the executable: "simpleperf record ...". */
export function recordShellArguments(parameters: SamplingParameters): string[] {
  const modifier = parameters.scope === 'USER' ? ':u' : parameters.scope === 'KERNEL' ? ':k' : '';
  const arguments_: string[] = ['record', '-e', parameters.event + modifier];
  arguments_.push(
    ...(parameters.rate.kind === 'FREQUENCY'
      ? ['-f', String(parameters.rate.hertz)]
      : ['-c', parameters.rate.events.toString()]),
  );
  if (parameters.durationSeconds !== undefined) {
    arguments_.push('--duration', commandNumberOf(parameters.durationSeconds));
  }
  switch (parameters.callGraph) {
    case 'DWARF':
      arguments_.push('-g');
      break;
    case 'FRAME_POINTER':
      arguments_.push('--call-graph', 'fp');
      break;
    default:
      break;
  }
  arguments_.push(...targetArguments(parameters.target));
  arguments_.push('-o', parameters.outputPath);
  return arguments_;
}

/** True when the recording is scoped to one app, which needs --app on device. */
export function isAppScoped(target: SimpleperfTarget): boolean {
  switch (target.kind) {
    case 'APP':
      return true;
    case 'PROCESS':
    case 'THREAD':
      return target.appPackage !== undefined;
    default:
      return false;
  }
}

function targetArguments(target: SimpleperfTarget): string[] {
  switch (target.kind) {
    case 'APP':
      return ['--app', target.packageName];
    case 'PROCESS':
      return [...appArguments(target.appPackage), '-p', String(target.pid)];
    case 'PROCESS_NAME':
      return ['-p', target.name];
    case 'THREAD':
      return [...appArguments(target.appPackage), '-t', String(target.tid)];
    default:
      return ['-a'];
  }
}

function appArguments(appPackage: string | undefined): string[] {
  return appPackage === undefined ? [] : ['--app', appPackage];
}

function commandNumberOf(value: number): string {
  return Number.isInteger(value) ? String(value) : String(value);
}

const SAFE_SHELL_TOKEN = /^[A-Za-z0-9_@%+=:,./-]+$/;

export function shellQuote(token: string): string {
  return SAFE_SHELL_TOKEN.test(token) ? token : "'" + token.replaceAll("'", "'\\''") + "'";
}

/** Human readable preview only; the real run passes an argument vector. */
export function commandPreview(executable: string, arguments_: readonly string[]): string {
  return [executable, ...arguments_].map(shellQuote).join(' ');
}

export function recordCommandPreview(parameters: SamplingParameters, executable = 'adb'): string {
  return commandPreview(executable, ['-s', '<serial>', 'shell', 'simpleperf', ...recordShellArguments(parameters)]);
}

/**
 * Arguments for the host-side conversion. The protobuf report is what the
 * parser reads; perf.data itself is not portable across simpleperf versions.
 */
export function reportSampleArguments(input: {
  readonly perfData: string;
  readonly protobufTrace: string;
  readonly symbolDirectory?: string;
  readonly proguardMapping?: string;
}): string[] {
  const arguments_: string[] = [
    'report-sample',
    '--protobuf',
    '--show-callchain',
    '-i',
    input.perfData,
    '-o',
    input.protobufTrace,
  ];
  if (input.symbolDirectory !== undefined) arguments_.push('--symdir', input.symbolDirectory);
  if (input.proguardMapping !== undefined) {
    arguments_.push('--proguard-mapping-file', input.proguardMapping);
  }
  return arguments_;
}

export const HOST_SIMPLEPERF_EXECUTABLE_NAME = 'simpleperf';
export const WINDOWS_HOST_SIMPLEPERF_EXECUTABLE_NAME = 'simpleperf.exe';

/** Accepts both process.platform ("win32") and os.name ("Windows 11"). */
export function hostSimpleperfExecutableName(osName: string): string {
  const name = osName.toLowerCase();
  const isWindows = name === 'win32' || name.startsWith('windows');
  return isWindows ? WINDOWS_HOST_SIMPLEPERF_EXECUTABLE_NAME : HOST_SIMPLEPERF_EXECUTABLE_NAME;
}
