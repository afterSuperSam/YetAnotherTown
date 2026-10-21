import { createHash, randomBytes, randomInt, scrypt, timingSafeEqual } from 'node:crypto';
import { mkdir, open, readFile, rename, unlink } from 'node:fs/promises';
import path from 'node:path';
import { promisify } from 'node:util';
import translations from './translations.json' with { type: 'json' };

export const DEFAULT_CONFIG = Object.freeze({
  host: '127.0.0.1', port: 37841, dataFile: './data/yetanothertown.json', sessionHours: 168,
  requestTimeoutSeconds: 15, maxBodyBytes: 65536,
  requestsPerMinute: 120, authenticationAttemptsPerMinute: 10,
  maximumRegionChunks: 65536,
  maximumPendingRequests: 64, maximumSessions: 10000, maximumSessionsPerPlayer: 8,
  maximumTowns: 1000, maximumTownRecords: 10000, maximumRateLimitEntries: 10000,
  maximumTownBytes: 7 * 1024 * 1024,
});

export const PERMISSIONS = Object.freeze({
  'town.manage': 'yat.permission.town.manage', 'members.manage': 'yat.permission.members.manage',
  'roles.manage': 'yat.permission.roles.manage', 'worlds.manage': 'yat.permission.worlds.manage',
  'project.create': 'yat.permission.project.create', 'project.edit': 'yat.permission.project.edit',
  'project.approve': 'yat.permission.project.approve',
  'ticket.create': 'yat.permission.ticket.create',
  'ticket.manage': 'yat.permission.ticket.manage',
});
const MEMBER_PERMISSIONS = [
  'project.create', 'project.edit', 'ticket.create',
];
const COLLECTIONS = ['roles', 'members', 'worlds', 'projects', 'tickets'];
const DIMENSION = /^[a-z0-9_.-]+:[a-z0-9_/.-]+$/;
const PLAYER_ID = /^[a-f0-9]{32}$/;
const READ_ACTIONS = new Set(['town.list', 'town.discover', 'town.snapshot']);
const ACTIONS = new Set(['auth.login', 'auth.logout', 'town.list', 'town.discover', 'town.snapshot', 'town.create', 'town.join', 'town.update', 'town.transfer', 'role.save', 'role.delete', 'member.role', 'member.remove', 'world.create', 'world.update', 'world.activate', 'world.archive', 'project.create', 'project.update', 'project.delete', 'project.status', 'project.repair.complete', 'ticket.create', 'ticket.update']);
const derivePassword = promisify(scrypt);

export class ApiError extends Error {
  constructor(code, messageKey, messageArgs = []) {
    super(translate(messageKey, messageArgs));
    this.code = code; this.messageKey = messageKey; this.messageArgs = messageArgs;
  }
  toJSON() { return { code: this.code, message: this.message, messageKey: this.messageKey, messageArgs: this.messageArgs }; }
}
export function translate(key, values = []) {
  let index = 0;
  return (translations.zh_cn[key] ?? key).replace(/%s/g, () => {
    const value = values[index++];
    return value && typeof value === 'object' && typeof value.translate === 'string' ? translate(value.translate) : String(value);
  });
}
const translated = key => ({ translate: key });
const fail = (code, messageKey, messageArgs) => { throw new ApiError(code, messageKey, messageArgs); };
const check = (condition, code, messageKey, messageArgs) => { if (!condition) fail(code, messageKey, messageArgs); };
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const identifier = () => randomBytes(12).toString('hex');
const clone = value => structuredClone(value);
const tokenKey = token => createHash('sha256').update(token).digest('hex');

export function validateConfig(input = {}) {
  check(object(input), 'invalid_argument', 'yat.error.config_object');
  const result = { ...DEFAULT_CONFIG };
  for (const [key, value] of Object.entries(input)) {
    check(Object.hasOwn(DEFAULT_CONFIG, key), 'invalid_argument', 'yat.error.config_unknown', [key]);
    result[key] = value;
  }
  for (const key of ['host', 'dataFile']) check(typeof result[key] === 'string' && result[key].trim().length > 0, 'invalid_argument', 'yat.error.config_invalid', [key]);
  const limits = {
    port: [1, 65535], sessionHours: [1, 8760],
    requestTimeoutSeconds: [1, 120], maxBodyBytes: [1024, 1048576],
    requestsPerMinute: [1, 10000], authenticationAttemptsPerMinute: [1, 100],
    maximumRegionChunks: [1, 1048576],
    maximumPendingRequests: [1, 10000], maximumSessions: [1, 100000], maximumSessionsPerPlayer: [1, 100],
    maximumTowns: [1, 10000], maximumTownRecords: [6, 100000], maximumRateLimitEntries: [1, 100000],
    maximumTownBytes: [65536, 7 * 1024 * 1024],
  };
  for (const [key, [minimum, maximum]] of Object.entries(limits)) {
    check(Number.isSafeInteger(result[key]) && result[key] >= minimum && result[key] <= maximum, 'invalid_argument', 'yat.error.config_integer', [key, minimum, maximum]);
  }
  return Object.freeze(result);
}

