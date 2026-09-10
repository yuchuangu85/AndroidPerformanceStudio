import { fail, ok, type StudioResult } from '@aps/contracts';
import { MAX_BENCHMARK_JSON_BYTES, parseBenchmarkJson } from '@aps/benchmark-regression/node';
import type { BenchmarkRun } from '@aps/benchmark-regression';

export interface BenchmarkImportDependencies {
  readonly readFileText: (path: string) => Promise<string>;
  readonly fileSize: (path: string) => Promise<number>;
  readonly isRegularFile: (path: string) => boolean;
  readonly newId: () => string;
  readonly now: () => number;
}

/** Imports an AndroidX Benchmark JSON report as an immutable run. */
export async function importBenchmarkJson(
  dependencies: BenchmarkImportDependencies,
  filePath: string,
): Promise<StudioResult<BenchmarkRun>> {
  if (!dependencies.isRegularFile(filePath)) {
    return fail('IO', 'BENCHMARK_JSON_NOT_FOUND', 'Benchmark JSON does not exist: ' + filePath);
  }
  let size: number;
  try {
    size = await dependencies.fileSize(filePath);
  } catch (error) {
    return fail('IO', 'BENCHMARK_JSON_UNREADABLE', describe(error));
  }
  if (size > MAX_BENCHMARK_JSON_BYTES) {
    return fail(
      'DATA_VALIDATION',
      'BENCHMARK_JSON_TOO_LARGE',
      'Benchmark JSON exceeds ' + MAX_BENCHMARK_JSON_BYTES + ' bytes',
    );
  }
  let text: string;
  try {
    text = await dependencies.readFileText(filePath);
  } catch (error) {
    return fail('IO', 'BENCHMARK_JSON_UNREADABLE', describe(error));
  }
  try {
    const parsed = parseBenchmarkJson(text, filePath, dependencies.newId(), dependencies.now());
    return ok(parsed.run);
  } catch (error) {
    return fail('DATA_VALIDATION', 'BENCHMARK_JSON_MALFORMED', describe(error));
  }
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : 'benchmark import failed';
}
