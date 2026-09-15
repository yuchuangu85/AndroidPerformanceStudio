import { describe, expect, it } from 'vitest';
import {
  compareBitmapDumps,
  type BitmapDumpImage,
  type BitmapDumpSession,
  type BitmapDumpSummary,
} from './bitmap-model.js';

const summary: BitmapDumpSummary = {
  recordedBitmapCount: 0,
  discoveredBitmapCount: 0,
  exportedImageCount: 0,
  uniqueImageCount: 0,
  duplicateGroupCount: 0,
  totalPngBytes: 0,
  estimatedBitmapBytes: 0,
};

function image(sha256: string, width: number, height: number, recordIndex: number): BitmapDumpImage {
  return {
    recordIndex,
    arrayObjectId: `0x${recordIndex.toString(16)}`,
    file: `/private/dump/${sha256}-${recordIndex}.png`,
    width,
    height,
    pngBytes: 32,
    estimatedMemoryBytes: width * height * 4,
    sha256,
    duplicateCount: 1,
  };
}

function session(id: string, images: readonly BitmapDumpImage[]): BitmapDumpSession {
  return {
    id,
    packageName: 'com.example.app',
    pid: 123,
    deviceSerial: 'SERIAL',
    sdkLevel: 35,
    capturedAtEpochMillis: 0,
    hprofFile: `/private/${id}.hprof`,
    imagesDirectory: `/private/${id}`,
    images,
    summary: { ...summary, exportedImageCount: images.length },
  };
}

describe('compareBitmapDumps', () => {
  it('classifies added, removed, and common content with changed duplicate counts', () => {
    const before = session('before', [
      image('removed', 10, 20, 0),
      image('shrunk', 30, 40, 1),
      image('shrunk', 30, 40, 2),
      image('shrunk', 30, 40, 3),
      image('unchanged', 50, 60, 4),
    ]);
    const after = session('after', [
      image('added', 70, 80, 0),
      image('added', 70, 80, 1),
      image('shrunk', 30, 40, 2),
      image('unchanged', 50, 60, 3),
    ]);

    const comparison = compareBitmapDumps(before, after);

    expect(comparison.added).toEqual([
      { sha256: 'added', width: 70, height: 80, beforeCount: 0, afterCount: 2, countDelta: 2 },
    ]);
    expect(comparison.removed).toEqual([
      { sha256: 'removed', width: 10, height: 20, beforeCount: 1, afterCount: 0, countDelta: -1 },
    ]);
    expect(comparison.changedDuplicateCounts).toEqual([
      { sha256: 'shrunk', width: 30, height: 40, beforeCount: 3, afterCount: 1, countDelta: -2 },
    ]);
    expect(JSON.stringify(comparison)).not.toContain('/private/');
  });

  it('sorts deterministically by absolute count delta and then hash', () => {
    const before = session('before', [
      image('z-last', 1, 1, 0),
      image('z-last', 1, 1, 1),
      image('z-last', 1, 1, 2),
      image('b-tie', 2, 2, 3),
      image('a-tie', 3, 3, 4),
    ]);
    const after = session('after', [
      image('z-last', 1, 1, 0),
      image('b-tie', 2, 2, 1),
      image('b-tie', 2, 2, 2),
      image('a-tie', 3, 3, 3),
      image('a-tie', 3, 3, 4),
    ]);

    const comparison = compareBitmapDumps(before, after);

    expect(comparison.changedDuplicateCounts.map((change) => change.sha256)).toEqual([
      'z-last',
      'a-tie',
      'b-tie',
    ]);
  });
});
