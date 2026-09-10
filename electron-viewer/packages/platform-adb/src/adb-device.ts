import { AdbOutputParseError } from './adb-errors.js';

export type AdbDeviceState = 'ONLINE' | 'OFFLINE' | 'UNAUTHORIZED' | 'NO_PERMISSIONS' | 'UNKNOWN';

export interface AdbDevice {
  readonly serial: string;
  readonly state: AdbDeviceState;
  readonly product?: string;
  readonly model?: string;
  readonly device?: string;
  readonly transportId?: number;
  readonly attributes: Readonly<Record<string, string>>;
  readonly rawState: string;
  readonly statusDetail?: string;
}

const DEVICES_HEADER = 'List of devices attached';
const NO_PERMISSIONS_STATE = 'no permissions';
const WHITESPACE = /\s+/;

function isNoise(line: string): boolean {
  return (
    line === DEVICES_HEADER ||
    line.startsWith('* daemon') ||
    line.startsWith('adb server version') ||
    line.startsWith("ADB server didn't ACK")
  );
}

function parseAttribute(token: string): [string, string] | undefined {
  const delimiter = token.indexOf(':');
  if (delimiter <= 0 || delimiter === token.length - 1) return undefined;
  return [token.slice(0, delimiter), token.slice(delimiter + 1)];
}

function parseNoPermissions(serial: string, details: string): AdbDevice {
  const statusDetail = details
    .slice(NO_PERMISSIONS_STATE.length)
    .trim()
    .replace(/^\(/, '')
    .split('); see')[0]
    .replace(/\)$/, '')
    .trim();
  return {
    serial,
    state: 'NO_PERMISSIONS',
    rawState: NO_PERMISSIONS_STATE,
    attributes: {},
    ...(statusDetail.length > 0 ? { statusDetail } : {}),
  };
}

function parseRegularDevice(serial: string, details: string): AdbDevice {
  const tokens = details.split(WHITESPACE);
  const rawState = tokens[0] ?? '';
  const attributes: Record<string, string> = {};
  for (const token of tokens.slice(1)) {
    const attribute = parseAttribute(token);
    if (attribute !== undefined) attributes[attribute[0]] = attribute[1];
  }
  const state: AdbDeviceState =
    rawState === 'device'
      ? 'ONLINE'
      : rawState === 'offline'
        ? 'OFFLINE'
        : rawState === 'unauthorized'
          ? 'UNAUTHORIZED'
          : 'UNKNOWN';
  const transportId = attributes['transport_id'];
  return {
    serial,
    state,
    rawState,
    attributes,
    ...(attributes['product'] !== undefined ? { product: attributes['product'] } : {}),
    ...(attributes['model'] !== undefined ? { model: attributes['model'] } : {}),
    ...(attributes['device'] !== undefined ? { device: attributes['device'] } : {}),
    ...(transportId !== undefined && /^\d+$/.test(transportId) ? { transportId: Number(transportId) } : {}),
  };
}

function parseLine(index: number, line: string): AdbDevice {
  const firstWhitespace = line.search(/\s/);
  const serial = firstWhitespace === -1 ? line : line.slice(0, firstWhitespace);
  const details = line.slice(serial.length).trim();
  if (serial.length === 0 || details.length === 0) {
    throw new AdbOutputParseError('Malformed adb devices line ' + (index + 1) + ': ' + line);
  }
  return details.startsWith(NO_PERMISSIONS_STATE)
    ? parseNoPermissions(serial, details)
    : parseRegularDevice(serial, details);
}

export function parseAdbDevices(output: string): AdbDevice[] {
  return output
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .filter((line) => !isNoise(line))
    .map((line, index) => parseLine(index, line));
}
