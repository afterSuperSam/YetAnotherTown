import { spawn } from 'node:child_process';
import { access, cp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { prepare, root, versions } from './dependencies.mjs';

const sourceRoot = path.join(root, 'client', 'src', 'main', 'java');
const mode = process.argv[2] ?? 'build';
if (!['check', 'test', 'client-test', 'build', 'compatibility'].includes(mode)) throw new Error('Usage: node tools/build.mjs check|test|client-test|build|compatibility');
const packaging = mode === 'build' || mode === 'compatibility';
const build = path.join(root, 'build', ...(packaging ? [] : [mode === 'check' ? 'check' : 'test']));
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
let serviceAvailable = true;
try { await access(path.join(root, 'server/package.json')); }
catch (error) { if (error.code === 'ENOENT') serviceAvailable = false; else throw error; }
for (const directory of serviceAvailable ? ['server/src', 'server/test'] : []) {
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
  if (!entries.length) { nestedCache.set(archive, []); return []; }
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
// 语言文件是界面文案来源；发布前核对键、占位符与服务错误协议。
const languageDirectory = path.join(root, 'client/src/main/resources/assets/yetanothertown/lang');
const english = JSON.parse(await readFile(path.join(languageDirectory, 'en_us.json'), 'utf8'));
const serviceLanguages = serviceAvailable ? JSON.parse(await readFile(path.join(root, 'server/src/translations.json'), 'utf8')) : {};
const placeholders = text => [...text.matchAll(/%(?:\d+\$)?s/g)].length;
for (const file of await files(languageDirectory, '.json')) {
  const translations = JSON.parse(await readFile(file, 'utf8'));
  for (const [key, value] of Object.entries(translations)) {
    if (!(key in english) || typeof value !== 'string' || placeholders(value) !== placeholders(english[key])) throw new Error(`Invalid translation: ${file}: ${key}`);
  }
  if (file.endsWith('zh_cn.json') && Object.keys(translations).length !== Object.keys(english).length) throw new Error('Chinese and English translation keys differ');
  for (const [key, value] of Object.entries(serviceLanguages[path.basename(file, '.json')] ?? {})) {
    if (translations[key] !== value) throw new Error(`Service translation mismatch: ${key}`);
  }
}
for (const source of sourceFiles) {
  for (const match of (await readFile(source, 'utf8')).matchAll(/TownText\.text\("([^"]+)"/g)) {
    if (!(match[1] in english)) throw new Error(`Missing translation: ${match[1]}`);
  }
}
const releaseVersion = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8')).version;
for (const file of [...(serviceAvailable ? ['server/package.json'] : []), 'client/src/main/resources/fabric.mod.json']) {
  if (JSON.parse(await readFile(path.join(root, file), 'utf8')).version !== releaseVersion) throw new Error(`Version mismatch: ${file}`);
}
console.log(`Translations verified: ${Object.keys(english).length} keys`);
const classpaths = {};
for (const version of versions) {
  const descriptor = dependencies[version];
  const variant = 'v' + version.replaceAll('.', '');
  const generated = path.join(build, 'generated', variant);
  await mkdir(generated, { recursive: true });
  const generatedFiles = [];
  for (const source of sourceFiles) {
    const text = (await readFile(source, 'utf8')).replaceAll('dev.yetanothertown.client', `dev.yetanothertown.${variant}`);
    const destination = path.join(generated, path.basename(source));
    await writeFile(destination, text);
    generatedFiles.push(destination);
  }
  const mods = Object.values(descriptor.mods).map(value => value.jar);
  const nested = [...await nestedJars(dependencies.loader)];
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
if (mode === 'check') {
  console.log(`Source check passed: ${versions.join(', ')}; release JAR unchanged`);
} else {
const metadata = JSON.parse(await readFile(path.join(classes, 'fabric.mod.json'), 'utf8'));
const artifactVersion = metadata.version.replace(/-alpha\.(\d+)$/, 'alpha$1').replace(/-beta$/, 'beta').replace(/-c\.(\d+)$/, 'c$1').replace(/-d$/, 'd');
const artifact = path.join(build, 'libs', `yetanothertown-${artifactVersion}.jar`);
if (packaging) {
await mkdir(path.dirname(artifact), { recursive: true });
await run(jar, ['--create', '--file', artifact, '--date=2026-10-18T00:00:00Z', '-C', classes, '.']);
}
const verifier = path.join(build, 'verifier');
await mkdir(verifier, { recursive: true });
await run(javac, ['--release', '25', '-encoding', 'UTF-8', '-cp', classpaths[versions[0]].join(path.delimiter), '-d', verifier, path.join(root, 'tools', 'VerifyLinks.java')]);
const verifierClasspath = [verifier, dependencies.loader, ...dependencies[versions[0]].classpath.filter(file => /^gson-\d.*\.jar$/.test(path.basename(file)))].join(path.delimiter);
for (const version of versions) {
  console.log(await run(java, ['-cp', verifierClasspath, 'VerifyLinks', packaging ? artifact : classes, 'dev.yetanothertown.v' + version.replaceAll('.', ''), path.join(build, `classpath-${version}.txt`)]));
}
if (mode === 'compatibility') {
  for (const version of versions) {
    const descriptor = dependencies[version];
    const options = slug => [descriptor.mods[slug], ...(descriptor.alternatives[slug] ?? [])];
    const matrices = [];
    const coverageByFile = new Map();
    for (const loader of dependencies.loaders) for (const api of options('fabric-api')) for (const world of options('xaeros-world-map')) for (const minimap of options('xaeros-minimap')) {
      const mods = [api, world, minimap, descriptor.mods.modmenu];
      const nested = [...await nestedJars(loader.jar)];
      for (const mod of mods) nested.push(...await nestedJars(mod.jar));
      const classpath = [descriptor.client, loader.jar, dependencies.mixinCompile, ...descriptor.classpath, ...mods.map(mod => mod.jar), ...nested];
      const name = `${version}-loader${loader.version}-api${api.version.split('+')[0]}-world${world.version.split('-').at(-1)}-mini${minimap.version.split('-').at(-1)}`;
      const file = path.join(build, `compatibility-${name}.txt`);
      await writeFile(file, classpath.join('\n') + '\n');
      matrices.push(file);
      coverageByFile.set(file, { loader: loader.version, api: api.version, world: world.version, minimap: minimap.version });
    }
    const compatible = [];
    const excluded = [];
    for (let index = 0; index < matrices.length; index += 50) {
      const report = await run(java, ['-Xmx768m', '-cp', verifierClasspath, 'VerifyLinks', '--metadata-only', artifact, 'dev.yetanothertown.v' + version.replaceAll('.', ''), ...matrices.slice(index, index + 50)]);
      for (const line of report.split(/\r?\n/)) {
        const [status, file, reason] = line.split('\t');
        if (status === 'COMPATIBLE') compatible.push(file);
        else if (status === 'INCOMPATIBLE') excluded.push({ file: path.basename(file), reason });
        else throw new Error(`Invalid Fabric metadata report: ${line}`);
      }
    }
    if (!compatible.length || compatible.length + excluded.length !== matrices.length) throw new Error(`Incomplete Fabric metadata matrix: ${version}`);
    const representativeApi = descriptor.mods['fabric-api'].version;
    const pairKey = file => {
      const value = coverageByFile.get(file);
      return `${value.loader}|${value.world}|${value.minimap}`;
    };
    const interfaces = new Set(compatible.filter(file => coverageByFile.get(file).api === representativeApi));
    const pairs = new Set([...interfaces].map(pairKey));
    for (const file of compatible) if (!pairs.has(pairKey(file))) {
      interfaces.add(file);
      pairs.add(pairKey(file));
    }
    for (const api of options('fabric-api')) {
      if ([...interfaces].some(file => coverageByFile.get(file).api === api.version)) continue;
      const candidates = compatible.filter(file => coverageByFile.get(file).api === api.version);
      const preferred = candidates.find(file => coverageByFile.get(file).world === descriptor.mods['xaeros-world-map'].version
        && coverageByFile.get(file).minimap === descriptor.mods['xaeros-minimap'].version);
      if (preferred ?? candidates[0]) interfaces.add(preferred ?? candidates[0]);
    }
    const checks = [...interfaces];
    const covered = new Set(checks.flatMap(file => Object.values(coverageByFile.get(file))));
    for (const option of [...dependencies.loaders, ...options('fabric-api'), ...options('xaeros-world-map'), ...options('xaeros-minimap')])
      if (!covered.has(option.version)) throw new Error(`Locked dependency has no compatible combination: ${version}: ${option.version}`);
    await writeFile(path.join(build, `compatibility-excluded-${version}.json`), JSON.stringify(excluded, null, 2) + '\n');
    for (let index = 0; index < checks.length; index += 50)
      await run(java, ['-Xmx768m', '-cp', verifierClasspath, 'VerifyLinks', artifact, 'dev.yetanothertown.v' + version.replaceAll('.', ''), ...checks.slice(index, index + 50)]);
    await writeFile(path.join(build, `compatibility-coverage-${version}.json`), JSON.stringify({ candidates: matrices.length, metadataCompatible: compatible.length,
      excluded: excluded.length, interfaceChecks: checks.length, xaeroPairs: pairs.size, representativeApi,
      checked: checks.map(file => coverageByFile.get(file)) }, null, 2) + '\n');
    console.log(`${version}: ${matrices.length} metadata candidates; ${compatible.length} compatible, ${excluded.length} declared conflicts; ${checks.length} interface checks cover all ${pairs.size} Xaero pairs and ${options('fabric-api').length} Fabric API versions (primary API ${representativeApi}; no game launched)`);
  }
}
if (mode === 'test' || mode === 'client-test' || mode === 'compatibility') {
  if (mode === 'test' && serviceAvailable) console.log(await run(process.execPath, ['--test', 'server/test/service.test.mjs']));
  if (mode === 'test' && !serviceAvailable) console.log('Client-only checkout: private service tests are not included');
  const tests = await files(path.join(root, 'client', 'src', 'test', 'java'));
  const testClasses = path.join(build, 'test-classes');
  await mkdir(testClasses, { recursive: true });
  const gateway = sourceFiles.find(file => file.endsWith('TownGateway.java'));
  const classpath = [classes, ...classpaths[versions[0]]].join(path.delimiter);
  await run(javac, ['--release', '25', '--add-modules', 'jdk.httpserver', '-encoding', 'UTF-8', '-proc:none', '-cp', classpath, '-d', testClasses, gateway, sourceFiles.find(file => file.endsWith('TownText.java')), ...tests.filter(source => source.endsWith('TownGatewayTest.java'))]);
  console.log(await run(java, ['--add-modules', 'jdk.httpserver', '-ea', '-cp', testClasses + path.delimiter + classpath, 'dev.yetanothertown.client.TownGatewayTest']));
  const keyboardTest = tests.find(source => source.endsWith('TownInputTest.java'));
  for (const version of versions) {
    const variant = 'v' + version.replaceAll('.', '');
    const directory = path.join(build, 'generated-tests', variant);
    await mkdir(directory, { recursive: true });
    const source = path.join(directory, 'TownInputTest.java');
    await writeFile(source, (await readFile(keyboardTest, 'utf8')).replaceAll('dev.yetanothertown.client', `dev.yetanothertown.${variant}`));
    const versionClasspath = [classes, ...classpaths[version]].join(path.delimiter);
    await run(javac, ['--release', '25', '-encoding', 'UTF-8', '-proc:none', '-cp', versionClasspath, '-d', testClasses, source]);
    console.log(version + ': ' + await run(java, ['-ea', '-cp', testClasses + path.delimiter + versionClasspath, `dev.yetanothertown.${variant}.TownInputTest`]));
  }
}
console.log(packaging ? `Single JAR: ${path.relative(root, artifact)} (${versions.join(', ')})` : `Tests passed: ${versions.join(', ')}; release JAR unchanged`);
}
