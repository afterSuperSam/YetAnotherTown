import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { ApiError, JsonStore, TownService, validateConfig } from '../src/service.mjs';
import { createHttpServer } from '../src/server.mjs';

const PLAYERS = {
  Alice: 'a'.repeat(32), Bob: 'b'.repeat(32), Carol: 'c'.repeat(32), Dana: 'd'.repeat(32),
};
const position = { dimension: 'minecraft:overworld', x: -17, y: 64, z: 0 };
const rejects = (operation, code) => assert.rejects(operation, error => error instanceof ApiError && error.code === code);

async function fixture(t, config = {}) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'yat-service-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  let time = Date.parse('2026-10-18T00:00:00Z');
  const store = new JsonStore(path.join(directory, 'state.json'));
  const service = await new TownService({ store, now: () => time, config }).initialize();
  const login = async name => service.handle('auth.login', { playerId: PLAYERS[name], name });
  const alice = await login('Alice');
  const call = (action, payload = {}, token = alice.token) => service.handle(action, payload, token);
  const { townId } = await call('town.create', { name: '弥生小镇', server: 'example.test' });
  const snapshot = () => call('town.snapshot', { townId });
  const worldId = (await snapshot()).worlds[0].id;
  const project = async extra => call('project.create', {
    townId, worldId, name: '建车站', type: 'building', purpose: '公共交通', dimension: 'minecraft:overworld',
    chunks: { minX: -2, maxX: 0, minZ: 1, maxZ: 2 }, ...extra,
  });
  const join = async (name = 'Bob', promote = true) => {
    const account = await login(name);
    await call('town.join', { townId }, account.token);
    if (promote) await call('member.role', { townId, playerId: account.player.id, roleId: 'member' });
    return account;
  };
  return { directory, store, service, login, alice, call, townId, worldId, snapshot, project, join, advance: milliseconds => { time += milliseconds; } };
}

test('只读查询不复制无关小镇，返回资料仍与服务状态隔离', async t => {
  const f = await fixture(t);
  let reads = 0;
  Object.defineProperty(f.service.state, 'unrelated', { enumerable: true, get() { reads++; return {}; } });
  const listed = await f.call('town.list');
  listed[0].name = '列表被修改';
  const snapshot = await f.snapshot();
  snapshot.town.name = '快照被修改';
  snapshot.members[0].name = '成员被修改';
  snapshot.roles[0].permissions.push('invalid');
  assert.equal(reads, 0);
  const next = await f.snapshot();
  assert.equal(next.town.name, '弥生小镇');
  assert.equal(next.members[0].name, 'Alice');
  assert.deepEqual(next.roles[0].permissions, []);
});

test('指定周目快照只报告同周目工程的区块重叠', async t => {
  const f = await fixture(t);
  const first = await f.project();
  const second = await f.project({ name: '同周目重叠' });
  const otherWorld = await f.call('world.create', { townId: f.townId, name: '新周目' });
  await f.project({ worldId: otherWorld.id, name: '其他周目重叠' });
  const snapshot = await f.call('town.snapshot', { townId: f.townId, worldId: f.worldId });
  assert.deepEqual(snapshot.projects.map(project => [project.id, project.overlapIds]), [[first.id, [second.id]], [second.id, [first.id]]]);
});

test('配置拒绝未知项、非法范围和相互冲突的提示距离', () => {
  assert.equal(validateConfig().port, 37841);
  const migrated = validateConfig({ challengeSeconds: 90, port: 37842 });
  assert.equal(migrated.port, 37842);
  assert.equal(Object.hasOwn(migrated, 'challengeSeconds'), false);
  for (const config of [{ port: 0 }, { port: '37841' }, { bypassAuthentication: true }, { sessionHours: -1 }, { defaultProximityRadius: 300, maximumProximityRadius: 100 }, JSON.parse('{"toString":1}'), JSON.parse('{"__proto__":{}}')]) assert.throws(() => validateConfig(config), ApiError);
});

