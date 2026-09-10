import { requireSerial } from './adb-input-validator.js';

export const DEFAULT_ADB_TIMEOUT_MS = 30_000;
export const DEFAULT_ADB_MAX_OUTPUT_BYTES = 16 * 1024 * 1024;

export interface AdbCommand {
  readonly executable: string;
  readonly args: readonly string[];
  readonly timeoutMs: number;
  readonly maxOutputBytesPerStream: number;
}

export function buildAdbDeviceArgs(serial: string, operation: string, args: readonly string[]): string[] {
  return ['-s', requireSerial(serial), operation, ...args];
}

/** Port of ADB's remote shell quoting: wrap in single quotes, escape embedded quotes. */
export function quoteRemoteShellArgument(argument: string): string {
  return "'" + argument.replace(/'/g, "'\"'\"'") + "'";
}
