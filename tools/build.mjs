import { spawn } from 'node:child_process';
import { access, cp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { prepare, root, versions } from './dependencies.mjs';

const build = path.join(root, 'build');
const sourceRoot = path.join(root, 'client', 'src', 'main', 'java');
const mode = process.argv[2] ?? 'build';
if (!['check', 'test', 'client-test', 'build', 'compatibility'].includes(mode)) throw new Error('Usage: node tools/build.mjs check|test|client-test|build|compatibility');
function run(command, arguments_, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, arguments_, { cwd: root, stdio: ['ignore', 'pipe', 'pipe'], ...options });
    let output = '';
    child.stdout.on('data', bytes => { output += bytes; });
    child.stderr.on('data', bytes => { output += bytes; });
    child.on('error', reject);
    child.on('close', code => code === 0 ? resolve(output.trim()) : reject(new Error(`${path.basename(command)} exited ${code}\n${output}`)));
  });
}
async function javaHome() {
  const output = await run('java', ['-XshowSettings:properties', '-version']);
  const home = output.match(/^\s*java\.home = (.+)$/m)?.[1].trim();
  if (!home) throw new Error('Cannot locate Java JDK; install JDK 25 or newer');
  return home;
}
const home = await javaHome();
const executable = name => path.join(home, 'bin', name + (process.platform === 'win32' ? '.exe' : ''));
const javac = executable('javac');
const jar = executable('jar');
const java = executable('java');
await access(javac);
for (const directory of ['server/src', 'server/test']) {
  for (const source of await files(path.join(root, directory), '.mjs')) await run(process.execPath, ['--check', source]);
}
const dependencies = await prepare(mode === 'compatibility');
async function files(directory, extension = '.java') {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) result.push(...await files(absolute, extension));
    else if (entry.name.endsWith(extension)) result.push(absolute);
  }
  return result.sort();
}
const nestedCache = new Map();
async function nestedJars(archive) {
  if (nestedCache.has(archive)) return nestedCache.get(archive);
  const listed = await run(jar, ['tf', archive]);
  const entries = listed.split(/\r?\n/).filter(name => name.startsWith('META-INF/jars/') && name.endsWith('.jar'));
  if (!entries.length) return [];
  const destination = path.join(root, '.cache', 'dependencies', 'nested', path.basename(archive, '.jar'));
  await mkdir(destination, { recursive: true });
  await run(jar, ['xf', archive, ...entries], { cwd: destination });
  const result = entries.map(name => path.join(destination, name));
  for (const nested of [...result]) result.push(...await nestedJars(nested));
  nestedCache.set(archive, result);
  return result;
}
await mkdir(build, { recursive: true });
const classes = path.join(build, 'classes');
// 仅重建已解析、位于本项目 build 下的产物目录。
if (!classes.startsWith(build + path.sep)) throw new Error('Invalid build directory');
await rm(classes, { recursive: true, force: true });
await mkdir(classes, { recursive: true });
const sourceFiles = [...await files(sourceRoot), ...await files(path.join(root, 'compat'))];
if (!sourceFiles.some(file => file.endsWith('VersionClient.java'))) throw new Error('Client source is incomplete: VersionClient.java missing');
const classpaths = {};
for (const version of versions) {
  const descriptor = dependencies[version];
  const variant = 'v' + version.replaceAll('.', '');
  const generated = path.join(build, 'generated', variant);
  await mkdir(generated, { recursive: true });
  const generatedFiles = [];
  for (const source of sourceFiles) {
    const text = (await readFile(source, 'utf8')).replaceAll('dev.ohmytown.client', `dev.ohmytown.${variant}`);
    const destination = path.join(generated, path.basename(source));
    await writeFile(destination, text);
    generatedFiles.push(destination);
  }
  const mods = Object.values(descriptor.mods).map(value => value.jar);
  const nested = [];
  for (const mod of mods) nested.push(...await nestedJars(mod));
  const classpath = [descriptor.client, dependencies.loader, dependencies.mixinCompile, ...descriptor.classpath, ...mods, ...nested];
  classpaths[version] = classpath;
  await writeFile(path.join(build, `classpath-${version}.txt`), classpath.join('\n') + '\n');
  const responseFile = path.join(build, `javac-${version}.args`);
  await writeFile(responseFile, ['--release', '25', '-encoding', 'UTF-8', '-Xlint:all,-deprecation,-processing,-classfile', '-proc:none', '-cp', classpath.join(path.delimiter), '-d', classes, ...generatedFiles].map(argument => '"' + argument.replaceAll('\\', '\\\\').replaceAll('"', '\\"') + '"').join('\n'));
  const output = await run(javac, ['@' + responseFile]);
  if (output) console.log(output);
  console.log(`${version}: real dependency compilation passed (Java 25 bytecode)`);
}
const bootstrap = await files(path.join(root, 'bootstrap', 'src', 'main', 'java'));
await run(javac, ['--release', '25', '-encoding', 'UTF-8', '-proc:none', '-cp', classpaths[versions[0]].join(path.delimiter), '-d', classes, ...bootstrap]);
await cp(path.join(root, 'client', 'src', 'main', 'resources'), classes, { recursive: true });
const metadata = JSON.parse(await readFile(path.join(classes, 'fabric.mod.json'), 'utf8'));
const artifactVersion = metadata.version.replace(/-alpha\.(\d+)$/, 'alpha$1');
const artifact = path.join(build, 'libs', `ohmytown-${artifactVersion}.jar`);
await mkdir(path.dirname(artifact), { recursive: true });
await run(jar, ['--create', '--file', artifact, '--date=2026-10-18T00:00:00Z', '-C', classes, '.']);
const verifier = path.join(build, 'verifier');
await mkdir(verifier, { recursive: true });
await run(javac, ['--release', '25', '-encoding', 'UTF-8', '-d', verifier, path.join(root, 'tools', 'VerifyLinks.java')]);
for (const version of versions) {
  console.log(await run(java, ['-cp', verifier, 'VerifyLinks', artifact, 'dev.ohmytown.v' + version.replaceAll('.', ''), path.join(build, `classpath-${version}.txt`)]));
}
if (mode === 'compatibility') {
  for (const version of versions) {
    const descriptor = dependencies[version];
    const options = slug => [descriptor.mods[slug], ...(descriptor.alternatives[slug] ?? [])];
    const matrices = [];
    for (const loader of dependencies.loaders) for (const api of options('fabric-api')) for (const world of options('xaeros-world-map')) for (const minimap of options('xaeros-minimap')) {
      const mods = [api, world, minimap, descriptor.mods.modmenu];
      const nested = [];
      for (const mod of mods) nested.push(...await nestedJars(mod.jar));
      const classpath = [descriptor.client, loader.jar, dependencies.mixinCompile, ...descriptor.classpath, ...mods.map(mod => mod.jar), ...nested];
      const name = `${version}-loader${loader.version}-api${api.version.split('+')[0]}-world${world.version.split('-').at(-1)}-mini${minimap.version.split('-').at(-1)}`;
      const file = path.join(build, `compatibility-${name}.txt`);
      await writeFile(file, classpath.join('\n') + '\n');
      matrices.push(file);
    }
    await run(java, ['-Xmx768m', '-cp', verifier, 'VerifyLinks', artifact, 'dev.ohmytown.v' + version.replaceAll('.', ''), ...matrices]);
    console.log(`${version}: ${matrices.length} dependency combinations verified (bytecode and reflection; no game launched)`);
  }
}
if (mode === 'test' || mode === 'client-test' || mode === 'compatibility') {
  if (mode === 'test') console.log(await run(process.execPath, ['--test', 'server/test/service.test.mjs']));
  const tests = await files(path.join(root, 'client', 'src', 'test', 'java'));
  const testClasses = path.join(build, 'test-classes');
  await mkdir(testClasses, { recursive: true });
  const gateway = sourceFiles.find(file => file.endsWith('TownGateway.java'));
  const classpath = [classes, ...classpaths[versions[0]]].join(path.delimiter);
  await run(javac, ['--release', '25', '--add-modules', 'jdk.httpserver', '-encoding', 'UTF-8', '-proc:none', '-cp', classpath, '-d', testClasses, gateway, ...tests.filter(source => source.endsWith('TownGatewayTest.java'))]);
  console.log(await run(java, ['--add-modules', 'jdk.httpserver', '-ea', '-cp', testClasses + path.delimiter + classpath, 'dev.ohmytown.client.TownGatewayTest']));
  const keyboardTest = tests.find(source => source.endsWith('TownInputTest.java'));
  for (const version of versions) {
    const variant = 'v' + version.replaceAll('.', '');
    const directory = path.join(build, 'generated-tests', variant);
    await mkdir(directory, { recursive: true });
    const source = path.join(directory, 'TownInputTest.java');
    await writeFile(source, (await readFile(keyboardTest, 'utf8')).replaceAll('dev.ohmytown.client', `dev.ohmytown.${variant}`));
    const versionClasspath = [classes, ...classpaths[version]].join(path.delimiter);
    await run(javac, ['--release', '25', '-encoding', 'UTF-8', '-proc:none', '-cp', versionClasspath, '-d', testClasses, source]);
    console.log(version + ': ' + await run(java, ['-ea', '-cp', testClasses + path.delimiter + versionClasspath, `dev.ohmytown.${variant}.TownInputTest`]));
  }
}
console.log(`Single JAR: ${path.relative(root, artifact)} (${versions.join(', ')})`);