test('直接使用游戏 UUID 登录且无外部请求，会话到期要求重新登录', async t => {
  t.mock.method(globalThis, 'fetch', async () => { throw new Error('Unexpected external request'); });
  const f = await fixture(t, { sessionHours: 1 });
  const result = await f.login('Bob');
  assert.deepEqual(result.player, { id: PLAYERS.Bob, name: 'Bob' });
  assert.match(result.token, /^[a-f0-9]{64}$/);
  assert.equal(globalThis.fetch.mock.callCount(), 0);
  f.advance(3600000);
  await rejects(f.call('town.list'), 'unauthenticated');
  await rejects(f.service.handle('town.list'), 'unauthenticated');
  const refreshed = await f.login('Alice');
  assert.equal((await f.call('town.list', {}, refreshed.token)).length, 1);
});

test('游戏 UUID 接受两种标准格式并规范化，登录更新现有成员游戏名', async t => {
  const f = await fixture(t);
  const bob = await f.join();
  const updated = await f.call('auth.login', { playerId: 'BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB', name: 'Builder_2' });
  assert.deepEqual(updated.player, { id: bob.player.id, name: 'Builder_2' });
  const snapshot = await f.call('town.snapshot', { townId: f.townId }, updated.token);
  assert.equal(snapshot.currentMember.name, 'Builder_2');
  assert.equal(snapshot.members.filter(member => member.playerId === bob.player.id).length, 1);
  const uppercase = await f.call('auth.login', { playerId: PLAYERS.Carol.toUpperCase(), name: 'C' });
  assert.equal(uppercase.player.id, PLAYERS.Carol);
});

test('游戏身份拒绝畸形 UUID 与非法游戏名且失败不创建会话', async t => {
  const f = await fixture(t);
  const before = await readFile(f.store.file, 'utf8');
  for (const playerId of [undefined, null, 123, '', 'x'.repeat(32), 'a'.repeat(31), 'a'.repeat(33), 'aaaaaaaa-aaaa-aaaa-aaaaaaaaaaaaaaaa', 'aaaaaaaaaaaa-aaaa-aaaa-aaaaaaaaaaaa', ` ${PLAYERS.Alice}`]) {
    await rejects(f.call('auth.login', { playerId, name: 'Alice' }), 'invalid_argument');
  }
  for (const name of [undefined, null, 123, '', 'a'.repeat(17), 'not valid', '中文名', 'bad-name', ' Alice']) {
    await rejects(f.call('auth.login', { playerId: PLAYERS.Alice, name }), 'invalid_argument');
  }
  assert.equal(await readFile(f.store.file, 'utf8'), before);
});

test('八位小镇 ID、密码哈希、可关闭密码、多小镇及新人只读', async t => {
  const f = await fixture(t);
  assert.match(f.townId, /^\d{8}$/);
  await f.call('town.update', { townId: f.townId, password: 'secret-123' });
  const bob = await f.login('Bob');
  await rejects(f.call('town.join', { townId: f.townId, password: 'wrong' }, bob.token), 'forbidden');
  await f.call('town.join', { townId: f.townId, password: 'secret-123' }, bob.token);
  const snapshot = await f.call('town.snapshot', { townId: f.townId, worldId: '' }, bob.token);
  assert.equal(snapshot.currentMember.roleId, 'newcomer');
  assert.deepEqual(snapshot.permissions, []);
  assert.deepEqual(snapshot.limits, { defaultProximityRadius: 32, maximumProximityRadius: 256, maximumRegionChunks: 65536 });
  assert.equal(snapshot.town.passwordEnabled, true);
  assert.equal('password' in snapshot.town, false);
  await rejects(f.call('project.create', { townId: f.townId, worldId: f.worldId }, bob.token), 'forbidden');
  await rejects(f.call('town.update', { townId: f.townId, name: 'changed' }, bob.token), 'forbidden');
  const secondTown = await f.call('town.create', { name: '第二小镇', worldKey: 'Multiplayer_example_DIM0' }, bob.token);
  const secondSnapshot = await f.call('town.snapshot', { townId: secondTown.townId }, bob.token);
  assert.equal(secondSnapshot.worlds[0].worldKey, 'Multiplayer_example_DIM0');
  assert.equal((await f.call('town.list', {}, bob.token)).length, 2);
  const protectedState = await readFile(f.store.file, 'utf8');
  assert.equal(protectedState.includes('secret-123'), false);
  assert.equal(protectedState.includes('"salt"'), true);
  assert.equal(protectedState.includes('"hash"'), true);
  await f.call('town.update', { townId: f.townId, password: '' });
  const carol = await f.login('Carol');
  await f.call('town.join', { townId: f.townId }, carol.token);
  const serialized = await readFile(f.store.file, 'utf8');
  assert.equal(serialized.includes('secret-123'), false);
  assert.equal(serialized.includes(f.alice.token), false);
  assert.equal(serialized.includes(bob.token), false);
  assert.equal((await f.snapshot()).town.passwordEnabled, false);
});

