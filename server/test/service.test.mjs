import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { ApiError, JsonStore, TownService, validateConfig, verifyMojang } from '../src/service.mjs';
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
  const verifier = async username => ({ id: PLAYERS[username], name: username });
  const service = await new TownService({ store, verifier, now: () => time, config }).initialize();
  const login = async username => {
    const challenge = await service.handle('auth.challenge', { username });
    assert.match(challenge.serverHash, /^[a-f0-9]{40}$/);
    return service.handle('auth.verify', { challengeId: challenge.challengeId });
  };
  const alice = await login('Alice');
  const call = (action, payload = {}, token = alice.token) => service.handle(action, payload, token);
  const { townId } = await call('town.create', { name: '弥生小镇', server: 'example.test' });
  const snapshot = () => call('town.snapshot', { townId });
  const worldId = (await snapshot()).worlds[0].id;
  const project = async extra => call('project.create', {
    townId, worldId, name: '建车站', type: 'building', purpose: '公共交通', dimension: 'minecraft:overworld',
    chunks: { minX: -2, maxX: 0, minZ: 1, maxZ: 2 }, expectedAt: '2026-11-01', ...extra,
  });
  const join = async (name = 'Bob', promote = true) => {
    const account = await login(name);
    await call('town.join', { townId }, account.token);
    if (promote) await call('member.role', { townId, playerId: account.player.id, roleId: 'member' });
    return account;
  };
  return { directory, store, service, verifier, login, alice, call, townId, worldId, snapshot, project, join, advance: milliseconds => { time += milliseconds; } };
}

test('配置拒绝未知项、非法范围和相互冲突的提示距离', () => {
  assert.equal(validateConfig().port, 37841);
  for (const config of [{ port: 0 }, { port: '37841' }, { bypassAuthentication: true }, { sessionHours: -1 }, { defaultProximityRadius: 300, maximumProximityRadius: 100 }, JSON.parse('{"toString":1}'), JSON.parse('{"__proto__":{}}')]) assert.throws(() => validateConfig(config), ApiError);
});

test('正版验证挑战单次使用、到期失效；会话到期要求重新登录', async t => {
  const f = await fixture(t, { challengeSeconds: 10, sessionHours: 1 });
  const challenge = await f.call('auth.challenge', { username: 'Bob' });
  const result = await f.call('auth.verify', { challengeId: challenge.challengeId });
  assert.equal(result.player.id, PLAYERS.Bob);
  await rejects(f.call('auth.verify', { challengeId: challenge.challengeId }), 'unauthenticated');
  const expired = await f.call('auth.challenge', { username: 'Carol' });
  f.advance(11000);
  await rejects(f.call('auth.verify', { challengeId: expired.challengeId }), 'unauthenticated');
  f.advance(3600000);
  await rejects(f.call('town.list'), 'unauthenticated');
  await rejects(f.service.handle('town.list'), 'unauthenticated');
});

test('验证结果必须匹配被挑战的游戏名和正版 UUID，拒绝认证绕过', async t => {
  const f = await fixture(t);
  f.service.verifier = async () => ({ id: PLAYERS.Bob, name: 'Bob' });
  const challenge = await f.call('auth.challenge', { username: 'Carol' });
  await rejects(f.call('auth.verify', { challengeId: challenge.challengeId, player: { id: PLAYERS.Carol, name: 'Carol' } }), 'unauthenticated');
  await rejects(f.call('auth.challenge', { username: 'not valid' }), 'invalid_argument');
});

test('Mojang 请求仅调用官方 hasJoined；上游失败及未完成登录明确分类', async t => {
  let observed;
  t.mock.method(globalThis, 'fetch', async url => { observed = url; return new Response(JSON.stringify({ id: PLAYERS.Alice, name: 'Alice' }), { status: 200 }); });
  assert.equal((await verifyMojang('Alice', 'hash')).id, PLAYERS.Alice);
  assert.equal(observed.origin, 'https://sessionserver.mojang.com');
  assert.equal(observed.searchParams.get('username'), 'Alice');
  assert.equal(observed.searchParams.get('serverId'), 'hash');
  globalThis.fetch.mock.mockImplementation(async () => new Response(null, { status: 204 }));
  await rejects(verifyMojang('Alice', 'hash'), 'unauthenticated');
  globalThis.fetch.mock.mockImplementation(async () => { throw new Error('offline'); });
  await rejects(verifyMojang('Alice', 'hash'), 'upstream_unavailable');
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
  const role = await f.call('role.save', { townId: f.townId, name: '维护员', permissions: ['facility.manage'] });
  await f.call('member.role', { townId: f.townId, playerId: bob.player.id, roleId: role.id });
  await rejects(f.call('role.delete', { townId: f.townId, id: role.id }), 'conflict');
  const edited = await f.call('role.save', { townId: f.townId, id: role.id, name: '设施维护员', permissions: ['facility.manage', 'ticket.manage'] });
  assert.equal(edited.name, '设施维护员');
  await f.call('member.role', { townId: f.townId, playerId: bob.player.id, roleId: 'member' });
  await f.call('role.delete', { townId: f.townId, id: role.id });
  assert.equal((await f.snapshot()).roles.some(item => item.id === role.id), false);
});

