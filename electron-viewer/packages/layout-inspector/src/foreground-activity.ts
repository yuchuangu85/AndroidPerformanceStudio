/**
 * Port of the Kotlin AdbOutputParser: which package is in front, from
 * `dumpsys activity activities`.
 *
 * The order matters. A multi-window device reports several resumed activities,
 * and only mFocusedApp names the one receiving input; the dump-visible-window-views
 * archive is matched against this package, so picking the wrong one captures the
 * wrong window.
 */
const FIELDS = ['mFocusedApp', 'topResumedActivity', 'mResumedActivity'] as const;

const patterns = FIELDS.map(
  (field) =>
    new RegExp(
      field + '=ActivityRecord\\{[^}]*\\su\\d+\\s+([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/',
    ),
);

export function parseForegroundPackage(output: string): string | undefined {
  for (const pattern of patterns) {
    const match = pattern.exec(output);
    const packageName = match?.[1];
    if (packageName !== undefined && packageName.length > 0) return packageName;
  }
  return undefined;
}
