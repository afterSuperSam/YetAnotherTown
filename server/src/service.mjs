import { createHash, randomBytes, randomInt, scryptSync, timingSafeEqual } from 'node:crypto';
import { mkdir, open, readFile, rename, unlink } from 'node:fs/promises';
import path from 'node:path';

export const DEFAULT_CONFIG = Object.freeze({
  host: '127.0.0.1', port: 37841, dataFile: './data/ohmytown.json', sessionHours: 168,
  requestTimeoutSeconds: 15, maxBodyBytes: 65536,
  requestsPerMinute: 120, authenticationAttemptsPerMinute: 10,
  maximumRegionChunks: 65536,
});

export const PERMISSIONS = Object.freeze({
  'town.manage': '管理小镇资料与密码', 'members.manage': '审核与管理成员',
  'roles.manage': '管理权限组', 'worlds.manage': '管理世界与周目',
  'project.create': '登记建设工程', 'project.edit': '维护所属工程',
  'project.approve': '管理所有工程',
  'ticket.create': '报告维修工单',
  'ticket.manage': '处理维修工单',
});
const MEMBER_PERMISSIONS = [
  'project.create', 'project.edit', 'ticket.create',
];
const COLLECTIONS = ['roles', 'members', 'worlds', 'projects', 'tickets'];
const DIMENSION = /^[a-z0-9_.-]+:[a-z0-9_/.-]+$/;

export class ApiError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}
const fail = (code, message) => { throw new ApiError(code, message); };
const check = (condition, code, message) => { if (!condition) fail(code, message); };
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const identifier = () => randomBytes(12).toString('hex');
const clone = value => structuredClone(value);
const tokenKey = token => createHash('sha256').update(token).digest('hex');

export function validateConfig(input = {}) {
  check(object(input), 'invalid_argument', '配置必须是 JSON 对象');
  const result = { ...DEFAULT_CONFIG };
  for (const [key, value] of Object.entries(input)) {
    check(Object.hasOwn(DEFAULT_CONFIG, key), 'invalid_argument', `未知配置项：${key}`);
    result[key] = value;
  }
  for (const key of ['host', 'dataFile']) check(typeof result[key] === 'string' && result[key].trim().length > 0, 'invalid_argument', `配置 ${key} 无效`);
  const limits = {
    port: [1, 65535], sessionHours: [1, 8760],
    requestTimeoutSeconds: [1, 120], maxBodyBytes: [1024, 1048576],
    requestsPerMinute: [1, 10000], authenticationAttemptsPerMinute: [1, 100],
    maximumRegionChunks: [1, 1048576],
  };
  for (const [key, [minimum, maximum]] of Object.entries(limits)) {
    check(Number.isSafeInteger(result[key]) && result[key] >= minimum && result[key] <= maximum, 'invalid_argument', `配置 ${key} 必须为 ${minimum}–${maximum} 的整数`);
  }
  return Object.freeze(result);
}

function text(value, name, maximum = 200, optional = false) {
  if (optional && value === undefined) return '';
  check(typeof value === 'string', 'invalid_argument', `${name}必须是文本`);
  const result = value.trim();
  check((optional || result.length > 0) && result.length <= maximum, 'invalid_argument', `${name}长度必须为 ${optional ? 0 : 1}–${maximum}`);
  return result;
}
function integer(value, name, minimum, maximum) {
  check(Number.isSafeInteger(value) && value >= minimum && value <= maximum, 'invalid_argument', `${name}必须为 ${minimum}–${maximum} 的整数`);
  return value;
}
function choice(value, values, name) {
  check(values.includes(value), 'invalid_argument', `${name}无效`);
  return value;
}
function hashPassword(password) {
  const value = text(password, '小镇密码', 128, true);
  if (!value) return null;
  check(value.length >= 6, 'invalid_argument', '小镇密码至少需要 6 个字符');
  const salt = randomBytes(16).toString('hex');
  return { salt, hash: scryptSync(value, salt, 32).toString('hex') };
}
function validPassword(password, saved) {
  if (!saved) return true;
  const value = text(password, '小镇密码', 128, true);
  return timingSafeEqual(scryptSync(value, saved.salt, 32), Buffer.from(saved.hash, 'hex'));
}
function publicTown(town) {
  const { password, ...details } = town;
  for (const key of COLLECTIONS) delete details[key];
  return { ...details, passwordEnabled: Boolean(password) };
}

