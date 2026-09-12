import { defineConfig } from 'vitest/config';

/**
 * The renderer's stylesheet carries a layout contract the shell test asserts
 * (four feature cards per row), so CSS has to come through as text instead of
 * Vitest's default empty stub.
 */
export default defineConfig({
  test: {
    css: true,
  },
});
