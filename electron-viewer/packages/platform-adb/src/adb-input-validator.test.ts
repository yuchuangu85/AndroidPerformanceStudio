import { describe, expect, it } from 'vitest';
import { AdbInputError } from './adb-errors.js';
import { requireForwardEndpoint, requireRemotePath, requireSerial } from './adb-input-validator.js';

describe('AdbInputValidator', () => {
  it('accepts valid serials and rejects option-like or malformed ones', () => {
    expect(requireSerial('emulator-5554')).toBe('emulator-5554');
    expect(requireSerial('192.168.0.1:5555')).toBe('192.168.0.1:5555');
    expect(() => requireSerial('-e')).toThrow(AdbInputError);
    expect(() => requireSerial('bad serial')).toThrow(AdbInputError);
  });

  it('accepts remote paths without control characters', () => {
    expect(requireRemotePath('/data/local/tmp/file.txt')).toBe('/data/local/tmp/file.txt');
    expect(() => requireRemotePath('   ')).toThrow(AdbInputError);
    expect(() => requireRemotePath('/tmp/a\u0000b')).toThrow(AdbInputError);
  });

  it('accepts forward endpoints and rejects unsafe characters', () => {
    expect(requireForwardEndpoint('tcp:0')).toBe('tcp:0');
    expect(requireForwardEndpoint('localabstract:agent')).toBe('localabstract:agent');
    expect(() => requireForwardEndpoint('tcp:0; rm -rf /')).toThrow(AdbInputError);
  });
});
