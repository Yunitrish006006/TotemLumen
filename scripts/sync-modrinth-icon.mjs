import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const mode = process.argv[2];
if (!['--check', '--upload'].includes(mode) || process.argv.length !== 3) {
  throw new Error('Usage: node scripts/sync-modrinth-icon.mjs --check|--upload');
}

const iconPath = 'src/main/resources/assets/totem-lumen/icon.png';
const mod = JSON.parse(readFileSync('src/main/resources/fabric.mod.json', 'utf8'));
const icon = readFileSync(iconPath);
const pngSignature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
if (mod.id !== 'totem-lumen' || mod.icon !== 'assets/totem-lumen/icon.png'
    || icon.length > 256 * 1024 || !icon.subarray(0, 8).equals(pngSignature)) {
  throw new Error('Totem Lumen icon metadata or PNG is invalid');
}
const width = icon.readUInt32BE(16);
const height = icon.readUInt32BE(20);
if (!width || width !== height) throw new Error('Totem Lumen icon must be square');

const details = {
  icon: mod.icon,
  width,
  height,
  bytes: icon.length,
  sha256: createHash('sha256').update(icon).digest('hex'),
};
if (mode === '--check') {
  console.log(JSON.stringify(details, null, 2));
  process.exit(0);
}

if (process.env.GITHUB_REF !== 'refs/heads/main'
    || process.env.GITHUB_REPOSITORY !== 'Yunitrish006006/TotemLumen') {
  throw new Error('Modrinth icon upload requires the owning repository main branch');
}
const token = process.env.MODRINTH_TOKEN;
const projectId = process.env.MODRINTH_PROJECT_ID;
if (!token || projectId !== 'DcB192se') {
  throw new Error('Expected Modrinth token and Totem Lumen project ID');
}

const base = `https://api.modrinth.com/v2/project/${projectId}`;
const headers = {
  Authorization: token,
  'User-Agent': 'Yunitrish006006/TotemLumen (https://github.com/Yunitrish006006/TotemLumen)',
};
async function project() {
  const response = await fetch(base, { headers, signal: AbortSignal.timeout(30_000) });
  if (!response.ok) throw new Error(`Modrinth project read returned ${response.status}`);
  return response.json();
}

const before = await project();
if (before.id !== projectId || before.slug !== 'totem-lumen') {
  throw new Error('Modrinth project identity mismatch');
}
const response = await fetch(`${base}/icon?ext=png`, {
  method: 'PATCH',
  headers: { ...headers, 'Content-Type': 'image/png' },
  body: icon,
  signal: AbortSignal.timeout(30_000),
});
if (response.status !== 204) {
  throw new Error(`Modrinth icon upload returned ${response.status}: ${await response.text()}`);
}
const after = await project();
if (after.id !== projectId || !after.icon_url) {
  throw new Error('Modrinth icon readback is missing');
}
console.log(JSON.stringify({ ...details, project_id: projectId,
  project_status: after.status, icon_url: after.icon_url }, null, 2));
