/**
 * Parses reg query output for HKCU\Software\JavaSoft\Prefs\<node>.
 * Java escapes non-ASCII string values as \uXXXX on Windows.
 */
export function parseWindowsRegistryQuery(output: string): Map<string, string> {
  const values = new Map<string, string>();
  for (const line of output.split('\n')) {
    const match = /^\s{2,}(\S.*?)\s{4}(REG_[A-Z_]+)\s{4}(.*)$/.exec(line.replace(/\r$/, ''));
    if (match === null) continue;
    const key = match[1] as string;
    const type = match[2] as string;
    const raw = (match[3] as string).trim();
    if (type === 'REG_BINARY') continue;
    if (type === 'REG_DWORD') values.set(key, String(Number.parseInt(raw, 16)));
    else values.set(key, decodeJavaWindowsEscapes(raw));
  }
  return values;
}

export function decodeJavaWindowsEscapes(value: string): string {
  return value.replace(/\\u([0-9a-fA-F]{4})/g, (_match, hex: string) =>
    String.fromCharCode(Number.parseInt(hex, 16)),
  );
}
