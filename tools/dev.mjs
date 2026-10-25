import { spawn } from 'node:child_process';
import { access, cp, mkdir, readFile, readdir, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { prepare, root, versions } from './dependencies.mjs';

// 开发输出使用源码包名，IDEA 可以直接重编译实际业务源码。
const options = { version: versions[0], outputName: '', javaHome: '' };
for (let index = 2; index < process.argv.length; index++) {
  const option = process.argv[index];
  if (option === '--version') options.version = process.argv[++index];
  else if (option === '--output-name') {
    options.outputName = process.argv[++index];
    if (!options.outputName || !/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(options.outputName) || /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(options.outputName) || options.outputName.endsWith('.')) {
      throw new Error('--output-name requires a single directory name');
    }
  }
  else if (option === '--java-home') {
    options.javaHome = process.argv[++index];
    if (!options.javaHome || options.javaHome.startsWith('--')) throw new Error('--java-home requires an absolute JDK path');
  }
  else throw new Error(`Unknown option: ${option}`);
}
if (!versions.includes(options.version)) {
  throw new Error('Use --version 26.1.2|26.2|26.3');
}
function run(command, arguments_, cwd = root) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, arguments_, { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    child.stdout.on('data', value => output += value);
    child.stderr.on('data', value => output += value);
    child.on('error', reject);
    child.on('close', code => code === 0 ? resolve(output.trim()) : reject(new Error(output || `${command}: ${code}`)));
  });
}
async function javaFiles(directory) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) result.push(...await javaFiles(file));
    else if (file.endsWith('.java')) result.push(file);
  }
  return result;
}
let configuredHome = '';
try {
  const runtime = JSON.parse(await readFile(path.join(root, '.cache/dev/runtime.json'), 'utf8'));
  if (!runtime || Array.isArray(runtime) || Object.keys(runtime).length !== 1 || typeof runtime.javaHome !== 'string' || !path.isAbsolute(runtime.javaHome)) {
    throw new Error('Invalid .cache/dev/runtime.json: expected only an absolute javaHome string');
  }
  configuredHome = runtime.javaHome;
} catch (error) { if (error.code !== 'ENOENT') throw error; }
const home = options.javaHome || configuredHome || (await run('java', ['-XshowSettings:properties', '-version'])).match(/^\s*java\.home = (.+)$/m)?.[1].trim();
if (!home) throw new Error('Cannot locate JDK; use --java-home');
if (!path.isAbsolute(home) || !(await stat(home)).isDirectory()) throw new Error('Java home must be an existing absolute JDK directory');
const executable = name => path.join(home, 'bin', name + (process.platform === 'win32' ? '.exe' : ''));
await access(executable('javac'));
let archiveTool = executable('jar');
try { await access(archiveTool); }
catch (error) {
  if (error.code !== 'ENOENT') throw error;
  const archiveHome = (await run('java', ['-XshowSettings:properties', '-version'])).match(/^\s*java\.home = (.+)$/m)?.[1].trim();
  if (!archiveHome) throw new Error('Selected JDK lacks jar; cannot locate a fallback JDK on PATH');
  archiveTool = path.join(archiveHome, 'bin', process.platform === 'win32' ? 'jar.exe' : 'jar');
  await access(archiveTool);
}
await run(archiveTool, ['--version']);
console.log(`Archive tool: ${archiveTool} (Java runtime and compiler remain ${home})`);
const launcherJava = executable(process.platform === 'win32' ? 'javaw' : 'java');
await access(launcherJava);
const javaSettings = await run(executable('java'), ['-XshowSettings:properties', '-version']);
const javaVersion = javaSettings.match(/^\s*java\.specification\.version = (\d+)$/m)?.[1];
if (!javaVersion || Number(javaVersion) < 25) throw new Error('Development requires JDK 25 or newer');
const sdkName = /JetBrains/.test(javaSettings) ? `JBR ${javaVersion}` : `JDK ${javaVersion}`;
let enhancedRedefinition = false;
try {
  await run(executable('java'), ['-XX:+AllowEnhancedClassRedefinition', '-version']);
  enhancedRedefinition = true;
} catch (error) {
  if (!/Unrecognized VM option.*AllowEnhancedClassRedefinition/.test(error.message)) throw error;
  console.log('Selected JDK supports basic HotSwap only; enhanced redefinition is unavailable.');
}
const dependencies = await prepare();
const descriptor = dependencies[options.version];
const output = path.join(root, 'build/dev', options.outputName || options.version);
const classes = path.join(output, 'classes');
const generated = path.join(output, 'bootstrap');
const resourcesDirectory = path.join(output, 'resources');
const project = path.join(output, 'idea');
await mkdir(classes, { recursive: true });
await mkdir(path.join(project, '.idea/runConfigurations'), { recursive: true });
async function nestedJars(archive) {
  const entries = (await run(archiveTool, ['tf', archive])).split(/\r?\n/)
    .filter(name => name.startsWith('META-INF/jars/') && name.endsWith('.jar'));
  if (!entries.length) return [];
  const destination = path.join(root, '.cache/dependencies/nested', path.basename(archive, '.jar'));
  await mkdir(destination, { recursive: true });
  await run(archiveTool, ['xf', archive, ...entries], destination);
  const result = entries.map(name => path.join(destination, name));
  for (const file of [...result]) result.push(...await nestedJars(file));
  return result;
}
const mods = Object.values(descriptor.mods).map(mod => mod.jar);
const classpath = [descriptor.client, dependencies.loader, dependencies.mixinCompile, ...descriptor.classpath, ...mods];
for (const mod of mods) classpath.push(...await nestedJars(mod));
const bootstrapRoot = path.join(root, 'bootstrap/src/main/java');
for (const source of await javaFiles(bootstrapRoot)) {
  const destination = path.join(generated, path.relative(bootstrapRoot, source));
  await mkdir(path.dirname(destination), { recursive: true });
  await writeFile(destination, (await readFile(source, 'utf8')).replace(/-> "v(?:263|262|2612)"/g, '-> "client"'));
}
const sourceRoots = [path.join(root, 'client/src/main/java'), path.join(root, 'compat'), generated];
const sources = (await Promise.all(sourceRoots.map(javaFiles))).flat();
const argumentFile = path.join(output, 'javac.args');
const quote = value => JSON.stringify(value.replaceAll('\\', '/'));
await writeFile(argumentFile, ['--release', '25', '-g', '-encoding', 'UTF-8', '-proc:none', '-classpath', classpath.join(path.delimiter), '-d', classes, ...sources].map(quote).join('\n'));
console.log(await run(executable('javac'), [`@${argumentFile}`]));
await cp(path.join(root, 'client/src/main/resources'), resourcesDirectory, { recursive: true });
const metadata = JSON.parse(await readFile(path.join(resourcesDirectory, 'fabric.mod.json'), 'utf8'));
metadata.depends.minecraft = options.version;
await writeFile(path.join(resourcesDirectory, 'fabric.mod.json'), JSON.stringify(metadata, null, 2));
await cp(resourcesDirectory, classes, { recursive: true });

