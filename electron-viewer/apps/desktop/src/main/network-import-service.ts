import { fail, ok, type StudioResult } from '@aps/contracts';
import type { NetworkCaptureResult } from '@aps/network-profiler';
import { MAX_HAR_BYTES, parseHar } from '@aps/network-profiler/node';

export interface NetworkImportDependencies {
  readonly readFileText: (path: string) => Promise<string>;
  readonly fileSize: (path: string) => Promise<number>;
  readonly isRegularFile: (path: string) => boolean;
}

/**
 * Imports a HAR file. Redaction happens inside the parser, so the captured
 * result is already minimized before it is stored or displayed.
 */
export async function importHarFile(
  dependencies: NetworkImportDependencies,
  filePath: string,
): Promise<StudioResult<NetworkCaptureResult>> {
  if (!dependencies.isRegularFile(filePath)) {
    return fail('IO', 'NETWORK_HAR_NOT_FOUND', 'HAR file does not exist: ' + filePath);
  }
  let size: number;
  try {
    size = await dependencies.fileSize(filePath);
  } catch (error) {
    return fail('IO', 'NETWORK_HAR_UNREADABLE', describe(error));
  }
  if (size > MAX_HAR_BYTES) {
    return fail('DATA_VALIDATION', 'NETWORK_HAR_TOO_LARGE', 'HAR exceeds ' + MAX_HAR_BYTES + ' bytes');
  }
  let text: string;
  try {
    text = await dependencies.readFileText(filePath);
  } catch (error) {
    return fail('IO', 'NETWORK_HAR_UNREADABLE', describe(error));
  }
  try {
    return ok(parseHar(text).result);
  } catch (error) {
    return fail('DATA_VALIDATION', 'NETWORK_HAR_MALFORMED', describe(error));
  }
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : 'HAR import failed';
}
