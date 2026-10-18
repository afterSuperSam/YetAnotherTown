import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const cache = path.join(root, '.cache', 'dependencies');
export const versions = ['26.3', '26.2', '26.1.2'];
const headers = { 'User-Agent': 'YetAnotherTown/1.0.0 (local development)' };
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
export async function prepare(compatibility = false) {
  const lock = JSON.parse(await readFile(path.join(root, 'compat', 'dependencies.lock.json'), 'utf8'));
  return prepareLocked(lock, compatibility);
}
async function prepareLocked(lock, compatibility) {
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
    const alternatives = {};
    if (compatibility) for (const [slug, entries] of Object.entries(descriptor.alternatives ?? {})) {
      if (!mods[slug] || !Array.isArray(entries)) throw new Error('Invalid compatibility alternatives');
      alternatives[slug] = [];
      for (const value of entries) alternatives[slug].push({ ...value, jar: await download(value.url, path.join(cache, version, value.filename), value.sha512, 'sha512') });
    }
    result[version] = { client, classpath, mods, alternatives, java: descriptor.java };
    console.log(`${version}: locked dependencies verified`);
  }
  result.loader = await download(lock.loader.url, path.join(cache, `fabric-loader-${lock.loader.version}.jar`), lock.loader.sha256, 'sha256');
  result.mixinCompile = await download(lock.mixinCompile.url, path.join(cache, `sponge-mixin-${lock.mixinCompile.version}.jar`), lock.mixinCompile.sha256, 'sha256');
  result.loaders = [{ ...lock.loader, jar: result.loader }];
  if (compatibility) for (const value of lock.alternativeLoaders ?? []) result.loaders.push({ ...value, jar: await download(value.url, path.join(cache, `fabric-loader-${value.version}.jar`), value.sha256, 'sha256') });
  await writeFile(path.join(cache, 'classpath.json'), JSON.stringify(result, null, 2));
  return result;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) await prepare();
