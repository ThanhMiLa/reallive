import { createServer, type Server } from 'node:http';
import { mkdtemp, writeFile, rm, symlink } from 'node:fs/promises';
import { basename, join } from 'node:path';
import { tmpdir } from 'node:os';
import { randomUUID } from 'node:crypto';
import { loadWorkerConfig } from '../src/common/config/worker-config';
import { R2ThumbnailStorage } from '../src/storage/r2-thumbnail-storage';

let server: Server;
let directory: string;
let origin: string;
let mode = 'ok';
let received: Buffer;
let requests = 0;
let contentType: string | undefined;
let path = '';
beforeAll(async () => {
  directory = await mkdtemp(join(tmpdir(), 'r2-test-'));
  server = createServer((req, res) => {
    requests++;
    path = req.url ?? '';
    contentType = req.headers['content-type'];
    const chunks: Buffer[] = [];
    req.on('data', (chunk: Buffer) => chunks.push(chunk));
    req.on('end', () => {
      received = Buffer.concat(chunks);
      if (mode === 'hang') return;
      if (mode === 'fail') {
        res.writeHead(503).end();
        return;
      }
      res.writeHead(200, { ETag: '"test"' }).end();
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
  await rm(directory, { recursive: true, force: true });
});
beforeEach(() => {
  mode = 'ok';
  requests = 0;
  path = '';
});
function config() {
  return loadWorkerConfig({
    MEDIA_WORKER_R2_ENDPOINT: origin,
    MEDIA_WORKER_R2_BUCKET: 'test-bucket',
    MEDIA_WORKER_R2_ACCESS_KEY_ID: 'example',
    MEDIA_WORKER_R2_SECRET_ACCESS_KEY: 'example-secret',
  });
}
async function input() {
  const filePath = join(directory, `${randomUUID()}.jpg`);
  const bytes = Buffer.from([255, 216, 255, 1, 2, 3, 255, 217]);
  await writeFile(filePath, bytes);
  return {
    filePath,
    bytes,
    sessionId: randomUUID(),
    jobId: randomUUID(),
    width: 640,
    height: 360,
    sizeBytes: bytes.length,
  };
}

test('uploads exact bytes with expected key and content type', async () => {
  const storage = new R2ThumbnailStorage(config());
  const data = await input();
  try {
    const result = await storage.upload(data);
    expect(result.objectKey).toBe(
      `livestream-thumbnails/${data.sessionId}/${data.jobId}.jpg`,
    );
    expect(path).toContain(`/test-bucket/${result.objectKey}`);
    expect(contentType).toBe('image/jpeg');
    expect(received).toEqual(data.bytes);
    expect(requests).toBe(1);
  } finally {
    await storage.onApplicationShutdown();
  }
});
test('does not retry failed uploads', async () => {
  mode = 'fail';
  const storage = new R2ThumbnailStorage(config());
  try {
    await expect(storage.upload(await input())).rejects.toThrow(
      'R2_UPLOAD_FAILED',
    );
    expect(requests).toBe(1);
  } finally {
    await storage.onApplicationShutdown();
  }
});
test('aborts a stalled request on timeout', async () => {
  mode = 'hang';
  const storage = new R2ThumbnailStorage(config());
  try {
    await expect(
      storage.upload({ ...(await input()), timeoutMs: 300 }),
    ).rejects.toThrow('UPLOAD_TIMEOUT');
    expect(requests).toBe(1);
  } finally {
    await storage.onApplicationShutdown();
  }
});
test('rejects wrong metadata and missing configuration', async () => {
  const data = await input();
  const storage = new R2ThumbnailStorage(config());
  try {
    await expect(
      storage.upload({ ...data, sizeBytes: data.sizeBytes + 1 }),
    ).rejects.toThrow('INVALID_UPLOAD_INPUT');
    expect(requests).toBe(0);
  } finally {
    await storage.onApplicationShutdown();
  }
  const unconfigured = new R2ThumbnailStorage(
  );
  try {
    await expect(unconfigured.upload(data)).rejects.toThrow(
      'R2_NOT_CONFIGURED',
    );
  } finally {
    await unconfigured.onApplicationShutdown();
  }
});

test('rejects symlink input without uploading', async () => {
  const data = await input();
  const link = join(directory, `${randomUUID()}.jpg`);
  await symlink(data.filePath, link);
  const storage = new R2ThumbnailStorage(config());
  try {
    await expect(storage.upload({ ...data, filePath: link })).rejects.toThrow();
    expect(requests).toBe(0);
  } finally {
    await storage.onApplicationShutdown();
  }
});

test('external cancellation stops an in-flight upload', async () => {
  mode = 'hang';
  const storage = new R2ThumbnailStorage(config());
  const abort = new AbortController();
  const data = await input();
  try {
    const pending = storage.upload({ ...data, signal: abort.signal });
    const deadline = Date.now() + 3000;
    while (requests === 0 && Date.now() < deadline)
      await new Promise((resolve) => setTimeout(resolve, 10));
    expect(requests).toBe(1);
    abort.abort();
    await expect(pending).rejects.toThrow('UPLOAD_ABORTED');
    expect(requests).toBe(1);
  } finally {
    await storage.onApplicationShutdown();
  }
});

test('CLI SIGTERM aborts a stalled upload and removes the JPEG', async () => {
  const { spawn, spawnSync } = await import('node:child_process');
  const { readdir, readFile } = await import('node:fs/promises');
  const generated = spawnSync('ffmpeg', [
    '-hide_banner',
    '-loglevel',
    'error',
    '-f',
    'lavfi',
    '-i',
    'testsrc=size=320x240:rate=10',
    '-t',
    '2',
    '-c:v',
    'mpeg2video',
    '-f',
    'hls',
    '-hls_time',
    '1',
    '-hls_list_size',
    '0',
    join(directory, 'index.m3u8'),
  ]);
  if (generated.status !== 0) throw new Error('fixture failed');
  const hls = createServer(async (req, res) => {
    try {
      res.end(
        await readFile(
          join(directory, basename((req.url ?? '').split('?')[0])),
        ),
      );
    } catch {
      res.writeHead(404).end();
    }
  });
  await new Promise<void>((resolve) => hls.listen(0, '127.0.0.1', resolve));
  const address = hls.address();
  if (!address || typeof address === 'string') throw new Error('address');
  const hlsOrigin = `http://127.0.0.1:${address.port}`;
  mode = 'hang';
  const sessionId = randomUUID();
  const jobId = randomUUID();
  const future = new Date(Date.now() + 60000).toISOString();
  const child = spawn(
    process.execPath,
    ['dist/processor/capture-upload-cli.js'],
    {
      cwd: process.cwd(),
      env: {
        ...process.env,
        NODE_ENV: 'test',
        MEDIA_WORKER_MEDIAMTX_BASE_URL: hlsOrigin,
        MEDIA_WORKER_TEMP_DIR: directory,
        MEDIA_WORKER_R2_ENDPOINT: origin,
        MEDIA_WORKER_R2_BUCKET: 'test-bucket',
        MEDIA_WORKER_R2_ACCESS_KEY_ID: 'example',
        MEDIA_WORKER_R2_SECRET_ACCESS_KEY: 'example-secret',
      },
      stdio: ['pipe', 'pipe', 'pipe'],
    },
  );
  let stderr = '';
  child.stderr.on('data', (data: Buffer) => {
    stderr += data.toString();
  });
  const closed = new Promise<number | null>((resolve) =>
    child.on('close', resolve),
  );
  child.stdin.end(
    JSON.stringify({
      jobId,
      sessionId,
      playbackUrl: `${hlsOrigin}/live/${sessionId}/index.m3u8`,
      readToken: 'example-jwt',
      readExpiresAt: future,
      jobDeadlineAt: future,
    }),
  );
  try {
    const until = Date.now() + 10000;
    while (requests === 0 && Date.now() < until)
      await new Promise((resolve) => setTimeout(resolve, 20));
    expect(requests).toBe(1);
    child.kill('SIGTERM');
    expect(await closed).toBe(143);
    expect(stderr).toContain('UPLOAD_ABORTED');
    expect(
      (await readdir(directory)).some((name) =>
        name.startsWith(`thumbnail-${jobId}-`),
      ),
    ).toBe(false);
  } finally {
    child.kill('SIGKILL');
    hls.closeAllConnections();
    await new Promise<void>((resolve) => hls.close(() => resolve()));
  }
}, 30000);
