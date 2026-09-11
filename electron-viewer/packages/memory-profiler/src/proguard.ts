/**
 * Port of ProguardMapping.kt: the R8/ProGuard mapping.txt that turns short
 * obfuscated class names back into source names, so histograms, leak suspects,
 * and Activity counts read as the classes a developer wrote.
 *
 * The Kotlin version rewrites a copied HeapDump. The TypeScript heap dump
 * resolves class names lazily from its string table, so rewriting the table
 * would also rewrite string constants that merely look like class names. This
 * port therefore exposes the mapping itself plus a name resolver, and leaves the
 * call sites to resolve names through it.
 */
export class ProguardMapping {
  readonly obfuscatedToOriginal: ReadonlyMap<string, string>;
  private readonly originalToObfuscated: ReadonlyMap<string, string>;

  constructor(obfuscatedToOriginal: ReadonlyMap<string, string>) {
    this.obfuscatedToOriginal = obfuscatedToOriginal;
    const reversed = new Map<string, string>();
    for (const [obfuscated, original] of obfuscatedToOriginal) {
      if (!reversed.has(original)) reversed.set(original, obfuscated);
    }
    this.originalToObfuscated = reversed;
  }

  get isEmpty(): boolean {
    return this.obfuscatedToOriginal.size === 0;
  }

  originalName(obfuscated: string): string {
    return this.obfuscatedToOriginal.get(obfuscated) ?? obfuscated;
  }

  obfuscatedName(original: string): string | undefined {
    return this.originalToObfuscated.get(original);
  }
}

export class ProguardMappingParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ProguardMappingParseError';
  }
}

/**
 * Class mappings are top level lines of the form `original -> obfuscated:`;
 * member mappings are indented and are ignored.
 */
export function parseProguardMapping(text: string): ProguardMapping {
  if (text.trim().length === 0) throw new ProguardMappingParseError('mapping.txt is empty');
  const obfuscatedToOriginal = new Map<string, string>();
  for (const rawLine of text.split('\n')) {
    const line = rawLine.trim();
    if (line.length === 0 || line.startsWith('#')) continue;
    if (rawLine.startsWith(' ') || !line.endsWith(':')) continue;
    const inner = line.slice(0, -1);
    const arrowIndex = inner.lastIndexOf(' -> ');
    if (arrowIndex <= 0) continue;
    const original = inner.slice(0, arrowIndex).trim();
    const obfuscated = inner.slice(arrowIndex + 4).trim();
    if (original.length > 0 && obfuscated.length > 0) obfuscatedToOriginal.set(obfuscated, original);
  }
  return new ProguardMapping(obfuscatedToOriginal);
}

/**
 * Remaps one class name, keeping array suffixes and JVM descriptors intact:
 * `a.b.c` becomes `com.example.MainActivity`, `a.b.c[]` keeps its brackets, and
 * `[La/b/c;` keeps its dimensions and becomes `[Lcom/example/MainActivity;`.
 */
export function remapClassName(name: string, mapping: ProguardMapping): string {
  if (mapping.isEmpty) return name;
  const bracketSuffix = trailingBrackets(name);
  if (bracketSuffix.length > 0 && bracketSuffix.length % 2 === 0) {
    return mapping.originalName(name.slice(0, name.length - bracketSuffix.length)) + bracketSuffix;
  }
  const dimensions = leadingBrackets(name);
  if (dimensions.length > 0 && name.startsWith(dimensions + 'L') && name.endsWith(';')) {
    const descriptor = name.slice(dimensions.length + 1, name.length - 1);
    const usesSlashes = descriptor.includes('/');
    const dotted = descriptor.replaceAll('/', '.');
    const original = usesSlashes ? mapping.originalName(dotted).replaceAll('.', '/') : mapping.originalName(dotted);
    return dimensions + 'L' + original + ';';
  }
  return mapping.originalName(name);
}

function trailingBrackets(name: string): string {
  let index = name.length;
  while (index > 0 && (name[index - 1] === '[' || name[index - 1] === ']')) index -= 1;
  return name.slice(index);
}

function leadingBrackets(name: string): string {
  let index = 0;
  while (index < name.length && name[index] === '[') index += 1;
  return name.slice(0, index);
}

const FRAMEWORK_PREFIXES = [
  'android.',
  'androidx.',
  'java.',
  'javax.',
  'kotlin.',
  'kotlinx.',
  'com.google.',
  'com.android.',
];

/** Best effort check for a short obfuscated name, used to prompt for a mapping. */
export function isLikelyObfuscatedClassName(className: string): boolean {
  if (!className.includes('.')) return false;
  if (FRAMEWORK_PREFIXES.some((prefix) => className.startsWith(prefix))) return false;
  return className.split('.').every((segment) => {
    if (segment.length === 0 || segment.length > 3) return false;
    for (const character of segment) {
      const lower = character.toLowerCase();
      if (lower < 'a' || lower > 'z') {
        if (character < '0' || character > '9') return false;
      }
    }
    return true;
  });
}
