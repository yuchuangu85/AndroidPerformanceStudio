import { AdbInputError } from './adb-errors.js';

// Kotlin: [A-Za-z0-9_.:\[\]-] and [A-Za-z0-9_./:\[\]-]. Inside a JS character
// class only the closing bracket needs escaping.
const SERIAL_PATTERN = /^[A-Za-z0-9_.:[\]-]+$/;
const ENDPOINT_PATTERN = /^[A-Za-z0-9_./:[\]-]+$/;
/** Matches Kotlin Char::isISOControl without tripping eslint no-control-regex. */
function isIsoControl(character: string): boolean {
  const code = character.codePointAt(0) ?? 0;
  return (code >= 0x00 && code <= 0x1f) || (code >= 0x7f && code <= 0x9f);
}

export function requireSerial(serial: string): string {
  if (!SERIAL_PATTERN.test(serial) || serial.startsWith('-')) {
    throw new AdbInputError('Invalid ADB device serial');
  }
  return serial;
}

export function requireRemotePath(path: string): string {
  if (path.trim().length === 0 || [...path].some(isIsoControl)) {
    throw new AdbInputError('Invalid remote path');
  }
  return path;
}

export function requireForwardEndpoint(endpoint: string): string {
  if (!ENDPOINT_PATTERN.test(endpoint)) {
    throw new AdbInputError('Invalid ADB forward endpoint');
  }
  return endpoint;
}
