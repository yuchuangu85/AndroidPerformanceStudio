import { resolve } from 'node:path';
import react from '@vitejs/plugin-react';
import { defineConfig, externalizeDepsPlugin } from 'electron-vite';

// @aps/contracts is a TypeScript source workspace package; it must be bundled
// rather than externalized, because there is no compiled JS entry for it.
const bundleContracts = { exclude: ['@aps/contracts'] };

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin(bundleContracts)],
  },
  preload: {
    plugins: [externalizeDepsPlugin(bundleContracts)],
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
