/**
 * CSV writing shared by the exporters.
 *
 * Port of the per-exporter helpers the Kotlin app repeats: a value is quoted
 * only when it contains a comma, a quote, or a newline, and a missing value is
 * written as the literal null the Kotlin code produces with toString().
 */
export function csvValue(value: unknown): string {
  if (value === undefined || value === null) return 'null';
  return escapeCsv(String(value));
}

export function escapeCsv(value: string): string {
  if (value.includes(',') || value.includes('"') || value.includes('\n')) {
    return '"' + value.replaceAll('"', '""') + '"';
  }
  return value;
}

export function csvRow(values: readonly unknown[]): string {
  return values.map(csvValue).join(',');
}

/** Kotlin appendLine semantics: every line, including the last, ends in \n. */
export function textFile(lines: readonly string[]): string {
  return lines.map((line) => line + '\n').join('');
}
