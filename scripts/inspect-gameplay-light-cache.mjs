import fs from 'node:fs';
import zlib from 'node:zlib';
import crypto from 'node:crypto';

const [path, ...rawPoints] = process.argv.slice(2);
if (!path || rawPoints.length === 0) {
  console.error('Usage: node scripts/inspect-gameplay-light-cache.mjs <cache.bin.gz> <x,y,z> [...]');
  process.exit(2);
}
const points = rawPoints.map(p => p.split(',').map(Number));
if (points.some(p => p.length !== 3 || p.some(n => !Number.isSafeInteger(n)))) {
  throw new Error('Every sample point must be three integer coordinates: x,y,z');
}
const data = zlib.gunzipSync(fs.readFileSync(path));
let offset = 0;
const int = () => { const value = data.readInt32BE(offset); offset += 4; return value; };
const long = () => { const value = data.readBigInt64BE(offset); offset += 8; return value; };
const char = () => { const value = data.readUInt16BE(offset); offset += 2; return value; };
const magic = int(), format = int(), solver = int(), rulesHash = int(), revision = long();
if (magic !== 0x544c5247 || format !== 1 || solver !== 1 || revision < 0n) {
  throw new Error('Unsupported gameplay-light cache header');
}
const sourceCount = int();
if (sourceCount < 0 || sourceCount > 1_000_000) throw new Error('Invalid source count');
const sources = new Map();
for (let i = 0; i < sourceCount; i++) {
  const x = int(), y = int(), z = int(), value = char();
  sources.set(`${x},${y},${z}`, value);
}
const sectionCount = int();
if (sectionCount < 0 || sectionCount > 65_536) throw new Error('Invalid section count');
const sections = new Map();
for (let i = 0; i < sectionCount; i++) {
  const x = int(), y = int(), z = int();
  if (offset + 8192 > data.length) throw new Error('Truncated section');
  sections.set(`${x},${y},${z}`, data.subarray(offset, offset + 8192));
  offset += 8192;
}
if (offset !== data.length) throw new Error('Trailing cache bytes');
const sectionKey = ([x,y,z]) => `${Math.floor(x / 16)},${Math.floor(y / 16)},${Math.floor(z / 16)}`;
const sample = ([x,y,z]) => {
  const bytes = sections.get(sectionKey([x,y,z]));
  if (!bytes) return 0;
  const index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
  return bytes.readUInt16BE(index * 2);
};
const result = {
  magic: `0x${magic.toString(16)}`, format, solver, rulesHash,
  revision: revision.toString(), sourceCount, sectionCount,
  sources: Object.fromEntries(points.map(p => [p.join(','), `0x${(sources.get(p.join(',')) ?? 0).toString(16)}`])),
  samples: Object.fromEntries(points.map(p => [p.join(','), `0x${sample(p).toString(16)}`])),
  sectionHashes: Object.fromEntries([...new Set(points.map(sectionKey))].map(key => [key, sections.has(key) ? crypto.createHash('sha256').update(sections.get(key)).digest('hex') : null])),
  bytesTrailing: data.length - offset,
};
console.log(JSON.stringify(result, null, 2));
