import { createServer, type Server } from 'node:http';
import { randomUUID } from 'node:crypto';
import { loadWorkerConfig } from '../common/config/worker-config';
import type { ThumbnailResult } from '../common/contracts/thumbnail-contract';
import { ThumbnailCallbackClientImpl } from './thumbnail-callback.client';

const PATH = '/stream/internal/api/thumbnail-results';

interface CapturedRequest {
  method?: string;
  url?: string;
  token?: string;
  contentType?: string;
  body: string;
}

let server: Server;
let origin: string;
let requests: CapturedRequest[];
let redirectRequests: number;
let statusOverride: number | undefined;
let closedResponses = 0;
let mode:
  | 'applied'
  | 'ignored'
  | 'hang'
  | 'bad-status'
  | 'invalid-json'
  | 'invalid-outcome'
  | 'wrong-content-type'
  | 'error-body-hang'
  | 'wrong-content-type-hang'
  | 'partial-json-hang'
  | 'redirect';

function result(): ThumbnailResult {
  const sessionId = randomUUID();
  const jobId = randomUUID();
  return {
    jobId,
    sessionId,
    objectKey: `livestream-thumbnails/${sessionId}/${jobId}.jpg`,
    contentType: 'image/jpeg',
    sizeBytes: 48210,
    width: 640,
    height: 360,
  };
}

function config(baseUrl: string) {
  return loadWorkerConfig({
    STREAM_SERVICE_BASE_URL: baseUrl,
  });
}

function client(baseUrl = origin): ThumbnailCallbackClientImpl {
  return new ThumbnailCallbackClientImpl(config(baseUrl));
}

