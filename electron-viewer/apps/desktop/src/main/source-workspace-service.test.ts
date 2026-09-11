import { describe, expect, it } from 'vitest';
import { javaAbsoluteHash, javaStringHash, type SourceFile } from '@aps/source-workspace';
import {
  indexLocalWorkspace,
  readIndexedSource,
  resolveInIndex,
  type SourceIndexDependencies,
} from './source-workspace-service.js';

const SOURCES: Record<string, string> = {
  'app/src/main/kotlin/MainActivity.kt': [
    'package com.example.app',
    'class MainActivity {',
    '    fun renderFrame(width: Int) {',
    '    }',
    '}',
  ].join('\n'),
  'app/src/main/res/layout/activity_main.xml': '<LinearLayout\n    android:text="@string/app_name">\n</LinearLayout>',
};

function dependencies(overrides: Partial<SourceIndexDependencies> = {}): SourceIndexDependencies {
  return {
    walk: () =>
      Object.keys(SOURCES).map((relativePath) => ({
        relativePath,
        sizeBytes: (SOURCES[relativePath] as string).length,
      })),
    readFile: (_root, relativePath) => new TextEncoder().encode(SOURCES[relativePath] as string),
    revisionOf: async () => 'abc123',
    now: () => 1_700_000_000_000,
    ...overrides,
  };
}

describe('indexLocalWorkspace', () => {
  it('builds a snapshot, files, and symbols', async () => {
    const result = await indexLocalWorkspace('ws-1', '/src', dependencies());
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.snapshot.workspaceId).toBe('ws-1');
    expect(result.value.snapshot.immutableRevision).toBe('abc123');
    expect(result.value.snapshot.indexComplete).toBe(true);
    expect(result.value.snapshot.indexVersion).toBe(1_700_000_000_000);
    expect(result.value.files.map((file) => file.relativePath)).toEqual(Object.keys(SOURCES));
    const names = result.value.symbols.map((symbol) => symbol.qualifiedName);
    expect(names).toContain('com.example.app.MainActivity');
    expect(names).toContain('string/app_name');
    expect(result.value.files[0]?.contentHash).toMatch(/^[0-9a-f]{64}$/);
  });

  it('is deterministic for the same tree and changes when content changes', async () => {
    const first = await indexLocalWorkspace('ws-1', '/src', dependencies());
    const second = await indexLocalWorkspace('ws-1', '/src', dependencies());
    expect(first.ok && second.ok).toBe(true);
    if (!first.ok || !second.ok) return;
    expect(first.value.snapshot.manifestHash).toBe(second.value.snapshot.manifestHash);
    expect(first.value.snapshot.id).toBe(second.value.snapshot.id);

    const changed = await indexLocalWorkspace(
      'ws-1',
      '/src',
      dependencies({
        readFile: (_root, relativePath) =>
          new TextEncoder().encode(
            relativePath.endsWith('.kt') ? 'package com.example.app\nclass Changed' : (SOURCES[relativePath] as string),
          ),
      }),
    );
    expect(changed.ok).toBe(true);
    if (!changed.ok) return;
    expect(changed.value.snapshot.manifestHash).not.toBe(first.value.snapshot.manifestHash);
  });

  it('marks a partially readable tree as incomplete', async () => {
    const result = await indexLocalWorkspace(
      'ws-1',
      '/src',
      dependencies({
        readFile: (_root, relativePath) => {
          if (relativePath.endsWith('.xml')) throw new Error('EACCES');
          return new TextEncoder().encode(SOURCES[relativePath] as string);
        },
      }),
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.snapshot.indexComplete).toBe(false);
    expect(result.value.files).toHaveLength(1);
  });

  it('reports an empty or unreadable root', async () => {
    const noRoot = await indexLocalWorkspace('ws-1', '  ', dependencies());
    expect(noRoot.ok).toBe(false);
    if (!noRoot.ok) expect(noRoot.error.code).toBe('SOURCE_ROOT_REQUIRED');

    const empty = await indexLocalWorkspace('ws-1', '/src', dependencies({ walk: () => [] }));
    expect(empty.ok).toBe(false);
    if (!empty.ok) expect(empty.error.code).toBe('SOURCE_ROOT_EMPTY');

    const unreadable = await indexLocalWorkspace(
      'ws-1',
      '/src',
      dependencies({
        walk: () => {
          throw new Error('ENOENT');
        },
      }),
    );
    expect(unreadable.ok).toBe(false);
    if (!unreadable.ok) expect(unreadable.error.code).toBe('SOURCE_ROOT_UNREADABLE');
  });
});

describe('resolveInIndex', () => {
  it('resolves a type and downgrades it when the build identity is unverified', async () => {
    const indexed = await indexLocalWorkspace('ws-1', '/src', dependencies());
    if (!indexed.ok) throw new Error('fixture failed to index');
    const index = indexed.value;

    const verified = resolveInIndex(
      index,
      [{ kind: 'TYPE_NAME', id: 'ev-1', qualifiedName: 'com.example.app.MainActivity' }],
      'VERIFIED',
    );
    expect(verified).toHaveLength(1);
    expect(verified[0]?.confidence).toBe('EXACT');
    expect(verified[0]?.location.relativePath).toBe('app/src/main/kotlin/MainActivity.kt');

    const unverified = resolveInIndex(index, [
      { kind: 'TYPE_NAME', id: 'ev-1', qualifiedName: 'com.example.app.MainActivity' },
    ]);
    expect(unverified[0]?.confidence).toBe('PROBABLE');
  });

  it('resolves Compose file lines through the package hash', async () => {
    const indexed = await indexLocalWorkspace('ws-1', '/src', dependencies());
    if (!indexed.ok) throw new Error('fixture failed to index');
    const candidates = resolveInIndex(
      indexed.value,
      [
        {
          kind: 'SOURCE_FILE_LINE',
          id: 'ev-2',
          fileName: 'MainActivity.kt',
          packageHash: javaAbsoluteHash(javaStringHash('com.example.app')),
          line: 12,
        },
      ],
      'VERIFIED',
    );
    expect(candidates[0]?.location.range).toEqual({ startLine: 12, endLine: 12 });
  });

  it('returns nothing without an index', () => {
    expect(resolveInIndex(undefined, [{ kind: 'TYPE_NAME', id: 'ev', qualifiedName: 'x' }])).toEqual([]);
    expect(resolveInIndex(undefined, [])).toEqual([]);
  });
});

describe('readIndexedSource', () => {
  it('reports current content and detects a stale file', async () => {
    const index = await indexLocalWorkspace('ws-1', '/src', dependencies());
    if (!index.ok) throw new Error('fixture failed to index');
    const file = index.value.files.find((entry) => entry.relativePath.endsWith('.kt')) as SourceFile;

    const current = readIndexedSource('/src', file, dependencies());
    expect(current.ok).toBe(true);
    if (current.ok) {
      expect(current.value.state).toBe('CURRENT');
      expect(current.value.language).toBe('KOTLIN');
      expect(current.value.text).toContain('class MainActivity');
    }

    const stale = readIndexedSource(
      '/src',
      file,
      dependencies({ readFile: () => new TextEncoder().encode('package com.example.app\nclass Other') }),
    );
    expect(stale.ok).toBe(true);
    if (stale.ok) expect(stale.value.state).toBe('STALE');

    const missing = readIndexedSource(
      '/src',
      file,
      dependencies({
        readFile: () => {
          throw new Error('ENOENT');
        },
      }),
    );
    expect(missing.ok).toBe(false);
    if (!missing.ok) expect(missing.error.code).toBe('SOURCE_FILE_UNREADABLE');
  });
});
