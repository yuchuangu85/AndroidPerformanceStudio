/**
 * Writes the release version into the desktop package manifest.
 *
 * electron-builder reads the version from package.json, and the artifact name
 * contract embeds it, so the version has to be in place before the build.
 * Failing on a malformed version here is better than a package named after a
 * typo.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const requested = process.argv[2];
if (requested === undefined || !/^[0-9]+\.[0-9]+\.[0-9]+$/.test(requested)) {
  console.error('usage: node scripts/set-version.mjs <major.minor.patch>');
  process.exit(1);
}

const manifestPath = join(dirname(fileURLToPath(import.meta.url)), '..', 'package.json');
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
manifest.version = requested;
writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
console.log('desktop version set to ' + requested);