test('自定义权限组不得越权或提权，镇长唯一且只能显式移交', async t => {
  const f = await fixture(t);
  const bob = await f.join();
  const role = await f.call('role.save', { townId: f.townId, name: '审核助手', permissions: ['roles.manage', 'members.manage'] });
  await f.call('member.role', { townId: f.townId, playerId: bob.player.id, roleId: role.id });
  await rejects(f.call('role.save', { townId: f.townId, name: '提权', permissions: ['project.approve'] }, bob.token), 'forbidden');
  await rejects(f.call('role.save', { townId: f.townId, id: 'member', name: '成员', permissions: [] }, bob.token), 'forbidden');
  await rejects(f.call('member.role', { townId: f.townId, playerId: bob.player.id, roleId: 'manager' }, bob.token), 'forbidden');
  await rejects(f.call('member.role', { townId: f.townId, playerId: bob.player.id, roleId: 'mayor' }), 'forbidden');
  await rejects(f.call('role.save', { townId: f.townId, id: 'newcomer', name: '新人', permissions: ['project.create'] }), 'forbidden');
  await rejects(f.call('town.transfer', { townId: f.townId, playerId: f.alice.player.id }, bob.token), 'forbidden');
  await f.call('town.transfer', { townId: f.townId, playerId: bob.player.id });
  const snapshot = await f.snapshot();
  assert.equal(snapshot.town.ownerId, bob.player.id);
  assert.equal(snapshot.members.filter(member => member.roleId === 'mayor').length, 1);
  assert.equal(snapshot.currentMember.roleId, 'manager');
  await rejects(f.call('member.remove', { townId: f.townId, playerId: bob.player.id }), 'forbidden');
});

test('自定义权限组可新增、更新和删除；有成员时不能删除', async t => {
  const f = await fixture(t);
  const bob = await f.join();
  const role = await f.call('role.save', { townId: f.townId, name: '维护员', permissions: ['project.edit'] });
  await f.call('member.role', { townId: f.townId, playerId: bob.player.id, roleId: role.id });
  await rejects(f.call('role.delete', { townId: f.townId, id: role.id }), 'conflict');
  const edited = await f.call('role.save', { townId: f.townId, id: role.id, name: '工程维护员', permissions: ['project.edit', 'ticket.manage'] });
  assert.equal(edited.name, '工程维护员');
  await f.call('member.role', { townId: f.townId, playerId: bob.player.id, roleId: 'member' });
  await f.call('role.delete', { townId: f.townId, id: role.id });
  assert.equal((await f.snapshot()).roles.some(item => item.id === role.id), false);
});

