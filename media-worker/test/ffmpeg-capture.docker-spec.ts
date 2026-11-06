import { createServer, type Server } from 'node:http';
import { spawnSync } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, basename } from 'node:path';
import { randomUUID } from 'node:crypto';
import { GenericContainer, Wait } from 'testcontainers';

let directory: string;
let server: Server;
let origin: string;
let stalledSeen: (() => void) | undefined;
beforeAll(async () => {
  directory = await mkdtemp(join(tmpdir(), 'capture-docker-'));
  const generated = spawnSync('ffmpeg', [
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
    join(directory, 'index.m3u8'),
  ]);
  if (generated.status !== 0) throw new Error('fixture generation failed');
  server = createServer(async (req, res) => {
    if (req.headers.authorization !== 'Bearer docker-fixture-token') {
      res.writeHead(401).end();
      return;
    }
    if (stalledSeen) {
      stalledSeen();
      return;
    }
    try {
      res.end(await readFile(join(directory, basename(req.url ?? ''))));
    } catch {
      res.writeHead(404).end();
    }
  });
  await new Promise<void>((resolve) => server.listen(0, '0.0.0.0', resolve));
  const addr = server.address();
  if (!addr || typeof addr === 'string') throw new Error('address');
  origin = `http://host.docker.internal:${addr.port}`;
});
afterAll(async () => {
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
  await rm(directory, { recursive: true, force: true });
});
function container() {
  return new GenericContainer('reallive-media-worker-capture-test')
    .withWaitStrategy(Wait.forSuccessfulCommand('true'))
    .withExtraHosts([
      { host: 'host.docker.internal', ipAddress: 'host-gateway' },
    ])
    .withEnvironment({
      MEDIA_WORKER_INTERNAL_TOKEN: 'a'.repeat(32),
      MEDIA_WORKER_MEDIAMTX_BASE_URL: origin,
      MEDIA_WORKER_FFMPEG_TIMEOUT_MS: '60000',
      MEDIA_WORKER_JOB_DEADLINE_SECONDS: '120',
      MEDIA_WORKER_READ_TOKEN_TTL_SECONDS: '300',
    });
}
function code() {
  const sessionId = randomUUID();
  const future = new Date(Date.now() + 120000).toISOString();
  const job = {
    jobId: randomUUID(),
    sessionId,
    playbackUrl: `${origin}/live/${sessionId}/index.m3u8`,
    readToken: 'docker-fixture-token',
    readExpiresAt: future,
    jobDeadlineAt: future,
  };
  return `const {NestFactory}=require('@nestjs/core');const {FfmpegModule}=require('./dist/ffmpeg/ffmpeg.module');const {ThumbnailCaptureService}=require('./dist/ffmpeg/thumbnail-capture.service');(async()=>{const app=await NestFactory.createApplicationContext(FfmpegModule,{logger:false});try{console.log('CAPTURE_STARTED');const service=app.get(ThumbnailCaptureService);const image=await service.capture(${JSON.stringify(job)});console.log(JSON.stringify(image));await service.release(image);}finally{await app.close();}})().catch(()=>{process.exitCode=1;});`;
}
test('bundled ffmpeg captures authenticated portrait HLS as JPEG', async () => {
  const running = await container().withCommand(['sleep', '120']).start();
  try {
    const result = await running.exec(['node', '-e', code()]);
    expect(result.exitCode).toBe(0);
    expect(result.output).toContain('"width":640');
    expect(result.output).toContain('"height":360');
  } finally {
    await running.stop();
  }
});
test('SIGKILL of container stops active ffmpeg without requiring cleanup hooks', async () => {
  const seen = new Promise<void>((resolve) => {
    stalledSeen = resolve;
  });
  const running = await container()
    .withCommand(['node', '-e', code()])
    .withWaitStrategy(Wait.forLogMessage('CAPTURE_STARTED'))
    .start();
  try {
    await Promise.race([
      seen,
      new Promise((_, reject) =>
        setTimeout(() => reject(new Error('no ffmpeg request')), 10000).unref(),
      ),
    ]);
    const before = spawnSync('docker', ['top', running.getId()], {
      encoding: 'utf8',
    });
    expect(before.stdout).toContain('ffmpeg');
    expect(
      spawnSync('docker', ['kill', '--signal=KILL', running.getId()]).status,
    ).toBe(0);
    const state = spawnSync(
      'docker',
      [
        'inspect',
        '--format',
        '{{.State.Running}} {{.State.ExitCode}}',
        running.getId(),
      ],
      { encoding: 'utf8' },
    );
    expect(state.stdout.trim()).toBe('false 137');
  } finally {
    stalledSeen = undefined;
    await running.stop().catch(() => undefined);
  }
});
