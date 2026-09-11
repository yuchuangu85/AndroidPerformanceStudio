/**
 * Port of StructuralSourceIndexer.kt: a structural, regex based index of the
 * declarations in one file. It is deliberately not a compiler front end — the
 * index exists so performance evidence can be pointed at a plausible line, and
 * every symbol records the line it was found on.
 */
import { sourceLanguage } from './language.js';
import type { SourceFile, SourceSymbol, SourceSymbolKind } from './model.js';

export function indexSourceFile(
  snapshotId: string,
  relativePath: string,
  content: string,
): SourceSymbol[] {
  const file: SourceFile = {
    snapshotId,
    relativePath,
    language: sourceLanguage(relativePath),
    contentHash: '',
    sizeBytes: content.length,
  };
  return indexWithFile(file, content);
}

function indexWithFile(file: SourceFile, content: string): SourceSymbol[] {
  switch (file.language) {
    case 'KOTLIN':
      return indexKotlin(file, content);
    case 'JAVA':
      return indexJava(file, content);
    case 'XML':
      return indexXml(file, content);
    case 'C':
    case 'CPP':
      return indexNative(file, content);
    default:
      return [];
  }
}

function indexKotlin(file: SourceFile, content: string): SourceSymbol[] {
  const packageName = firstGroup(PACKAGE_PATTERN, content) ?? '';
  const symbols: SourceSymbol[] = [];
  for (const match of content.matchAll(TYPE_PATTERN)) {
    symbols.push(toSymbol(file, content, match, 'TYPE', qualify(packageName, group(match, 2))));
  }
  for (const match of content.matchAll(KOTLIN_FUNCTION_PATTERN)) {
    symbols.push(
      toSymbol(file, content, match, 'FUNCTION', qualify(packageName, group(match, 1)), group(match, 2)),
    );
  }
  return symbols;
}

function indexJava(file: SourceFile, content: string): SourceSymbol[] {
  const packageName = firstGroup(PACKAGE_PATTERN, content) ?? '';
  const symbols: SourceSymbol[] = [];
  for (const match of content.matchAll(JAVA_TYPE_PATTERN)) {
    symbols.push(toSymbol(file, content, match, 'TYPE', qualify(packageName, group(match, 2))));
  }
  for (const match of content.matchAll(JAVA_METHOD_PATTERN)) {
    const methodName = group(match, 1);
    if (!JAVA_KEYWORDS.has(methodName)) {
      symbols.push(toSymbol(file, content, match, 'METHOD', qualify(packageName, methodName), group(match, 2)));
    }
  }
  return symbols;
}

function indexXml(file: SourceFile, content: string): SourceSymbol[] {
  const symbols: SourceSymbol[] = [];
  for (const match of content.matchAll(RESOURCE_PATTERN)) {
    symbols.push(toSymbol(file, content, match, 'RESOURCE', group(match, 1) + '/' + group(match, 2)));
  }
  return symbols;
}

function indexNative(file: SourceFile, content: string): SourceSymbol[] {
  const symbols: SourceSymbol[] = [];
  for (const match of content.matchAll(NATIVE_FUNCTION_PATTERN)) {
    symbols.push(toSymbol(file, content, match, 'NATIVE_SYMBOL', group(match, 1), group(match, 2)));
  }
  return symbols;
}

function toSymbol(
  file: SourceFile,
  content: string,
  match: RegExpMatchArray,
  kind: SourceSymbolKind,
  qualifiedName: string,
  signature?: string,
): SourceSymbol {
  const index = match.index ?? 0;
  const line = content.slice(0, index).split('\n').length;
  const trimmed = signature?.trim();
  return {
    snapshotId: file.snapshotId,
    relativePath: file.relativePath,
    kind,
    qualifiedName,
    ...(trimmed !== undefined && trimmed.length > 0 ? { signature: trimmed } : {}),
    startLine: line,
    endLine: line,
  };
}

function group(match: RegExpMatchArray, index: number): string {
  return match[index] ?? '';
}

function firstGroup(pattern: RegExp, content: string): string | undefined {
  return pattern.exec(content)?.[1];
}

function qualify(packageName: string, name: string): string {
  return packageName.trim().length === 0 ? name : packageName + '.' + name;
}

// The patterns are built through the RegExp constructor because their inline
// (?m) form is valid ECMAScript but rejected by the TypeScript scanner here.
const PACKAGE_PATTERN = new RegExp('^\\s*package\\s+([A-Za-z_][\\w.]*)', 'm');
const TYPE_PATTERN = /\b(class|interface|object|enum\s+class|sealed\s+class)\s+([A-Za-z_]\w*)/g;
const KOTLIN_FUNCTION_PATTERN = /\bfun\s+(?:<[^>]+>\s*)?(?:[\w?.<>]+\.)?([A-Za-z_]\w*)\s*\(([^)]*)\)/g;
const JAVA_TYPE_PATTERN = /\b(class|interface|enum|record)\s+([A-Za-z_]\w*)/g;
const JAVA_METHOD_PATTERN = /(?:public|protected|private|static|final|native|synchronized|abstract|\s)+[\w<>, ?[\].]+\s+([A-Za-z_]\w*)\s*\(([^)]*)\)/g;
const NATIVE_FUNCTION_PATTERN = new RegExp(
  '^[\\w:<>,*&\\s]+\\s+([A-Za-z_~][\\w:]*)\\s*\\(([^;{}]*)\\)\\s*(?:const\\s*)?\\{',
  'gm',
);
const RESOURCE_PATTERN = /@\+?([a-zA-Z_][\w]*)\/([a-zA-Z_][\w]*)/g;
const JAVA_KEYWORDS = new Set(['if', 'for', 'while', 'switch', 'catch', 'return', 'new']);
