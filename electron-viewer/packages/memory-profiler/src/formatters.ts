/** Port of MemoryFormatters.kt: the sizes and counts the memory UI shows. */
const BYTES_PER_KB = 1024;
const BYTES_PER_MB = 1024 * 1024;

/** US grouping, matching the Kotlin NumberFormat instance. */
export function formatInteger(value: number | bigint): string {
  const text = String(value);
  const negative = text.startsWith('-');
  const digits = negative ? text.slice(1) : text;
  let grouped = '';
  for (let index = 0; index < digits.length; index += 1) {
    if (index > 0 && (digits.length - index) % 3 === 0) grouped += ',';
    grouped += digits[index];
  }
  return negative ? '-' + grouped : grouped;
}

export function formatBytes(bytes: number): string {
  if (bytes >= BYTES_PER_MB) return (bytes / BYTES_PER_MB).toFixed(1) + ' MB';
  if (bytes >= BYTES_PER_KB) return (bytes / BYTES_PER_KB).toFixed(1) + ' KB';
  return String(bytes) + ' B';
}