// 数据仅在完整校验后加载；每次事务先落盘，再替换内存快照。
export class JsonStore {
  constructor(file) { this.file = file; }
  async load() {
    let serialized;
    try { serialized = await readFile(this.file, 'utf8'); }
    catch (error) { if (error.code === 'ENOENT') return { version: 3, towns: {}, sessions: {} }; throw error; }
    let state;
    try { state = JSON.parse(serialized); } catch { throw new Error('Invalid persisted JSON; restore a verified backup before starting.'); }
    check(object(state) && state.version === 3 && object(state.towns) && object(state.sessions), 'invalid_argument', '持久化数据版本或结构无效');
    for (const [id, town] of Object.entries(state.towns)) {
      check(/^\d{8}$/.test(id) && object(town) && town.id === id && COLLECTIONS.every(key => Array.isArray(town[key])), 'invalid_argument', '持久化小镇数据无效');
      check(town.members.filter(member => member.roleId === 'mayor').length === 1 && town.members.some(member => member.playerId === town.ownerId && member.roleId === 'mayor'), 'invalid_argument', '持久化镇长数据无效');
    }
    return state;
  }
  async save(state) {
    await mkdir(path.dirname(this.file), { recursive: true });
    const temporary = `${this.file}.tmp-${process.pid}-${identifier()}`;
    let handle;
    try {
      handle = await open(temporary, 'wx', 0o600);
      await handle.writeFile(JSON.stringify(state), 'utf8');
      await handle.sync();
      await handle.close();
      handle = undefined;
      await rename(temporary, this.file);
    } catch (error) {
      await handle?.close().catch(() => {});
      await unlink(temporary).catch(() => {});
      throw error;
    }
  }
}

