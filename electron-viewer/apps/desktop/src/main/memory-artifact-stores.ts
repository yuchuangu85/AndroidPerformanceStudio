/**
 * Session stores for the two memory artifacts that are not heap-dump sessions:
 * bitmap dumps and heapprofd traces. Same JSON-per-session layout as the other
 * feature stores, so a corrupt file only loses its own session.
 */
import { copyFile, mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type {
  BitmapDumpSession,
  BitmapImagePayload,
  BitmapSessionSummary,
  NativeHeapCaptureRecord,
  NativeHeapSessionSummary,
} from '../shared/ipc.js';
import { summarizeBitmapSession, summarizeNativeHeapSession } from './memory-summaries.js';

const INDEX_FILE = 'index.json';
const MANAGED_IMAGES_DIRECTORY = 'images';
const NOT_RETAINED_HPROF = '(not retained)';
const MAX_BITMAP_RECORD_INDEX = 1_000_000;
const PNG_SIGNATURE = Uint8Array.of(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);

/** One image is read on demand across the Electron IPC boundary, never a full session. */
export const DEFAULT_MAX_BITMAP_IMAGE_PAYLOAD_BYTES = 32 * 1024 * 1024;

export class BitmapDumpStore {
  private readonly directory: string;
  private readonly maximumImagePayloadBytes: number;

  constructor(directory: string, maximumImagePayloadBytes = DEFAULT_MAX_BITMAP_IMAGE_PAYLOAD_BYTES) {
    this.directory = directory;
    this.maximumImagePayloadBytes = maximumImagePayloadBytes;
  }

  pathFor(id: string): string {
    return join(this.directory, id + '.json');
  }

  private artifactDirectoryFor(id: string): string {
    return join(this.directory, id);
  }

  private imagesDirectoryFor(id: string): string {
    return join(this.artifactDirectoryFor(id), MANAGED_IMAGES_DIRECTORY);
  }

  private stagedArtifactDirectoryFor(id: string): string {
    return join(this.directory, '.' + id + '.pending');
  }

  private imageFileName(recordIndex: number): string {
    return 'bitmap-' + String(recordIndex) + '.png';
  }

  private imageRelativePath(recordIndex: number): string {
    return MANAGED_IMAGES_DIRECTORY + '/' + this.imageFileName(recordIndex);
  }

  private imagePathFor(id: string, recordIndex: number): string {
    return join(this.imagesDirectoryFor(id), this.imageFileName(recordIndex));
  }

  /**
   * Owns the PNG artifacts before the capture handler removes its temporary
   * directory. JSON keeps only store-relative logical names, never host paths.
   */
  async add(session: BitmapDumpSession): Promise<BitmapSessionSummary> {
    const persisted = this.persistedSession(session);
    const stagedDirectory = this.stagedArtifactDirectoryFor(session.id);
    await mkdir(this.directory, { recursive: true });
    await rm(stagedDirectory, { recursive: true, force: true });
    try {
      await mkdir(join(stagedDirectory, MANAGED_IMAGES_DIRECTORY), { recursive: true });
      const recordIndexes = new Set<number>();
      for (const image of session.images) {
        if (!Number.isSafeInteger(image.recordIndex) || image.recordIndex < 0 || image.recordIndex > MAX_BITMAP_RECORD_INDEX) {
          throw new TypeError('Bitmap record index is outside the supported range');
        }
        if (recordIndexes.has(image.recordIndex)) {
          throw new TypeError('Bitmap dump contains duplicate record indexes');
        }
        recordIndexes.add(image.recordIndex);
        const source = await stat(image.file);
        if (!source.isFile() || source.size !== image.pngBytes) {
          throw new TypeError('Bitmap PNG artifact is missing or has an unexpected size');
        }
        await copyFile(image.file, join(stagedDirectory, MANAGED_IMAGES_DIRECTORY, this.imageFileName(image.recordIndex)));
      }
      await rm(this.artifactDirectoryFor(session.id), { recursive: true, force: true });
      await rename(stagedDirectory, this.artifactDirectoryFor(session.id));
      const summary = summarizeBitmapSession(persisted);
      await writeFile(this.pathFor(session.id), JSON.stringify(persisted));
      const records = (await this.list()).filter((entry) => entry.id !== session.id);
      await writeFile(join(this.directory, INDEX_FILE), JSON.stringify([summary, ...records], null, 2));
      return summary;
    } catch (error) {
      await rm(stagedDirectory, { recursive: true, force: true });
      throw error;
    }
  }

  async list(): Promise<BitmapSessionSummary[]> {
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directory, INDEX_FILE), 'utf8'));
      if (!Array.isArray(parsed)) return [];
      return parsed as BitmapSessionSummary[];
    } catch {
      return [];
    }
  }

  /** Returns metadata only; managed filenames deliberately hide host paths. */
  async load(id: string): Promise<BitmapDumpSession | undefined> {
    const session = await this.read(id);
    return session === undefined ? undefined : this.publicSession(session);
  }

  /** Reads exactly one managed PNG after metadata and byte-limit checks. */
  async loadImage(id: string, recordIndex: number): Promise<BitmapImagePayload | undefined> {
    const session = await this.read(id);
    const image = session?.images.find((candidate) => candidate.recordIndex === recordIndex);
    if (image === undefined) return undefined;
    try {
      const imagePath = this.imagePathFor(id, recordIndex);
      const file = await stat(imagePath);
      if (!file.isFile() || file.size !== image.pngBytes || file.size > this.maximumImagePayloadBytes) return undefined;
      const bytes = await readFile(imagePath);
      if (!isPng(bytes)) return undefined;
      return {
        recordIndex: image.recordIndex,
        width: image.width,
        height: image.height,
        pngBytes: bytes.byteLength,
        sha256: image.sha256,
        dataUrl: 'data:image/png;base64,' + bytes.toString('base64'),
      };
    } catch {
      return undefined;
    }
  }

  async remove(id: string): Promise<boolean> {
    const existing = await this.read(id);
    if (existing === undefined) return false;
    await rm(this.pathFor(id), { force: true });
    await rm(this.artifactDirectoryFor(id), { recursive: true, force: true });
    const records = (await this.list()).filter((entry) => entry.id !== id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify(records, null, 2));
    return true;
  }

  private async read(id: string): Promise<BitmapDumpSession | undefined> {
    try {
      const parsed = JSON.parse(await readFile(this.pathFor(id), 'utf8')) as BitmapDumpSession;
      return typeof parsed.id === 'string' && parsed.summary !== undefined && Array.isArray(parsed.images) ? parsed : undefined;
    } catch {
      return undefined;
    }
  }

  private persistedSession(session: BitmapDumpSession): BitmapDumpSession {
    return {
      ...session,
      hprofFile: NOT_RETAINED_HPROF,
      imagesDirectory: MANAGED_IMAGES_DIRECTORY,
      images: session.images.map((image) => ({ ...image, file: this.imageRelativePath(image.recordIndex) })),
    };
  }

  private publicSession(session: BitmapDumpSession): BitmapDumpSession {
    return {
      ...session,
      hprofFile: NOT_RETAINED_HPROF,
      imagesDirectory: MANAGED_IMAGES_DIRECTORY,
      images: session.images.map((image) => ({ ...image, file: this.imageRelativePath(image.recordIndex) })),
    };
  }
}

