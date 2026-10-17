import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const cache = path.join(root, '.cache', 'dependencies');
export const versions = ['26.3', '26.2', '26.1.2'];
const headers = { 'User-Agent': 'YetAnotherTown/1.0.0 (local development)' };
export async function json(url) {
  const response = await fetch(url, { headers, signal: AbortSignal.timeout(30000) });
  if (!response.ok) throw new Error(`Dependency metadata ${response.status}: ${url}`);
  return response.json();
}
export async function download(url, target, checksum, algorithm = 'sha1') {
  await mkdir(path.dirname(target), { recursive: true });
  try {
    const bytes = await readFile(target);
    if (!checksum || createHash(algorithm).update(bytes).digest('hex') === checksum) return target;
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const response = await fetch(url, { headers, signal: AbortSignal.timeout(45000) });
  if (!response.ok) throw new Error(`Dependency download ${response.status}: ${url}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (checksum && createHash(algorithm).update(bytes).digest('hex') !== checksum) throw new Error(`Dependency checksum mismatch: ${target}`);
  await writeFile(target, bytes);
  return target;
}
export async function prepare() {
  await mkdir(cache, { recursive: true });
  try {
    const lock = JSON.parse(await readFile(path.join(root, 'compat', 'dependencies.lock.json'), 'utf8'));
    return await prepareLocked(lock);
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const manifest = await json('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json');
  const result = {};
  const lock = { schema: 1, versions: {}, loader: { version: '0.19.5' } };
  for (const version of versions) {
    const descriptor = manifest.versions.find(value => value.id === version);
    if (!descriptor) throw new Error(`Unsupported Minecraft metadata: ${version}`);
    const data = await json(descriptor.url);
    await writeFile(path.join(cache, `minecraft-${version}.json`), JSON.stringify(data, null, 2));
    const client = await download(data.downloads.client.url, path.join(cache, `minecraft-${version}.jar`), data.downloads.client.sha1);
    const libraries = data.libraries.filter(value => value.downloads?.artifact && !value.name.includes('natives-')).map(value => value.downloads.artifact);
    lock.versions[version] = { client: data.downloads.client, libraries, mods: {}, java: data.javaVersion };
    const classpath = [];
    for (let index = 0; index < libraries.length; index += 8) {
      classpath.push(...await Promise.all(libraries.slice(index, index + 8).map(value => download(value.url, path.join(cache, 'libraries', value.path), value.sha1))));
    }
    const mods = {};
    for (const slug of ['fabric-api', 'xaeros-world-map', 'xaeros-minimap']) {
      const candidates = await json(`https://api.modrinth.com/v2/project/${slug}/version?loaders=${encodeURIComponent('["fabric"]')}&game_versions=${encodeURIComponent(JSON.stringify([version]))}`);
      const selected = candidates.find(value => value.version_type === 'release') ?? candidates[0];
      if (!selected) throw new Error(`No Fabric ${slug} release for ${version}`);
      const file = selected.files.find(value => value.primary) ?? selected.files[0];
      const jar = await download(file.url, path.join(cache, version, file.filename), file.hashes.sha512, 'sha512');
      mods[slug] = { version: selected.version_number, jar, url: file.url, sha512: file.hashes.sha512 };
      lock.versions[version].mods[slug] = { version: selected.version_number, filename: file.filename, url: file.url, sha512: file.hashes.sha512 };
    }
    result[version] = { client, classpath, mods, java: data.javaVersion };
    console.log(`${version}: client verified, ${classpath.length} libraries, ${Object.entries(mods).map(([name, value]) => `${name}=${value.version}`).join(', ')}`);
  }
  const loader = '0.19.5';
  result.loader = await download(`https://maven.fabricmc.net/net/fabricmc/fabric-loader/${loader}/fabric-loader-${loader}.jar`, path.join(cache, `fabric-loader-${loader}.jar`));
  lock.loader.url = `https://maven.fabricmc.net/net/fabricmc/fabric-loader/${loader}/fabric-loader-${loader}.jar`;
  lock.loader.sha256 = createHash('sha256').update(await readFile(result.loader)).digest('hex');
  await mkdir(path.join(root, 'compat'), { recursive: true });
  await writeFile(path.join(root, 'compat', 'dependencies.lock.json'), JSON.stringify(lock, null, 2) + '\n');
  await writeFile(path.join(cache, 'classpath.json'), JSON.stringify(result, null, 2));
  return result;
}
async function prepareLocked(lock) {
  if (lock.schema !== 1 || JSON.stringify(Object.keys(lock.versions)) !== JSON.stringify(versions)) throw new Error('Invalid dependency lock');
  const result = {};
  for (const version of versions) {
    const descriptor = lock.versions[version];
    const client = await download(descriptor.client.url, path.join(cache, `minecraft-${version}.jar`), descriptor.client.sha1);
    const classpath = [];
    for (let index = 0; index < descriptor.libraries.length; index += 8) {
      classpath.push(...await Promise.all(descriptor.libraries.slice(index, index + 8).map(value => download(value.url, path.join(cache, 'libraries', value.path), value.sha1))));
    }
    const mods = {};
    for (const [slug, value] of Object.entries(descriptor.mods)) {
      mods[slug] = { ...value, jar: await download(value.url, path.join(cache, version, value.filename), value.sha512, 'sha512') };
    }
    result[version] = { client, classpath, mods, java: descriptor.java };
    console.log(`${version}: locked dependencies verified`);
  }
  result.loader = await download(lock.loader.url, path.join(cache, `fabric-loader-${lock.loader.version}.jar`), lock.loader.sha256, 'sha256');
  await writeFile(path.join(cache, 'classpath.json'), JSON.stringify(result, null, 2));
  return result;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) await prepare();