test('编辑他人已使用权限组必须同时具备成员管理权限', async t => {
  const f = await fixture(t); const bob = await f.join(); const carol = await f.join('Carol');
  const editor = await f.call('role.save', { townId: f.townId, name: '权限维护', permissions: ['roles.manage', 'project.edit'] });
  const builders = await f.call('role.save', { townId: f.townId, name: '建设员', permissions: ['project.edit'] });
  await f.call('member.role', { townId: f.townId, playerId: bob.player.id, roleId: editor.id });
  await f.call('member.role', { townId: f.townId, playerId: carol.player.id, roleId: builders.id });
  await rejects(f.call('role.save', { townId: f.townId, id: builders.id, name: '被降权', permissions: [] }, bob.token), 'forbidden');
  assert.deepEqual((await f.snapshot()).roles.find(role => role.id === builders.id).permissions, ['project.edit']);
  await f.call('role.save', { townId: f.townId, id: editor.id, name: '权限维护', permissions: ['roles.manage', 'members.manage', 'project.edit'] });
  await f.call('role.save', { townId: f.townId, id: builders.id, name: '建设员', permissions: [] }, bob.token);
  assert.deepEqual((await f.snapshot()).roles.find(role => role.id === builders.id).permissions, []);
});

test('全局工程管理权限可独立维护工程和维修工单', async t => {
  const f = await fixture(t); const bob = await f.join();
  const role = await f.call('role.save', { townId: f.townId, name: '工程管理', permissions: ['project.approve'] });
  await f.call('member.role', { townId: f.townId, playerId: bob.player.id, roleId: role.id });
  const project = await f.project();
  await f.call('project.update', { townId: f.townId, id: project.id, name: '新车站' }, bob.token);
  await f.call('project.status', { townId: f.townId, id: project.id, status: 'active' }, bob.token);
  await f.call('project.status', { townId: f.townId, id: project.id, status: 'completed' }, bob.token);
  const ticket = await f.call('ticket.create', { townId: f.townId, projectId: project.id, ...position, title: '维修' });
  await f.call('ticket.update', { townId: f.townId, id: ticket.id, status: 'resolved', resolution: '恢复' }, bob.token);
  assert.equal((await f.snapshot()).projects[0].status, 'completed');
});

test('计划占区、开工、完工及区块重叠验证', async t => {
  const f = await fixture(t);
  await rejects(f.project({ chunks: { minX: 2, maxX: 1, minZ: 0, maxZ: 1 } }), 'invalid_argument');
  await rejects(f.project({ dimension: 'overworld' }), 'invalid_argument');
  await rejects(f.project({ memberIds: [] }), 'invalid_argument');
  await rejects(f.project({ expectedAt: '2026-11-01' }), 'invalid_argument');
  const first = await f.project({ name: '', purpose: '' });
  assert.equal(first.status, 'planned'); assert.equal(first.name, '预留地块');
  const second = await f.project();
  assert.deepEqual((await f.snapshot()).projects.find(item => item.id === first.id).overlapIds, [second.id]);
  await rejects(f.call('project.status', { townId: f.townId, id: first.id, status: 'active' }), 'invalid_argument');
  await f.call('project.update', { townId: f.townId, id: first.id, name: '车站' });
  await rejects(f.call('project.update', { townId: f.townId, id: first.id, status: 'completed' }), 'invalid_argument');
  await rejects(f.call('project.status', { townId: f.townId, id: first.id, status: 'completed' }), 'conflict');
  await f.call('project.status', { townId: f.townId, id: first.id, status: 'active' });
  await f.call('project.status', { townId: f.townId, id: first.id, status: 'completed' });
  const snapshot = await f.snapshot();
  assert.equal(snapshot.projects.length, 2);
  assert.equal(snapshot.projects.find(item => item.id === first.id).status, 'completed');
  for (const key of ['tasks', 'materials', 'pledges', 'facilities']) assert.equal(Object.hasOwn(snapshot, key), false);
  for (const action of ['task.create', 'material.create', 'facility.create', 'project.review']) await rejects(f.call(action, { townId: f.townId }), 'not_found');
});

