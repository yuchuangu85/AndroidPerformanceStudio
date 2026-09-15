import { describe, expect, it } from 'vitest';
import * as browserApi from './index.js';
import { parseBitmapDump } from './node.js';

describe('memory-profiler entry points', () => {
  it('exports the bitmap parser from the public Node entry point only', () => {
    expect(parseBitmapDump).toBeTypeOf('function');
    expect(browserApi).not.toHaveProperty('parseBitmapDump');
  });
});
