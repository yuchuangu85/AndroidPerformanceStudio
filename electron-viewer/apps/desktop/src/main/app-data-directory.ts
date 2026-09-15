import { cp, mkdir, readdir, stat } from 'node:fs/promises';
import { join } from 'node:path';

/** The Kotlin desktop application's shared on-disk root on every host OS. */
export function kotlinApplicationDataDirectory(homeDirectory: string): string {
  return join(homeDirectory, '.android-performance-studio');
}

export interface ApplicationDataMigrationIo {
  readonly readdir: typeof readdir;
  readonly mkdir: typeof mkdir;
  readonly stat: typeof stat;
  readonly cp: typeof cp;
}

const nodeIo: ApplicationDataMigrationIo = { readdir, mkdir, stat, cp };

/**
 * Adds legacy Electron-only records to the Kotlin-compatible root without
 * replacing a record Kotlin or a newer Electron run already owns.
 */
export async function migrateLegacyElectronData(
  legacyDirectory: string,
  targetDirectory: string,
  io: ApplicationDataMigrationIo = nodeIo,
): Promise<void> {
  if (legacyDirectory === targetDirectory) return;
  let entries;
  try {
    entries = await io.readdir(legacyDirectory, { withFileTypes: true });
  } catch {
    return;
  }
  await io.mkdir(targetDirectory, { recursive: true });
  for (const entry of entries) {
    const source = join(legacyDirectory, entry.name);
    const target = join(targetDirectory, entry.name);
    try {
      await io.stat(target);
      continue;
    } catch {
      await io.cp(source, target, { recursive: entry.isDirectory(), force: false, errorOnExist: false });
    }
  }
}
