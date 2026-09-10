import { SUPPORTED_PROTOCOL_MAJOR } from './model.js';
import { decodeWireSnapshot, type LayoutSnapshot } from './snapshot.js';

export class UnsupportedProtocolVersionError extends Error {
  readonly actualMajor: number;
  readonly supportedMajor: number;
  constructor(actualMajor: number, supportedMajor: number) {
    super('Unsupported protocol major ' + actualMajor + '; viewer supports ' + supportedMajor);
    this.name = 'UnsupportedProtocolVersionError';
    this.actualMajor = actualMajor;
    this.supportedMajor = supportedMajor;
  }
}

/** Decodes a protocol snapshot, rejecting an unsupported major version. */
export function decodeLayoutSnapshot(value: string): LayoutSnapshot {
  const parsed: unknown = JSON.parse(value);
  const version = (parsed as { protocolVersion?: { major?: unknown } }).protocolVersion;
  const major = typeof version?.major === 'number' ? version.major : Number.NaN;
  if (major !== SUPPORTED_PROTOCOL_MAJOR) {
    throw new UnsupportedProtocolVersionError(major, SUPPORTED_PROTOCOL_MAJOR);
  }
  return decodeWireSnapshot(parsed);
}

/**
 * Encodes a snapshot. Kotlin uses encodeDefaults = false while this encoder keeps
 * populated defaults, which stays decodable because readers ignore unknown keys.
 */
export function encodeLayoutSnapshot(snapshot: LayoutSnapshot): string {
  return JSON.stringify(snapshot, null, 2);
}