test('工程区块矩形校验、重叠提示、审批和重新框选后重新审批', async t => {
  const f = await fixture(t);
  await rejects(f.project({ chunks: { minX: 2, maxX: 1, minZ: 0, maxZ: 1 } }), 'invalid_argument');
  await rejects(f.project({ chunks: { minX: 0.5, maxX: 1, minZ: 0, maxZ: 1 } }), 'invalid_argument');
  await rejects(f.project({ dimension: 'overworld' }), 'invalid_argument');
  const first = await f.project();
  const second = await f.project({ name: '相邻建筑' });
  const nether = await f.project({ name: '下界建筑', dimension: 'minecraft:the_nether' });
  const projects = (await f.snapshot()).projects;
  assert.deepEqual(projects.find(item => item.id === first.id).overlapIds, [second.id]);
  assert.deepEqual(projects.find(item => item.id === nether.id).overlapIds, []);
  await f.call('project.review', { townId: f.townId, id: first.id, decision: 'approve' });
  const updated = await f.call('project.update', { townId: f.townId, id: first.id, chunks: { minX: 5, maxX: 5, minZ: 5, maxZ: 5 } });
  assert.equal(updated.status, 'pending');
  assert.equal(updated.createdByName, 'Alice');
});

test('工程任务、材料认捐到负责人确认及整体验收的完整流程', async t => {
  const f = await fixture(t);
  const bob = await f.join();
  const project = await f.project();
  const task = await f.call('task.create', { townId: f.townId, projectId: project.id, title: '清地', description: '清理两区块' });
  const material = await f.call('material.create', { townId: f.townId, projectId: project.id, name: '铁锭', required: 64 });
  await rejects(f.call('task.claim', { townId: f.townId, id: task.id }, bob.token), 'conflict');
  await f.call('project.review', { townId: f.townId, id: project.id, decision: 'approve' });
  await f.call('task.claim', { townId: f.townId, id: task.id }, bob.token);
  await rejects(f.call('task.submit', { townId: f.townId, id: task.id }), 'forbidden');
  await f.call('task.submit', { townId: f.townId, id: task.id }, bob.token);
  await rejects(f.call('task.confirm', { townId: f.townId, id: task.id }, bob.token), 'forbidden');
  await f.call('task.confirm', { townId: f.townId, id: task.id });
  await rejects(f.call('project.submit', { townId: f.townId, id: project.id }), 'conflict');
  const pledge = await f.call('material.pledge', { townId: f.townId, materialId: material.id, quantity: 64 }, bob.token);
  await rejects(f.call('pledge.confirm', { townId: f.townId, id: pledge.id }), 'conflict');
  await f.call('pledge.submit', { townId: f.townId, id: pledge.id }, bob.token);
  await f.call('pledge.confirm', { townId: f.townId, id: pledge.id });
  assert.equal((await f.call('project.submit', { townId: f.townId, id: project.id })).status, 'review');
  assert.equal((await f.call('project.review', { townId: f.townId, id: project.id, decision: 'accept' })).status, 'completed');
  await rejects(f.call('task.create', { townId: f.townId, projectId: project.id, title: '迟到任务' }), 'conflict');
});

