import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { exportCpuSessionPackage } from './cpu-session-package-export.js';
import { SessionPackageCodec } from './session-package-codec.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-cpu-package-export-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

describe('exportCpuSessionPackage', () => {
  it('rejects report-only sessions without invoking the generic package codec', async () => {
    let exported = false;
    const result = await exportCpuSessionPackage(
      { id: 'report-only', destinationArchive: '/exports/report-only.apsession.zip' },
      {
        sessionPackageDirectoryFor: async () => undefined,
        exportPackage: async () => {
          exported = true;
          return { archive: '/exports/unexpected.zip', fileCount: 0 };
        },
      },
    );

    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('CPU_SESSION_PACKAGE_EXPORT_UNAVAILABLE');
    expect(exported).toBe(false);
  });

  it('exports retained native or imported package evidence without rebuilding it', async () => {
    const calls: string[][] = [];
    const result = await exportCpuSessionPackage(
      { id: 'captured', destinationArchive: '/exports/captured.apsession.zip' },
      {
        sessionPackageDirectoryFor: async (id) => id === 'captured' ? '/profiles/captured' : undefined,
        exportPackage: async (source, target) => {
          calls.push([source, target]);
          return { archive: target, fileCount: 7 };
        },
      },
    );

    expect(result).toEqual({ ok: true, value: { archive: '/exports/captured.apsession.zip', fileCount: 7 } });
    expect(calls).toEqual([['/profiles/captured', '/exports/captured.apsession.zip']]);
  });

  it('writes a generic manifest package from the exact retained source directory', async () => {
    const directory = await temporaryDirectory();
    const source = join(directory, 'source-session');
    const destination = join(directory, 'exported.apsession.zip');
    await mkdir(join(source, 'future-evidence'), { recursive: true });
    await writeFile(join(source, 'perf.data'), Buffer.from([1, 2, 3]));
    await writeFile(join(source, 'future-evidence', 'diagnostic.bin'), Buffer.from([0, 255]));
    const codec = new SessionPackageCodec();

    const result = await exportCpuSessionPackage(
      { id: 'imported', destinationArchive: destination },
      {
        sessionPackageDirectoryFor: async () => source,
        exportPackage: (sessionDirectory, archive) => codec.export(sessionDirectory, archive),
      },
    );

    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.fileCount).toBe(2);
    const document = await codec.read(destination);
    expect(document.files.get('perf.data')).toEqual(Buffer.from([1, 2, 3]));
    expect(document.files.get('future-evidence/diagnostic.bin')).toEqual(Buffer.from([0, 255]));
  });

  it('reports package-codec failures as an export failure', async () => {
    const result = await exportCpuSessionPackage(
      { id: 'captured', destinationArchive: '/exports/captured.apsession.zip' },
      {
        sessionPackageDirectoryFor: async () => '/profiles/captured',
        exportPackage: async () => { throw new Error('archive already exists'); },
      },
    );

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.code).toBe('CPU_SESSION_PACKAGE_EXPORT_FAILED');
      expect(result.error.message).toContain('archive already exists');
    }
  });
});