export class TownService {
  constructor({ store, now = Date.now, config = {} }) {
    this.store = store;
    this.now = now;
    this.config = validateConfig(config);
    this.attempts = new Map();
    this.queue = Promise.resolve();
  }
  async initialize() { this.state = await this.store.load(); return this; }
  handle(action, payload = {}, token) {
    const operation = this.queue.then(() => this.execute(action, payload, token));
    this.queue = operation.catch(() => {});
    return operation;
  }
  authenticationAttempt(key) {
    const now = this.now();
    for (const [entry, window] of this.attempts) if (window.until <= now) this.attempts.delete(entry);
    const window = this.attempts.get(key) ?? { until: now + 60000, count: 0 };
    window.count += 1;
    this.attempts.set(key, window);
    check(window.count <= this.config.authenticationAttemptsPerMinute, 'rate_limited', '尝试次数过多，请稍后重试');
  }
  async execute(action, payload, token) {
    check(typeof action === 'string' && object(payload), 'invalid_argument', '请求格式无效');
    const now = this.now();
    const state = ['town.list', 'town.snapshot'].includes(action) ? this.state : clone(this.state);
    let result;
    let mutated = false;
    if (action === 'auth.login') {
      check(typeof payload.playerId === 'string' && /^(?:[a-f0-9]{32}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})$/i.test(payload.playerId), 'invalid_argument', '游戏身份 UUID 无效');
      check(typeof payload.name === 'string' && /^[A-Za-z0-9_]{1,16}$/.test(payload.name), 'invalid_argument', '游戏名必须为 1–16 位字母、数字或下划线');
      const player = { id: payload.playerId.replaceAll('-', '').toLowerCase(), name: payload.name };
      this.authenticationAttempt(`login:${player.id}`);
      const sessionToken = randomBytes(32).toString('hex');
      for (const [existingToken, session] of Object.entries(state.sessions)) if (session.expiresAt <= now) delete state.sessions[existingToken];
      state.sessions[tokenKey(sessionToken)] = { player, expiresAt: now + this.config.sessionHours * 3600000 };
      for (const town of Object.values(state.towns)) {
        const member = town.members.find(item => item.playerId === player.id);
        if (member) member.name = player.name;
      }
      result = { token: sessionToken, player };
      mutated = true;
    } else {
      const session = typeof token === 'string' ? state.sessions[tokenKey(token)] : undefined;
      check(session && session.expiresAt > now, 'unauthenticated', '请先使用当前游戏身份登录');
      const context = { state, player: session.player, sessionKey: tokenKey(token), now, config: this.config };
      ({ result, mutated } = this.dispatch(action, payload, context));
    }
    if (mutated) { await this.store.save(state); this.state = state; }
    return clone(result);
  }

  // 所有小镇操作在同一事务队列执行；权限及周目写保护集中在这里。
  dispatch(action, payload, context) {
    const { state, player, now, config } = context;
    const timestamp = new Date(now).toISOString();
    const base = worldId => ({ id: identifier(), worldId, createdBy: player.id, createdByName: player.name, createdAt: timestamp });
    const done = (result, mutated = true) => ({ result, mutated });
    if (action === 'auth.logout') { delete state.sessions[context.sessionKey]; return done({}); }
    if (action === 'town.list') return done(Object.values(state.towns).filter(town => town.members.some(member => member.playerId === player.id)).map(town => ({ ...publicTown(town), memberCount: town.members.length, roleId: town.members.find(member => member.playerId === player.id).roleId })), false);
    if (action === 'town.create') {
      let id;
      do { id = String(randomInt(0, 100000000)).padStart(8, '0'); } while (state.towns[id]);
      const initialWorld = { ...base(undefined), name: '当前周目', serverAddress: text(payload.server, '服务器', 200, true), worldKey: text(payload.worldKey ?? 'default', '世界标识', 200, true), archived: false };
      delete initialWorld.worldId;
      state.towns[id] = {
        id, name: text(payload.name, '小镇名称', 64), introduction: text(payload.introduction, '介绍', 2000, true),
        logo: text(payload.logo, '标志', 512, true), server: text(payload.server, '服务器', 200, true),
        ownerId: player.id, password: hashPassword(payload.password), createdAt: timestamp,
        roles: [
          { id: 'newcomer', name: '新人', permissions: [] },
          { id: 'member', name: '成员', permissions: [...MEMBER_PERMISSIONS] },
          { id: 'manager', name: '管理', permissions: Object.keys(PERMISSIONS) },
          { id: 'mayor', name: '镇长', permissions: Object.keys(PERMISSIONS) },
        ],
        members: [{ playerId: player.id, name: player.name, roleId: 'mayor', joinedAt: timestamp }],
        worlds: [initialWorld], projects: [], tickets: [],
      };
      return done({ townId: id });
    }
    const townId = text(payload.townId, '小镇 ID', 8);
    check(/^\d{8}$/.test(townId), 'invalid_argument', '小镇 ID 必须是八位数字');
    const town = state.towns[townId];
    check(town, 'not_found', '小镇不存在');
    let currentMember = town.members.find(member => member.playerId === player.id);
    if (action === 'town.join') {
      if (currentMember) return done({ townId }, false);
      this.authenticationAttempt(`join:${player.id}`);
      check(validPassword(payload.password, town.password), 'forbidden', '小镇密码不正确');
      town.members.push({ playerId: player.id, name: player.name, roleId: 'newcomer', joinedAt: timestamp });
      return done({ townId });
    }
    check(currentMember, 'forbidden', '你还不是本小镇成员');
    const permissions = currentMember.roleId === 'mayor' ? Object.keys(PERMISSIONS) : town.roles.find(role => role.id === currentMember.roleId)?.permissions ?? [];
    const has = permission => permissions.includes(permission);
    const requirePermission = permission => check(has(permission), 'forbidden', '当前权限组无权进行此操作');
    const find = (collection, id = payload.id) => {
      const result = town[collection].find(item => item.id === id);
      check(result, 'not_found', '记录不存在');
      return result;
    };
    const member = id => {
      const result = town.members.find(item => item.playerId === id);
      check(result, 'invalid_argument', '指定玩家不是小镇成员');
      return result;
    };
    const writableWorld = id => {
      const world = find('worlds', id);
      check(!world.archived, 'archived', '历史周目为只读，不能修改');
      return world;
    };
    const writable = entity => { writableWorld(entity.worldId); return entity; };
    const manageProject = project => {
      check(has('project.approve') || has('project.edit') && project.leaderId === player.id, 'forbidden', '只有负责人或管理可以维护此工程');
    };
    const projectWritable = id => writable(find('projects', id));
    const position = values => {
      const dimension = text(values.dimension, '维度', 128);
      check(DIMENSION.test(dimension), 'invalid_argument', '维度必须使用命名空间格式');
      return { dimension, x: integer(values.x, 'X 坐标', -30000000, 30000000), y: integer(values.y, 'Y 坐标', -2048, 2048), z: integer(values.z, 'Z 坐标', -30000000, 30000000) };
    };
    const projectFields = (values, previous) => {
      const merged = { ...previous, ...values };
      check(object(merged.chunks), 'invalid_argument', '必须框选区块矩形');
      const chunks = {};
      for (const key of ['minX', 'maxX', 'minZ', 'maxZ']) chunks[key] = integer(merged.chunks[key], '区块坐标', -1875000, 1875000);
      check(chunks.minX <= chunks.maxX && chunks.minZ <= chunks.maxZ, 'invalid_argument', '区块范围无效');
      check((chunks.maxX - chunks.minX + 1) * (chunks.maxZ - chunks.minZ + 1) <= config.maximumRegionChunks, 'invalid_argument', '框选范围过大');
      const dimension = text(merged.dimension, '维度', 128);
      check(DIMENSION.test(dimension), 'invalid_argument', '维度必须使用命名空间格式');
      check(previous || !('leaderId' in values), 'invalid_argument', '新工程负责人自动使用当前登录玩家');
      const leaderId = previous ? merged.leaderId : player.id;
      if (previous && leaderId !== previous.leaderId) requirePermission('project.approve');
      member(leaderId);
      check(!('memberIds' in values) && !('expectedAt' in values), 'invalid_argument', '项目组和预计完工时间已移除');
      const status = previous?.status ?? choice(values.status ?? 'planned', ['planned', 'active', 'completed'], '工程状态');
      const name = text(merged.name ?? '', '工程名称', 100, status === 'planned');
      check(status === 'planned' || name !== '预留地块', 'invalid_argument', '请填写具体工程名称');
      return {
        name: name || '预留地块',
        type: choice(merged.type ?? 'other', ['building', 'machine', 'other'], '工程类型'),
        purpose: text(merged.purpose, '简介', 2000, true),
        leaderId, dimension, chunks, status,
      };
    };

    const checkRegion = (fields, worldId, previous) => {
      if (previous && fields.dimension === previous.dimension && ['minX', 'maxX', 'minZ', 'maxZ'].every(key => fields.chunks[key] === previous.chunks[key])) return;
      check(!town.projects.some(other => other.id !== previous?.id && other.worldId === worldId && other.dimension === fields.dimension && fields.chunks.minX <= other.chunks.maxX && fields.chunks.maxX >= other.chunks.minX && fields.chunks.minZ <= other.chunks.maxZ && fields.chunks.maxZ >= other.chunks.minZ), 'conflict', '所选区块与现有工程重叠，请重新框选');
    };

    if (action === 'town.snapshot') {
      if (payload.worldId) find('worlds', payload.worldId);
      const result = { town: publicTown(town), currentMember, roles: town.roles, members: town.members, worlds: town.worlds, permissions, permissionCatalog: Object.entries(PERMISSIONS).map(([id, name]) => ({ id, name })) };
      result.limits = { maximumRegionChunks: config.maximumRegionChunks };
      for (const key of ['projects', 'tickets']) result[key] = town[key].filter(item => !payload.worldId || item.worldId === payload.worldId);
      result.projects = result.projects.map(project => ({ ...project, overlapIds: result.projects.filter(other => other.id !== project.id && other.worldId === project.worldId && other.dimension === project.dimension && project.chunks.minX <= other.chunks.maxX && project.chunks.maxX >= other.chunks.minX && project.chunks.minZ <= other.chunks.maxZ && project.chunks.maxZ >= other.chunks.minZ).map(other => other.id) }));
      return done(result, false);
    }
    if (action === 'town.update') {
      requirePermission('town.manage');
      for (const [key, maximum] of [['name', 64], ['introduction', 2000], ['logo', 512], ['server', 200]]) if (key in payload) town[key] = text(payload[key], key, maximum, key !== 'name');
      if ('password' in payload) town.password = hashPassword(payload.password);
      return done(publicTown(town));
    }
    if (action === 'town.transfer') {
      check(town.ownerId === player.id, 'forbidden', '只有镇长可以移交职位');
      const next = member(payload.playerId);
      check(next.playerId !== player.id, 'conflict', '请选择其他成员');
      currentMember.roleId = 'manager'; next.roleId = 'mayor'; town.ownerId = next.playerId;
      return done(publicTown(town));
    }
    if (action === 'role.save') {
      requirePermission('roles.manage');
      const existing = payload.id ? find('roles') : null;
      check(!existing || !['mayor', 'newcomer'].includes(existing.id), 'forbidden', '镇长与新人权限组不可修改');
      if (existing && town.members.some(member => member.roleId === existing.id && member.playerId !== player.id)) requirePermission('members.manage');
      check(Array.isArray(payload.permissions), 'invalid_argument', '权限列表无效');
      for (const permission of payload.permissions) check(typeof permission === 'string' && permission in PERMISSIONS && has(permission), 'forbidden', '不能授予自己没有的权限');
      if (existing) for (const permission of existing.permissions) check(has(permission), 'forbidden', '不能管理高于自己的权限组');
      const role = { id: existing?.id ?? identifier(), name: text(payload.name, '权限组名称', 32), permissions: [...new Set(payload.permissions)] };
      if (existing) Object.assign(existing, role); else town.roles.push(role);
      return done(role);
    }
    if (action === 'role.delete') {
      requirePermission('roles.manage');
      const role = find('roles');
      check(!['mayor', 'newcomer', 'member', 'manager'].includes(role.id), 'forbidden', '内置权限组不能删除');
      check(role.permissions.every(has), 'forbidden', '不能管理高于自己的权限组');
      check(!town.members.some(item => item.roleId === role.id), 'conflict', '该权限组仍有成员');
      town.roles = town.roles.filter(item => item.id !== role.id);
      return done({});
    }
    if (action === 'member.role' || action === 'member.remove') {
      requirePermission('members.manage');
      const target = member(payload.playerId);
      check(target.roleId !== 'mayor', 'forbidden', '镇长只能通过职位移交变更');
      const previousRole = find('roles', target.roleId);
      check(previousRole.permissions.every(has), 'forbidden', '不能管理高于自己的成员');
      if (action === 'member.role') {
        const role = find('roles', payload.roleId);
        check(role.id !== 'mayor' && role.permissions.every(has), 'forbidden', '不能授予该权限组');
        target.roleId = role.id;
      } else {
        const currentWorld = id => !find('worlds', id).archived;
        check(!town.projects.some(project => currentWorld(project.worldId) && project.leaderId === target.playerId), 'conflict', '该成员仍负责当前周目的工程，请先移交负责人（包括已完工工程）');
        town.members = town.members.filter(item => item.playerId !== target.playerId);
      }
      return done({});
    }
    if (action === 'world.create') {
      requirePermission('worlds.manage');
      const world = { ...base(undefined), name: text(payload.name, '周目名称', 100), serverAddress: text(payload.serverAddress, '服务器地址', 200, true), worldKey: text(payload.worldKey, '世界标识', 200, true), archived: false };
      delete world.worldId;
      town.worlds.push(world);
      return done(world);
    }
    if (action === 'world.update') {
      requirePermission('worlds.manage'); const world = writableWorld(payload.id);
      for (const [key, name, maximum] of [['name', '周目名称', 100], ['serverAddress', '服务器地址', 200], ['worldKey', '世界标识', 200]]) if (key in payload) world[key] = text(payload[key], name, maximum, key !== 'name');
      world.updatedAt = timestamp; return done(world);
    }
    if (action === 'world.archive') {
      requirePermission('worlds.manage');
      const world = writableWorld(payload.worldId);
      world.archived = true; world.archivedAt = timestamp;
      return done(world);
    }
    if (action === 'project.create') {
      requirePermission('project.create'); writableWorld(payload.worldId);
      const project = { ...base(payload.worldId), ...projectFields(payload) };
      checkRegion(project, project.worldId);
      town.projects.push(project); return done(project);
    }
    if (action === 'project.update') {
      const project = projectWritable(payload.id); manageProject(project);
      check(!('status' in payload), 'invalid_argument', '请使用工程状态操作');
      const fields = projectFields(payload, project);
      checkRegion(fields, project.worldId, project);
      Object.assign(project, fields, { updatedAt: timestamp });
      return done(project);
    }
    if (action === 'project.status') {
      const project = projectWritable(payload.id); manageProject(project);
      const status = choice(payload.status, ['active', 'completed'], '工程状态');
      check(project.status === 'planned' && status === 'active' || project.status === 'active' && status === 'completed', 'conflict', '当前工程状态不能这样变更');
      check(status !== 'active' || project.name.trim().length > 0 && project.name !== '预留地块', 'invalid_argument', '请先填写具体工程名称再开工');
      Object.assign(project, { status, updatedAt: timestamp }); return done(project);
    }
    if (action === 'ticket.create') {
      requirePermission('ticket.create');
      const project = projectWritable(payload.projectId);
      check(!payload.worldId || project.worldId === payload.worldId, 'invalid_argument', '工单与工程必须属于同一周目');
      check(['completed', 'maintenance'].includes(project.status), 'conflict', '只有已完工工程可以报修');
      const location = position(payload);
      check(location.dimension === project.dimension, 'invalid_argument', '报修位置与工程维度不一致');
      const ticket = { ...base(project.worldId), ...location, projectId: project.id, title: text(payload.title, '维修事项', 100), description: text(payload.description, '故障说明', 2000, true), status: 'open', resolution: '' };
      town.tickets.push(ticket); project.status = 'maintenance'; project.updatedAt = timestamp;
      return done(ticket);
    }
    if (action === 'ticket.update') {
      const ticket = writable(find('tickets')); const project = projectWritable(ticket.projectId);
      if (!has('ticket.manage')) manageProject(project);
      const status = choice(payload.status, ['open', 'working', 'resolved'], '工单状态');
      check(status !== ticket.status, 'conflict', '工单状态未变化');
      const resolution = text(payload.resolution, '处理说明', 2000, true);
      check(status !== 'resolved' || resolution.length > 0, 'invalid_argument', '解决工单时请填写处理说明');
      Object.assign(ticket, { status, resolution, updatedBy: player.id, updatedAt: timestamp });
      if (town.tickets.some(item => item.projectId === project.id && item.status !== 'resolved')) project.status = 'maintenance';
      else if (project.status === 'maintenance') project.status = 'completed';
      project.updatedAt = timestamp; return done(ticket);
    }
    fail('not_found', '操作不存在');
  }
}
