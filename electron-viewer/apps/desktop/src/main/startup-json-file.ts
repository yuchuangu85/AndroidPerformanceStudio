import { open, type FileHandle } from 'node:fs/promises';
import { MAX_KOTLIN_STARTUP_JSON_BYTES } from '@aps/startup-profiler';

/** Reads exactly one regular Startup JSON file through a bounded file handle. */
export async function readKotlinStartupJsonFile(path: string): Promise<string> {
  let handle: FileHandle;
  try {
    handle = await open(path, 'r');
  } catch (error) {
    throw new TypeError('Startup JSON cannot be opened: ' + describe(error), { cause: error });
  }

  try {
    const initial = await handle.stat();
    if (!initial.isFile()) throw new TypeError('Selected startup report is not a regular file');
    if (initial.size > MAX_KOTLIN_STARTUP_JSON_BYTES) {
      throw new TypeError('Startup JSON exceeds the 16 MiB import limit');
    }

    const bytes = Buffer.alloc(initial.size);
    let offset = 0;
    while (offset < bytes.length) {
      const read = await handle.read(bytes, offset, bytes.length - offset, offset);
      if (read.bytesRead === 0) throw new TypeError('Startup JSON changed while it was being read');
      offset += read.bytesRead;
    }

    const final = await handle.stat();
    if (final.size !== initial.size) throw new TypeError('Startup JSON changed while it was being read');
    return bytes.toString('utf8');
  } finally {
    await handle.close();
  }
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : 'unknown error';
}
