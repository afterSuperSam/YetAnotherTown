import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { request as httpRequest } from 'node:http';
import { connect } from 'node:net';
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
  const second = { ...structuredClone(first), id: 'historical-overlap' };
  f.service.state.towns[f.townId].projects.push(second);
  const otherWorld = await f.call('world.create', { townId: f.townId, name: '新周目' });
  await f.project({ worldId: otherWorld.id, name: '其他周目重叠' });
  const snapshot = await f.call('town.snapshot', { townId: f.townId, worldId: f.worldId });
  assert.deepEqual(snapshot.projects.map(project => [project.id, project.overlapIds]), [[first.id, [second.id]], [second.id, [first.id]]]);
});

test('区域扫描与固定种子朴素算法等价，保留历史重叠、闭区间边界和原序', async t => {
  const f = await fixture(t);
  const template = await f.project();
  const next = await f.call('world.create', { townId: f.townId, name: '其他周目' });
  let randomState = 0x19a30f;
  const random = maximum => { randomState = (Math.imul(randomState, 1664525) + 1013904223) >>> 0; return randomState % maximum; };
  const projects = Array.from({ length: 500 }, (_, index) => {
    const minX = random(100) - 50; const minZ = random(100) - 50;
    return { ...template, id: String(index), worldId: random(2) ? next.id : f.worldId, dimension: ['minecraft:overworld', 'minecraft:the_nether', 'minecraft:the_end'][random(3)], chunks: { minX, maxX: minX + random(8), minZ, maxZ: minZ + random(8) } };
  });
  for (const chunks of [
    { minX: -3, maxX: -2, minZ: -1, maxZ: 0 },
    { minX: -2, maxX: -1, minZ: 0, maxZ: 1 },
    { minX: -1, maxX: -1, minZ: -2, maxZ: -1 },
  ]) projects.push({ ...template, id: String(projects.length), chunks });
  const vertical = Array.from({ length: 50 }, (_, index) => ({ ...template, id: `column-${index}`, chunks: { minX: -1, maxX: 0, minZ: index - 25, maxZ: index - 24 } })).reverse();
  const horizontal = vertical.map(project => ({ ...project, chunks: { minX: project.chunks.minZ, maxX: project.chunks.maxZ, minZ: project.chunks.minX, maxZ: project.chunks.maxX } }));
  for (const layout of [projects, vertical, horizontal]) {
    f.service.state.towns[f.townId].projects = layout;
    for (const worldId of [undefined, f.worldId, next.id]) {
      const selected = layout.filter(project => !worldId || project.worldId === worldId);
      const expected = selected.map(project => [project.id, selected.filter(other => other.id !== project.id && other.worldId === project.worldId && other.dimension === project.dimension && project.chunks.minX <= other.chunks.maxX && project.chunks.maxX >= other.chunks.minX && project.chunks.minZ <= other.chunks.maxZ && project.chunks.maxZ >= other.chunks.minZ).map(other => other.id)]);
      const actual = await f.call('town.snapshot', { townId: f.townId, worldId });
      assert.deepEqual(actual.projects.map(project => [project.id, project.overlapIds]), expected);
    }
  }
});

