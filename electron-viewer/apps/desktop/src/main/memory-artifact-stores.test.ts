import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import type { BitmapDumpSession } from '../shared/ipc.js';
import { BitmapDumpStore } from './memory-artifact-stores.js';

const directories: string[] = [];
const ONE_PIXEL_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAF/gL+3P9I7wAAAABJRU5ErkJggg==',
  'base64',
);

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-bitmap-store-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

function session(id: string, imageFile: string): BitmapDumpSession {
  return {
    id,
    packageName: 'com.example.app',
    pid: 42,
    deviceSerial: 'emulator-5554',
    sdkLevel: 35,
    capturedAtEpochMillis: 100,
    hprofFile: '/private/temporary/bitmap.hprof',
    imagesDirectory: '/private/temporary/images',
    images: [
      {
        recordIndex: 0,
        arrayObjectId: '123',
        file: imageFile,
        width: 1,
        height: 1,
        pngBytes: ONE_PIXEL_PNG.byteLength,
        estimatedMemoryBytes: 4,
        sha256: 'a'.repeat(64),
        duplicateCount: 1,
      },
    ],
    summary: {
      recordedBitmapCount: 1,
      discoveredBitmapCount: 1,
      exportedImageCount: 1,
      uniqueImageCount: 1,
      duplicateGroupCount: 0,
      totalPngBytes: ONE_PIXEL_PNG.byteLength,
      estimatedBitmapBytes: 4,
    },
  };
}

describe('BitmapDumpStore', () => {
  it('copies PNG assets before the capture temporary directory is removed and hides host paths', async () => {
    const root = await temporaryDirectory();
    const sourceDirectory = join(root, 'temporary');
    const source = join(sourceDirectory, 'bitmap-0.png');
    await mkdir(sourceDirectory, { recursive: true });
    await writeFile(source, ONE_PIXEL_PNG);
    const store = new BitmapDumpStore(join(root, 'bitmap-dumps'));

    await store.add(session('first', source));
    await rm(source, { force: true });

    const loaded = await store.load('first');
    expect(loaded).toMatchObject({ hprofFile: '(not retained)', imagesDirectory: 'images' });
    expect(loaded?.images[0]?.file).toBe('images/bitmap-0.png');
    expect(loaded?.images[0]?.file).not.toContain('/private/');
    expect(await store.loadImage('first', 0)).toMatchObject({
      recordIndex: 0,
      width: 1,
      height: 1,
      sha256: 'a'.repeat(64),
      dataUrl: expect.stringMatching(/^data:image\/png;base64,/),
    });
  });

  it('removes session metadata and its owned image artifacts together', async () => {
    const root = await temporaryDirectory();
    const source = join(root, 'bitmap-0.png');
    await writeFile(source, ONE_PIXEL_PNG);
    const store = new BitmapDumpStore(join(root, 'bitmap-dumps'));
    await store.add(session('first', source));

    expect(await store.remove('first')).toBe(true);
    expect(await store.load('first')).toBeUndefined();
    expect(await store.loadImage('first', 0)).toBeUndefined();
    await expect(readFile(join(root, 'bitmap-dumps', 'first', 'images', 'bitmap-0.png'))).rejects.toThrow();
  });

  it('keeps legacy JSON readable but treats its non-managed image assets as unavailable', async () => {
    const root = await temporaryDirectory();
    const storeDirectory = join(root, 'bitmap-dumps');
    const store = new BitmapDumpStore(storeDirectory);
    const legacy = session('legacy', '/private/deleted/bitmap-0.png');
    await mkdir(storeDirectory, { recursive: true });
    await writeFile(join(storeDirectory, 'legacy.json'), JSON.stringify(legacy));
    await writeFile(join(storeDirectory, 'index.json'), JSON.stringify([]));

    const loaded = await store.load('legacy');
    expect(loaded?.images[0]?.file).toBe('images/bitmap-0.png');
    expect(await store.loadImage('legacy', 0)).toBeUndefined();
  });

  it('does not send an image when its bounded IPC payload limit would be exceeded', async () => {
    const root = await temporaryDirectory();
    const source = join(root, 'bitmap-0.png');
    await writeFile(source, ONE_PIXEL_PNG);
    const store = new BitmapDumpStore(join(root, 'bitmap-dumps'), 1);
    await store.add(session('first', source));

    expect(await store.loadImage('first', 0)).toBeUndefined();
  });
});