test('留言全镇可见，其他成员不可修改工程或删除他人留言', async t => {
  const f = await fixture(t); const bob = await f.join();
  const project = await f.project();
  const message = await f.call('message.create', { townId: f.townId, worldId: f.worldId, projectId: project.id, ...position, text: '材料在二楼' });
  assert.equal((await f.call('town.snapshot', { townId: f.townId }, bob.token)).messages.length, 1);
  await rejects(f.call('message.create', { townId: f.townId, worldId: f.worldId, projectId: project.id, ...position, text: '私密内容', visibility: 'project' }), 'invalid_argument');
  await rejects(f.call('project.update', { townId: f.townId, id: project.id, name: '越权' }, bob.token), 'forbidden');
  await rejects(f.call('message.delete', { townId: f.townId, id: message.id }, bob.token), 'forbidden');
});

test('完工工程报修后待维护，全部工单解决才回到完工，负责人可维护', async t => {
  const f = await fixture(t); const bob = await f.join(); const carol = await f.join('Carol');
  const project = await f.project({ leaderId: bob.player.id });
  const report = () => f.call('ticket.create', { townId: f.townId, worldId: f.worldId, projectId: project.id, ...position, title: '机器堵塞' }, carol.token);
  await rejects(report(), 'conflict');
  await f.call('project.status', { townId: f.townId, id: project.id, status: 'active' }, bob.token);
  await f.call('project.status', { townId: f.townId, id: project.id, status: 'completed' }, bob.token);
  const results = await Promise.all([report(), report()]);
  assert.equal((await f.snapshot()).projects[0].status, 'maintenance');
  assert.equal(results[0].x, position.x);
  await rejects(f.call('ticket.update', { townId: f.townId, id: results[0].id, status: 'resolved', resolution: '完成' }, carol.token), 'forbidden');
  await rejects(f.call('ticket.update', { townId: f.townId, id: results[0].id, status: 'resolved' }, bob.token), 'invalid_argument');
  await f.call('ticket.update', { townId: f.townId, id: results[0].id, status: 'resolved', resolution: '疏通' }, bob.token);
  assert.equal((await f.snapshot()).projects[0].status, 'maintenance');
  await f.call('ticket.update', { townId: f.townId, id: results[1].id, status: 'resolved', resolution: '恢复' }, bob.token);
  assert.equal((await f.snapshot()).projects[0].status, 'completed');
  await f.call('ticket.update', { townId: f.townId, id: results[1].id, status: 'open' });
  assert.equal((await f.snapshot()).projects[0].status, 'maintenance');
});

test('周目隔离与归档保护覆盖工程、留言和维修', async t => {
  const f = await fixture(t); const project = await f.project({ status: 'active' });
  await f.call('project.status', { townId: f.townId, id: project.id, status: 'completed' });
  const message = await f.call('message.create', { townId: f.townId, worldId: f.worldId, ...position, text: '旧留言' });
  const ticket = await f.call('ticket.create', { townId: f.townId, worldId: f.worldId, projectId: project.id, ...position, title: '维修' });
  const next = await f.call('world.create', { townId: f.townId, name: '第二周目', worldKey: 'world-2' });
  await rejects(f.call('ticket.create', { townId: f.townId, worldId: next.id, projectId: project.id, ...position, title: '跨周目' }), 'invalid_argument');
  await rejects(f.call('message.create', { townId: f.townId, worldId: next.id, projectId: project.id, ...position, text: '跨周目' }), 'invalid_argument');
  await f.call('world.archive', { townId: f.townId, worldId: f.worldId });
  for (const [action, payload] of [['project.update', { id: project.id, name: '改名' }], ['project.status', { id: project.id, status: 'active' }], ['message.delete', { id: message.id }], ['ticket.update', { id: ticket.id, status: 'working' }], ['ticket.create', { projectId: project.id, ...position, title: '新增' }]]) await rejects(f.call(action, { townId: f.townId, ...payload }), 'archived');
  assert.equal((await f.call('town.snapshot', { townId: f.townId, worldId: next.id })).projects.length, 0);
  assert.equal((await f.call('town.snapshot', { townId: f.townId, worldId: f.worldId })).projects.length, 1);
});

