import type { SourceLanguage } from './model.js';

/** Mirrors sourceLanguage in StructuralSourceIndexer.kt. */
export function sourceLanguage(relativePath: string): SourceLanguage {
  const extension = relativePath.split('.').pop()?.toLowerCase() ?? '';
  switch (extension) {
    case 'kt':
    case 'kts':
      return 'KOTLIN';
    case 'java':
      return 'JAVA';
    case 'xml':
      return 'XML';
    case 'c':
    case 'h':
      return 'C';
    case 'cc':
    case 'cpp':
    case 'cxx':
    case 'hh':
    case 'hpp':
      return 'CPP';
    default:
      return 'OTHER';
  }
}

/** True when a path is worth indexing; the ignore list matches the provider. */
export function isIndexableSourcePath(relativePath: string): boolean {
  const segments = relativePath.split('/');
  if (segments.some((segment) => IGNORED_DIRECTORY_NAMES.has(segment))) return false;
  return EXTENSIONS.has(relativePath.split('.').pop()?.toLowerCase() ?? '');
}

export const SOURCE_EXTENSIONS: readonly string[] = [
  'kt',
  'kts',
  'java',
  'xml',
  'c',
  'cc',
  'cpp',
  'cxx',
  'h',
  'hh',
  'hpp',
];

const EXTENSIONS = new Set(SOURCE_EXTENSIONS);
const IGNORED_DIRECTORY_NAMES = new Set(['.git', '.gradle', '.idea', 'build', 'out', 'node_modules']);

export const MAX_SOURCE_FILE_BYTES = 5 * 1024 * 1024;

/**
 * Java's String.hashCode over UTF-16 code units. Compose traces store the
 * package hash this way, so the value must match the JVM exactly.
 */
export function javaStringHash(value: string): number {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = (Math.imul(31, hash) + value.charCodeAt(index)) | 0;
  }
  return hash;
}

/** Kotlin's Int.absoluteValue overflows for Int.MIN_VALUE; so does this. */
export function javaAbsoluteHash(value: number): number {
  return value === -2147483648 ? -2147483648 : Math.abs(value);
}