test('配置拒绝未知项、旧配置和非法范围', () => {
  assert.equal(validateConfig().port, 37841);
  assert.equal(validateConfig().dataFile, './data/yetanothertown.json');
  for (const key of ['challengeSeconds', 'defaultProximityRadius', 'maximumProximityRadius']) assert.throws(() => validateConfig({ [key]: 10 }), ApiError);
  for (const config of [{ port: 0 }, { port: '37841' }, { bypassAuthentication: true }, { sessionHours: -1 }, { maximumPendingRequests: 0 }, { maximumSessions: 1.5 }, { maximumSessionsPerPlayer: 0 }, { maximumTowns: 0 }, { maximumTownRecords: 5 }, { maximumRateLimitEntries: 0 }, { maximumTownBytes: 65535 }, { maximumTownBytes: 7340033 }, JSON.parse('{"toString":1}'), JSON.parse('{"__proto__":{}}')]) assert.throws(() => validateConfig(config), ApiError);
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
  assert.deepEqual(snapshot.limits, { maximumRegionChunks: 65536 });
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
  await rejects(f.project(), 'conflict');
  const second = await f.project({ chunks: { minX: 1, maxX: 1, minZ: 1, maxZ: 2 } });
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

test('留言接口、数据和权限目录均已移除，普通成员不能修改他人工程', async t => {
  const f = await fixture(t); const bob = await f.join();
  const project = await f.project();
  for (const action of ['message.create', 'message.delete']) await rejects(f.call(action, { townId: f.townId }), 'not_found');
  const snapshot = await f.snapshot();
  assert.equal(Object.hasOwn(snapshot, 'messages'), false);
  assert.equal(snapshot.permissionCatalog.some(item => item.id.startsWith('message.')), false);
  assert.equal(snapshot.roles.some(role => role.permissions.includes('message.create')), false);
  await rejects(f.call('project.update', { townId: f.townId, id: project.id, name: '越权' }, bob.token), 'forbidden');
});

test('完工工程报修后待维护，全部工单解决才回到完工，负责人可维护', async t => {
  const f = await fixture(t); const bob = await f.join(); const carol = await f.join('Carol');
  const project = await f.project();
  await f.call('project.update', { townId: f.townId, id: project.id, leaderId: bob.player.id });
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

test('周目隔离与归档保护覆盖工程和维修', async t => {
  const f = await fixture(t); const project = await f.project({ status: 'active' });
  await f.call('project.status', { townId: f.townId, id: project.id, status: 'completed' });
  const ticket = await f.call('ticket.create', { townId: f.townId, worldId: f.worldId, projectId: project.id, ...position, title: '维修' });
  const next = await f.call('world.create', { townId: f.townId, name: '第二周目', worldKey: 'world-2' });
  await rejects(f.call('ticket.create', { townId: f.townId, worldId: next.id, projectId: project.id, ...position, title: '跨周目' }), 'invalid_argument');
  await f.call('world.archive', { townId: f.townId, worldId: f.worldId });
  for (const [action, payload] of [['project.update', { id: project.id, name: '改名' }], ['project.status', { id: project.id, status: 'active' }], ['ticket.update', { id: ticket.id, status: 'working' }], ['ticket.create', { projectId: project.id, ...position, title: '新增' }]]) await rejects(f.call(action, { townId: f.townId, ...payload }), 'archived');
  assert.equal((await f.call('town.snapshot', { townId: f.townId, worldId: next.id })).projects.length, 0);
  assert.equal((await f.call('town.snapshot', { townId: f.townId, worldId: f.worldId })).projects.length, 1);
});

test('不同小镇不能引用彼此数据，移除工程负责人前必须完成移交', async t => {
  const f = await fixture(t);
  const bob = await f.join();
  const project = await f.project();
  await f.call('project.update', { townId: f.townId, id: project.id, leaderId: bob.player.id });
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
  const project = await f.project();
  await f.call('project.update', { townId: f.townId, id: project.id, leaderId: bob.player.id });
  await f.call('world.archive', { townId: f.townId, worldId: f.worldId });
  const before = JSON.stringify((await f.snapshot()).projects);
  await f.call('member.remove', { townId: f.townId, playerId: bob.player.id });
  const after = await f.snapshot();
  assert.equal(JSON.stringify(after.projects), before);
  assert.equal(after.projects.find(item => item.id === project.id).createdByName, 'Alice');
  const reloaded = await new TownService({ store: f.store }).initialize();
  assert.equal(JSON.stringify((await reloaded.handle('town.snapshot', { townId: f.townId }, f.alice.token)).projects), before);
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
  assert.equal((await (await fetch(`${endpoint}/health`)).json()).data.version, '1.0.0-alpha.11');
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


test('创建负责人固定当前 UUID，允许三种初始状态且开工完工必须有具体名称', async t => {
  const f = await fixture(t); const bob = await f.join();
  await rejects(f.project({ leaderId: bob.player.id }), 'invalid_argument');
  for (const status of ['active', 'completed']) {
    await rejects(f.project({ status, name: '' }), 'invalid_argument');
    await rejects(f.project({ status, name: '预留地块' }), 'invalid_argument');
  }
  await rejects(f.project({ status: 'maintenance' }), 'invalid_argument');
  const project = await f.project({ status: 'completed' });
  assert.equal(project.leaderId, f.alice.player.id);
  assert.equal(project.status, 'completed');
  const active = await f.project({ status: 'active', dimension: 'minecraft:the_nether' });
  assert.equal(active.status, 'active');
  await rejects(f.project(), 'conflict');
  await f.call('project.update', { townId: f.townId, id: project.id, leaderId: bob.player.id });
  await rejects(f.call('project.update', { townId: f.townId, id: project.id, leaderId: f.alice.player.id }, bob.token), 'forbidden');
});

test('并发创建和移动在事务中禁止重叠，维度隔离，历史重叠仍可编辑简介', async t => {
  const f = await fixture(t);
  const results = await Promise.allSettled([f.project(), f.project()]);
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
  assert.equal(results.find(result => result.status === 'rejected').reason.code, 'conflict');
  const first = results.find(result => result.status === 'fulfilled').value;
  const second = await f.project({ chunks: { minX: 1, maxX: 1, minZ: 1, maxZ: 2 } });
  await rejects(f.call('project.update', { townId: f.townId, id: second.id, chunks: first.chunks }), 'conflict');
  await f.project({ dimension: 'minecraft:the_nether' });
  f.service.state.towns[f.townId].projects[1].chunks = structuredClone(first.chunks);
  const edited = await f.call('project.update', { townId: f.townId, id: second.id, purpose: '保留历史占区' });
  assert.equal(edited.purpose, '保留历史占区');
  assert.equal((await f.snapshot()).projects.length, 3);
});

test('只接受当前数据格式，旧版本拒绝启动且文件保持原样', async t => {
  const f = await fixture(t);
  const state = JSON.parse(await readFile(f.store.file, 'utf8'));
  assert.equal(state.version, 3);
  for (const version of [1, 2]) {
    state.version = version;
    const serialized = JSON.stringify(state);
    await writeFile(f.store.file, serialized);
    await rejects(new TownService({ store: f.store }).initialize(), 'invalid_argument');
    assert.equal(await readFile(f.store.file, 'utf8'), serialized);
  }
});


test('权限组默认图标和自定义图标有效，内置身份与权限保护保持不变', async t => {
  const f = await fixture(t); const bob = await f.join();
  const initial = await f.snapshot();
  assert.deepEqual(initial.roles.map(role => [role.id, role.icon]), [
    ['newcomer', 'minecraft:dirt'], ['member', 'minecraft:cobblestone'],
    ['manager', 'minecraft:iron_block'], ['mayor', 'minecraft:emerald_block'],
  ]);
  const role = await f.call('role.save', { townId: f.townId, name: '维护员', icon: 'minecraft:gold_block', permissions: ['project.edit'] });
  assert.equal(role.icon, 'minecraft:gold_block');
  const changed = await f.call('role.save', { townId: f.townId, id: role.id, icon: 'example:role/stone' });
  assert.equal(changed.icon, 'example:role/stone');
  assert.deepEqual(changed.permissions, role.permissions);
  for (const icon of [null, 5, '', 'stone', 'Minecraft:stone', 'minecraft:bad icon', 'texture:minecraft:stone.png']) {
    await rejects(f.call('role.save', { townId: f.townId, id: role.id, icon }), 'invalid_argument');
  }
  for (const id of ['newcomer', 'mayor']) {
    const before = (await f.snapshot()).roles.find(role => role.id === id);
    const after = await f.call('role.save', { townId: f.townId, id, icon: 'minecraft:diamond_block' });
    assert.deepEqual(after, { ...before, icon: 'minecraft:diamond_block' });
    await rejects(f.call('role.save', { townId: f.townId, id, name: '改身份' }), 'forbidden');
    await rejects(f.call('role.save', { townId: f.townId, id, permissions: id === 'newcomer' ? ['project.create'] : [] }), 'forbidden');
    await rejects(f.call('role.delete', { townId: f.townId, id }), 'forbidden');
  }
  await rejects(f.call('role.save', { townId: f.townId, id: 'member', icon: 'minecraft:gold_block' }, bob.token), 'forbidden');
  const limited = await f.call('role.save', { townId: f.townId, name: '图标助手', permissions: ['roles.manage', 'members.manage'] });
  await f.call('member.role', { townId: f.townId, playerId: bob.player.id, roleId: limited.id });
  await rejects(f.call('role.save', { townId: f.townId, id: 'mayor', icon: 'minecraft:dirt' }, bob.token), 'forbidden');
  const snapshot = await f.snapshot();
  assert.deepEqual(snapshot.roles.find(role => role.id === 'newcomer').permissions, []);
  assert.equal(snapshot.members.filter(member => member.roleId === 'mayor').length, 1);
});

test('维修工单阻止工程跨维度移动，原有工单与磁盘保持一致', async t => {
  const f = await fixture(t);
  const project = await f.project({ status: 'completed' });
  await f.call('ticket.create', { townId: f.townId, projectId: project.id, ...position, title: '维修' });
  const original = await readFile(f.store.file, 'utf8');
  await rejects(f.call('project.update', { townId: f.townId, id: project.id, dimension: 'minecraft:the_nether' }), 'conflict');
  assert.equal(await readFile(f.store.file, 'utf8'), original);
  const reloaded = await new TownService({ store: f.store }).initialize();
  const snapshot = await reloaded.handle('town.snapshot', { townId: f.townId }, f.alice.token);
  assert.equal(snapshot.projects[0].dimension, snapshot.tickets[0].dimension);
});

test('密码派生等待期间事件循环仍可处理其他请求', async t => {
  const f = await fixture(t);
  f.store.save = async () => {};
  let responsive = false;
  const eventLoop = new Promise(resolve => setImmediate(() => { responsive = true; resolve(); }));
  await f.call('town.update', { townId: f.townId, password: 'strong_password' });
  assert.equal(responsive, true);
  await eventLoop;
});

test('会话数量有界，超限淘汰当前玩家最旧设备，已有设备同步游戏名', async t => {
  const f = await fixture(t, { maximumSessionsPerPlayer: 2, maximumSessions: 3 });
  const second = await f.login('Alice');
  const newest = await f.call('auth.login', { playerId: PLAYERS.Alice, name: 'Alice_New' });
  await rejects(f.call('town.list'), 'unauthenticated');
  assert.equal((await f.call('town.list', {}, second.token)).length, 1);
  assert.equal((await f.call('town.list', {}, newest.token)).length, 1);
  assert.equal(Object.values(f.service.state.sessions).every(session => session.player.name === 'Alice_New'), true);
  await f.login('Bob');
  await rejects(f.login('Carol'), 'rate_limited');
  assert.equal(Object.keys(f.service.state.sessions).length, 3);
  f.advance(168 * 3600000);
  await f.login('Carol');
  assert.equal(Object.keys(f.service.state.sessions).length, 1);
});

test('登录限流键有容量边界，到期后可重新登录', async t => {
  const f = await fixture(t, { maximumRateLimitEntries: 2 });
  await f.login('Bob');
  await rejects(f.login('Carol'), 'rate_limited');
  assert.equal(f.service.attempts.size, 2);
  f.advance(60000);
  await f.login('Carol');
  assert.equal(f.service.attempts.size, 1);
});

test('事务队列超限立即拒绝，保存完成或失败后释放容量', async t => {
  const f = await fixture(t, { maximumPendingRequests: 1 });
  const save = f.store.save.bind(f.store);
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  f.store.save = async state => { await gate; await save(state); };
  const first = f.call('town.update', { townId: f.townId, name: '队列验证' });
  await rejects(f.snapshot(), 'rate_limited');
  release(); await first;
  assert.equal((await f.snapshot()).town.name, '队列验证');
  f.store.save = async () => { throw new Error('disk full'); };
  await assert.rejects(f.call('town.update', { townId: f.townId, name: '失败' }), /disk full/);
  assert.equal(f.service.pendingRequests, 0);
  assert.equal((await f.snapshot()).town.name, '队列验证');
});

test('小镇和记录容量只限制新增，已存在资料仍可读取编辑', async t => {
  const f = await fixture(t, { maximumTowns: 1, maximumTownRecords: 7 });
  await rejects(f.call('town.create', { name: '超限' }), 'conflict');
  const project = await f.project();
  await rejects(f.project({ dimension: 'minecraft:the_nether' }), 'conflict');
  await rejects(f.call('world.create', { townId: f.townId, name: '超限周目' }), 'conflict');
  await rejects(f.call('role.save', { townId: f.townId, name: '超限权限组', permissions: [] }), 'conflict');
  await f.call('project.update', { townId: f.townId, id: project.id, purpose: '仍可维护' });
  const smaller = await new TownService({ store: f.store, config: { maximumTownRecords: 6 } }).initialize();
  assert.equal((await smaller.handle('town.snapshot', { townId: f.townId }, f.alice.token)).projects.length, 1);
});

test('小镇容量按中文 UTF-8 字节校验，超限写入不改变内存与磁盘', async t => {
  const f = await fixture(t, { maximumTownBytes: 65536 });
  for (let index = 0; index < 7; index++) await f.project({ purpose: '中'.repeat(2000), chunks: { minX: index, maxX: index, minZ: 0, maxZ: 0 } });
  const original = await readFile(f.store.file, 'utf8');
  const snapshot = await f.snapshot();
  const payload = { purpose: '中'.repeat(2000), chunks: { minX: 8, maxX: 8, minZ: 0, maxZ: 0 } };
  await rejects(f.project(payload), 'conflict');
  assert.equal(await readFile(f.store.file, 'utf8'), original);
  assert.deepEqual(await f.snapshot(), snapshot);
  await f.project({ ...payload, purpose: 'x'.repeat(2000) });
  const latest = await f.snapshot();
  assert.equal(latest.projects.length, 8);
  assert.ok(Buffer.byteLength(JSON.stringify({ ok: true, data: latest })) < f.service.config.maximumTownBytes);
});

test('已有超限资料可原样打开并缩减，容量仍超限时拒绝新增与增长', async t => {
  const f = await fixture(t);
  for (let index = 0; index < 12; index++) await f.project({ purpose: '中'.repeat(2000), chunks: { minX: index, maxX: index, minZ: 0, maxZ: 0 } });
  const service = await new TownService({ store: f.store, config: { maximumTownBytes: 65536 } }).initialize();
  const snapshot = await service.handle('town.snapshot', { townId: f.townId }, f.alice.token);
  const original = await readFile(f.store.file, 'utf8');
  await rejects(service.handle('town.update', { townId: f.townId, introduction: '增长' }, f.alice.token), 'conflict');
  await rejects(service.handle('world.create', { townId: f.townId, name: '增长' }, f.alice.token), 'conflict');
  assert.equal(await readFile(f.store.file, 'utf8'), original);
  await service.handle('project.update', { townId: f.townId, id: snapshot.projects[0].id, purpose: '' }, f.alice.token);
  assert.equal((await service.handle('town.snapshot', { townId: f.townId }, f.alice.token)).projects[0].purpose, '');
  assert.ok(Buffer.byteLength(await readFile(f.store.file, 'utf8')) < Buffer.byteLength(original));
});

test('容量预算计入历史工程重叠列表，原始文件较小仍不能继续增长', async t => {
  const f = await fixture(t, { maximumTownBytes: 65536 });
  const template = await f.project();
  f.service.state.towns[f.townId].projects = Array.from({ length: 100 }, (_, index) => ({ ...template, id: String(index).padStart(24, '0') }));
  await f.store.save(f.service.state);
  assert.ok(Buffer.byteLength(JSON.stringify(f.service.state.towns[f.townId])) < 65536);
  const original = await readFile(f.store.file, 'utf8');
  await rejects(f.call('role.save', { townId: f.townId, name: '额外权限组', permissions: [] }), 'conflict');
  assert.equal(await readFile(f.store.file, 'utf8'), original);
});

test('无效认证与未知操作不复制全服务状态', async t => {
  const f = await fixture(t);
  let reads = 0;
  Object.defineProperty(f.service.state, 'unrelated', { enumerable: true, get() { reads++; return {}; } });
  await rejects(f.service.handle('town.update', { townId: f.townId, name: '越权' }), 'unauthenticated');
  await rejects(f.call('missing.action', { townId: f.townId }), 'not_found');
  await rejects(f.call('auth.login', { playerId: 'invalid', name: 'Alice' }), 'invalid_argument');
  assert.equal(reads, 0);
});

test('持久化损坏的子记录、权限和关联拒绝加载且原文不变', async t => {
  const f = await fixture(t);
  const project = await f.project({ status: 'completed' });
  await f.call('ticket.create', { townId: f.townId, projectId: project.id, ...position, title: '维修' });
  const original = JSON.parse(await readFile(f.store.file, 'utf8'));
  const corruptions = [
    town => { town.members.push(null); },
    town => { town.roles[0].permissions = ['town.manage']; },
    town => { town.roles[1].permissions = ['toString']; },
    town => { town.members[0].roleId = 'missing'; },
    town => { town.password = { salt: 'invalid', hash: 'invalid' }; },
    town => { town.projects[0].chunks.minX = 3; },
    town => { town.projects[0].worldId = 'missing'; },
    town => { town.tickets[0].dimension = 'minecraft:the_nether'; },
    town => { town.projects[0].status = 'completed'; },
    town => { town.tickets[0].projectId = 'missing'; },
  ];
  for (const corrupt of corruptions) {
    const state = structuredClone(original); corrupt(state.towns[f.townId]);
    const serialized = JSON.stringify(state); await writeFile(f.store.file, serialized);
    await rejects(new TownService({ store: f.store }).initialize(), 'invalid_argument');
    assert.equal(await readFile(f.store.file, 'utf8'), serialized);
  }
  const state = structuredClone(original); Object.values(state.sessions)[0].player.name = { toString: null };
  await writeFile(f.store.file, JSON.stringify(state));
  await rejects(new TownService({ store: f.store }).initialize(), 'invalid_argument');
});

test('当前格式的密码、自定义权限、多设备、周目和历史维修均可原样重启', async t => {
  const f = await fixture(t); const bob = await f.join();
  await f.login('Alice');
  await f.call('town.update', { townId: f.townId, password: 'strong_password' });
  const role = await f.call('role.save', { townId: f.townId, name: '维护员', icon: 'example:role/stone', permissions: ['project.edit', 'ticket.create'] });
  await f.call('member.role', { townId: f.townId, playerId: bob.player.id, roleId: role.id });
  const project = await f.project({ status: 'completed' });
  const ticket = await f.call('ticket.create', { townId: f.townId, projectId: project.id, ...position, title: '历史维修' }, bob.token);
  await f.call('ticket.update', { townId: f.townId, id: ticket.id, status: 'resolved', resolution: '恢复' });
  await f.call('world.archive', { townId: f.townId, worldId: f.worldId });
  await f.call('member.remove', { townId: f.townId, playerId: bob.player.id });
  const world = await f.call('world.create', { townId: f.townId, name: '新周目' });
  await f.project({ worldId: world.id });
  await f.project({ worldId: world.id, dimension: 'minecraft:the_nether', status: 'active' });
  const current = await f.project({ worldId: world.id, dimension: 'minecraft:the_end', status: 'completed' });
  await f.call('ticket.create', { townId: f.townId, projectId: current.id, ...position, dimension: current.dimension, title: '当前维修' });
  const before = await readFile(f.store.file, 'utf8');
  const reloaded = await new TownService({ store: f.store }).initialize();
  assert.deepEqual(reloaded.state, f.service.state);
  assert.equal(await readFile(f.store.file, 'utf8'), before);
});

test('HTTP 读取中途断连不会产生未处理流错误，服务继续响应', async t => {
  const f = await fixture(t);
  const server = createHttpServer(f.service, f.service.config, { error() {} });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  let close;
  const closed = new Promise(resolve => { close = resolve; });
  const received = new Promise(resolve => server.once('request', request => { request.once('close', close); resolve(); }));
  const socket = connect(server.address().port, '127.0.0.1');
  t.after(() => socket.destroy());
  await new Promise((resolve, reject) => { socket.once('connect', resolve); socket.once('error', reject); });
  socket.write('POST /api HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{');
  await received; socket.destroy(); await closed;
  assert.equal((await fetch(`http://127.0.0.1:${server.address().port}/health`)).status, 200);
});

test('HTTP 立即拒绝未结束超限流和超时请求，显式空 payload 不当作有效对象', async t => {
  const f = await fixture(t, { maxBodyBytes: 1024, requestTimeoutSeconds: 1 });
  const server = createHttpServer(f.service, f.service.config, { error() {} });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  const endpoint = `http://127.0.0.1:${server.address().port}/api`;
  const partial = (headers, body) => new Promise((resolve, reject) => {
    const request = httpRequest(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers } }, response => {
      response.resume(); response.once('end', () => { clearTimeout(deadline); request.destroy(); resolve(response.statusCode); });
    });
    const deadline = setTimeout(() => { request.destroy(); reject(new Error('Response did not arrive before request ended')); }, 2500);
    request.on('error', error => { clearTimeout(deadline); reject(error); });
    request.flushHeaders(); if (body) request.write(body);
  });
  assert.equal(await partial({ 'Content-Length': '2048' }), 413);
  assert.equal(await partial({ 'Transfer-Encoding': 'chunked' }, 'x'.repeat(2048)), 413);
  assert.equal(await partial({ 'Transfer-Encoding': 'chunked' }, '{'), 408);
  const response = await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${f.alice.token}` }, body: JSON.stringify({ action: 'town.list', payload: null }) });
  assert.equal(response.status, 400);
});
