export type ThemePreference = 'system' | 'light' | 'dark';
export type ResolvedTheme = 'light' | 'dark';

/** Mirrors ApplicationThemePreference.resolveDark. */
export function resolveDark(preference: ThemePreference, systemPrefersDark: boolean): boolean {
  if (preference === 'light') return false;
  if (preference === 'dark') return true;
  return systemPrefersDark;
}

export function resolvedTheme(preference: ThemePreference, systemPrefersDark: boolean): ResolvedTheme {
  return resolveDark(preference, systemPrefersDark) ? 'dark' : 'light';
}
