import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import react from '@vitejs/plugin-react';
import { defineConfig, externalizeDepsPlugin } from 'electron-vite';

/**
 * Every @aps/* package is TypeScript source with no compiled entry point, so the
 * main and preload bundles must inline them. Externalizing one leaves a runtime
 * import that Node cannot resolve: the packaged application fails at startup with
 * ERR_MODULE_NOT_FOUND on packages/<name>/src/index.ts.
 *
 * The list is read from the workspace instead of hard-coded, so adding a package
 * cannot silently reintroduce the bug. If the workspace cannot be found the
 * contracts package is still excluded, which is the one the shell needs first.
 */
function workspacePackageNames(): string[] {
  const candidates = [resolve(process.cwd(), '..', '..', 'packages'), resolve(process.cwd(), 'packages')];
  for (const directory of candidates) {
    try {
      const names = readdirSync(directory, { withFileTypes: true })
        .filter((entry) => entry.isDirectory())
        .map((entry) => {
          const manifest = JSON.parse(readFileSync(join(directory, entry.name, 'package.json'), 'utf8'));
          return typeof manifest.name === 'string' ? manifest.name : '';
        })
        .filter((name) => name.startsWith('@aps/'));
      if (names.length > 0) return names;
    } catch {
      // Try the next candidate.
    }
  }
  return ['@aps/contracts'];
}

const bundleWorkspacePackages = { exclude: workspacePackageNames() };

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin(bundleWorkspacePackages)],
  },
  preload: {
    plugins: [externalizeDepsPlugin(bundleWorkspacePackages)],
  },
  renderer: {
    resolve: {
      alias: {
        '@renderer': resolve('src/renderer/src'),
      },
    },
    plugins: [react()],
  },
});
