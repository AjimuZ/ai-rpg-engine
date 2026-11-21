// Writes the original sample artwork of the bundled adventure: one 96x96 PNG icon of a plain sword.
//
// Responsibility: produce the committed bytes of backend/src/main/resources/sample-adventure/assets/sword.png
// from geometry alone. Run: node tools/sample-art/make-sample-art.mjs [output-file]
//
// Always holds: the output depends on nothing but this file and Node's built-in zlib (no download, no
// dependency, no random number, no clock), so two runs write identical bytes. The picture is a flat vector-style
// shape set drawn by this script, so it is original work covered by the project's MIT license.
import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SIZE = 96;
const here = dirname(fileURLToPath(import.meta.url));
const target = resolve(process.argv[2] ?? resolve(here, '../../backend/src/main/resources/sample-adventure/assets/sword.png'));

/** RGBA pixel buffer, row-major, starting fully transparent. */
const pixels = new Uint8Array(SIZE * SIZE * 4);

function paint(x, y, [r, g, b, a = 255]) {
  const offset = (y * SIZE + x) * 4;
  pixels[offset] = r;
  pixels[offset + 1] = g;
  pixels[offset + 2] = b;
  pixels[offset + 3] = a;
}

/** Distance from a point to a segment, which gives every stroke a round, even width. */
function segmentDistance(px, py, ax, ay, bx, by) {
  const dx = bx - ax;
  const dy = by - ay;
  const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

const BACKGROUND = [38, 56, 74];
const BORDER = [96, 124, 148];
const BLADE = [214, 222, 230];
const BLADE_EDGE = [128, 142, 156];
const GUARD = [196, 150, 62];
const GRIP = [110, 70, 44];

for (let y = 0; y < SIZE; y++) {
  for (let x = 0; x < SIZE; x++) {
    const cx = x + 0.5;
    const cy = y + 0.5;
    const inside = Math.min(cx, cy, SIZE - cx, SIZE - cy) > 4;
    if (!inside) {
      continue; // transparent corner margin
    }
    const edge = Math.min(cx, cy, SIZE - cx, SIZE - cy) < 7;
    // A soft vertical shade keeps the flat background from reading as a placeholder block.
    const shade = Math.round((y / SIZE) * 14);
    paint(x, y, edge ? BORDER : BACKGROUND.map((c) => Math.min(255, c + shade)));
    // The sword lies on the diagonal from the lower left (grip) to the upper right (tip).
    const blade = segmentDistance(cx, cy, 36, 60, 76, 20);
    if (blade < 4.2) {
      paint(x, y, blade > 3 ? BLADE_EDGE : BLADE);
    }
    const guard = segmentDistance(cx, cy, 28, 36, 44, 52);
    if (guard < 3) {
      paint(x, y, GUARD);
    }
    const grip = segmentDistance(cx, cy, 30, 66, 22, 74);
    if (grip < 3.4) {
      paint(x, y, GRIP);
    }
    if (Math.hypot(cx - 19, cy - 77) < 4.4) {
      paint(x, y, GUARD);
    }
  }
}

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) {
    c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  }
  return c >>> 0;
});

function crc32(bytes) {
  let c = 0xffffffff;
  for (const byte of bytes) {
    c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const body = Buffer.concat([Buffer.from(type, 'latin1'), data]);
  const frame = Buffer.alloc(8 + data.length + 4);
  frame.writeUInt32BE(data.length, 0);
  body.copy(frame, 4);
  frame.writeUInt32BE(crc32(body), 8 + data.length);
  return frame;
}

const header = Buffer.alloc(13);
header.writeUInt32BE(SIZE, 0);
header.writeUInt32BE(SIZE, 4);
header.set([8, 6, 0, 0, 0], 8); // 8-bit RGBA, no interlace

// Each scanline is prefixed with filter type 0 (none).
const scanlines = Buffer.alloc(SIZE * (SIZE * 4 + 1));
for (let y = 0; y < SIZE; y++) {
  const row = y * (SIZE * 4 + 1);
  scanlines[row] = 0;
  Buffer.from(pixels.buffer, y * SIZE * 4, SIZE * 4).copy(scanlines, row + 1);
}

const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', header),
  chunk('IDAT', deflateSync(scanlines, { level: 9 })),
  chunk('IEND', Buffer.alloc(0)),
]);

mkdirSync(dirname(target), { recursive: true });
writeFileSync(target, png);
console.log(`wrote ${png.length} bytes to ${target}`);