test('旧版数据迁移保留旧业务与私密留言，不对外泄露存档', async t => {
  const f = await fixture(t); const project = await f.project();
  const legacy = JSON.parse(await readFile(f.store.file, 'utf8')); legacy.version = 1;
  const town = legacy.towns[f.townId];
  town.tasks = [{ id: 'old-task' }]; town.materials = []; town.pledges = []; town.facilities = [{ id: 'old-facility' }];
  town.projects[0].status = 'pending'; town.projects[0].memberIds = [f.alice.player.id]; town.projects[0].expectedAt = '2026-11-01';
  town.tickets.push({ id: 'invalid-ticket', projectId: project.id, worldId: 'other-world', ...position, status: 'open' });
  town.messages.push({ id: 'private', worldId: f.worldId, projectId: project.id, visibility: 'project', text: '旧组内内容' });
  town.roles[1].permissions.push('task.claim');
  await writeFile(f.store.file, JSON.stringify(legacy));
  const service = await new TownService({ store: f.store }).initialize();
  const snapshot = await service.handle('town.snapshot', { townId: f.townId }, f.alice.token);
  assert.equal(snapshot.projects[0].status, 'planned');
  assert.equal(snapshot.projects[0].expectedAt, undefined); assert.equal(snapshot.projects[0].memberIds, undefined);
  assert.equal(snapshot.messages.length, 0); assert.equal(snapshot.town.legacy, undefined);
  assert.equal(snapshot.tickets.length, 0);
  assert.equal(JSON.stringify(snapshot).includes('old-task'), false);
  assert.equal((await service.handle('town.list', {}, f.alice.token))[0].legacy, undefined);
  await service.handle('town.update', { townId: f.townId, name: '保留存档' }, f.alice.token);
  const saved = JSON.parse(await readFile(f.store.file, 'utf8'));
  assert.equal(saved.version, 2); assert.equal(saved.towns[f.townId].legacy.tasks[0].id, 'old-task');
  assert.equal(saved.towns[f.townId].legacy.privateMessages[0].text, '旧组内内容');
  assert.equal(saved.towns[f.townId].legacy.projects[0].expectedAt, '2026-11-01');
  assert.equal(saved.towns[f.townId].legacy.tickets[0].id, 'invalid-ticket');
  const second = await new TownService({ store: f.store }).initialize();
  assert.equal(second.state.towns[f.townId].legacy.tasks.length, 1);
});

test('不同小镇不能引用彼此数据，移除工程负责人前必须完成移交', async t => {
  const f = await fixture(t);
  const bob = await f.join();
  const project = await f.project({ leaderId: bob.player.id });
  const { townId: otherTown } = await f.call('town.create', { name: '外部小镇' });
  await rejects(f.call('project.update', { townId: otherTown, id: project.id, name: '跨镇' }), 'not_found');
  await rejects(f.call('member.remove', { townId: f.townId, playerId: bob.player.id }), 'conflict');
  await f.call('project.status', { townId: f.townId, id: project.id, status: 'active' }, bob.token);
  await f.call('project.status', { townId: f.townId, id: project.id, status: 'completed' }, bob.token);
  await rejects(f.call('member.remove', { townId: f.townId, playerId: bob.player.id }), 'conflict');
  assert.equal((await f.snapshot()).projects.find(item => item.id === project.id).leaderId, bob.player.id);
  await f.call('project.update', { townId: f.townId, id: project.id, leaderId: f.alice.player.id });
  await f.call('member.remove', { townId: f.townId, playerId: bob.player.id });
  await rejects(f.call('town.snapshot', { townId: f.townId }, bob.token), 'forbidden');
});


test('移除成员不改写历史周目的工程，历史登记者游戏名保留', async t => {
  const f = await fixture(t);
  const bob = await f.join();
  const project = await f.project({ leaderId: bob.player.id });
  await f.call('world.archive', { townId: f.townId, worldId: f.worldId });
  const before = JSON.stringify((await f.snapshot()).projects);
  await f.call('member.remove', { townId: f.townId, playerId: bob.player.id });
  const after = await f.snapshot();
  assert.equal(JSON.stringify(after.projects), before);
  assert.equal(after.projects.find(item => item.id === project.id).createdByName, 'Alice');
});

