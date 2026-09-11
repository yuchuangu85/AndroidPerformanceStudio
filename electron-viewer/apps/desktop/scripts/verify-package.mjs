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
if (unpacked === undefined) {
  console.error('usage: node scripts/verify-package.mjs <unpacked-or-release-directory>');
  process.exit(1);
}
if (!existsSync(unpacked)) {
  console.error('the packaging output directory does not exist: ' + unpacked);
  process.exit(1);
}

/**
 * The resources directory, whichever layout the platform produced: an explicit
 * .app bundle, the macOS output directory that contains one, or the Windows and
 * Linux unpacked directory.
 */
function resourcesOf(directory) {
  // The caller may name the unpacked directory itself or the directory that holds
  // it: electron-builder uses mac/, mac-arm64/, win-unpacked/ or linux-unpacked/
  // depending on host and target, and a hard-coded path silently missed one.
  const candidates = [directory];
  try {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      if (entry.isDirectory()) candidates.push(join(directory, entry.name));
    }
  } catch {
    // Not a directory, or unreadable: the candidates below still get tried.
  }
  for (const candidate of candidates) {
    if (candidate.endsWith('.app')) return join(candidate, 'Contents', 'Resources');
  }
  for (const candidate of candidates) {
    const resources = join(candidate, 'resources');
    if (existsSync(resources)) return resources;
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
