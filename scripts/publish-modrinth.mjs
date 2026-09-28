import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { basename, join } from 'node:path';

const mode = process.argv[2];
if (!['--dry-run', '--publish'].includes(mode) || process.argv.length !== 3) {
  throw new Error('Usage: node scripts/publish-modrinth.mjs --dry-run|--publish');
}

const properties = Object.fromEntries(readFileSync('gradle.properties', 'utf8')
  .split(/\r?\n/).filter((line) => /^[\w.]+\s*=/.test(line))
  .map((line) => line.split(/=(.*)/s).slice(0, 2).map((part) => part.trim())));
const version = properties.mod_version;
const minecraft = properties.minecraft_version;
if (!/^0\.1\.0-alpha\.\d+$/.test(version) || minecraft !== '26.3') {
  throw new Error('Unexpected release version or Minecraft version');
}

const file = join('build', 'libs', `totem-lumen-${version}.jar`);
const bytes = readFileSync(file);
const sha512 = createHash('sha512').update(bytes).digest('hex');
const mod = JSON.parse(execFileSync('unzip', ['-p', file, 'fabric.mod.json'], { encoding: 'utf8' }));
if (mod.id !== 'totem-lumen' || mod.version !== version || mod.depends?.minecraft !== '~26.3'
    || mod.depends?.java !== '>=25' || mod.depends?.['fabric-api'] !== '*') {
  throw new Error('JAR metadata does not match release metadata');
}
const zipEntries = execFileSync('unzip', ['-Z1', file], { encoding: 'utf8' }).split('\n');
if (!zipEntries.includes('assets/totem-lumen/shaders/core/held_light_rgb.fsh')
    || !zipEntries.includes('LICENSE.txt_totem-lumen')) {
  throw new Error('Release JAR is missing packaged shader assets or license');
}
const changelog = readFileSync('docs/RELEASE_ALPHA61.md', 'utf8');
const projectBody = readFileSync('docs/MODRINTH_PROJECT.md', 'utf8');
if (!changelog.includes(version) || projectBody.length < 100) {
  throw new Error('Release notes or project description are incomplete');
}

const marker = {
  project_slug: 'totem-lumen',
  version_number: version,
  game_versions: [minecraft],
  loaders: ['fabric'],
  filename: basename(file),
  sha512,
};
if (mode === '--dry-run') {
  console.log(JSON.stringify({ mode: 'dry-run', ...marker }, null, 2));
  process.exit(0);
}
if (process.env.GITHUB_REF !== 'refs/heads/main'
    || process.env.GITHUB_REPOSITORY !== 'Yunitrish006006/TotemLumen') {
  throw new Error('Production publish requires the owning repository main branch');
}
const token = process.env.MODRINTH_TOKEN;
if (!token) throw new Error('MODRINTH_TOKEN is missing');

const base = 'https://api.modrinth.com/v2';
async function request(path, options = {}) {
  const response = await fetch(`${base}${path}`, {
    ...options,
    headers: {
      Authorization: token,
      'User-Agent': 'Yunitrish006006/TotemLumen (https://github.com/Yunitrish006006/TotemLumen)',
      ...options.headers,
    },
    signal: AbortSignal.timeout(120_000),
  });
  if (response.status === 404) return null;
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(`Modrinth ${options.method ?? 'GET'} ${path} returned ${response.status}: ${JSON.stringify(body)}`);
  }
  return body;
}

let project = await request('/project/totem-lumen');
if (!project) {
  const form = new FormData();
  form.set('data', JSON.stringify({
    slug: 'totem-lumen',
    title: 'Totem Lumen',
    description: 'RGB gameplay lighting and an experimental Vulkan renderer for Minecraft 26.3.',
    body: projectBody,
    categories: ['technology', 'utility'],
    additional_categories: [],
    project_type: 'mod',
    license_id: 'Apache-2.0',
    source_url: 'https://github.com/Yunitrish006006/TotemLumen',
    issues_url: 'https://github.com/Yunitrish006006/TotemLumen/issues',
    client_side: 'optional',
    server_side: 'optional',
    initial_versions: [],
    gallery_items: [],
    is_draft: true,
  }));
  project = await request('/project', { method: 'POST', body: form });
}
if (project.slug !== 'totem-lumen' || project.project_type !== 'mod'
    || project.source_url !== 'https://github.com/Yunitrish006006/TotemLumen') {
  throw new Error('Modrinth project identity mismatch');
}

const existing = await request(`/project/${project.id}/version/${encodeURIComponent(version)}`);
let published = existing;
if (!published) {
  const form = new FormData();
  form.set('data', JSON.stringify({
    name: `Totem Lumen ${version}`,
    version_number: version,
    changelog,
    dependencies: [{ project_id: 'P7dR8mSH', dependency_type: 'required' }],
    game_versions: [minecraft],
    version_type: 'alpha',
    loaders: ['fabric'],
    featured: false,
    status: 'listed',
    project_id: project.id,
    file_parts: ['release_jar'],
    primary_file: 'release_jar',
    environment: 'client_or_server_prefers_both',
  }));
  form.set('release_jar', new Blob([bytes], { type: 'application/java-archive' }), basename(file));
  published = await request('/version', { method: 'POST', body: form });
}

const [readbackProject, readbackVersion] = await Promise.all([
  request(`/project/${project.id}`),
  request(`/version/${published.id}`),
]);
const primary = readbackVersion?.files?.find((entry) => entry.primary);
if (readbackProject?.id !== project.id || readbackVersion?.project_id !== project.id
    || readbackVersion?.version_number !== version
    || readbackVersion?.version_type !== 'alpha'
    || JSON.stringify(readbackVersion?.game_versions) !== JSON.stringify([minecraft])
    || JSON.stringify(readbackVersion?.loaders) !== JSON.stringify(['fabric'])
    || primary?.filename !== basename(file) || primary?.hashes?.sha512 !== sha512) {
  throw new Error('Modrinth readback does not match the built release JAR');
}
const receipt = {
  ...marker,
  project_id: project.id,
  project_status: readbackProject.status,
  version_id: readbackVersion.id,
  version_status: readbackVersion.status,
  verified_at: new Date().toISOString(),
};
writeFileSync(join('build', `modrinth-published-${version}.json`), `${JSON.stringify(receipt, null, 2)}\n`);
console.log(JSON.stringify(receipt, null, 2));