test('并发领取只能一人成功，认捐原子计数禁止超额，可释放与取消纠错', async t => {
  const f = await fixture(t);
  const bob = await f.join();
  const carol = await f.join('Carol');
  const project = await f.project();
  await f.call('project.review', { townId: f.townId, id: project.id, decision: 'approve' });
  const task = await f.call('task.create', { townId: f.townId, projectId: project.id, title: '备料' });
  const claims = await Promise.allSettled([bob, carol].map(account => f.call('task.claim', { townId: f.townId, id: task.id }, account.token)));
  assert.equal(claims.filter(item => item.status === 'fulfilled').length, 1);
  await f.call('task.release', { townId: f.townId, id: task.id });
  assert.equal((await f.snapshot()).tasks[0].status, 'open');
  const material = await f.call('material.create', { townId: f.townId, projectId: project.id, name: '石头', required: 100 });
  const pledges = await Promise.allSettled([bob, carol].map(account => f.call('material.pledge', { townId: f.townId, materialId: material.id, quantity: 60 }, account.token)));
  assert.equal(pledges.filter(item => item.status === 'fulfilled').length, 1);
  assert.equal(pledges.find(item => item.status === 'rejected').reason.code, 'conflict');
  const pledge = pledges.find(item => item.status === 'fulfilled').value;
  await f.call('pledge.cancel', { townId: f.townId, id: pledge.id });
  const full = await f.call('material.pledge', { townId: f.townId, materialId: material.id, quantity: 100 }, bob.token);
  assert.equal(full.quantity, 100);
  assert.equal((await f.snapshot()).pledges.length, 1);
});

test('项目组空间留言在服务端过滤，普通成员不可查看或修改他人工程', async t => {
  const f = await fixture(t);
  const bob = await f.join();
  const carol = await f.join('Carol');
  const project = await f.project({ memberIds: [bob.player.id] });
  const privateMessage = await f.call('message.create', { townId: f.townId, worldId: f.worldId, projectId: project.id, ...position, visibility: 'project', text: '材料在二楼' }, bob.token);
  await f.call('message.create', { townId: f.townId, worldId: f.worldId, ...position, text: '这里准备接路' });
  const bobSnapshot = await f.call('town.snapshot', { townId: f.townId }, bob.token);
  const carolSnapshot = await f.call('town.snapshot', { townId: f.townId }, carol.token);
  assert.equal(bobSnapshot.messages.length, 2);
  assert.equal(carolSnapshot.messages.length, 1);
  await rejects(f.call('message.create', { townId: f.townId, worldId: f.worldId, projectId: project.id, ...position, visibility: 'project', text: '越权' }, carol.token), 'forbidden');
  await rejects(f.call('message.delete', { townId: f.townId, id: privateMessage.id }, carol.token), 'forbidden');
  await rejects(f.call('project.update', { townId: f.townId, id: project.id, name: '越权修改' }, carol.token), 'forbidden');
  await f.call('message.delete', { townId: f.townId, id: privateMessage.id }, bob.token);
  assert.equal((await f.snapshot()).messages.length, 1);
});

test('公共设施卡可更新维护人，维修工单自动携带位置并经历处理闭环', async t => {
  const f = await fixture(t);
  const bob = await f.join();
  await rejects(f.call('facility.create', { townId: f.townId, worldId: f.worldId }, bob.token), 'forbidden');
  const facility = await f.call('facility.create', { townId: f.townId, worldId: f.worldId, ...position, name: '刷铁机', instructions: '站在平台挂机', rules: '离开前关闭', maintainerId: bob.player.id });
  assert.equal(facility.radius, 32);
  const updated = await f.call('facility.update', { townId: f.townId, id: facility.id, instructions: '先检查开关' });
  assert.equal(updated.instructions, '先检查开关');
  const ticket = await f.call('ticket.create', { townId: f.townId, worldId: f.worldId, ...position, title: '机器堵塞', description: '漏斗满了' }, bob.token);
  assert.equal(ticket.worldId, f.worldId);
  assert.equal(ticket.dimension, position.dimension);
  assert.equal(ticket.x, -17);
  await rejects(f.call('ticket.update', { townId: f.townId, id: ticket.id, status: 'working' }, bob.token), 'forbidden');
  await f.call('ticket.update', { townId: f.townId, id: ticket.id, status: 'working' });
  await rejects(f.call('ticket.update', { townId: f.townId, id: ticket.id, status: 'resolved' }), 'invalid_argument');
  await f.call('ticket.update', { townId: f.townId, id: ticket.id, status: 'resolved', resolution: '疏通漏斗' });
  assert.equal((await f.snapshot()).tickets[0].status, 'resolved');
});

