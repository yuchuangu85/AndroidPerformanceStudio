import { fail, ok, type StudioResult } from '@aps/contracts';

export interface CpuSessionPackageExportDependencies {
  /** Resolves either opaque imported provenance or native retained evidence. */
  readonly sessionPackageDirectoryFor: (id: string) => Promise<string | undefined>;
  readonly exportPackage: (sessionDirectory: string, destinationArchive: string) => Promise<{
    readonly archive: string;
    readonly fileCount: number;
  }>;
}

export interface CpuSessionPackageExportResult {
  readonly archive: string;
  readonly fileCount: number;
}

/**
 * Exports only a session with retained `perf.data`; report-only sessions must
 * not be mislabeled as Kotlin-compatible captured-session packages.
 */
export async function exportCpuSessionPackage(
  input: { readonly id: string; readonly destinationArchive: string },
  dependencies: CpuSessionPackageExportDependencies,
): Promise<StudioResult<CpuSessionPackageExportResult>> {
  const sessionDirectory = await dependencies.sessionPackageDirectoryFor(input.id);
  if (sessionDirectory === undefined) {
    return fail(
      'DATA_VALIDATION',
      'CPU_SESSION_PACKAGE_EXPORT_UNAVAILABLE',
      'This CPU session has no retained perf.data and cannot be exported as a Kotlin session package',
    );
  }
  try {
    const exported = await dependencies.exportPackage(sessionDirectory, input.destinationArchive);
    return ok(exported);
  } catch (error) {
    return fail(
      'IO',
      'CPU_SESSION_PACKAGE_EXPORT_FAILED',
      error instanceof Error ? error.message : 'Failed to export CPU session package',
    );
  }
}