function text(value, name, maximum = 200, optional = false) {
  if (optional && value === undefined) return '';
  check(typeof value === 'string', 'invalid_argument', 'yat.error.text_required', [translated(name)]);
  const result = value.trim();
  check((optional || result.length > 0) && result.length <= maximum, 'invalid_argument', 'yat.error.text_length', [translated(name), optional ? 0 : 1, maximum]);
  return result;
}
function integer(value, name, minimum, maximum) {
  check(Number.isSafeInteger(value) && value >= minimum && value <= maximum, 'invalid_argument', 'yat.error.integer_range', [translated(name), minimum, maximum]);
  return value;
}
function choice(value, values, name) {
  check(values.includes(value), 'invalid_argument', 'yat.error.choice_invalid', [translated(name)]);
  return value;
}
async function hashPassword(password) {
  const value = text(password, 'yat.field.password', 128, true);
  if (!value) return null;
  check(value.length >= 6, 'invalid_argument', 'yat.error.password_short');
  const salt = randomBytes(16).toString('hex');
  return { salt, hash: (await derivePassword(value, salt, 32)).toString('hex') };
}
async function validPassword(password, saved) {
  if (!saved) return true;
  const value = text(password, 'yat.field.password', 128, true);
  return timingSafeEqual(await derivePassword(value, saved.salt, 32), Buffer.from(saved.hash, 'hex'));
}
function publicTown(town) {
  const { password, ...details } = town;
  for (const key of COLLECTIONS) delete details[key];
  return { ...details, passwordEnabled: Boolean(password) };
}

// 按周目和维度扫描区域，返回顺序与原工程列表保持一致。
function scanProjectOverlaps(projects, overlap) {
  const groups = new Map();
  for (const [index, project] of projects.entries()) {
    const key = JSON.stringify([project.worldId, project.dimension]);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push({ project, index });
  }
  for (const group of groups.values()) {
    const axis = new Set(group.map(item => item.project.chunks.minX)).size >= new Set(group.map(item => item.project.chunks.minZ)).size ? 'X' : 'Z';
    const minimum = axis === 'X' ? 'minX' : 'minZ'; const maximum = axis === 'X' ? 'maxX' : 'maxZ';
    const otherMinimum = axis === 'X' ? 'minZ' : 'minX'; const otherMaximum = axis === 'X' ? 'maxZ' : 'maxX';
    group.sort((left, right) => left.project.chunks[minimum] - right.project.chunks[minimum] || left.index - right.index);
    for (let left = 0; left < group.length; left++) {
      const current = group[left];
      const currentChunks = current.project.chunks;
      for (let right = left + 1; right < group.length; right++) {
        const next = group[right];
        const nextChunks = next.project.chunks;
        if (nextChunks[minimum] > currentChunks[maximum]) break;
        if (current.project.id !== next.project.id && currentChunks[otherMinimum] <= nextChunks[otherMaximum] && currentChunks[otherMaximum] >= nextChunks[otherMinimum]) {
          overlap(current.index, next.index);
        }
      }
    }
  }
}
function projectsWithOverlaps(projects) {
  const overlaps = projects.map(() => []);
  scanProjectOverlaps(projects, (left, right) => { overlaps[left].push(right); overlaps[right].push(left); });
  return projects.map((project, index) => ({ ...project, overlapIds: overlaps[index].sort((left, right) => left - right).map(other => projects[other].id) }));
}

// 容量按 UTF-8 响应预算，含历史重叠 ID、当前成员副本和接口元数据余量。
function townSnapshotBytes(town) {
  const bytes = value => Buffer.byteLength(JSON.stringify(value));
  let total = bytes(town) + town.members.reduce((maximum, member) => Math.max(maximum, bytes(member)), 0) + 16384;
  const identifiers = town.projects.map(project => bytes(project.id));
  const counts = town.projects.map(() => 0);
  for (const project of town.projects) total += bytes({ ...project, overlapIds: [] }) - bytes(project);
  scanProjectOverlaps(town.projects, (left, right) => {
    total += identifiers[right] + (counts[left]++ > 0 ? 1 : 0);
    total += identifiers[left] + (counts[right]++ > 0 ? 1 : 0);
  });
  return total;
}

