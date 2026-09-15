import { mkdtemp, mkdir, rm, truncate, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { MAX_KOTLIN_STARTUP_JSON_BYTES } from '@aps/startup-profiler';
import { readKotlinStartupJsonFile } from './startup-json-file.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-startup-json-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

describe('readKotlinStartupJsonFile', () => {
  it('reads a regular UTF-8 file through one bounded handle', async () => {
    const directory = await temporaryDirectory();
    const file = join(directory, 'startup.json');
    await writeFile(file, '{"schemaVersion":1}', 'utf8');

    await expect(readKotlinStartupJsonFile(file)).resolves.toBe('{"schemaVersion":1}');
  });

  it('rejects directories and oversized files before parsing them', async () => {
    const directory = await temporaryDirectory();
    const nested = join(directory, 'directory');
    const oversized = join(directory, 'oversized.json');
    await mkdir(nested);
    await writeFile(oversized, '');
    await truncate(oversized, MAX_KOTLIN_STARTUP_JSON_BYTES + 1);

    await expect(readKotlinStartupJsonFile(nested)).rejects.toThrow('not a regular file');
    await expect(readKotlinStartupJsonFile(oversized)).rejects.toThrow('16 MiB import limit');
  });
});
