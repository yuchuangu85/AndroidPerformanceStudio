/**
 * Port of com.androidperformancestudio.model.StudioResult / StudioError.
 *
 * Kotlin models this as a sealed interface with Success/Failure. TypeScript uses
 * a discriminated union on `ok`; the error categories and codes are preserved.
 */

export const ERROR_CATEGORIES = [
  'CONFIGURATION',
  'PROCESS_START',
  'PROCESS_TIMEOUT',
  'PROCESS_CANCELLED',
  'PROCESS_EXIT',
  'IO',
  'DATA_VALIDATION',
  'UNSUPPORTED_PLATFORM',
  'UNKNOWN',
] as const;

export type ErrorCategory = (typeof ERROR_CATEGORIES)[number];

export interface StudioError {
  readonly category: ErrorCategory;
  readonly code: string;
  readonly message: string;
  /** Optional diagnostic detail; never a raw Throwable across the IPC boundary. */
  readonly cause?: string;
}

export interface StudioSuccess<T> {
  readonly ok: true;
  readonly value: T;
}

export interface StudioFailure {
  readonly ok: false;
  readonly error: StudioError;
}

export type StudioResult<T> = StudioSuccess<T> | StudioFailure;

export function ok<T>(value: T): StudioSuccess<T> {
  return { ok: true, value };
}

export function fail(
  category: ErrorCategory,
  code: string,
  message: string,
  cause?: string,
): StudioFailure {
  return {
    ok: false,
    error: cause === undefined ? { category, code, message } : { category, code, message, cause },
  };
}

export function failure(error: StudioError): StudioFailure {
  return { ok: false, error };
}

export function isOk<T>(result: StudioResult<T>): result is StudioSuccess<T> {
  return result.ok;
}

export function isFailure<T>(result: StudioResult<T>): result is StudioFailure {
  return !result.ok;
}

export function unwrap<T>(result: StudioResult<T>): T {
  if (!result.ok) {
    throw new Error(result.error.category + ':' + result.error.code + ': ' + result.error.message);
  }
  return result.value;
}

export function mapResult<T, U>(result: StudioResult<T>, transform: (value: T) => U): StudioResult<U> {
  return result.ok ? ok(transform(result.value)) : result;
}
