import { createServer } from 'node:http';
import { spawnSync } from 'node:child_process';
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { GenericContainer, Wait } from 'testcontainers';
import {
  CreateBucketCommand,
  GetObjectCommand,
  S3Client,
} from '@aws-sdk/client-s3';
import { loadWorkerConfig } from '../src/common/config/worker-config';
import { FfmpegRunnerImpl } from '../src/ffmpeg/ffmpeg-runner';
import { ThumbnailCaptureService } from '../src/ffmpeg/thumbnail-capture.service';
import { ThumbnailImageService } from '../src/processor/thumbnail-image.service';
import { R2ThumbnailStorage } from '../src/storage/r2-thumbnail-storage';

test('captures HLS, uploads to S3 and retrieves the JPEG', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'thumbnail-s3-'));
  const gen = spawnSync('ffmpeg', [
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
    join(dir, 'index.m3u8'),
  ]);
  if (gen.status !== 0) throw new Error('FFmpeg fixture failed');
  const server = createServer(async (req, res) => {
    if (req.headers.authorization !== 'Bearer test-jwt') {
      res.writeHead(401).end();
      return;
    }
    try {
      res.end(
        await readFile(join(dir, basename((req.url ?? '').split('?')[0]))),
      );
    } catch {
      res.writeHead(404).end();
    }
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('address');
  const origin = `http://127.0.0.1:${address.port}`;
  const minio = await new GenericContainer('localstack/localstack:3.8.1')
    .withEnvironment({
      SERVICES: 's3',
      AWS_ACCESS_KEY_ID: 'test-access',
      AWS_SECRET_ACCESS_KEY: 'test-secret-password',
    })
    .withExposedPorts(4566)
    .withWaitStrategy(Wait.forHttp('/_localstack/health', 4566))
    .start()
    .catch(async (error: unknown) => {
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
      await rm(dir, { recursive: true, force: true });
      throw error;
    });
  const endpoint = `http://${minio.getHost()}:${minio.getMappedPort(4566)}`;
  const credentials = {
    accessKeyId: 'test-access',
    secretAccessKey: 'test-secret-password',
  };
  const client = new S3Client({
    endpoint,
    region: 'us-east-1',
    credentials,
    forcePathStyle: true,
    requestChecksumCalculation: 'WHEN_REQUIRED',
    responseChecksumValidation: 'WHEN_REQUIRED',
  });
  try {
    await client.send(new CreateBucketCommand({ Bucket: 'thumbnail-test' }));
    const config = loadWorkerConfig({
      MEDIA_WORKER_MEDIAMTX_BASE_URL: origin,
      MEDIA_WORKER_TEMP_DIR: dir,
      MEDIA_WORKER_R2_ENDPOINT: endpoint,
      MEDIA_WORKER_R2_REGION: 'us-east-1',
      MEDIA_WORKER_R2_BUCKET: 'thumbnail-test',
      MEDIA_WORKER_R2_ACCESS_KEY_ID: credentials.accessKeyId,
      MEDIA_WORKER_R2_SECRET_ACCESS_KEY: credentials.secretAccessKey,
    });
    const runner = new FfmpegRunnerImpl(config);
    const capture = new ThumbnailCaptureService(config, runner);
    const storage = new R2ThumbnailStorage(config);
    const service = new ThumbnailImageService(capture, storage);
    try {
      const sessionId = randomUUID();
      const jobId = randomUUID();
      const future = new Date(Date.now() + 120000).toISOString();
      const result = await service.create({
        jobId,
        sessionId,
        playbackUrl: `${origin}/live/${sessionId}/index.m3u8`,
        readToken: 'test-jwt',
        readExpiresAt: future,
        jobDeadlineAt: future,
      });
      const object = await client.send(
        new GetObjectCommand({
          Bucket: 'thumbnail-test',
          Key: result.objectKey,
        }),
      );
      const bytes = await object.Body!.transformToByteArray();
      expect(result.objectKey).toBe(
        `livestream-thumbnails/${sessionId}/${jobId}.jpg`,
      );
      expect(object.ContentType).toBe('image/jpeg');
      expect(bytes.length).toBe(result.sizeBytes);
      expect(Buffer.from(bytes).subarray(0, 2)).toEqual(
        Buffer.from([255, 216]),
      );
      expect((await readdir(dir)).some((n) => n.startsWith('thumbnail-'))).toBe(
        false,
      );
    } finally {
      await runner.onApplicationShutdown();
      await storage.onApplicationShutdown();
    }
  } finally {
    client.destroy();
    await minio.stop();
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(dir, { recursive: true, force: true });
  }
}, 120000);
