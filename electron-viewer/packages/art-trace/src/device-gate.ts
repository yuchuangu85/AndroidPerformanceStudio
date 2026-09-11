/**
 * Pre-flight gate for method tracing, ported from MethodTraceDeviceGate and the
 * package-flag parsing in AdbTargetCatalog.
 *
 * Method tracing needs API 21+, and the target app must be debuggable or
 * profileable by the shell — unless the device is rooted, in which case any
 * process can be traced. The parsing is pure so it can be tested without a
 * device; the desktop main process supplies the raw command output.
 */
export const MINIMUM_METHOD_TRACE_API = 21;

export interface MethodTraceDeviceSupport {
  readonly supported: boolean;
  /** Why tracing is unavailable; absent when supported. */
  readonly reason?: string;
  readonly sdkApiLevel?: number;
}

export interface PackageCapability {
  readonly debuggable: boolean;
  readonly profileableByShell: boolean;
}

export function parseSdkApiLevel(stdout: string): number | undefined {
  const parsed = Number.parseInt(stdout.trim(), 10);
  return Number.isFinite(parsed) ? parsed : undefined;
}

/**
 * Parses the "Package [name] (id):" blocks of dumpsys package. The lookahead
 * stops at the next block or at the end of input.
 *
 * Built through the RegExp constructor on purpose: the inline (?ms) form is
 * valid ECMAScript but the TypeScript scanner used here rejects it.
 */
export function parsePackageCapabilities(output: string): Map<string, PackageCapability> {
  const capabilities = new Map<string, PackageCapability>();
  const blockPattern = new RegExp(
    '^[ \\t]*Package \\[([^\\]]+)\\] \\([^\\n]*\\):(.*?)(?=^[ \\t]*Package \\[|(?![\\s\\S]))',
    'gms',
  );
  for (const match of output.matchAll(blockPattern)) {
    const packageName = match[1];
    const body = match[2] ?? '';
    if (packageName === undefined) continue;
    capabilities.set(packageName, {
      debuggable: DEBUGGABLE_FLAG.test(body),
      profileableByShell: PROFILEABLE_BY_SHELL_FLAG.test(body),
    });
  }
  return capabilities;
}

const DEBUGGABLE_FLAG = /\bDEBUGGABLE\b/;
const PROFILEABLE_BY_SHELL_FLAG = /\b(?:PRIVATE_FLAG_)?PROFILEABLE_BY_SHELL\b/;

export function evaluateMethodTraceSupport(input: {
  readonly packageName: string;
  readonly sdkApiLevel?: number;
  readonly isRoot: boolean;
  readonly capabilities?: ReadonlyMap<string, PackageCapability>;
}): MethodTraceDeviceSupport {
  const sdkApiLevel = input.sdkApiLevel;
  if (sdkApiLevel === undefined) {
    return { supported: false, reason: 'Unable to read the device API level.' };
  }
  if (sdkApiLevel < MINIMUM_METHOD_TRACE_API) {
    return {
      supported: false,
      sdkApiLevel,
      reason:
        'Method tracing requires Android API ' +
        String(MINIMUM_METHOD_TRACE_API) +
        '+; this device is API ' +
        String(sdkApiLevel) +
        '.',
    };
  }
  if (input.isRoot) return { supported: true, sdkApiLevel };
  const capability = input.capabilities?.get(input.packageName);
  if (capability !== undefined && (capability.debuggable || capability.profileableByShell)) {
    return { supported: true, sdkApiLevel };
  }
  return {
    supported: false,
    sdkApiLevel,
    reason:
      input.packageName +
      ' is not debuggable or profileable; use a debuggable build or a rooted device.',
  };
}
