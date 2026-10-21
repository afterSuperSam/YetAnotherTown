import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ApiError, JsonStore, TownService, validateConfig } from './service.mjs';

const STATUS = {
  invalid_argument: 400, unauthenticated: 401, forbidden: 403, not_found: 404,
  conflict: 409, archived: 409, payload_too_large: 413, unsupported_media_type: 415,
  request_timeout: 408, rate_limited: 429,
};

export function createHttpServer(service, config, logger = console) {
  const windows = new Map();
  const server = createServer(async (request, response) => {
    response.setHeader('Content-Type', 'application/json; charset=utf-8');
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    const send = (status, result) => {
      if (response.destroyed || response.writableEnded) return;
      if (!request.complete) {
        response.setHeader('Connection', 'close');
        response.once('finish', () => request.destroy());
      }
      response.writeHead(status); response.end(JSON.stringify(result));
    };
    request.on('error', () => send(400, { ok: false, error: new ApiError('invalid_argument', 'yat.error.request_read_failed') }));
    try {
      const now = Date.now();
      for (const [address, window] of windows) if (window.until <= now) windows.delete(address);
      const address = request.socket.remoteAddress ?? 'unknown';
      if (!windows.has(address) && windows.size >= config.maximumRateLimitEntries) throw new ApiError('rate_limited', 'yat.error.requests_limited');
      const window = windows.get(address) ?? { until: now + 60000, count: 0 };
      window.count += 1; windows.set(address, window);
      if (window.count > config.requestsPerMinute) throw new ApiError('rate_limited', 'yat.error.requests_limited');
      if (request.method === 'GET' && request.url === '/health') { send(200, { ok: true, data: { version: '1.0.0-beta' } }); return; }
      if (request.url !== '/api') throw new ApiError('not_found', 'yat.error.endpoint_not_found');
      if (request.method !== 'POST') { response.setHeader('Allow', 'POST'); send(405, { ok: false, error: new ApiError('invalid_argument', 'yat.error.post_required') }); return; }
      if (!/^application\/json(?:\s*;|$)/i.test(request.headers['content-type'] ?? '')) throw new ApiError('unsupported_media_type', 'yat.error.json_content_type');
      if (Number(request.headers['content-length']) > config.maxBodyBytes) throw new ApiError('payload_too_large', 'yat.error.request_too_large');
      const body = await new Promise((resolve, reject) => {
        let size = 0;
        let settled = false;
        const parts = [];
        const finish = (error, value) => {
          if (settled) return;
          settled = true;
          clearTimeout(timeout);
          request.off('data', data); request.off('end', end); request.off('error', failed); request.off('aborted', aborted);
          if (error) reject(error); else resolve(value);
        };
        const data = part => {
          size += part.length;
          if (size > config.maxBodyBytes) finish(new ApiError('payload_too_large', 'yat.error.request_too_large'));
          else parts.push(part);
        };
        const end = () => finish(null, Buffer.concat(parts).toString('utf8'));
        const failed = () => finish(new ApiError('invalid_argument', 'yat.error.request_read_failed'));
        const aborted = () => finish(new ApiError('invalid_argument', 'yat.error.request_aborted'));
        const timeout = setTimeout(() => finish(new ApiError('request_timeout', 'yat.error.request_timeout')), config.requestTimeoutSeconds * 1000);
        timeout.unref();
        request.on('data', data); request.on('end', end); request.on('error', failed); request.on('aborted', aborted);
      });
      let input;
      try { input = JSON.parse(body); } catch { throw new ApiError('invalid_argument', 'yat.error.json_invalid'); }
      if (!input || typeof input !== 'object' || Array.isArray(input)) throw new ApiError('invalid_argument', 'yat.error.json_object_required');
      const authorization = request.headers.authorization ?? '';
      const token = /^Bearer ([a-f0-9]{64})$/.exec(authorization)?.[1];
      const data = await service.handle(input.action, Object.hasOwn(input, 'payload') ? input.payload : {}, token);
      send(200, { ok: true, data });
    } catch (error) {
      if (error instanceof ApiError) send(STATUS[error.code] ?? 400, { ok: false, error: error });
      else {
        logger.error('Request failed:', error.code ?? error.name ?? 'UnknownError');
        send(500, { ok: false, error: new ApiError('internal', 'yat.error.internal') });
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
  console.log(`Yet Another Town service listening on ${config.host}:${config.port}`);
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
