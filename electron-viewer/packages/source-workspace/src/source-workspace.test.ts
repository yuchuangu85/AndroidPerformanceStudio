import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { indexSourceFile } from './indexer.js';
import { isIndexableSourcePath, javaAbsoluteHash, javaStringHash, sourceLanguage } from './language.js';
import { resolveEvidence, type SourceIndexView } from './resolver.js';
import type { SourceFile, SourceSnapshot, SourceSymbol } from './model.js';
import { ContentAddressedSourceCache, readSourceFile, sha256Text, walkSourceFiles } from './node.js';

const directories: string[] = [];

function temporaryDirectory(): string {
  const directory = mkdtempSync(join(tmpdir(), 'aps-source-'));
  directories.push(directory);
  return directory;
}

afterEach(() => {
  for (const directory of directories.splice(0)) rmSync(directory, { recursive: true, force: true });
});

const SNAPSHOT: SourceSnapshot = {
  id: 'snap-1',
  workspaceId: 'ws-1',
  immutableRevision: 'abc123',
  manifestHash: 'manifest',
  createdAtEpochMillis: 1_700_000_000_000,
  indexVersion: 3,
  indexComplete: true,
};

function file(relativePath: string, content = ''): SourceFile {
  return {
    snapshotId: SNAPSHOT.id,
    relativePath,
    language: sourceLanguage(relativePath),
    contentHash: sha256Text(content),
    sizeBytes: content.length,
  };
}

function indexOf(entries: readonly { readonly file: SourceFile; readonly content: string }[]): SourceIndexView {
  const symbols: SourceSymbol[] = [];
  for (const entry of entries) {
    symbols.push(...indexSourceFile(SNAPSHOT.id, entry.file.relativePath, entry.content));
  }
  return {
    snapshot: (snapshotId) => (snapshotId === SNAPSHOT.id ? SNAPSHOT : undefined),
    files: () => entries.map((entry) => entry.file),
    symbols: () => symbols,
  };
}

describe('language detection', () => {
  it('maps extensions and rejects non-source paths', () => {
    expect(sourceLanguage('src/main/kotlin/Foo.kt')).toBe('KOTLIN');
    expect(sourceLanguage('build.gradle.kts')).toBe('KOTLIN');
    expect(sourceLanguage('app/src/main/java/Foo.java')).toBe('JAVA');
    expect(sourceLanguage('res/layout/activity_main.xml')).toBe('XML');
    expect(sourceLanguage('jni/native.c')).toBe('C');
    expect(sourceLanguage('jni/native.cpp')).toBe('CPP');
    expect(sourceLanguage('README.md')).toBe('OTHER');
    expect(isIndexableSourcePath('app/src/main/java/Foo.java')).toBe(true);
    expect(isIndexableSourcePath('app/build/tmp/Foo.java')).toBe(false);
    expect(isIndexableSourcePath('node_modules/pkg/index.js')).toBe(false);
    expect(isIndexableSourcePath('.git/config')).toBe(false);
    expect(isIndexableSourcePath('README.md')).toBe(false);
  });

  it('hashes strings exactly like the JVM', () => {
    expect(javaStringHash('')).toBe(0);
    expect(javaStringHash('a')).toBe(97);
    expect(javaStringHash('com.example')).toBe('com.example'.split('').reduce((hash, character) => (Math.imul(31, hash) + character.charCodeAt(0)) | 0, 0));
    expect(javaAbsoluteHash(-5)).toBe(5);
    expect(javaAbsoluteHash(-2147483648)).toBe(-2147483648);
  });
});

