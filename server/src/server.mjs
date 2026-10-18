import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ApiError, JsonStore, TownService, validateConfig } from './service.mjs';

const STATUS = {
  invalid_argument: 400, unauthenticated: 401, forbidden: 403, not_found: 404,
  conflict: 409, archived: 409, payload_too_large: 413, unsupported_media_type: 415,
  rate_limited: 429,
};

export function createHttpServer(service, config, logger = console) {
  const windows = new Map();
  const server = createServer(async (request, response) => {
    response.setHeader('Content-Type', 'application/json; charset=utf-8');
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    const send = (status, result) => { if (!response.destroyed) { response.writeHead(status); response.end(JSON.stringify(result)); } };
    try {
      const now = Date.now();
      for (const [address, window] of windows) if (window.until <= now) windows.delete(address);
      const address = request.socket.remoteAddress ?? 'unknown';
      const window = windows.get(address) ?? { until: now + 60000, count: 0 };
      window.count += 1; windows.set(address, window);
      if (window.count > config.requestsPerMinute || windows.size > 10000) throw new ApiError('rate_limited', '请求过多，请稍后重试');
      if (request.method === 'GET' && request.url === '/health') { send(200, { ok: true, data: { version: '1.1.0' } }); return; }
      if (request.url !== '/api') throw new ApiError('not_found', '接口不存在');
      if (request.method !== 'POST') { response.setHeader('Allow', 'POST'); send(405, { ok: false, error: { code: 'invalid_argument', message: '此接口只接受 POST 请求' } }); return; }
      if (!/^application\/json(?:\s*;|$)/i.test(request.headers['content-type'] ?? '')) throw new ApiError('unsupported_media_type', '请求必须使用 application/json');
      const body = await new Promise((resolve, reject) => {
        let size = 0;
        const parts = [];
        request.on('data', part => { size += part.length; if (size <= config.maxBodyBytes) parts.push(part); });
        request.on('end', () => size > config.maxBodyBytes ? reject(new ApiError('payload_too_large', '请求内容过大')) : resolve(Buffer.concat(parts).toString('utf8')));
        request.on('error', reject);
        request.on('aborted', () => reject(new ApiError('invalid_argument', '请求已中断')));
      });
      let input;
      try { input = JSON.parse(body); } catch { throw new ApiError('invalid_argument', '请求不是有效的 JSON'); }
      if (!input || typeof input !== 'object' || Array.isArray(input)) throw new ApiError('invalid_argument', '请求必须是 JSON 对象');
      const authorization = request.headers.authorization ?? '';
      const token = /^Bearer ([a-f0-9]{64})$/.exec(authorization)?.[1];
      const data = await service.handle(input.action, input.payload ?? {}, token);
      send(200, { ok: true, data });
    } catch (error) {
      if (error instanceof ApiError) send(STATUS[error.code] ?? 400, { ok: false, error: { code: error.code, message: error.message } });
      else {
        logger.error('Request failed:', error.code ?? error.name ?? 'UnknownError');
        send(500, { ok: false, error: { code: 'internal', message: '服务暂时无法完成操作，请稍后重试' } });
      }
    }
  });
  server.requestTimeout = config.requestTimeoutSeconds * 1000;
  server.headersTimeout = config.requestTimeoutSeconds * 1000;
  server.on('clientError', (_error, socket) => { socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n'); });
  return server;
}

export async function start(configFile = path.resolve(fileURLToPath(new URL('..', import.meta.url)), 'config.json')) {
  let input;
  try { input = JSON.parse(await readFile(configFile, 'utf8')); }
  catch (error) { if (error.code !== 'ENOENT') throw error; input = {}; }
  const config = validateConfig(input);
  const dataFile = path.resolve(path.dirname(configFile), config.dataFile);
  const service = await new TownService({ store: new JsonStore(dataFile), config }).initialize();
  const server = createHttpServer(service, config);
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(config.port, config.host, resolve); });
  console.log(`YetAnotherTown service listening on ${config.host}:${config.port}`);
  const close = () => { server.close(); server.closeIdleConnections(); };
  process.once('SIGINT', close); process.once('SIGTERM', close);
  return { server, service };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  start(process.argv[2] ? path.resolve(process.argv[2]) : undefined).catch(error => {
    console.error('Startup failed:', error.code ?? error.message);
    process.exitCode = 1;
  });
}
