import type { BitmapDumpImage } from '@aps/memory-profiler';
import type { BitmapSessionSummary } from '../../shared/ipc';

/** Keeps an image-heavy gallery responsive without omitting later dump records. */
export const BITMAP_GALLERY_PAGE_SIZE = 24;

/** Chooses the immediately preceding capture chronologically, regardless of index order. */
export function defaultBitmapBaselineId(
  sessions: readonly BitmapSessionSummary[],
  currentId: string,
): string | undefined {
  const ordered = [...sessions].sort((left, right) => left.capturedAtEpochMillis - right.capturedAtEpochMillis);
  const currentIndex = ordered.findIndex((session) => session.id === currentId);
  return currentIndex > 0 ? ordered[currentIndex - 1]?.id : undefined;
}

/** Pages gallery metadata; image data stays out of React state until requested. */
export function visibleBitmapImages(
  images: readonly BitmapDumpImage[],
  page: number,
): readonly BitmapDumpImage[] {
  const safePage = Math.max(0, Math.floor(page));
  const start = safePage * BITMAP_GALLERY_PAGE_SIZE;
  return images.slice(start, start + BITMAP_GALLERY_PAGE_SIZE);
}

export function bitmapGalleryPageCount(imageCount: number): number {
  return Math.max(1, Math.ceil(imageCount / BITMAP_GALLERY_PAGE_SIZE));
}

export function signedNumber(value: number): string {
  return (value >= 0 ? '+' : '') + String(value);
}
