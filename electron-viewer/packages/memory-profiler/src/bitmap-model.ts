/**
 * Bitmap dump model and analysis. Kept apart from the parser so the renderer
 * can hold a session without pulling in the file reader.
 */

export interface BitmapDumpImage {
  readonly recordIndex: number;
  readonly arrayObjectId: string;
  readonly file: string;
  readonly width: number;
  readonly height: number;
  readonly pngBytes: number;
  readonly estimatedMemoryBytes: number;
  readonly sha256: string;
  readonly duplicateCount: number;
}

export interface BitmapDumpParseResult {
  readonly recordedBitmapCount: number;
  readonly discoveredBitmapCount: number;
  readonly images: readonly BitmapDumpImage[];
}

export interface ProcessMemorySnapshot {
  readonly totalPssBytes: number;
  readonly javaHeapPssBytes: number;
  readonly nativeHeapPssBytes: number;
}

export interface BitmapDumpSummary {
  readonly recordedBitmapCount: number;
  readonly discoveredBitmapCount: number;
  readonly exportedImageCount: number;
  readonly uniqueImageCount: number;
  readonly duplicateGroupCount: number;
  readonly totalPngBytes: number;
  readonly estimatedBitmapBytes: number;
  /** Estimated bitmap bytes as a share of the native heap PSS, when known. */
  readonly bitmapNativeHeapRatioPercent?: number;
}

export interface BitmapDumpSession {
  readonly id: string;
  readonly packageName: string;
  readonly pid: number;
  readonly deviceSerial: string;
  readonly sdkLevel: number;
  readonly capturedAtEpochMillis: number;
  readonly hprofFile: string;
  readonly imagesDirectory: string;
  readonly images: readonly BitmapDumpImage[];
  readonly memorySnapshot?: ProcessMemorySnapshot;
  readonly summary: BitmapDumpSummary;
}

export interface BitmapContentChange {
  readonly sha256: string;
  readonly width: number;
  readonly height: number;
  readonly beforeCount: number;
  readonly afterCount: number;
  readonly countDelta: number;
}

export interface BitmapDumpComparison {
  readonly before: BitmapDumpSummary;
  readonly after: BitmapDumpSummary;
  readonly added: readonly BitmapContentChange[];
  readonly removed: readonly BitmapContentChange[];
  readonly changedDuplicateCounts: readonly BitmapContentChange[];
}

export interface BitmapDumpAnalysisRequest {
  readonly id: string;
  readonly packageName: string;
  readonly pid: number;
  readonly deviceSerial: string;
  readonly sdkLevel: number;
  readonly capturedAtEpochMillis: number;
  readonly hprofFile: string;
  readonly imagesDirectory: string;
  readonly parsed: BitmapDumpParseResult;
  readonly memorySnapshot?: ProcessMemorySnapshot;
}

const PERCENT_MULTIPLIER = 100;

/** Counts duplicates by content and derives the summary the panel shows. */
export function analyzeBitmapDump(request: BitmapDumpAnalysisRequest): BitmapDumpSession {
  const counts = new Map<string, number>();
  for (const image of request.parsed.images) counts.set(image.sha256, (counts.get(image.sha256) ?? 0) + 1);
  const images = request.parsed.images.map((image) => ({
    ...image,
    duplicateCount: counts.get(image.sha256) ?? 1,
  }));
  const estimatedBytes = images.reduce((total, image) => total + image.estimatedMemoryBytes, 0);
  const nativeBytes =
    request.memorySnapshot !== undefined && request.memorySnapshot.nativeHeapPssBytes > 0
      ? request.memorySnapshot.nativeHeapPssBytes
      : undefined;
  return {
    id: request.id,
    packageName: request.packageName,
    pid: request.pid,
    deviceSerial: request.deviceSerial,
    sdkLevel: request.sdkLevel,
    capturedAtEpochMillis: request.capturedAtEpochMillis,
    hprofFile: request.hprofFile,
    imagesDirectory: request.imagesDirectory,
    images,
    ...(request.memorySnapshot !== undefined ? { memorySnapshot: request.memorySnapshot } : {}),
    summary: {
      recordedBitmapCount: request.parsed.recordedBitmapCount,
      discoveredBitmapCount: request.parsed.discoveredBitmapCount,
      exportedImageCount: images.length,
      uniqueImageCount: counts.size,
      duplicateGroupCount: [...counts.values()].filter((count) => count > 1).length,
      totalPngBytes: images.reduce((total, image) => total + image.pngBytes, 0),
      estimatedBitmapBytes: estimatedBytes,
      ...(nativeBytes !== undefined
        ? { bitmapNativeHeapRatioPercent: (estimatedBytes / nativeBytes) * PERCENT_MULTIPLIER }
        : {}),
    },
  };
}

/** Content-level diff: which images appeared, disappeared, or multiplied. */
export function compareBitmapDumps(
  before: BitmapDumpSession,
  after: BitmapDumpSession,
): BitmapDumpComparison {
  const index = (session: BitmapDumpSession): Map<string, { count: number; sample: BitmapDumpImage }> => {
    const map = new Map<string, { count: number; sample: BitmapDumpImage }>();
    for (const image of session.images) {
      const existing = map.get(image.sha256);
      if (existing === undefined) map.set(image.sha256, { count: 1, sample: image });
      else existing.count += 1;
    }
    return map;
  };
  const beforeContent = index(before);
  const afterContent = index(after);
  const changes: BitmapContentChange[] = [];
  for (const sha of new Set([...beforeContent.keys(), ...afterContent.keys()])) {
    const old = beforeContent.get(sha);
    const next = afterContent.get(sha);
    const beforeCount = old?.count ?? 0;
    const afterCount = next?.count ?? 0;
    if (beforeCount === afterCount) continue;
    const sample = next?.sample ?? old?.sample;
    if (sample === undefined) continue;
    changes.push({
      sha256: sha,
      width: sample.width,
      height: sample.height,
      beforeCount,
      afterCount,
      countDelta: afterCount - beforeCount,
    });
  }
  changes.sort((left, right) => {
    const difference = Math.abs(right.countDelta) - Math.abs(left.countDelta);
    if (difference !== 0) return difference;
    return left.sha256 < right.sha256 ? -1 : left.sha256 > right.sha256 ? 1 : 0;
  });
  return {
    before: before.summary,
    after: after.summary,
    added: changes.filter((change) => change.beforeCount === 0),
    removed: changes.filter((change) => change.afterCount === 0),
    changedDuplicateCounts: changes.filter((change) => change.beforeCount > 0 && change.afterCount > 0),
  };
}
