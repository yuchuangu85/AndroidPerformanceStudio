import js from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    ignores: [
      '**/node_modules/**',
      '**/out/**',
      '**/dist/**',
      '**/release/**',
      '**/coverage/**',
      '**/.pnpm-store/**',
      '**/.corepack/**',
      '**/.home/**',
      '**/.cache/**',
      '**/.electron-cache/**',
      '**/.tooling/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    // Plain Node scripts (corpus helpers, the UI perf harness) are not
    // TypeScript, so the globals they rely on have to be declared here.
    files: ['**/scripts/**/*.mjs', 'e2e/**/*.mjs', 'apps/desktop/build/*.mjs'],
    languageOptions: {
      globals: {
        Buffer: 'readonly',
        WebSocket: 'readonly',
        clearTimeout: 'readonly',
        console: 'readonly',
        fetch: 'readonly',
        process: 'readonly',
        setTimeout: 'readonly',
      },
    },
  },
);