beforeAll(async () => {
  server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on('data', (chunk: Buffer) => chunks.push(chunk));
    req.on('end', () => {
      if (req.url === '/second') {
        redirectRequests++;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end('{"outcome":"APPLIED"}');
        return;
      }
      requests.push({
        method: req.method,
        url: req.url,
        token: req.headers['x-internal-token'] as string | undefined,
        contentType: req.headers['content-type'],
        body: Buffer.concat(chunks).toString('utf8'),
      });
      if (statusOverride !== undefined) {
        res.writeHead(statusOverride, { 'Content-Type': 'application/json' });
        res.end('{"error":"test"}');
        return;
      }
      switch (mode) {
        case 'error-body-hang':
        case 'wrong-content-type-hang':
        case 'partial-json-hang':
          res.on('close', () => closedResponses++);
          res.writeHead(mode === 'error-body-hang' ? 503 : 200, {
            'Content-Type': mode === 'wrong-content-type-hang'
              ? 'text/plain' : 'application/json',
          });
          res.write('{');
          return;
        case 'hang':
          return;
        case 'bad-status':
          res.writeHead(503, { 'Content-Type': 'application/json' });
          res.end('{"error":"unavailable"}');
          return;
        case 'invalid-json':
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end('not-json');
          return;
        case 'invalid-outcome':
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end('{"outcome":"MAYBE"}');
          return;
        case 'wrong-content-type':
          res.writeHead(200, { 'Content-Type': 'text/plain' });
          res.end('{"outcome":"APPLIED"}');
          return;
        case 'redirect':
          res.writeHead(302, { Location: `${origin}/second` });
          res.end();
          return;
        case 'ignored':
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end('{"outcome":"IGNORED"}');
          return;
        default:
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end('{"outcome":"APPLIED"}');
      }
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('address');
  origin = `http://127.0.0.1:${address.port}`;
});

afterAll(async () => {
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

beforeEach(() => {
  requests = [];
  redirectRequests = 0;
  statusOverride = undefined;
  closedResponses = 0;
  mode = 'applied';
});

describe('ThumbnailCallbackClient', () => {
  it.each([
    ['error-body-hang', 'CALLBACK_REJECTED'],
    ['wrong-content-type-hang', 'CALLBACK_INVALID_RESPONSE'],
    ['partial-json-hang', 'CALLBACK_TIMEOUT'],
  ] as const)('closes the unfinished response body (%s)', async (kind, code) => {
    mode = kind;
    const callback = client();
    try {
      await expect(callback.send(result(), { timeoutMs: 200 }))
        .rejects.toThrow(code);
      const deadline = Date.now() + 1000;
      while (closedResponses === 0 && Date.now() < deadline)
        await new Promise((resolve) => setTimeout(resolve, 10));
      expect(closedResponses).toBeGreaterThan(0);
      expect(requests).toHaveLength(1);
    } finally {
      await callback.onApplicationShutdown();
    }
  });

  it('posts the exact contract once and accepts APPLIED', async () => {
    const callback = client();
    const payload = result();
    await expect(callback.send(payload)).resolves.toEqual({
      outcome: 'APPLIED',
    });
    expect(requests).toHaveLength(1);
    expect(requests[0]).toMatchObject({
      method: 'POST',
      url: PATH,
      token: undefined,
    });
    expect(requests[0].contentType).toContain('application/json');
    expect(JSON.parse(requests[0].body)).toEqual(payload);
    await callback.onApplicationShutdown();
  });

  it('accepts IGNORED as a completed callback', async () => {
    mode = 'ignored';
    const callback = client();
    await expect(callback.send(result())).resolves.toEqual({
      outcome: 'IGNORED',
    });
    expect(requests).toHaveLength(1);
    await callback.onApplicationShutdown();
  });

  it.each([400, 401, 503])('rejects HTTP %s without retrying', async (status) => {
    statusOverride = status;
    const callback = client();
    await expect(callback.send(result())).rejects.toThrow('CALLBACK_REJECTED');
    expect(requests).toHaveLength(1);
    await callback.onApplicationShutdown();
  });

  it('normalizes connection failures without retrying', async () => {
    const closed = createServer();
    await new Promise<void>((resolve) => closed.listen(0, '127.0.0.1', resolve));
    const address = closed.address();
    if (!address || typeof address === 'string') throw new Error('address');
    const failedOrigin = `http://127.0.0.1:${address.port}`;
    await new Promise<void>((resolve) => closed.close(() => resolve()));
    const callback = client(failedOrigin);
    await expect(callback.send(result())).rejects.toThrow('CALLBACK_FAILED');
    await callback.onApplicationShutdown();
  });

  it('times out while waiting for the response and keeps a single request', async () => {
    mode = 'hang';
    const callback = client();
    await expect(
      callback.send(result(), { timeoutMs: 200 }),
    ).rejects.toThrow('CALLBACK_TIMEOUT');
    expect(requests).toHaveLength(1);
    await callback.onApplicationShutdown();
  });

  it('does not follow redirects', async () => {
    mode = 'redirect';
    const callback = client();
    await expect(callback.send(result())).rejects.toThrow('CALLBACK_FAILED');
    expect(requests).toHaveLength(1);
    expect(redirectRequests).toBe(0);
    await callback.onApplicationShutdown();
  });

  it.each([
    ['invalid-json', 'CALLBACK_INVALID_RESPONSE'],
    ['invalid-outcome', 'CALLBACK_INVALID_RESPONSE'],
    ['wrong-content-type', 'CALLBACK_INVALID_RESPONSE'],
  ] as const)('rejects a malformed response (%s)', async (kind, error) => {
    mode = kind;
    const callback = client();
    await expect(callback.send(result())).rejects.toThrow(error);
    expect(requests).toHaveLength(1);
    await callback.onApplicationShutdown();
  });

  it('aborts an in-flight callback through the caller signal', async () => {
    mode = 'hang';
    const callback = client();
    const abort = new AbortController();
    const pending = callback.send(result(), { signal: abort.signal });
    const deadline = Date.now() + 2000;
    while (requests.length === 0 && Date.now() < deadline)
      await new Promise((resolve) => setTimeout(resolve, 10));
    abort.abort();
    await expect(pending).rejects.toThrow('CALLBACK_ABORTED');
    expect(requests).toHaveLength(1);
    await callback.onApplicationShutdown();
  });

  it('aborts an in-flight callback on shutdown', async () => {
    mode = 'hang';
    const callback = client();
    const pending = callback.send(result());
    const deadline = Date.now() + 2000;
    while (requests.length === 0 && Date.now() < deadline)
      await new Promise((resolve) => setTimeout(resolve, 10));
    const shutdown = callback.onApplicationShutdown();
    await expect(pending).rejects.toThrow('CALLBACK_ABORTED');
    await shutdown;
    expect(requests).toHaveLength(1);
  });

  it('rejects invalid result metadata before sending', async () => {
    const callback = client();
    const invalid = { ...result(), objectKey: 'other/key.jpg' };
    await expect(callback.send(invalid)).rejects.toThrow(
      'INVALID_CALLBACK_RESULT',
    );
    expect(requests).toHaveLength(0);
    await callback.onApplicationShutdown();
  });

  it('sends callbacks without internal credentials', async () => {
    const callback = new ThumbnailCallbackClientImpl(config(origin));
    await expect(callback.send(result())).resolves.toEqual({ outcome: 'APPLIED' });
    expect(requests).toHaveLength(1);
    expect(requests[0].token).toBeUndefined();
    await callback.onApplicationShutdown();
  });
});