test('周目严格隔离、归档后所有业务只读，同时可建立新周目', async t => {
  const f = await fixture(t);
  const project = await f.project();
  await f.call('project.review', { townId: f.townId, id: project.id, decision: 'approve' });
  const task = await f.call('task.create', { townId: f.townId, projectId: project.id, title: '清地' });
  const material = await f.call('material.create', { townId: f.townId, projectId: project.id, name: '木头', required: 64 });
  const pledge = await f.call('material.pledge', { townId: f.townId, materialId: material.id, quantity: 64 });
  const message = await f.call('message.create', { townId: f.townId, worldId: f.worldId, ...position, text: '旧留言' });
  const facility = await f.call('facility.create', { townId: f.townId, worldId: f.worldId, ...position, name: '旧机器', instructions: '旧说明' });
  const ticket = await f.call('ticket.create', { townId: f.townId, worldId: f.worldId, ...position, title: '旧维修' });
  const next = await f.call('world.create', { townId: f.townId, name: '第二周目', serverAddress: 'example.test', worldKey: 'world-2' });
  await f.call('world.update', { townId: f.townId, id: f.worldId, worldKey: 'world-1' });
  await rejects(f.call('message.create', { townId: f.townId, worldId: next.id, projectId: project.id, ...position, text: '跨周目' }), 'invalid_argument');
  await rejects(f.call('ticket.create', { townId: f.townId, worldId: next.id, projectId: project.id, ...position, title: '跨周目' }), 'invalid_argument');
  await f.call('world.archive', { townId: f.townId, worldId: f.worldId });
  for (const [action, payload] of [
    ['world.update', { id: f.worldId, worldKey: '不能覆盖历史标识' }],
    ['project.update', { id: project.id, name: '改名' }], ['project.review', { id: project.id, decision: 'reject' }],
    ['task.claim', { id: task.id }], ['task.create', { projectId: project.id, title: '新增' }],
    ['material.pledge', { materialId: material.id, quantity: 1 }], ['pledge.cancel', { id: pledge.id }],
    ['message.delete', { id: message.id }], ['facility.update', { id: facility.id, name: '改名' }],
    ['ticket.update', { id: ticket.id, status: 'working' }],
    ['ticket.create', { worldId: f.worldId, ...position, title: '新增' }],
  ]) await rejects(f.call(action, { townId: f.townId, ...payload }), 'archived');
  const history = await f.call('town.snapshot', { townId: f.townId, worldId: f.worldId });
  assert.equal(history.projects.length, 1);
  const current = await f.call('town.snapshot', { townId: f.townId, worldId: next.id });
  assert.equal(current.projects.length, 0);
  assert.equal(current.worlds.find(world => world.id === next.id).worldKey, 'world-2');
});

test('不同小镇不能引用彼此数据，移除工程负责人前必须完成移交', async t => {
  const f = await fixture(t);
  const bob = await f.join();
  const project = await f.project({ leaderId: bob.player.id });
  const { townId: otherTown } = await f.call('town.create', { name: '外部小镇' });
  await rejects(f.call('project.update', { townId: otherTown, id: project.id, name: '跨镇' }), 'not_found');
  await rejects(f.call('member.remove', { townId: f.townId, playerId: bob.player.id }), 'conflict');
  await f.call('project.update', { townId: f.townId, id: project.id, leaderId: f.alice.player.id });
  await f.call('member.remove', { townId: f.townId, playerId: bob.player.id });
  await rejects(f.call('town.snapshot', { townId: f.townId }, bob.token), 'forbidden');
});


test('移除成员不改写历史周目的项目组，历史登记者游戏名保留', async t => {
  const f = await fixture(t);
  const bob = await f.join();
  const project = await f.project({ leaderId: bob.player.id, memberIds: [bob.player.id] });
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
  const reloaded = await new TownService({ store: f.store, verifier: f.verifier }).initialize();
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
  assert.equal((await (await fetch(`${endpoint}/health`)).json()).data.version, '1.0.0');
  assert.equal((await request('{broken')).status, 400);
  assert.equal((await request('[]')).status, 400);
  assert.equal((await request(JSON.stringify({ action: 'town.list' }))).status, 401);
  assert.equal((await request('{}', { 'Content-Type': 'text/plain' })).status, 415);
  assert.equal((await request(JSON.stringify({ padding: 'x'.repeat(2048) }))).status, 413);
  const response = await request(JSON.stringify({ action: 'town.snapshot', payload: { townId: f.townId } }), { Authorization: `Bearer ${f.alice.token}` });
  const result = await response.json();
  assert.equal(result.ok, true);
  assert.equal(result.data.town.name, '弥生小镇');
  assert.equal(JSON.stringify(result).includes('sessions'), false);
});

test('登录和 HTTP 请求频率受限，退出后会话失效', async t => {
  const f = await fixture(t, { authenticationAttemptsPerMinute: 1, requestsPerMinute: 1 });
  await f.call('auth.challenge', { username: 'Bob' });
  await rejects(f.call('auth.challenge', { username: 'Bob' }), 'rate_limited');
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
