import { copyFileSync, mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { indexSourceFile } from './indexer.js';
import { isIndexableSourcePath, javaAbsoluteHash, javaStringHash, sourceLanguage } from './language.js';
import { resolveEvidence, type SourceIndexView } from './resolver.js';
import type { SourceFile, SourceSnapshot, SourceSymbol } from './model.js';
import { ContentAddressedSourceCache, readSourceFile, sha256Text, walkSourceFiles } from './node.js';
import { SqliteSourceWorkspaceRepository } from './sqlite-repository.js';

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

const KOTLIN_SOURCE_WORKSPACE_FIXTURE = fileURLToPath(
  new URL('./fixtures/kotlin-source-workspace.db', import.meta.url),
);

const ELECTRON_SOURCE_WORKSPACE_FIXTURE_PATH = process.env['APS_ELECTRON_SOURCE_WORKSPACE_FIXTURE_PATH'];

/**
 * The fixture is written by Kotlin's SqliteSourceWorkspaceRepository, not by
 * node:sqlite. Regenerate it with:
 *   cd desktop-viewer/source-workspace && ./gradlew writeElectronInteropFixture \
 *     -PfixturePath=../../electron-viewer/packages/source-workspace/src/fixtures/kotlin-source-workspace.db
 */
describe('Kotlin-created source-workspace SQLite fixture', () => {
  it('opens Kotlin-written workspace, snapshot, candidate, and nullable-range records without reindexing', () => {
    const path = join(temporaryDirectory(), 'source-workspaces.db');
    copyFileSync(KOTLIN_SOURCE_WORKSPACE_FIXTURE, path);

    const raw = new DatabaseSync(path);
    try {
      const candidate = raw
        .prepare(
          'SELECT start_line, start_column, end_line, end_column, reasons, index_complete ' +
            'FROM resolution_candidate WHERE id = ?',
        )
        .get('kotlin-candidate') as Record<string, unknown>;
      expect(candidate).toEqual({
        start_line: 17,
        start_column: 4,
        end_line: 22,
        end_column: 19,
        reasons: '["Qualified type matched","Build identity verified"]',
        index_complete: 1,
      });
    } finally {
      raw.close();
    }

    const repository = new SqliteSourceWorkspaceRepository(path);
    try {
      expect(repository.workspace('kotlin-workspace')).toEqual({
        id: 'kotlin-workspace',
        displayName: 'Kotlin-written source workspace',
        config: {
          kind: 'GITHUB',
          owner: 'android',
          repository: 'performance-studio',
          ref: 'refs/tags/v1.0.0',
          credentialKey: 'kotlin-fixture-credential',
        },
        activeSnapshotId: 'kotlin-snapshot',
        phase: 'READY',
        progress: 1,
        message: 'Written by the Kotlin fixture generator',
        allowAiSourceUpload: true,
      });
      expect(repository.snapshot('kotlin-snapshot')).toEqual({
        id: 'kotlin-snapshot',
        workspaceId: 'kotlin-workspace',
        immutableRevision: 'a'.repeat(40),
        dirtyContentDigest: 'dirty-content-digest',
        manifestHash: 'b'.repeat(64),
        createdAtEpochMillis: Date.parse('2026-09-14T00:00:00Z'),
        indexVersion: 7,
        indexComplete: true,
      });
      expect(repository.files('kotlin-snapshot')).toEqual([
        {
          snapshotId: 'kotlin-snapshot',
          relativePath: 'src/main/kotlin/com/example/Renderer.kt',
          language: 'KOTLIN',
          contentHash: 'c'.repeat(64),
          sizeBytes: 1234,
        },
      ]);
      expect(repository.symbols('kotlin-snapshot')).toEqual([
        {
          snapshotId: 'kotlin-snapshot',
          relativePath: 'src/main/kotlin/com/example/Renderer.kt',
          kind: 'TYPE',
          qualifiedName: 'com.example.Renderer',
          startLine: 3,
          endLine: 42,
        },
        {
          snapshotId: 'kotlin-snapshot',
          relativePath: 'src/main/kotlin/com/example/Renderer.kt',
          kind: 'FUNCTION',
          qualifiedName: 'com.example.Renderer.render',
          signature: 'frame: Frame',
          startLine: 17,
          endLine: 22,
        },
      ]);
      expect(repository.candidate('kotlin-candidate')).toEqual({
        id: 'kotlin-candidate',
        evidenceId: 'kotlin-evidence',
        location: {
          workspaceId: 'kotlin-workspace',
          snapshotId: 'kotlin-snapshot',
          relativePath: 'src/main/kotlin/com/example/Renderer.kt',
          // The TypeScript model deliberately supports only Kotlin's line range.
          range: { startLine: 17, endLine: 22 },
          contentHash: 'c'.repeat(64),
        },
        confidence: 'EXACT',
        reasons: ['Qualified type matched', 'Build identity verified'],
        indexVersion: 7,
        indexComplete: true,
      });
      expect(repository.candidate('kotlin-null-range-candidate')).toEqual({
        id: 'kotlin-null-range-candidate',
        evidenceId: 'kotlin-null-range-evidence',
        location: {
          workspaceId: 'kotlin-workspace',
          snapshotId: 'kotlin-snapshot',
          relativePath: 'src/main/kotlin/com/example/Renderer.kt',
          contentHash: 'c'.repeat(64),
        },
        confidence: 'PROBABLE',
        reasons: ['No source range was available'],
        indexVersion: 7,
        indexComplete: true,
      });
    } finally {
      repository.close();
    }
  });
});

/**
 * The fixture is written by Electron's SqliteSourceWorkspaceRepository, not by
 * node:sqlite. Regenerate it with:
 *   APS_ELECTRON_SOURCE_WORKSPACE_FIXTURE_PATH=../desktop-viewer/source-workspace/src/test/resources/electron-source-workspace.db \
 *     corepack pnpm@12.3.4 --dir electron-viewer exec vitest run \
 *       packages/source-workspace/src/source-workspace.test.ts -t "Electron-created source-workspace SQLite fixture"
 */
describe('Electron-created source-workspace SQLite fixture', () => {
  it('writes provider metadata, source indexes, and nullable source ranges for Kotlin', () => {
    const path = electronSourceWorkspaceFixturePath();
    writeElectronSourceWorkspaceFixture(path);

    const raw = new DatabaseSync(path);
    try {
      expect(raw.prepare(
        'SELECT provider_kind, github_owner, github_repository, provider_ref, credential_key, active_snapshot_id, phase, progress, message, allow_ai_source_upload FROM source_workspace WHERE id = ?',
      ).get('electron-workspace')).toEqual({
        provider_kind: 'GITHUB', github_owner: 'android', github_repository: 'performance-studio',
        provider_ref: 'refs/tags/electron-v1', credential_key: 'electron-fixture-credential',
        active_snapshot_id: 'electron-snapshot', phase: 'READY', progress: 1,
        message: 'Written by the Electron fixture generator', allow_ai_source_upload: 1,
      });
      expect(raw.prepare(
        'SELECT immutable_revision, dirty_digest, manifest_hash, created_at, index_version, index_complete FROM source_snapshot WHERE id = ?',
      ).get('electron-snapshot')).toEqual({
        immutable_revision: 'd'.repeat(40), dirty_digest: 'electron-dirty-content-digest', manifest_hash: 'e'.repeat(64),
        created_at: '2026-09-14T02:00:00.000Z', index_version: 11, index_complete: 1,
      });
      expect(raw.prepare(
        'SELECT id, start_line, start_column, end_line, end_column, confidence, reasons, index_complete FROM resolution_candidate WHERE workspace_id = ? ORDER BY id',
      ).all('electron-workspace')).toEqual([
        { id: 'electron-candidate', start_line: 31, start_column: 1, end_line: 37, end_column: 1, confidence: 'EXACT', reasons: '["Qualified type matched","Build identity verified"]', index_complete: 1 },
        { id: 'electron-null-range-candidate', start_line: null, start_column: null, end_line: null, end_column: null, confidence: 'PROBABLE', reasons: '["No source range was available"]', index_complete: 0 },
      ]);
    } finally { raw.close(); }

    const repository = new SqliteSourceWorkspaceRepository(path);
    try {
      expect(repository.workspace('electron-workspace')).toEqual({
        id: 'electron-workspace', displayName: 'Electron-written source workspace',
        config: { kind: 'GITHUB', owner: 'android', repository: 'performance-studio', ref: 'refs/tags/electron-v1', credentialKey: 'electron-fixture-credential' },
        activeSnapshotId: 'electron-snapshot', phase: 'READY', progress: 1,
        message: 'Written by the Electron fixture generator', allowAiSourceUpload: true,
      });
      expect(repository.snapshot('electron-snapshot')).toEqual({
        id: 'electron-snapshot', workspaceId: 'electron-workspace', immutableRevision: 'd'.repeat(40),
        dirtyContentDigest: 'electron-dirty-content-digest', manifestHash: 'e'.repeat(64),
        createdAtEpochMillis: 1_789_351_200_000, indexVersion: 11, indexComplete: true,
      });
      expect(repository.files('electron-snapshot')).toEqual([{
        snapshotId: 'electron-snapshot', relativePath: 'src/main/kotlin/com/example/Renderer.kt', language: 'KOTLIN', contentHash: 'f'.repeat(64), sizeBytes: 4321,
      }]);
      expect(repository.symbols('electron-snapshot')).toEqual([
        { snapshotId: 'electron-snapshot', relativePath: 'src/main/kotlin/com/example/Renderer.kt', kind: 'TYPE', qualifiedName: 'com.example.Renderer', startLine: 3, endLine: 48 },
        { snapshotId: 'electron-snapshot', relativePath: 'src/main/kotlin/com/example/Renderer.kt', kind: 'FUNCTION', qualifiedName: 'com.example.Renderer.render', signature: 'frame: Frame', startLine: 31, endLine: 37 },
      ]);
      expect(repository.candidate('electron-candidate')).toEqual({
        id: 'electron-candidate', evidenceId: 'electron-evidence',
        location: { workspaceId: 'electron-workspace', snapshotId: 'electron-snapshot', relativePath: 'src/main/kotlin/com/example/Renderer.kt', range: { startLine: 31, endLine: 37 }, contentHash: 'f'.repeat(64) },
        confidence: 'EXACT', reasons: ['Qualified type matched', 'Build identity verified'], indexVersion: 11, indexComplete: true,
      });
      expect(repository.candidate('electron-null-range-candidate')).toEqual({
        id: 'electron-null-range-candidate', evidenceId: 'electron-null-range-evidence',
        location: { workspaceId: 'electron-workspace', snapshotId: 'electron-snapshot', relativePath: 'src/main/kotlin/com/example/Fallback.kt', contentHash: 'f'.repeat(64) },
        confidence: 'PROBABLE', reasons: ['No source range was available'], indexVersion: 11, indexComplete: false,
      });
    } finally { repository.close(); }
    checkpointStandaloneSqliteFixture(path);
  });
});

function electronSourceWorkspaceFixturePath(): string {
  if (ELECTRON_SOURCE_WORKSPACE_FIXTURE_PATH !== undefined && ELECTRON_SOURCE_WORKSPACE_FIXTURE_PATH.trim().length > 0) return ELECTRON_SOURCE_WORKSPACE_FIXTURE_PATH;
  return join(temporaryDirectory(), 'electron-source-workspace.db');
}

function writeElectronSourceWorkspaceFixture(path: string): void {
  for (const suffix of ['', '-wal', '-shm']) rmSync(path + suffix, { force: true });
  const repository = new SqliteSourceWorkspaceRepository(path);
  try {
    repository.saveWorkspace({
      id: 'electron-workspace', displayName: 'Electron-written source workspace',
      config: { kind: 'GITHUB', owner: 'android', repository: 'performance-studio', ref: 'refs/tags/electron-v1', credentialKey: 'electron-fixture-credential' },
      activeSnapshotId: 'electron-snapshot', phase: 'READY', progress: 1,
      message: 'Written by the Electron fixture generator', allowAiSourceUpload: true,
    });
    repository.saveSnapshot({
      id: 'electron-snapshot', workspaceId: 'electron-workspace', immutableRevision: 'd'.repeat(40),
      dirtyContentDigest: 'electron-dirty-content-digest', manifestHash: 'e'.repeat(64),
      createdAtEpochMillis: 1_789_351_200_000, indexVersion: 11, indexComplete: true,
    }, [{
      snapshotId: 'electron-snapshot', relativePath: 'src/main/kotlin/com/example/Renderer.kt', language: 'KOTLIN', contentHash: 'f'.repeat(64), sizeBytes: 4321,
    }], [
      { snapshotId: 'electron-snapshot', relativePath: 'src/main/kotlin/com/example/Renderer.kt', kind: 'TYPE', qualifiedName: 'com.example.Renderer', startLine: 3, endLine: 48 },
      { snapshotId: 'electron-snapshot', relativePath: 'src/main/kotlin/com/example/Renderer.kt', kind: 'FUNCTION', qualifiedName: 'com.example.Renderer.render', signature: 'frame: Frame', startLine: 31, endLine: 37 },
    ]);
    repository.saveCandidates([
      {
        id: 'electron-candidate', evidenceId: 'electron-evidence',
        location: { workspaceId: 'electron-workspace', snapshotId: 'electron-snapshot', relativePath: 'src/main/kotlin/com/example/Renderer.kt', range: { startLine: 31, endLine: 37 }, contentHash: 'f'.repeat(64) },
        confidence: 'EXACT', reasons: ['Qualified type matched', 'Build identity verified'], indexVersion: 11, indexComplete: true,
      },
      {
        id: 'electron-null-range-candidate', evidenceId: 'electron-null-range-evidence',
        location: { workspaceId: 'electron-workspace', snapshotId: 'electron-snapshot', relativePath: 'src/main/kotlin/com/example/Fallback.kt', contentHash: 'f'.repeat(64) },
        confidence: 'PROBABLE', reasons: ['No source range was available'], indexVersion: 11, indexComplete: false,
      },
    ]);
  } finally { repository.close(); }
}

function checkpointStandaloneSqliteFixture(path: string): void {
  const database = new DatabaseSync(path);
  try {
    database.exec('PRAGMA wal_checkpoint(TRUNCATE)');
    database.exec('PRAGMA journal_mode=DELETE');
  } finally { database.close(); }
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
