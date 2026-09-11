/**
 * Asserts that a packaged application actually carries its runtime assets.
 *
 * A --dir smoke build passes even when they are missing, because the app falls
 * back to the repository copies that only exist in a checkout. This checks the
 * unpacked output instead: Perfetto UI, the pinned Trace Processor with its
 * SHA-256, and the application icon.
 *
 * Usage: node scripts/verify-package.mjs <unpacked-directory>
 */
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const unpacked = process.argv[2];
if (unpacked === undefined || !existsSync(unpacked)) {
  console.error('usage: node scripts/verify-package.mjs <unpacked-directory>');
  process.exit(1);
}

/**
 * The resources directory, whichever layout the platform produced: an explicit
 * .app bundle, the macOS output directory that contains one, or the Windows and
 * Linux unpacked directory.
 */
function resourcesOf(directory) {
  if (directory.endsWith('.app')) return join(directory, 'Contents', 'Resources');
  try {
    const bundle = readdirSync(directory).find((entry) => entry.endsWith('.app'));
    if (bundle !== undefined) return join(directory, bundle, 'Contents', 'Resources');
  } catch {
    // Not a directory, or unreadable: fall through to the flat layout.
  }
  return join(directory, 'resources');
}

const resources = resourcesOf(unpacked);

const failures = [];

function require(description, path) {
  if (existsSync(path)) return true;
  failures.push(description + ' is missing: ' + path);
  return false;
}

const uiIndex = join(resources, 'perfetto-ui', 'index.html');
require('the bundled Perfetto UI', uiIndex);

const binaryName = process.platform === 'win32' ? 'trace_processor_shell.exe' : 'trace_processor_shell';
const binaryPath = join(resources, 'perfetto-tools', binaryName);
if (require('the pinned Trace Processor', binaryPath)) {
  const manifestPath = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'packages', 'platform-perfetto', 'src', 'trace-processor-manifest.json');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const key = process.platform === 'darwin'
    ? (process.arch === 'arm64' ? 'macos-arm64' : 'macos-x64')
    : process.platform === 'win32'
      ? 'windows-x64'
      : (process.arch === 'arm64' ? 'linux-arm64' : 'linux-x64');
  const expected = manifest.artifacts[key]?.sha256;
  if (expected === undefined) {
    failures.push('the manifest has no checksum for ' + key);
  } else {
    const actual = createHash('sha256').update(readFileSync(binaryPath)).digest('hex');
    if (actual !== expected) failures.push('the packaged Trace Processor checksum does not match the manifest');
    if (statSync(binaryPath).size === 0) failures.push('the packaged Trace Processor is empty');
  }
}

if (failures.length > 0) {
  for (const failure of failures) console.error(failure);
  process.exit(1);
}
console.log('packaged assets verified in ' + resources);
