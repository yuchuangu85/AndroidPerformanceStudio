/**
 * JSON text helpers matching the Kotlin exporters byte for byte.
 *
 * The exporters hand format their documents (key order and indentation are part
 * of the file format), so JSON.stringify is not used for the document itself.
 * Kotlin prints a whole Double and JavaScript prints the shortest round trip
 * form, which differs only for values like 1.0.
 */
export function jsonValue(value: unknown): string {
  if (value === undefined || value === null) return 'null';
  if (typeof value === 'string') return jsonString(value);
  if (typeof value === 'boolean' || typeof value === 'number' || typeof value === 'bigint') {
    return String(value);
  }
  return jsonString(String(value));
}

/** Escapes exactly like the Kotlin exporters, including the \b and \f forms. */
export function jsonString(value: string): string {
  let text = '"';
  for (const character of value) {
    const code = character.codePointAt(0) as number;
    if (character === '"') text += '\\"';
    else if (character === '\\') text += '\\\\';
    else if (code === 0x08) text += '\\b';
    else if (code === 0x0c) text += '\\f';
    else if (code === 0x0a) text += '\\n';
    else if (code === 0x0d) text += '\\r';
    else if (code === 0x09) text += '\\t';
    else if (code < 0x20) text += '\\u' + code.toString(16).padStart(4, '0');
    else text += character;
  }
  return text + '"';
}

export function jsonStringArray(values: readonly string[]): string {
  return '[' + values.map(jsonString).join(', ') + ']';
}
export function jsonArrayOf(values: readonly unknown[]): string {
  return '[' + values.map(jsonValue).join(', ') + ']';
}

export function jsonObjectOf(values: Readonly<Record<string, unknown>>): string {
  return (
    '{' +
    Object.entries(values)
      .map(([key, value]) => jsonString(key) + ': ' + jsonValue(value))
      .join(', ') +
    '}'
  );
}
