import { readFile, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { decodeCaptureArtifact, fail, ok, type StudioResult } from '@aps/contracts';
import { sha256File } from '@aps/contracts/node';
import { SessionPackageCodec } from './session-package-codec.js';

const PERF_DATA = 'perf.data';
const SIMPLEPERF_PROTOBUF = 'simpleperf.protobuf';
const SYMBOLS_DIRECTORY = 'symbols';
const PROGUARD_MAPPING = 'mapping.txt';
const CAPTURE_ARTIFACT = 'capture-artifact.json';

/** Kotlin classifies both .apsession.zip and plain .zip as a session package. */
export function isCpuSessionPackageFileName(fileName: string): boolean {
  const normalized = fileName.toLowerCase();
  return normalized.endsWith('.apsession.zip') || normalized.endsWith('.zip');
}

export interface ImportedCpuSessionPackage {
  /** Validated package content retained by CpuProfileStore for future export/provenance. */
  readonly sessionDirectory: string;
  readonly perfData: string;
  readonly protobufTrace: string;
  readonly symbolDirectory?: string;
  readonly proguardMapping?: string;
  readonly verifiedFiles: number;
}

export interface CpuSessionPackageDependencies {
  readonly codec: Pick<SessionPackageCodec, 'import'>;
  readonly sha256File: (path: string) => Promise<string>;
  readonly isRegularFile: (path: string) => Promise<boolean>;
  readonly isDirectory: (path: string) => Promise<boolean>;
  readonly readText: (path: string) => Promise<string>;
}

const DEFAULT_DEPENDENCIES: CpuSessionPackageDependencies = {
  codec: new SessionPackageCodec(),
  sha256File,
  isRegularFile: async (path) => stat(path).then((value) => value.isFile()).catch(() => false),
  isDirectory: async (path) => stat(path).then((value) => value.isDirectory()).catch(() => false),
  readText: async (path) => readFile(path, 'utf8'),
};

/**
 * Opens a Kotlin Simpleperf package into a caller-owned staging directory.
 * Kotlin's `importCapturedSession` requires perf.data even when an old
 * protobuf is present, so Electron preserves that requirement and lets host
 * simpleperf regenerate the report with the packaged symbols and mapping.
 */
export async function importCpuSessionPackage(
  input: { readonly archive: string; readonly destinationRoot: string },
  dependencies: CpuSessionPackageDependencies = DEFAULT_DEPENDENCIES,
): Promise<StudioResult<ImportedCpuSessionPackage>> {
  let imported: { readonly sessionDirectory: string; readonly verifiedFiles: number };
  try {
    imported = await dependencies.codec.import(input.archive, input.destinationRoot);
  } catch (error) {
    return fail(
      'DATA_VALIDATION',
      'CPU_SESSION_PACKAGE_INVALID',
      error instanceof Error ? error.message : 'Session package is invalid',
    );
  }

  const perfData = join(imported.sessionDirectory, PERF_DATA);
  try {
    if (!(await dependencies.isRegularFile(perfData))) {
      return fail(
        'IO',
        'CAPTURED_SESSION_PERF_DATA_NOT_FOUND',
        'Captured session does not contain perf.data',
      );
    }

    const artifact = join(imported.sessionDirectory, CAPTURE_ARTIFACT);
    if (await dependencies.isRegularFile(artifact)) {
      const expectedHash = captureArtifactSha256(await dependencies.readText(artifact));
      if (expectedHash === undefined || expectedHash !== await dependencies.sha256File(perfData)) {
        return fail(
          'DATA_VALIDATION',
          'CAPTURED_SESSION_HASH_MISMATCH',
          'Captured session perf.data no longer matches its Capture Artifact hash',
        );
      }
    }

    const symbolDirectory = join(imported.sessionDirectory, SYMBOLS_DIRECTORY);
    const proguardMapping = join(imported.sessionDirectory, PROGUARD_MAPPING);
    return ok({
      sessionDirectory: imported.sessionDirectory,
      perfData,
      protobufTrace: join(imported.sessionDirectory, SIMPLEPERF_PROTOBUF),
      ...(await dependencies.isDirectory(symbolDirectory) ? { symbolDirectory } : {}),
      ...(await dependencies.isRegularFile(proguardMapping) ? { proguardMapping } : {}),
      verifiedFiles: imported.verifiedFiles,
    });
  } catch (error) {
    return fail(
      'IO',
      'CPU_SESSION_PACKAGE_PREPARE_FAILED',
      error instanceof Error ? error.message : 'Failed to prepare the captured session package',
    );
  }
}

function captureArtifactSha256(text: string): string | undefined {
  try {
    return decodeCaptureArtifact(text).sha256;
  } catch {
    return undefined;
  }
}
