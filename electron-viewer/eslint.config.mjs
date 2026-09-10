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
);