describe('structural indexing', () => {
  it('indexes Kotlin packages, types, and functions with their line numbers', () => {
    const content = [
      'package com.example.app',
      '',
      'class MainActivity {',
      '    fun onCreate(savedInstanceState: Bundle?) {',
      '    }',
      '}',
      '',
      'interface Renderer',
    ].join('\n');
    const symbols = indexSourceFile('snap-1', 'MainActivity.kt', content);
    expect(symbols.map((symbol) => [symbol.kind, symbol.qualifiedName, symbol.startLine])).toEqual([
      ['TYPE', 'com.example.app.MainActivity', 3],
      ['TYPE', 'com.example.app.Renderer', 8],
      ['FUNCTION', 'com.example.app.onCreate', 4],
    ]);
    expect(symbols[2]?.signature).toBe('savedInstanceState: Bundle?');
    expect(symbols.every((symbol) => symbol.snapshotId === 'snap-1')).toBe(true);
  });

  it('indexes Java methods, skipping keywords', () => {
    const content = [
      'package com.example.app;',
      'public class Foo {',
      '    public void render(int width) {',
      '        if (width > 0) { return; }',
      '    }',
      '    private static final int bar() { return 1; }',
      '}',
    ].join('\n');
    const symbols = indexSourceFile('snap-1', 'Foo.java', content);
    const methods = symbols.filter((symbol) => symbol.kind === 'METHOD').map((symbol) => symbol.qualifiedName);
    expect(methods).toContain('com.example.app.render');
    expect(methods).not.toContain('com.example.app.if');
    expect(symbols.filter((symbol) => symbol.kind === 'TYPE').map((symbol) => symbol.qualifiedName)).toEqual([
      'com.example.app.Foo',
    ]);
  });

  it('indexes XML resources and C functions', () => {
    // Resources are indexed where they are referenced, i.e. in layout files.
    const xml = [
      '<LinearLayout',
      '    android:id="@+id/root"',
      '    android:text="@string/app_name"',
      '    android:background="@color/primary">',
      '</LinearLayout>',
    ].join('\n');
    const resources = indexSourceFile('snap-1', 'res/layout/activity_main.xml', xml);
    expect(resources.map((symbol) => symbol.qualifiedName)).toEqual(['id/root', 'string/app_name', 'color/primary']);

    const native = ['#include <jni.h>', '', 'static jint JNI_OnLoad(JavaVM* vm, void* reserved) {', '  return JNI_VERSION_1_6;', '}'].join('\n');
    const nativeSymbols = indexSourceFile('snap-1', 'jni/native.c', native);
    expect(nativeSymbols).toHaveLength(1);
    expect(nativeSymbols[0]?.kind).toBe('NATIVE_SYMBOL');
    expect(nativeSymbols[0]?.qualifiedName).toBe('JNI_OnLoad');
    // The pattern's leading character class spans newlines, so the match starts
    // at the blank line above the function. Inherited from the Kotlin indexer.
    expect(nativeSymbols[0]?.startLine).toBe(2);
  });

  it('returns nothing for other languages', () => {
    expect(indexSourceFile('snap-1', 'README.md', '# hello')).toEqual([]);
  });
});

describe('evidence resolution', () => {
  const activity = file('app/src/main/kotlin/MainActivity.kt');
  const activityContent = [
    'package com.example.app',
    'class MainActivity {',
    '    fun renderFrame(width: Int) {',
    '    }',
    '}',
  ].join('\n');
  const renderer = file('app/src/main/kotlin/Renderer.kt');
  const rendererContent = ['package com.example.app.ui', 'class Renderer {', '    fun draw(canvas: Canvas) {', '    }', '}'].join('\n');
  const layout = file('app/src/main/res/layout/activity_main.xml');
  const layoutContent = [
    '<LinearLayout',
    '    android:text="@string/app_name">',
    '</LinearLayout>',
  ].join('\n');
  const index = indexOf([
    { file: activity, content: activityContent },
    { file: renderer, content: rendererContent },
    { file: layout, content: layoutContent },
  ]);

  it('resolves a qualified type name exactly', () => {
    const candidates = resolveEvidence(
      index,
      [SNAPSHOT.id],
      [{ kind: 'TYPE_NAME', id: 'ev-1', qualifiedName: 'com.example.app.MainActivity' }],
      'VERIFIED',
    );
    expect(candidates).toHaveLength(1);
    expect(candidates[0]?.location.relativePath).toBe('app/src/main/kotlin/MainActivity.kt');
    expect(candidates[0]?.confidence).toBe('EXACT');
    expect(candidates[0]?.reasons).toEqual(['Qualified type matched']);
    expect(candidates[0]?.location.range).toEqual({ startLine: 2, endLine: 2 });
  });

  it('downgrades an exact match when the build identity is unverified', () => {
    const candidates = resolveEvidence(index, [SNAPSHOT.id], [
      { kind: 'TYPE_NAME', id: 'ev-1', qualifiedName: 'com.example.app.MainActivity' },
    ]);
    expect(candidates[0]?.confidence).toBe('PROBABLE');
    expect(candidates[0]?.reasons).toContain('Build identity not verified');
  });

  it('resolves managed symbols by method name and class file', () => {
    const exact = resolveEvidence(
      index,
      [SNAPSHOT.id],
      [{ kind: 'MANAGED_SYMBOL', id: 'ev-2', className: 'com.example.app.MainActivity', methodName: 'renderFrame' }],
      'VERIFIED',
    );
    expect(exact).toHaveLength(1);
    expect(exact[0]?.confidence).toBe('EXACT');
    expect(exact[0]?.location.range).toEqual({ startLine: 3, endLine: 3 });

    // A different class name still matches by method name, but only probably.
    const probable = resolveEvidence(
      index,
      [SNAPSHOT.id],
      [{ kind: 'MANAGED_SYMBOL', id: 'ev-3', className: 'com.other.Thing', methodName: 'renderFrame' }],
      'VERIFIED',
    );
    expect(probable[0]?.confidence).toBe('PROBABLE');
    // The file name does not end with the evidence class, so the match is weaker.
    expect(probable[0]?.reasons).toEqual(['Method name matched']);
  });

  it('resolves Android resources and Compose file lines', () => {
    const resources = resolveEvidence(
      index,
      [SNAPSHOT.id],
      [{ kind: 'ANDROID_RESOURCE', id: 'ev-4', resourceType: 'string', resourceName: 'app_name' }],
      'VERIFIED',
    );
    expect(resources[0]?.location.relativePath).toBe('app/src/main/res/layout/activity_main.xml');
    expect(resources[0]?.confidence).toBe('EXACT');

    const compose = resolveEvidence(
      index,
      [SNAPSHOT.id],
      [
        {
          kind: 'SOURCE_FILE_LINE',
          id: 'ev-5',
          fileName: 'MainActivity.kt',
          packageHash: javaAbsoluteHash(javaStringHash('com.example.app')),
          line: 42,
        },
      ],
      'VERIFIED',
    );
    expect(compose).toHaveLength(1);
    expect(compose[0]?.location.range).toEqual({ startLine: 42, endLine: 42 });

    const wrongHash = resolveEvidence(
      index,
      [SNAPSHOT.id],
      [{ kind: 'SOURCE_FILE_LINE', id: 'ev-6', fileName: 'MainActivity.kt', packageHash: 12345, line: 1 }],
      'VERIFIED',
    );
    expect(wrongHash).toEqual([]);
  });

  it('prefers a symbolizer source path and falls back to the symbol name', () => {
    const byPath = resolveEvidence(
      index,
      [SNAPSHOT.id],
      [
        {
          kind: 'NATIVE_SYMBOL',
          id: 'ev-7',
          symbolName: 'JNI_OnLoad',
          sourcePath: '/app/src/main/kotlin/Renderer.kt',
          sourceLine: 12,
        },
      ],
      'VERIFIED',
    );
    expect(byPath[0]?.location.relativePath).toBe('app/src/main/kotlin/Renderer.kt');
    expect(byPath[0]?.confidence).toBe('EXACT');

    const byName = resolveEvidence(
      index,
      [SNAPSHOT.id],
      [{ kind: 'NATIVE_SYMBOL', id: 'ev-8', symbolName: 'JNI_OnLoad' }],
      'VERIFIED',
    );
    expect(byName).toEqual([]);
  });

  it('deduplicates candidates and ignores unknown snapshots', () => {
    const evidence = { kind: 'TYPE_NAME' as const, id: 'ev-9', qualifiedName: 'com.example.app.MainActivity' };
    const candidates = resolveEvidence(index, [SNAPSHOT.id, SNAPSHOT.id], [evidence, evidence], 'VERIFIED');
    expect(candidates).toHaveLength(1);
    expect(resolveEvidence(index, ['missing'], [evidence], 'VERIFIED')).toEqual([]);
  });
});