// IDEA 只写入开发目录；真实游戏与依赖仍由已有启动器提供。
const xml = value => value.replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;');
const url = value => 'file://' + xml(value.replaceAll('\\', '/'));
const resources = `<content url="${url(resourcesDirectory)}"><sourceFolder url="${url(resourcesDirectory)}" type="java-resource" /></content>`;
await writeFile(path.join(project, 'YetAnotherTown.iml'), `<module type="JAVA_MODULE" version="4"><component name="NewModuleRootManager" inherit-compiler-output="false"><output url="${url(classes)}"/><exclude-output/>${sourceRoots.map(directory => `<content url="${url(directory)}"><sourceFolder url="${url(directory)}" isTestSource="false"/></content>`).join('')}${resources}<orderEntry type="inheritedJdk"/><orderEntry type="sourceFolder" forTests="false"/><orderEntry type="module-library"><library><CLASSES>${classpath.map(file => `<root url="jar://${xml(file.replaceAll('\\', '/'))}!/"/>`).join('')}</CLASSES><JAVADOC/><SOURCES/></library></orderEntry></component></module>`);
await writeFile(path.join(project, '.idea/modules.xml'), '<project version="4"><component name="ProjectModuleManager"><modules><module fileurl="file://$PROJECT_DIR$/YetAnotherTown.iml" filepath="$PROJECT_DIR$/YetAnotherTown.iml"/></modules></component></project>');
await writeFile(path.join(project, '.idea/misc.xml'), `<project version="4"><component name="ProjectRootManager" version="2" languageLevel="JDK_25" project-jdk-name="${sdkName}" project-jdk-type="JavaSDK"/></project>`);
await writeFile(path.join(project, '.idea/compiler.xml'), '<project version="4"><component name="CompilerConfiguration"><bytecodeTargetLevel target="25"/></component></project>');
await writeFile(path.join(project, '.idea/runConfigurations/Attach_Yet_Another_Town.xml'), '<component name="ProjectRunConfigurationManager"><configuration default="false" name="Attach Yet Another Town" type="Remote" factoryName="Remote JVM Debug"><option name="USE_SOCKET_TRANSPORT" value="true"/><option name="SERVER_MODE" value="false"/><option name="HOST" value="127.0.0.1"/><option name="PORT" value="5005"/><method v="2"/></configuration></component>');
const arguments_ = [
  '-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005',
  ...(enhancedRedefinition ? ['-XX:+AllowEnhancedClassRedefinition'] : []),
  '-Dfabric.development=true',
  `-Dfabric.addMods=${classes}`
];
await writeFile(path.join(output, 'launcher-args.txt'), arguments_.map(value => value.includes(' ') ? `"${value}"` : value).join('\n') + '\n');
await writeFile(path.join(output, 'launcher-java.txt'), launcherJava + '\n');
console.log(`Development compile complete: ${classes}\nIDEA project: ${project}\nCompiler JDK: ${home}\nLauncher Java: ${launcherJava}\nLauncher arguments: ${path.join(output, 'launcher-args.txt')}\nIn IDEA, manually select the SDK at ${home} (suggested name: ${sdkName}). Remove the release Yet Another Town JAR from the launcher instance; retain Fabric API and Xaero mods.\nHotSwap: ${enhancedRedefinition ? 'enhanced redefinition flag accepted' : 'basic only'}. No game was started.`);
