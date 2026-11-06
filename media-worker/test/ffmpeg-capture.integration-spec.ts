import { createServer, type Server } from 'node:http';
import { spawnSync } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
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
  server = createServer(async (request, response) => {
    paths.push(request.url ?? '');
    if (request.headers.authorization !== `Bearer ${token}`) {
      response.writeHead(401).end();
      return;
    }
    try {
      response.end(
        await readFile(
          join(directory, basename((request.url ?? '').split('?')[0])),
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
