/**
 * Node-only contract helpers. Kept out of the main entry so renderer bundles
 * never pull in node builtins.
 */

import { createHash, randomBytes as secureRandomBytes } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, open, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

const SHA_256 = /^[0-9a-f]{64}$/;
const HASH_BUFFER_SIZE = 1024 * 1024;

export const MINIMUM_DEVICE_IDENTITY_SALT_BYTES = 16;
const DEVICE_IDENTITY_SALT_BYTES = 32;

export async function sha256Bytes(bytes: Uint8Array): Promise<string> {
  return createHash('sha256').update(bytes).digest('hex');
}

/** Port of ArtifactFileEvidence.sha256: hashes the artifact's actual bytes. */
export async function sha256File(path: string): Promise<string> {
  const handle = await open(path, 'r');
  try {
    const stats = await handle.stat();
    if (!stats.isFile()) {
      throw new Error('artifact content is not a regular file: ' + path);
    }
    const hash = createHash('sha256');
    const stream = createReadStream(path, { highWaterMark: HASH_BUFFER_SIZE });
    for await (const chunk of stream) {
      hash.update(chunk as Buffer);
    }
    return hash.digest('hex');
  } finally {
    await handle.close();
  }
}

export function defaultDeviceIdentitySaltFile(): string {
  return join(homedir(), '.android-performance-studio', 'device-identity.salt');
}

/** Port of DeviceIdentitySaltStore.loadOrCreate. */
export async function loadOrCreateDeviceIdentitySalt(
  saltFile: string = defaultDeviceIdentitySaltFile(),
  randomBytes: () => Uint8Array = () => new Uint8Array(secureRandomBytes(DEVICE_IDENTITY_SALT_BYTES)),
): Promise<Uint8Array> {
  try {
    const existing = new Uint8Array(await readFile(saltFile));
    if (existing.length < MINIMUM_DEVICE_IDENTITY_SALT_BYTES) {
      throw new Error('stored device identity salt is invalid');
    }
    return existing;
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (code !== 'ENOENT') throw error;
  }

  await mkdir(dirname(saltFile), { recursive: true });
  const salt = randomBytes();
  if (salt.length < MINIMUM_DEVICE_IDENTITY_SALT_BYTES) {
    throw new Error('device identity salt must contain at least ' + MINIMUM_DEVICE_IDENTITY_SALT_BYTES + ' bytes');
  }
  const temporary = saltFile + '.' + process.pid + '.' + Date.now() + '.tmp';
  try {
    await writeFile(temporary, salt);
    try {
      await rename(temporary, saltFile);
    } catch (renameError) {
      const renameCode = (renameError as NodeJS.ErrnoException).code;
      if (renameCode === 'EEXIST') {
        return new Uint8Array(await readFile(saltFile));
      }
      throw renameError;
    }
  } finally {
    await rm(temporary, { force: true });
  }
  return salt;
}

/** Port of DeviceLocalId.fromRawSerial: SHA-256(applicationSalt || utf8(rawSerial)). */
export function deviceLocalIdFromRawSerial(rawSerial: string, applicationSalt: Uint8Array): string {
  if (rawSerial.trim().length === 0) {
    throw new Error('device raw serial must not be blank');
  }
  if (applicationSalt.length < MINIMUM_DEVICE_IDENTITY_SALT_BYTES) {
    throw new Error('device id application salt must contain at least ' + MINIMUM_DEVICE_IDENTITY_SALT_BYTES + ' bytes');
  }
  const hash = createHash('sha256');
  hash.update(applicationSalt);
  hash.update(Buffer.from(rawSerial, 'utf8'));
  return hash.digest('hex');
}

/** Port of DeviceIdentityPseudonymizer.localId. */
export function deviceLocalId(rawSerialOrLocalId: string, applicationSalt: Uint8Array): string {
  return SHA_256.test(rawSerialOrLocalId)
    ? rawSerialOrLocalId
    : deviceLocalIdFromRawSerial(rawSerialOrLocalId, applicationSalt);
}