describe('node-side walking and caching', () => {
  it('walks source files, skipping ignored directories and other extensions', () => {
    const root = temporaryDirectory();
    mkdirSync(join(root, 'src'), { recursive: true });
    mkdirSync(join(root, 'build', 'tmp'), { recursive: true });
    mkdirSync(join(root, 'node_modules', 'pkg'), { recursive: true });
    writeFileSync(join(root, 'src', 'Main.kt'), 'package a\nclass Main\n');
    writeFileSync(join(root, 'build', 'tmp', 'Generated.java'), 'class Generated {}');
    writeFileSync(join(root, 'node_modules', 'pkg', 'index.js'), 'module.exports = 1');
    writeFileSync(join(root, 'README.md'), '# readme');
    const files = walkSourceFiles(root);
    expect(files.map((entry) => entry.relativePath)).toEqual(['src/Main.kt']);
    expect(files[0]?.sizeBytes).toBeGreaterThan(0);
  });

  it('refuses to read outside the root', () => {
    const root = temporaryDirectory();
    writeFileSync(join(root, 'Main.kt'), 'class Main');
    expect(new TextDecoder().decode(readSourceFile(root, 'Main.kt'))).toBe('class Main');
    expect(() => readSourceFile(root, '../escape.kt')).toThrow(/escapes workspace/);
  });

  it('stores content by hash and detects tampering', () => {
    const root = temporaryDirectory();
    const cache = new ContentAddressedSourceCache(join(root, 'cache'));
    const content = new TextEncoder().encode('class Main');
    const hash = cache.put(content);
    expect(hash).toBe(sha256Text('class Main'));
    expect(cache.contains(hash)).toBe(true);
    expect(new TextDecoder().decode(cache.read(hash))).toBe('class Main');
    // Putting the same content twice is a no-op.
    expect(cache.put(content)).toBe(hash);
    expect(cache.contains('not-a-hash')).toBe(false);
    expect(() => cache.read('not-a-hash')).toThrow(/Invalid cache hash/);

    writeFileSync(cache.pathFor(hash), 'tampered');
    expect(() => cache.read(hash)).toThrow(/hash mismatch/);
  });
});