function isPng(bytes: Uint8Array): boolean {
  return bytes.length >= PNG_SIGNATURE.length && PNG_SIGNATURE.every((value, index) => bytes[index] === value);
}

export class NativeHeapStore {
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = directory;
  }

  pathFor(id: string): string {
    return join(this.directory, id + '.json');
  }

  async add(record: NativeHeapCaptureRecord): Promise<NativeHeapSessionSummary> {
    await mkdir(this.directory, { recursive: true });
    const summary = summarizeNativeHeapSession(record);
    await writeFile(this.pathFor(record.id), JSON.stringify(record));
    const records = (await this.list()).filter((entry) => entry.id !== record.id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify([summary, ...records], null, 2));
    return summary;
  }

  async list(): Promise<NativeHeapSessionSummary[]> {
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directory, INDEX_FILE), 'utf8'));
      if (!Array.isArray(parsed)) return [];
      return parsed as NativeHeapSessionSummary[];
    } catch {
      return [];
    }
  }

  async load(id: string): Promise<NativeHeapCaptureRecord | undefined> {
    try {
      const parsed = JSON.parse(await readFile(this.pathFor(id), 'utf8')) as NativeHeapCaptureRecord;
      return typeof parsed.id === 'string' && parsed.analysis !== undefined ? parsed : undefined;
    } catch {
      return undefined;
    }
  }

  async remove(id: string): Promise<boolean> {
    const existing = await this.load(id);
    if (existing === undefined) return false;
    await rm(this.pathFor(id), { force: true });
    const records = (await this.list()).filter((entry) => entry.id !== id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify(records, null, 2));
    return true;
  }
}
