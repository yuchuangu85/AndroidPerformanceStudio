/**
 * Refuses to pack a bundle that is older than the sources it was built from.
 *
 * electron-builder copies whatever `out/` holds; it never builds it. Invoking it
 * on its own after a source change therefore produces a well-formed package
 * carrying the previous renderer, and the application opens a window with
 * nothing in it. Nothing downstream catches that: the bundle is present, the
 * asar parses, the packaged runtime assets are all there. The package is not
 * malformed, only old — which is what makes the blank window expensive to
 * diagnose.
 *
 * `electron-vite build` rewrites all three bundles, so a source that is newer
 * than any bundle means the build step was skipped. Running this as the
 * `beforePack` hook puts the failure before the package exists, whichever way
 * electron-builder was invoked.
 */
import { existsSync, readdirSync, statSync } from 'node:fs';
import { dirname, extname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const desktop = join(dirname(fileURLToPath(import.meta.url)), '..');

/** The outputs electron-builder copies into the application. */
const BUNDLES = ['out/main/index.js', 'out/preload/index.js', 'out/renderer/index.html'];

/** The extensions the build compiles. Anything else under src/ is not an input. */
const SOURCE_EXTENSIONS = new Set(['.ts', '.tsx', '.css', '.html', '.json']);

function sourceFiles(directory) {
  const found = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) found.push(...sourceFiles(path));
    else if (SOURCE_EXTENSIONS.has(extname(entry.name))) found.push(path);
  }
  return found;
}

/** The file with the largest modification time, and that time. */
function newest(files) {
  let found = null;
  for (const file of files) {
    const time = statSync(file).mtimeMs;
    if (found === null || time > found.time) found = { file, time };
  }
  return found;
}

/** The file with the smallest modification time, and that time. */
function oldest(files) {
  let found = null;
  for (const file of files) {
    const time = statSync(file).mtimeMs;
    if (found === null || time < found.time) found = { file, time };
  }
  return found;
}

function stamp(time) {
  return new Date(time).toISOString();
}

export default async function checkBuildFreshness() {
  const newestSource = newest([
    ...sourceFiles(join(desktop, 'src')),
    join(desktop, 'electron.vite.config.ts'),
  ]);
  if (newestSource === null) {
    throw new Error('no source files were found under ' + join(desktop, 'src'));
  }
  const present = BUNDLES.filter((bundle) => existsSync(join(desktop, bundle)));
  if (present.length !== BUNDLES.length) {
    const missing = BUNDLES.filter((bundle) => !present.includes(bundle));
    throw new Error(
      'the application has not been built: ' + missing.join(', ') + ' does not exist\n' +
      'build before packaging: pnpm --filter @aps/desktop build',
    );
  }
  const oldestBundle = oldest(BUNDLES.map((bundle) => join(desktop, bundle)));
  if (oldestBundle !== null && oldestBundle.time < newestSource.time) {
    throw new Error(
      'the built bundle is older than the sources it was built from\n' +
      '  newest source: ' + relative(desktop, newestSource.file) + ' (' + stamp(newestSource.time) + ')\n' +
      '  oldest bundle: ' + relative(desktop, oldestBundle.file) + ' (' + stamp(oldestBundle.time) + ')\n' +
      'build before packaging: pnpm --filter @aps/desktop build',
    );
  }
}

// Also runnable on its own, which is how the guard is exercised without waiting
// for a package to be produced: node scripts/check-build-freshness.mjs
if (process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await checkBuildFreshness();
  console.log('the built bundle is newer than the sources it was built from');
}
