import { describe, expect, it } from 'vitest';
import type { BitmapDumpImage } from '@aps/memory-profiler';
import type { BitmapSessionSummary } from '../../shared/ipc';
import {
  BITMAP_GALLERY_PAGE_SIZE,
  bitmapGalleryPageCount,
  defaultBitmapBaselineId,
  signedNumber,
  visibleBitmapImages,
} from './bitmap-session-presentation';

function summary(id: string, capturedAtEpochMillis: number): BitmapSessionSummary {
  return {
    id,
    packageName: 'com.example.app',
    deviceSerial: 'SER',
    capturedAtEpochMillis,
    exportedImageCount: 0,
    uniqueImageCount: 0,
    duplicateGroupCount: 0,
    estimatedBitmapBytes: 0,
  };
}

function image(recordIndex: number): BitmapDumpImage {
  return {
    recordIndex,
    arrayObjectId: String(recordIndex),
    file: 'images/bitmap-' + String(recordIndex) + '.png',
    width: 1,
    height: 1,
    pngBytes: 1,
    estimatedMemoryBytes: 4,
    sha256: String(recordIndex).padStart(64, '0'),
    duplicateCount: 1,
  };
}

describe('bitmap session presentation', () => {
  it('selects the preceding capture chronologically as its comparison baseline', () => {
    const sessions = [summary('newest', 300), summary('oldest', 100), summary('middle', 200)];
    expect(defaultBitmapBaselineId(sessions, 'newest')).toBe('middle');
    expect(defaultBitmapBaselineId(sessions, 'middle')).toBe('oldest');
    expect(defaultBitmapBaselineId(sessions, 'oldest')).toBeUndefined();
  });

  it('pages gallery metadata without preloading every image payload', () => {
    const images = Array.from({ length: BITMAP_GALLERY_PAGE_SIZE + 2 }, (_unused, index) => image(index));
    expect(visibleBitmapImages(images, 0)).toHaveLength(BITMAP_GALLERY_PAGE_SIZE);
    expect(visibleBitmapImages(images, 1).map((entry) => entry.recordIndex)).toEqual([BITMAP_GALLERY_PAGE_SIZE, BITMAP_GALLERY_PAGE_SIZE + 1]);
    expect(bitmapGalleryPageCount(images.length)).toBe(2);
    expect(signedNumber(3)).toBe('+3');
    expect(signedNumber(-3)).toBe('-3');
  });
});