// 加载时检查记录与关联，损坏数据保持原文件并拒绝启动。
function validateState(state) {
  check(object(state) && state.version === 3 && object(state.towns) && object(state.sessions), 'invalid_argument', 'yat.error.state_invalid');
  const valid = (condition, name) => check(condition, 'invalid_argument', 'yat.error.state_record_invalid', [translated(name)]);
  const string = (value, maximum, optional = false) => typeof value === 'string' && (optional || value.trim().length > 0) && value.length <= maximum;
  const date = value => typeof value === 'string' && Number.isFinite(Date.parse(value));
  const player = value => typeof value === 'string' && PLAYER_ID.test(value);
  const record = (value, maximum = 128) => object(value) && string(value.id, maximum);
  const unique = (values, key) => new Set(values.map(value => value[key])).size === values.length;
  for (const [key, session] of Object.entries(state.sessions)) valid(/^[a-f0-9]{64}$/.test(key) && object(session) && object(session.player) && player(session.player.id) && typeof session.player.name === 'string' && /^[A-Za-z0-9_]{1,16}$/.test(session.player.name) && Number.isSafeInteger(session.expiresAt) && session.expiresAt > 0, 'yat.record.session');
  for (const [id, town] of Object.entries(state.towns)) {
    valid(/^\d{8}$/.test(id) && object(town) && town.id === id && COLLECTIONS.every(key => Array.isArray(town[key]) && town[key].every(object)), 'yat.record.town');
    valid(string(town.name, 64) && string(town.introduction, 2000, true) && string(town.logo, 512, true) && string(town.server, 200, true) && date(town.createdAt), 'yat.record.town_details');
    valid(town.listed === undefined || typeof town.listed === 'boolean', 'yat.record.town_listing');
    valid(town.password === null || object(town.password) && typeof town.password.salt === 'string' && /^[a-f0-9]{32}$/.test(town.password.salt) && typeof town.password.hash === 'string' && /^[a-f0-9]{64}$/.test(town.password.hash), 'yat.record.password');
    valid(unique(town.roles, 'id') && town.roles.every(role => record(role) && string(role.name, 32) && (role.nameKey === undefined || ['newcomer', 'member', 'manager', 'mayor'].includes(role.id) && role.nameKey === `yat.role.${role.id}` && role.name === translate(role.nameKey)) && typeof role.icon === 'string' && role.icon.length <= 128 && DIMENSION.test(role.icon) && Array.isArray(role.permissions) && new Set(role.permissions).size === role.permissions.length && role.permissions.every(permission => typeof permission === 'string' && Object.hasOwn(PERMISSIONS, permission))), 'yat.record.role');
    const roles = new Map(town.roles.map(role => [role.id, role]));
    valid(['newcomer', 'member', 'manager', 'mayor'].every(role => roles.has(role)) && roles.get('newcomer').permissions.length === 0 && roles.get('mayor').permissions.length === Object.keys(PERMISSIONS).length, 'yat.record.builtin_roles');
    valid(unique(town.members, 'playerId') && town.members.every(member => player(member.playerId) && typeof member.name === 'string' && /^[A-Za-z0-9_]{1,16}$/.test(member.name) && roles.has(member.roleId) && date(member.joinedAt)), 'yat.record.member');
    valid(town.members.filter(member => member.roleId === 'mayor').length === 1 && town.members.some(member => member.playerId === town.ownerId && member.roleId === 'mayor'), 'yat.record.mayor');
    for (const key of ['worlds', 'projects', 'tickets']) valid(unique(town[key], 'id') && town[key].every(value => record(value) && player(value.createdBy) && typeof value.createdByName === 'string' && /^[A-Za-z0-9_]{1,16}$/.test(value.createdByName) && date(value.createdAt) && (value.updatedAt === undefined || date(value.updatedAt))), 'yat.record.record');
    valid(town.worlds.every(world => string(world.name, 100) && (world.nameKey === undefined || world.nameKey === 'yat.default.current_world' && world.name === translate(world.nameKey)) && string(world.serverAddress, 200, true) && string(world.worldKey, 200, true) && typeof world.archived === 'boolean' && (!world.archived || date(world.archivedAt))), 'yat.record.world');
    valid(town.worlds.every(world => (world.mapName === undefined || string(world.mapName, 200)) && (world.mapNames === undefined || object(world.mapNames) && Object.keys(world.mapNames).length <= DEFAULT_CONFIG.maximumTownRecords && Object.entries(world.mapNames).every(([dimension, name]) => dimension.length <= 128 && DIMENSION.test(dimension) && string(name, 200)))), 'yat.record.world_maps');
    const worlds = new Map(town.worlds.map(world => [world.id, world]));
    valid(town.currentWorldId === undefined || string(town.currentWorldId, 128) && worlds.has(town.currentWorldId) && !worlds.get(town.currentWorldId).archived, 'yat.record.current_world');
    valid(town.projects.every(project => {
      const world = worlds.get(project.worldId);
      const chunks = project.chunks;
      return world && string(project.name, 100) && (project.nameKey === undefined || project.nameKey === 'yat.default.reserved_plot' && project.name === translate(project.nameKey)) && (project.status === 'planned' || project.name !== translate('yat.default.reserved_plot')) && ['building', 'machine', 'other'].includes(project.type) && string(project.purpose, 2000, true) && player(project.leaderId) && (world.archived || town.members.some(member => member.playerId === project.leaderId)) && typeof project.dimension === 'string' && project.dimension.length <= 128 && DIMENSION.test(project.dimension) && object(chunks) && ['minX', 'maxX', 'minZ', 'maxZ'].every(key => Number.isSafeInteger(chunks[key]) && Math.abs(chunks[key]) <= 1875000) && chunks.minX <= chunks.maxX && chunks.minZ <= chunks.maxZ && ['planned', 'active', 'completed', 'maintenance'].includes(project.status);
    }), 'yat.record.project');
    const projects = new Map(town.projects.map(project => [project.id, project]));
    valid(town.tickets.every(ticket => {
      const project = projects.get(ticket.projectId);
      return project && ['completed', 'maintenance'].includes(project.status) && ticket.worldId === project.worldId && ticket.dimension === project.dimension && Number.isSafeInteger(ticket.x) && Math.abs(ticket.x) <= 30000000 && Number.isSafeInteger(ticket.y) && Math.abs(ticket.y) <= 2048 && Number.isSafeInteger(ticket.z) && Math.abs(ticket.z) <= 30000000 && string(ticket.title, 100) && string(ticket.description, 2000, true) && ['open', 'working', 'resolved'].includes(ticket.status) && string(ticket.resolution, 2000, ticket.status !== 'resolved') && (ticket.resolutionKey === undefined || ticket.resolutionKey === 'yat.default.repair_completed' && ticket.status === 'resolved' && ticket.resolution === translate(ticket.resolutionKey)) && (ticket.updatedBy === undefined || player(ticket.updatedBy));
    }), 'yat.record.ticket');
    const unresolved = new Set(town.tickets.filter(ticket => ticket.status !== 'resolved').map(ticket => ticket.projectId));
    valid(town.projects.every(project => (project.status === 'maintenance') === unresolved.has(project.id)), 'yat.record.project_maintenance');
  }
  return state;
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
    return validateState(state);
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
    this.pendingRequests = 0;
  }
  async initialize() { this.state = await this.store.load(); return this; }
  handle(action, payload = {}, token) {
    if (this.pendingRequests >= this.config.maximumPendingRequests) return Promise.reject(new ApiError('rate_limited', 'yat.error.service_busy'));
    this.pendingRequests += 1;
    const operation = this.queue.then(() => this.execute(action, payload, token));
    const completed = operation.finally(() => { this.pendingRequests -= 1; });
    this.queue = completed.catch(() => {});
    return completed;
  }
  authenticationAttempt(key) {
    const now = this.now();
    for (const [entry, window] of this.attempts) if (window.until <= now) this.attempts.delete(entry);
    check(this.attempts.has(key) || this.attempts.size < this.config.maximumRateLimitEntries, 'rate_limited', 'yat.error.authentication_limited');
    const window = this.attempts.get(key) ?? { until: now + 60000, count: 0 };
    window.count += 1;
    this.attempts.set(key, window);
    check(window.count <= this.config.authenticationAttemptsPerMinute, 'rate_limited', 'yat.error.authentication_limited');
  }
  async execute(action, payload, token) {
    check(typeof action === 'string' && object(payload), 'invalid_argument', 'yat.error.request_invalid');
    const now = this.now();
    let state;
    const changedTowns = new Set();
    let result;
    let mutated = false;
    if (action === 'auth.login') {
      check(typeof payload.playerId === 'string' && /^(?:[a-f0-9]{32}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})$/i.test(payload.playerId), 'invalid_argument', 'yat.error.player_uuid_invalid');
      check(typeof payload.name === 'string' && /^[A-Za-z0-9_]{1,16}$/.test(payload.name), 'invalid_argument', 'yat.error.player_name_invalid');
      const player = { id: payload.playerId.replaceAll('-', '').toLowerCase(), name: payload.name };
      this.authenticationAttempt(`login:${player.id}`);
      state = clone(this.state);
      const sessionToken = randomBytes(32).toString('hex');
      for (const [existingToken, session] of Object.entries(state.sessions)) if (session.expiresAt <= now) delete state.sessions[existingToken];
      const playerSessions = Object.entries(state.sessions).filter(([, session]) => session.player.id === player.id);
      while (playerSessions.length >= this.config.maximumSessionsPerPlayer) delete state.sessions[playerSessions.shift()[0]];
      check(Object.keys(state.sessions).length < this.config.maximumSessions, 'rate_limited', 'yat.error.sessions_full');
      for (const [, session] of playerSessions) session.player.name = player.name;
      state.sessions[tokenKey(sessionToken)] = { player, expiresAt: now + this.config.sessionHours * 3600000 };
      for (const town of Object.values(state.towns)) {
        const member = town.members.find(item => item.playerId === player.id);
        if (member && member.name !== player.name) { member.name = player.name; changedTowns.add(town.id); }
      }
      result = { token: sessionToken, player };
      mutated = true;
    } else {
      const session = typeof token === 'string' && /^[a-f0-9]{64}$/.test(token) ? this.state.sessions[tokenKey(token)] : undefined;
      check(session && session.expiresAt > now, 'unauthenticated', 'yat.error.login_required');
      check(ACTIONS.has(action), 'not_found', 'yat.error.action_not_found');
      state = READ_ACTIONS.has(action) ? this.state : clone(this.state);
      const context = { state, player: session.player, sessionKey: tokenKey(token), now, config: this.config, changedTowns };
      ({ result, mutated } = await this.dispatch(action, payload, context));
    }
    if (mutated) {
      for (const id of changedTowns) {
        const size = townSnapshotBytes(state.towns[id]);
        check(size <= this.config.maximumTownBytes || this.state.towns[id] && size <= townSnapshotBytes(this.state.towns[id]), 'conflict', 'yat.error.town_bytes_full');
      }
      await this.store.save(state); this.state = state;
    }
    return clone(result);
  }

  // 所有小镇操作在同一事务队列执行；权限及周目写保护集中在这里。
  async dispatch(action, payload, context) {
    const { state, player, now, config } = context;
    const timestamp = new Date(now).toISOString();
    const base = worldId => ({ id: identifier(), worldId, createdBy: player.id, createdByName: player.name, createdAt: timestamp });
    const done = (result, mutated = true) => ({ result, mutated });
    if (action === 'auth.logout') { delete state.sessions[context.sessionKey]; return done({}); }
    if (action === 'town.list') return done(Object.values(state.towns).filter(town => town.members.some(member => member.playerId === player.id)).map(town => ({ ...publicTown(town), memberCount: town.members.length, roleId: town.members.find(member => member.playerId === player.id).roleId })), false);
    if (action === 'town.discover') return done(Object.values(state.towns).filter(town => town.listed === true).map(town => ({ id: town.id, name: town.name, introduction: town.introduction, logo: town.logo, server: town.server, memberCount: town.members.length, passwordEnabled: Boolean(town.password) })), false);
    if (action === 'town.create') {
      check(Object.keys(state.towns).length < config.maximumTowns, 'conflict', 'yat.error.towns_full');
      let id;
      do { id = String(randomInt(0, 100000000)).padStart(8, '0'); } while (state.towns[id]);
      const initialWorld = { ...base(undefined), name: translate('yat.default.current_world'), nameKey: 'yat.default.current_world', serverAddress: text(payload.server, 'yat.field.server', 200, true), worldKey: text(payload.worldKey ?? 'default', 'yat.field.world_key', 200, true), archived: false };
      delete initialWorld.worldId;
      state.towns[id] = {
        id, name: text(payload.name, 'yat.field.town_name', 64), introduction: text(payload.introduction, 'yat.field.introduction', 2000, true),
        logo: text(payload.logo, 'yat.field.logo', 512, true), server: text(payload.server, 'yat.field.server', 200, true), listed: false,
        ownerId: player.id, currentWorldId: initialWorld.id, password: await hashPassword(payload.password), createdAt: timestamp,
        roles: [
          { id: 'newcomer', icon: 'minecraft:dirt', name: translate('yat.role.newcomer'), nameKey: 'yat.role.newcomer', permissions: [] },
          { id: 'member', icon: 'minecraft:cobblestone', name: translate('yat.role.member'), nameKey: 'yat.role.member', permissions: [...MEMBER_PERMISSIONS] },
          { id: 'manager', icon: 'minecraft:iron_block', name: translate('yat.role.manager'), nameKey: 'yat.role.manager', permissions: Object.keys(PERMISSIONS) },
          { id: 'mayor', icon: 'minecraft:emerald_block', name: translate('yat.role.mayor'), nameKey: 'yat.role.mayor', permissions: Object.keys(PERMISSIONS) },
        ],
        members: [{ playerId: player.id, name: player.name, roleId: 'mayor', joinedAt: timestamp }],
        worlds: [initialWorld], projects: [], tickets: [],
      };
      context.changedTowns.add(id);
      return done({ townId: id });
    }
    const townId = text(payload.townId, 'yat.field.town_id', 8);
    check(/^\d{8}$/.test(townId), 'invalid_argument', 'yat.error.town_id_invalid');
    const town = state.towns[townId];
    check(town, 'not_found', 'yat.error.town_not_found');
    context.changedTowns.add(townId);
    const reserveRecord = () => check(COLLECTIONS.reduce((total, key) => total + town[key].length, 0) < config.maximumTownRecords, 'conflict', 'yat.error.town_records_full');
    let currentMember = town.members.find(member => member.playerId === player.id);
    if (action === 'town.join') {
      if (currentMember) return done({ townId }, false);
      this.authenticationAttempt(`join:${player.id}`);
      check(await validPassword(payload.password, town.password), 'forbidden', 'yat.error.password_incorrect');
      reserveRecord();
      town.members.push({ playerId: player.id, name: player.name, roleId: 'newcomer', joinedAt: timestamp });
      return done({ townId });
    }
    check(currentMember, 'forbidden', 'yat.error.membership_required');
    const permissions = currentMember.roleId === 'mayor' ? Object.keys(PERMISSIONS) : town.roles.find(role => role.id === currentMember.roleId)?.permissions ?? [];
    const has = permission => permissions.includes(permission);
    const requirePermission = permission => check(has(permission), 'forbidden', 'yat.error.permission_denied');
    const find = (collection, id = payload.id) => {
      const result = town[collection].find(item => item.id === id);
      check(result, 'not_found', 'yat.error.record_not_found');
      return result;
    };
    const member = id => {
      const result = town.members.find(item => item.playerId === id);
      check(result, 'invalid_argument', 'yat.error.player_not_member');
      return result;
    };
    const writableWorld = id => {
      const world = find('worlds', id);
      check(!world.archived, 'archived', 'yat.error.world_archived');
      return world;
    };
    const writable = entity => { writableWorld(entity.worldId); return entity; };
    const manageProject = project => {
      check(has('project.approve') || has('project.edit') && project.leaderId === player.id, 'forbidden', 'yat.error.project_permission_denied');
    };
    const projectWritable = id => writable(find('projects', id));
    const position = values => {
      const dimension = text(values.dimension, 'yat.field.dimension', 128);
      check(DIMENSION.test(dimension), 'invalid_argument', 'yat.error.dimension_invalid');
      return { dimension, x: integer(values.x, 'yat.field.x', -30000000, 30000000), y: integer(values.y, 'yat.field.y', -2048, 2048), z: integer(values.z, 'yat.field.z', -30000000, 30000000) };
    };
    const projectFields = (values, previous) => {
      const merged = { ...previous, ...values };
      check(object(merged.chunks), 'invalid_argument', 'yat.error.region_required');
      const chunks = {};
      for (const key of ['minX', 'maxX', 'minZ', 'maxZ']) chunks[key] = integer(merged.chunks[key], 'yat.field.chunk_coordinate', -1875000, 1875000);
      check(chunks.minX <= chunks.maxX && chunks.minZ <= chunks.maxZ, 'invalid_argument', 'yat.error.region_invalid');
      check((chunks.maxX - chunks.minX + 1) * (chunks.maxZ - chunks.minZ + 1) <= config.maximumRegionChunks, 'invalid_argument', 'yat.error.region_too_large');
      const dimension = text(merged.dimension, 'yat.field.dimension', 128);
      check(DIMENSION.test(dimension), 'invalid_argument', 'yat.error.dimension_invalid');
      check(previous || !('leaderId' in values), 'invalid_argument', 'yat.error.project_leader_automatic');
      const leaderId = previous ? merged.leaderId : player.id;
      if (previous && leaderId !== previous.leaderId) requirePermission('project.approve');
      member(leaderId);
      check(!('memberIds' in values) && !('expectedAt' in values), 'invalid_argument', 'yat.error.project_legacy_fields');
      const status = previous?.status ?? choice(values.status ?? 'planned', ['planned', 'active', 'completed'], 'yat.field.project_status');
      const name = text(merged.name ?? '', 'yat.field.project_name', 100, status === 'planned');
      check(status === 'planned' || name !== translate('yat.default.reserved_plot'), 'invalid_argument', 'yat.error.project_name_required');
      return {
        name: name || translate('yat.default.reserved_plot'),
        ...(!name || previous?.nameKey && name === previous.name ? { nameKey: 'yat.default.reserved_plot' } : {}),
        type: choice(merged.type ?? 'other', ['building', 'machine', 'other'], 'yat.field.project_type'),
        purpose: text(merged.purpose, 'yat.field.purpose', 2000, true),
        leaderId, dimension, chunks, status,
      };
    };

    const checkRegion = (fields, worldId, previous) => {
      if (previous && fields.dimension === previous.dimension && ['minX', 'maxX', 'minZ', 'maxZ'].every(key => fields.chunks[key] === previous.chunks[key])) return;
      check(!town.projects.some(other => other.id !== previous?.id && other.worldId === worldId && other.dimension === fields.dimension && fields.chunks.minX <= other.chunks.maxX && fields.chunks.maxX >= other.chunks.minX && fields.chunks.minZ <= other.chunks.maxZ && fields.chunks.maxZ >= other.chunks.minZ), 'conflict', 'yat.error.region_overlap');
    };

    if (action === 'town.snapshot') {
      if (payload.worldId) find('worlds', payload.worldId);
      const result = { town: publicTown(town), currentMember, roles: town.roles, members: town.members, worlds: town.worlds, permissions, permissionCatalog: Object.entries(PERMISSIONS).map(([id, nameKey]) => ({ id, name: translate(nameKey), nameKey })) };
      result.limits = { maximumRegionChunks: config.maximumRegionChunks };
      for (const key of ['projects', 'tickets']) result[key] = town[key].filter(item => !payload.worldId || item.worldId === payload.worldId);
      result.projects = projectsWithOverlaps(result.projects);
      return done(result, false);
    }
    if (action === 'town.update') {
      requirePermission('town.manage');
      if ('listed' in payload) {
        check(typeof payload.listed === 'boolean', 'invalid_argument', 'yat.error.listed_invalid');
        town.listed = payload.listed;
      }
      for (const [key, label, maximum] of [['name', 'yat.field.town_name', 64], ['introduction', 'yat.field.introduction', 2000], ['logo', 'yat.field.logo', 512], ['server', 'yat.field.server', 200]]) if (key in payload) town[key] = text(payload[key], label, maximum, key !== 'name');
      if ('password' in payload) town.password = await hashPassword(payload.password);
      return done(publicTown(town));
    }
    if (action === 'town.transfer') {
      check(town.ownerId === player.id, 'forbidden', 'yat.error.mayor_transfer_only');
      const next = member(payload.playerId);
      check(next.playerId !== player.id, 'conflict', 'yat.error.other_member_required');
      currentMember.roleId = 'manager'; next.roleId = 'mayor'; town.ownerId = next.playerId;
      return done(publicTown(town));
    }
    if (action === 'role.save') {
      requirePermission('roles.manage');
      const existing = payload.id ? find('roles') : null;
      const selectedPermissions = Object.hasOwn(payload, 'permissions') ? payload.permissions : existing?.permissions;
      const name = text(Object.hasOwn(payload, 'name') ? payload.name : existing?.name, 'yat.field.role_name', 32);
      if (existing && ['mayor', 'newcomer'].includes(existing.id)) {
        check(name === existing.name && Array.isArray(selectedPermissions) && selectedPermissions.length === existing.permissions.length && existing.permissions.every(permission => selectedPermissions.includes(permission)), 'forbidden', 'yat.error.role_locked');
      }
      if (existing && town.members.some(member => member.roleId === existing.id && member.playerId !== player.id)) requirePermission('members.manage');
      check(Array.isArray(selectedPermissions), 'invalid_argument', 'yat.error.permissions_invalid');
      for (const permission of selectedPermissions) check(typeof permission === 'string' && Object.hasOwn(PERMISSIONS, permission) && has(permission), 'forbidden', 'yat.error.permission_grant_denied');
      if (existing) for (const permission of existing.permissions) check(has(permission), 'forbidden', 'yat.error.role_management_denied');
      const icon = text(Object.hasOwn(payload, 'icon') ? payload.icon : existing?.icon ?? 'minecraft:cobblestone', 'yat.field.role_icon', 128);
      check(DIMENSION.test(icon), 'invalid_argument', 'yat.error.role_icon_invalid');
      const role = { id: existing?.id ?? identifier(), name, icon, permissions: [...new Set(selectedPermissions)] };
      if (existing?.nameKey && name === existing.name) role.nameKey = existing.nameKey;
      else if (existing) delete existing.nameKey;
      if (existing) Object.assign(existing, role); else { reserveRecord(); town.roles.push(role); }
      return done(role);
    }
    if (action === 'role.delete') {
      requirePermission('roles.manage');
      const role = find('roles');
      check(!['mayor', 'newcomer', 'member', 'manager'].includes(role.id), 'forbidden', 'yat.error.role_builtin_delete');
      check(role.permissions.every(has), 'forbidden', 'yat.error.role_management_denied');
      check(!town.members.some(item => item.roleId === role.id), 'conflict', 'yat.error.role_has_members');
      town.roles = town.roles.filter(item => item.id !== role.id);
      return done({});
    }
    if (action === 'member.role' || action === 'member.remove') {
      requirePermission('members.manage');
      const target = member(payload.playerId);
      check(target.roleId !== 'mayor', 'forbidden', 'yat.error.mayor_role_locked');
      const previousRole = find('roles', target.roleId);
      check(previousRole.permissions.every(has), 'forbidden', 'yat.error.member_management_denied');
      if (action === 'member.role') {
        const role = find('roles', payload.roleId);
        check(role.id !== 'mayor' && role.permissions.every(has), 'forbidden', 'yat.error.role_assignment_denied');
        target.roleId = role.id;
      } else {
        const currentWorld = id => !find('worlds', id).archived;
        check(!town.projects.some(project => currentWorld(project.worldId) && project.leaderId === target.playerId), 'conflict', 'yat.error.member_leads_projects');
        town.members = town.members.filter(item => item.playerId !== target.playerId);
      }
      return done({});
    }
    if (action === 'world.create') {
      requirePermission('worlds.manage');
      reserveRecord();
      const world = { ...base(undefined), name: text(payload.name, 'yat.field.world_name', 100), serverAddress: text(payload.serverAddress, 'yat.field.server_address', 200, true), worldKey: text(payload.worldKey, 'yat.field.world_key', 200, true), archived: false };
      delete world.worldId;
      town.worlds.push(world);
      return done(world);
    }
    if (action === 'world.update') {
      requirePermission('worlds.manage'); const world = writableWorld(payload.id);
      if ('serverAddress' in payload) {
        const serverAddress = text(payload.serverAddress, 'yat.field.server_address', 200, true);
        if (serverAddress !== world.serverAddress) {
          check(town.currentWorldId !== world.id || town.ownerId === player.id, 'forbidden', 'yat.error.current_world_server_mayor');
          delete world.mapName; delete world.mapNames;
        }
      }
      for (const [key, name, maximum] of [['name', 'yat.field.world_name', 100], ['serverAddress', 'yat.field.server_address', 200], ['worldKey', 'yat.field.world_key', 200]]) if (key in payload) world[key] = text(payload[key], name, maximum, key !== 'name');
      if ('name' in payload) delete world.nameKey;
      world.updatedAt = timestamp; return done(world);
    }
    if (action === 'world.activate') {
      check(town.ownerId === player.id, 'forbidden', 'yat.error.current_world_mayor');
      const world = writableWorld(payload.worldId);
      const serverAddress = text(payload.serverAddress, 'yat.field.server_address', 200);
      const mapName = text(payload.mapName, 'yat.field.map_name', 200);
      const dimension = text(payload.dimension, 'yat.field.dimension', 128);
      check(DIMENSION.test(dimension), 'invalid_argument', 'yat.error.dimension_invalid');
      const sameServer = world.serverAddress === serverAddress;
      const mapNames = sameServer ? { ...world.mapNames } : {};
      check(Object.hasOwn(mapNames, dimension) || Object.keys(mapNames).length < Math.min(config.maximumTownRecords, DEFAULT_CONFIG.maximumTownRecords), 'conflict', 'yat.error.map_dimensions_full');
      mapNames[dimension] = mapName;
      if (town.currentWorldId !== world.id || !sameServer || !world.mapName) world.mapName = mapName;
      Object.assign(world, { serverAddress, mapNames, updatedAt: timestamp });
      town.currentWorldId = world.id;
      return done(world);
    }
    if (action === 'world.archive') {
      requirePermission('worlds.manage');
      const world = writableWorld(payload.worldId);
      check(town.currentWorldId !== world.id, 'conflict', 'yat.error.current_world_archive');
      world.archived = true; world.archivedAt = timestamp;
      return done(world);
    }
    if (action === 'project.create') {
      requirePermission('project.create'); writableWorld(payload.worldId);
      check(town.currentWorldId === undefined || payload.worldId === town.currentWorldId, 'conflict', 'yat.error.current_world_changed');
      reserveRecord();
      const project = { ...base(payload.worldId), ...projectFields(payload) };
      checkRegion(project, project.worldId);
      town.projects.push(project); return done(project);
    }
    if (action === 'project.update') {
      const project = projectWritable(payload.id); manageProject(project);
      check(!('status' in payload), 'invalid_argument', 'yat.error.project_status_action');
      const fields = projectFields(payload, project);
      check(fields.dimension === project.dimension || !town.tickets.some(ticket => ticket.projectId === project.id), 'conflict', 'yat.error.project_dimension_has_tickets');
      checkRegion(fields, project.worldId, project);
      if (!fields.nameKey) delete project.nameKey;
      Object.assign(project, fields, { updatedAt: timestamp });
      return done(project);
    }
    if (action === 'project.delete') {
      const project = projectWritable(payload.id); manageProject(project);
      town.tickets = town.tickets.filter(ticket => ticket.projectId !== project.id);
      town.projects = town.projects.filter(item => item.id !== project.id);
      return done({ id: project.id });
    }
    if (action === 'project.repair.complete') {
      const project = projectWritable(payload.id);
      if (!has('ticket.manage')) manageProject(project);
      check(project.status === 'maintenance', 'conflict', 'yat.error.project_no_repair');
      for (const ticket of town.tickets) {
        if (ticket.projectId === project.id && ticket.status !== 'resolved')
          Object.assign(ticket, { status: 'resolved', resolution: translate('yat.default.repair_completed'), resolutionKey: 'yat.default.repair_completed', updatedBy: player.id, updatedAt: timestamp });
      }
      Object.assign(project, { status: 'completed', updatedAt: timestamp });
      return done(project);
    }
    if (action === 'project.status') {
      const project = projectWritable(payload.id); manageProject(project);
      const status = choice(payload.status, ['active', 'completed'], 'yat.field.project_status');
      check(project.status === 'planned' && status === 'active' || project.status === 'active' && status === 'completed', 'conflict', 'yat.error.project_status_invalid');
      check(status !== 'active' || project.name.trim().length > 0 && project.name !== translate('yat.default.reserved_plot'), 'invalid_argument', 'yat.error.project_name_before_start');
      Object.assign(project, { status, updatedAt: timestamp }); return done(project);
    }
    if (action === 'ticket.create') {
      requirePermission('ticket.create');
      reserveRecord();
      const project = projectWritable(payload.projectId);
      check(!payload.worldId || project.worldId === payload.worldId, 'invalid_argument', 'yat.error.ticket_world_mismatch');
      check(['completed', 'maintenance'].includes(project.status), 'conflict', 'yat.error.ticket_completed_project');
      const location = position(payload);
      check(location.dimension === project.dimension, 'invalid_argument', 'yat.error.ticket_dimension_mismatch');
      const description = text(payload.description, 'yat.field.ticket_description', 2000, payload.title !== undefined);
      const title = payload.title === undefined ? Array.from(description.replace(/\s+/g, ' ')).slice(0, 50).join('') : text(payload.title, 'yat.field.ticket_title', 100);
      const ticket = { ...base(project.worldId), ...location, projectId: project.id, title, description, status: 'open', resolution: '' };
      town.tickets.push(ticket); project.status = 'maintenance'; project.updatedAt = timestamp;
      return done(ticket);
    }
    if (action === 'ticket.update') {
      const ticket = writable(find('tickets')); const project = projectWritable(ticket.projectId);
      if (!has('ticket.manage')) manageProject(project);
      const status = choice(payload.status, ['open', 'working', 'resolved'], 'yat.field.ticket_status');
      check(status !== ticket.status, 'conflict', 'yat.error.ticket_status_unchanged');
      const resolution = text(payload.resolution, 'yat.field.resolution', 2000, true);
      check(status !== 'resolved' || resolution.length > 0, 'invalid_argument', 'yat.error.ticket_resolution_required');
      Object.assign(ticket, { status, resolution, updatedBy: player.id, updatedAt: timestamp });
      delete ticket.resolutionKey;
      if (town.tickets.some(item => item.projectId === project.id && item.status !== 'resolved')) project.status = 'maintenance';
      else if (project.status === 'maintenance') project.status = 'completed';
      project.updatedAt = timestamp; return done(ticket);
    }
    fail('not_found', 'yat.error.action_not_found');
  }
}
