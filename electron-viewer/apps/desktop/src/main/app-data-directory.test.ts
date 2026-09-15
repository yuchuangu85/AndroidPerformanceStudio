import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { kotlinApplicationDataDirectory, migrateLegacyElectronData } from './app-data-directory.js';

describe('Kotlin-compatible application data directory', () => {
  it('uses the same hidden application root as Kotlin', () => {
    expect(kotlinApplicationDataDirectory('/Users/example')).toBe('/Users/example/.android-performance-studio');
  });

  it('migrates legacy Electron records without overwriting existing shared records', async () => {
    const root = await mkdtemp(join(tmpdir(), 'aps-data-root-'));
    try {
      const legacy = join(root, 'legacy');
      const target = join(root, 'target');
      await mkdir(legacy, { recursive: true });
      await writeFile(join(legacy, 'settings.json'), 'legacy');
      await mkdir(target, { recursive: true });
      await writeFile(join(target, 'settings.json'), 'kotlin');
      await writeFile(join(legacy, 'layout.json'), 'capture');

      await migrateLegacyElectronData(legacy, target);

      expect(await readFile(join(target, 'settings.json'), 'utf8')).toBe('kotlin');
      expect(await readFile(join(target, 'layout.json'), 'utf8')).toBe('capture');
    } finally { await rm(root, { recursive: true, force: true }); }
  });
});
