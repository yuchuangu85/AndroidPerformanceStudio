/**
 * Assembles a Windows .ico from the sized PNGs beside it.
 *
 * An ICO is a small directory followed by the images; Vista and later accept
 * PNG-compressed entries, so no BMP encoding is needed. Generating it here keeps
 * the build independent of any converter download.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const sizes = [16, 32, 48, 64, 128, 256];
const images = sizes.map((size) => ({
  size,
  bytes: readFileSync(join(here, 'icon-' + String(size) + '.png')),
}));

const header = Buffer.alloc(6);
header.writeUInt16LE(0, 0);
header.writeUInt16LE(1, 2);
header.writeUInt16LE(images.length, 4);

const directory = Buffer.alloc(16 * images.length);
let offset = header.length + directory.length;
images.forEach((image, index) => {
  const entry = 16 * index;
  // 256 is written as 0 in the directory, which is what the format specifies.
  directory.writeUInt8(image.size >= 256 ? 0 : image.size, entry);
  directory.writeUInt8(image.size >= 256 ? 0 : image.size, entry + 1);
  directory.writeUInt8(0, entry + 2);
  directory.writeUInt8(0, entry + 3);
  directory.writeUInt16LE(1, entry + 4);
  directory.writeUInt16LE(32, entry + 6);
  directory.writeUInt32LE(image.bytes.length, entry + 8);
  directory.writeUInt32LE(offset, entry + 12);
  offset += image.bytes.length;
});

writeFileSync(join(here, 'icon.ico'), Buffer.concat([header, directory, ...images.map((image) => image.bytes)]));
console.log('icon.ico written from ' + String(images.length) + ' sizes');
