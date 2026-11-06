import { createServer, type Server } from 'node:http';
import { spawn, spawnSync } from 'node:child_process';
import { mkdtemp, readFile, rm, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, basename } from 'node:path';
import { randomUUID } from 'node:crypto';
import { loadWorkerConfig } from '../src/common/config/worker-config';
import { FfmpegRunnerImpl } from '../src/ffmpeg/ffmpeg-runner';
import { ThumbnailCaptureService } from '../src/ffmpeg/thumbnail-capture.service';
import type { ThumbnailJobInput } from '../src/common/contracts/thumbnail-contract';

let directory: string;
let server: Server;
let base: string;
const token = 'integration-token';
const paths: string[] = [];
const portraitSession = randomUUID();
let disconnectSegments = false;
beforeAll(async () => {
  directory = await mkdtemp(join(tmpdir(), 'thumbnail-integration-'));
  const result = spawnSync(
    'ffmpeg',
    [
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
    ],
    { encoding: 'utf8' },
  );
  if (result.status !== 0)
    throw new Error(`fixture generation failed: ${result.stderr}`);
  const portrait = spawnSync(
    'ffmpeg',
    [
      '-hide_banner',
      '-loglevel',
      'error',
      '-f',
      'lavfi',
      '-i',
      'testsrc=size=240x480:rate=10',
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
      join(directory, 'portrait.m3u8'),
    ],
    { encoding: 'utf8' },
  );
  if (portrait.status !== 0)
    throw new Error('portrait fixture generation failed');
  server = createServer(async (request, response) => {
    paths.push(request.url ?? '');
    if (request.headers.authorization !== `Bearer ${token}`) {
      response.writeHead(401).end();
      return;
    }
    if (disconnectSegments && request.url?.endsWith('.ts')) {
      request.socket.destroy();
      return;
    }
    try {
      response.end(
        await readFile(
          join(
            directory,
            request.url?.includes(portraitSession) &&
              request.url.endsWith('index.m3u8')
              ? 'portrait.m3u8'
              : basename((request.url ?? '').split('?')[0]),
          ),
        ),
      );
    } catch {
      response.writeHead(404).end();
    }
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const addr = server.address();
  if (!addr || typeof addr === 'string') throw new Error('missing address');
  base = `http://127.0.0.1:${addr.port}`;
});
afterAll(async () => {
  if (server)
    await new Promise<void>((resolve) => server.close(() => resolve()));
  if (directory) await rm(directory, { recursive: true, force: true });
});

test('captures and validates JPEG through an authenticated HLS server', async () => {
  const config = loadWorkerConfig({
    MEDIA_WORKER_INTERNAL_TOKEN: 'a'.repeat(32),
    MEDIA_WORKER_MEDIAMTX_BASE_URL: base,
    MEDIA_WORKER_TEMP_DIR: directory,
  });
  const runner = new FfmpegRunnerImpl(config);
  const capture = new ThumbnailCaptureService(config, runner);
  const sessionId = randomUUID();
  const future = new Date(Date.now() + 60000).toISOString();
  const job: ThumbnailJobInput = {
    jobId: randomUUID(),
    sessionId,
    playbackUrl: `${base}/live/${sessionId}/index.m3u8`,
    readToken: token,
    readExpiresAt: future,
    jobDeadlineAt: future,
  };
  const image = await capture.capture(job);
  try {
    expect(image).toMatchObject({
      width: 640,
      height: 360,
      contentType: 'image/jpeg',
    });
    expect(image.sizeBytes).toBeGreaterThan(0);
    expect((await readFile(image.filePath)).subarray(0, 2)).toEqual(
      Buffer.from([0xff, 0xd8]),
    );
    expect(paths.some((p) => p.includes('.ts'))).toBe(true);
  } finally {
    await capture.release(image);
    await runner.onApplicationShutdown();
  }
});

test('rejects expired jobs and invalid media source before running ffmpeg', async () => {
  const config = loadWorkerConfig({
    MEDIA_WORKER_INTERNAL_TOKEN: 'a'.repeat(32),
    MEDIA_WORKER_MEDIAMTX_BASE_URL: base,
    MEDIA_WORKER_TEMP_DIR: directory,
  });
  const capture = new ThumbnailCaptureService(
    config,
    new FfmpegRunnerImpl(config),
  );
  const sessionId = randomUUID();
  const job: ThumbnailJobInput = {
    jobId: randomUUID(),
    sessionId,
    playbackUrl: `${base}/live/${sessionId}/index.m3u8`,
    readToken: token,
    readExpiresAt: new Date(Date.now() - 1000).toISOString(),
    jobDeadlineAt: new Date(Date.now() + 60000).toISOString(),
  };
  await expect(capture.capture(job)).rejects.toThrow('JOB_EXPIRED');
  await expect(
    capture.capture({ ...job, playbackUrl: 'http://invalid.example/test' }),
  ).rejects.toThrow('INVALID_JOB');
});

test('times out on a stalled HLS source and removes its temporary directory', async () => {
  const stalled = createServer(() => undefined);
  await new Promise<void>((resolve) => stalled.listen(0, '127.0.0.1', resolve));
  const address = stalled.address();
  if (!address || typeof address === 'string')
    throw new Error('missing address');
  const origin = `http://127.0.0.1:${address.port}`;
  const config = loadWorkerConfig({
    MEDIA_WORKER_INTERNAL_TOKEN: 'a'.repeat(32),
    MEDIA_WORKER_MEDIAMTX_BASE_URL: origin,
    MEDIA_WORKER_TEMP_DIR: directory,
  });
  config.timeouts.ffmpegMs = 300;
  const runner = new FfmpegRunnerImpl(config);
  const service = new ThumbnailCaptureService(config, runner);
  const sessionId = randomUUID();
  const jobId = randomUUID();
  const future = new Date(Date.now() + 60000).toISOString();
  try {
    await expect(
      service.capture({
        jobId,
        sessionId,
        playbackUrl: `${origin}/live/${sessionId}/index.m3u8`,
        readToken: token,
        readExpiresAt: future,
        jobDeadlineAt: future,
      }),
    ).rejects.toThrow('CAPTURE_TIMEOUT');
    const { readdir } = await import('node:fs/promises');
    expect(
      (await readdir(directory)).some((name) =>
        name.startsWith(`thumbnail-${jobId}-`),
      ),
    ).toBe(false);
  } finally {
    await runner.onApplicationShutdown();
    stalled.closeAllConnections();
    await new Promise<void>((resolve) => stalled.close(() => resolve()));
  }
});

test('CLI SIGTERM waits for FFmpeg exit and cleanup, and token is absent from argv', async () => {
  let started!: () => void;
  const seenRequest = new Promise<void>((resolve) => {
    started = resolve;
  });
  const stalled = createServer(() => started());
  await new Promise<void>((resolve) => stalled.listen(0, '127.0.0.1', resolve));
  const addr = stalled.address();
  if (!addr || typeof addr === 'string') throw new Error('missing address');
  const origin = `http://127.0.0.1:${addr.port}`;
  const root = await mkdtemp(join(tmpdir(), 'cli-capture-'));
  const sessionId = randomUUID();
  const jobId = randomUUID();
  const future = new Date(Date.now() + 60000).toISOString();
  const child = spawn(process.execPath, ['dist/ffmpeg/capture-cli.js'], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      NODE_ENV: 'test',
      MEDIA_WORKER_INTERNAL_TOKEN: 'a'.repeat(32),
      MEDIA_WORKER_TEMP_DIR: root,
      MEDIA_WORKER_MEDIAMTX_BASE_URL: origin,
      MEDIA_WORKER_FFMPEG_TIMEOUT_MS: '15000',
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let stderr = '';
  child.stderr.on('data', (chunk: Buffer) => {
    stderr += chunk.toString();
  });
  const ended = new Promise<number | null>((resolve) =>
    child.on('close', resolve),
  );
  child.stdin.end(
    JSON.stringify({
      jobId,
      sessionId,
      playbackUrl: `${origin}/live/${sessionId}/index.m3u8`,
      readToken: token,
      readExpiresAt: future,
      jobDeadlineAt: future,
    }),
  );
  let pid: number | undefined;
  try {
    await Promise.race([
      seenRequest,
      ended.then(() => {
        throw new Error(`CLI exited: ${stderr}`);
      }),
      new Promise((_, reject) =>
        setTimeout(
          () => reject(new Error('CLI did not request HLS')),
          10000,
        ).unref(),
      ),
    ]);
    const rows = spawnSync('ps', ['-axo', 'pid=,ppid=,args='], {
      encoding: 'utf8',
    }).stdout.split('\n');
    const row = rows.find(
      (line) => Number(line.trim().split(/\s+/)[1]) === child.pid,
    );
    expect(row).toBeDefined();
    expect(row).not.toContain(token);
    expect(row).not.toContain('Authorization');
    pid = Number(row!.trim().split(/\s+/)[0]);
    child.kill('SIGTERM');
    expect(await ended).toBe(143);
    expect(stderr).toContain('CAPTURE_ABORTED');
    expect(await readdir(root)).toEqual([]);
    expect(() => process.kill(pid!, 0)).toThrow();
  } finally {
    child.kill('SIGKILL');
    if (pid) {
      try {
        process.kill(pid, 'SIGKILL');
      } catch {
        /* already stopped */
      }
    }
    stalled.closeAllConnections();
    await new Promise<void>((resolve) => stalled.close(() => resolve()));
    await rm(root, { recursive: true, force: true });
  }
});

test('captures portrait and landscape concurrently into separate directories', async () => {
  const config = loadWorkerConfig({
    MEDIA_WORKER_INTERNAL_TOKEN: 'a'.repeat(32),
    MEDIA_WORKER_MEDIAMTX_BASE_URL: base,
    MEDIA_WORKER_TEMP_DIR: directory,
  });
  const runner = new FfmpegRunnerImpl(config);
  const service = new ThumbnailCaptureService(config, runner);
  const future = new Date(Date.now() + 60000).toISOString();
  const images = await Promise.all(
    [portraitSession, randomUUID()].map((sessionId) =>
      service.capture({
        jobId: randomUUID(),
        sessionId,
        playbackUrl: `${base}/live/${sessionId}/index.m3u8`,
        readToken: token,
        readExpiresAt: future,
        jobDeadlineAt: future,
      }),
    ),
  );
  try {
    expect(images[0].directory).not.toBe(images[1].directory);
    for (const image of images)
      expect(image).toMatchObject({
        width: 640,
        height: 360,
        contentType: 'image/jpeg',
      });
  } finally {
    await Promise.all(images.map((image) => service.release(image)));
    await runner.onApplicationShutdown();
  }
});

test('handles ffmpeg nonzero exit and filesystem failures without leaving a job directory', async () => {
  const config = loadWorkerConfig({
    MEDIA_WORKER_INTERNAL_TOKEN: 'a'.repeat(32),
    MEDIA_WORKER_MEDIAMTX_BASE_URL: base,
    MEDIA_WORKER_TEMP_DIR: directory,
  });
  const future = new Date(Date.now() + 60000).toISOString();
  const sessionId = randomUUID();
  const jobId = randomUUID();
  const job = {
    jobId,
    sessionId,
    playbackUrl: `${base}/live/${sessionId}/index.m3u8`,
    readToken: 'invalid-token',
    readExpiresAt: future,
    jobDeadlineAt: future,
  };
  const runner = new FfmpegRunnerImpl(config);
  const service = new ThumbnailCaptureService(config, runner);
  await expect(service.capture(job)).rejects.toThrow('PROCESS_FAILED');
  expect(
    (await readdir(directory)).some((name) =>
      name.startsWith(`thumbnail-${jobId}-`),
    ),
  ).toBe(false);
  config.job.tempDir = join(directory, 'index.m3u8');
  await expect(service.capture(job)).rejects.toThrow();
  await runner.onApplicationShutdown();
});

test('rejects corrupt JPEG output and cleans it', async () => {
  const { writeFile, chmod } = await import('node:fs/promises');
  const fake = join(directory, 'fake-ffmpeg');
  await writeFile(
    fake,
    `#!${process.execPath}\nrequire('node:fs').writeFileSync(process.argv.at(-1),'not a JPEG');\n`,
  );
  await chmod(fake, 0o700);
  const config = loadWorkerConfig({
    MEDIA_WORKER_INTERNAL_TOKEN: 'a'.repeat(32),
    MEDIA_WORKER_MEDIAMTX_BASE_URL: base,
    MEDIA_WORKER_TEMP_DIR: directory,
    MEDIA_WORKER_FFMPEG_PATH: fake,
  });
  const runner = new FfmpegRunnerImpl(config);
  const service = new ThumbnailCaptureService(config, runner);
  const sessionId = randomUUID();
  const jobId = randomUUID();
  const future = new Date(Date.now() + 60000).toISOString();
  try {
    await expect(
      service.capture({
        jobId,
        sessionId,
        playbackUrl: `${base}/live/${sessionId}/index.m3u8`,
        readToken: token,
        readExpiresAt: future,
        jobDeadlineAt: future,
      }),
    ).rejects.toThrow('INVALID_JPEG');
    expect(
      (await readdir(directory)).some((name) =>
        name.startsWith(`thumbnail-${jobId}-`),
      ),
    ).toBe(false);
  } finally {
    await runner.onApplicationShutdown();
  }
});

test('fails without retaining output if the HLS server disconnects during segment loading', async () => {
  const config = loadWorkerConfig({
    MEDIA_WORKER_INTERNAL_TOKEN: 'a'.repeat(32),
    MEDIA_WORKER_MEDIAMTX_BASE_URL: base,
    MEDIA_WORKER_TEMP_DIR: directory,
  });
  const runner = new FfmpegRunnerImpl(config);
  const service = new ThumbnailCaptureService(config, runner);
  const sessionId = randomUUID();
  const jobId = randomUUID();
  const future = new Date(Date.now() + 60000).toISOString();
  disconnectSegments = true;
  try {
    await expect(
      service.capture({
        jobId,
        sessionId,
        playbackUrl: `${base}/live/${sessionId}/index.m3u8`,
        readToken: token,
        readExpiresAt: future,
        jobDeadlineAt: future,
      }),
    ).rejects.toThrow('PROCESS_FAILED');
    expect(
      (await readdir(directory)).some((name) =>
        name.startsWith(`thumbnail-${jobId}-`),
      ),
    ).toBe(false);
  } finally {
    disconnectSegments = false;
    await runner.onApplicationShutdown();
  }
});