test('原子持久化可重启读取，失败事务不改变内存或磁盘，损坏文件拒绝启动', async t => {
  const f = await fixture(t);
  await f.project();
  const reloaded = await new TownService({ store: f.store }).initialize();
  assert.equal((await reloaded.handle('town.snapshot', { townId: f.townId }, f.alice.token)).projects.length, 1);
  const original = await readFile(f.store.file, 'utf8');
  const save = f.store.save;
  f.store.save = async () => { throw Object.assign(new Error('disk full'), { code: 'ENOSPC' }); };
  await assert.rejects(f.call('town.update', { townId: f.townId, name: '不能保存' }), { code: 'ENOSPC' });
  assert.equal((await f.snapshot()).town.name, '弥生小镇');
  assert.equal(await readFile(f.store.file, 'utf8'), original);
  f.store.save = save;
  assert.equal((await readdir(f.directory)).some(name => name.includes('.tmp-')), false);
  await writeFile(f.store.file, '{broken');
  await assert.rejects(new TownService({ store: f.store }).initialize(), /Invalid persisted JSON/);
});

test('HTTP 封装、请求格式、认证、请求大小及真实接口交互', async t => {
  const f = await fixture(t, { maxBodyBytes: 1024 });
  const server = createHttpServer(f.service, f.service.config, { error() {} });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  const endpoint = `http://127.0.0.1:${server.address().port}`;
  const request = (body, headers = {}) => fetch(`${endpoint}/api`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body });
  assert.equal((await (await fetch(`${endpoint}/health`)).json()).data.version, '1.0.0-alpha.5');
  assert.equal((await request('{broken')).status, 400);
  assert.equal((await request('[]')).status, 400);
  assert.equal((await request(JSON.stringify({ action: 'town.list' }))).status, 401);
  assert.equal((await request('{}', { 'Content-Type': 'text/plain' })).status, 415);
  assert.equal((await request(JSON.stringify({ padding: 'x'.repeat(2048) }))).status, 413);
  const login = await request(JSON.stringify({ action: 'auth.login', payload: { playerId: 'AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA', name: 'Alice' } }));
  assert.equal(login.status, 200);
  const identity = await login.json();
  assert.deepEqual(identity.data.player, { id: PLAYERS.Alice, name: 'Alice' });
  assert.match(identity.data.token, /^[a-f0-9]{64}$/);
  const response = await request(JSON.stringify({ action: 'town.snapshot', payload: { townId: f.townId } }), { Authorization: `Bearer ${identity.data.token}` });
  const result = await response.json();
  assert.equal(result.ok, true);
  assert.equal(result.data.town.name, '弥生小镇');
  assert.equal(JSON.stringify(result).includes('sessions'), false);
});

test('登录和 HTTP 请求频率受限，退出后会话失效', async t => {
  const f = await fixture(t, { authenticationAttemptsPerMinute: 1, requestsPerMinute: 1 });
  await f.login('Bob');
  await rejects(f.call('auth.login', { playerId: 'BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB', name: 'Renamed_Bob' }), 'rate_limited');
  const server = createHttpServer(f.service, f.service.config);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  const endpoint = `http://127.0.0.1:${server.address().port}/health`;
  assert.equal((await fetch(endpoint)).status, 200);
  assert.equal((await fetch(endpoint)).status, 429);
  await f.call('auth.logout');
  await rejects(f.call('town.list'), 'unauthenticated');
});

test('退出仅吊销当前设备，其他设备会话继续有效', async t => {
  const f = await fixture(t);
  const otherDevice = await f.login('Alice');
  await f.call('auth.logout');
  await rejects(f.call('town.list'), 'unauthenticated');
  assert.equal((await f.call('town.list', {}, otherDevice.token)).length, 1);
});
