import { BullmqThumbnailQueue } from '../src/queue/bullmq-thumbnail-queue';
import { UnavailableThumbnailQueue } from '../src/queue/unavailable-thumbnail-queue';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import { AppModule } from '../src/app.module';
import { configureApplication } from '../src/app.setup';
import {
  WORKER_CONFIG,
  WORKER_ENV_KEYS,
  type WorkerConfig,
} from '../src/common/config/worker-config';

const MEDIAMTX_BASE_URL = 'http://media-server:8888';
const BATCH_PATH = '/internal/api/thumbnail-jobs/batch';
const FIXTURES = join(
  __dirname,
  '..',
  '..',
  'docs',
  'contracts',
  'thumbnail',
  'fixtures',
);

interface BatchFixture {
  jobs: Array<Record<string, unknown>>;
}

function fixture(name: string): BatchFixture {
  return JSON.parse(readFileSync(join(FIXTURES, name), 'utf8')) as BatchFixture;
}

function setEnvironment(overrides: NodeJS.ProcessEnv): void {
  for (const key of WORKER_ENV_KEYS) {
    delete process.env[key];
  }
  Object.assign(process.env, {
    NODE_ENV: 'test',
    MEDIA_WORKER_MEDIAMTX_BASE_URL: MEDIAMTX_BASE_URL,
    MEDIA_WORKER_MAX_BATCH_SIZE: '2',
    MEDIA_WORKER_MAX_BODY_BYTES: '8192',
  } satisfies NodeJS.ProcessEnv);
  Object.assign(process.env, overrides);
}

async function createApplication(
  overrides: NodeJS.ProcessEnv = {},
): Promise<NestExpressApplication> {
  setEnvironment(overrides);
  const moduleRef = await Test.createTestingModule({
    imports: [AppModule],
  })
    .overrideProvider(BullmqThumbnailQueue)
    .useClass(UnavailableThumbnailQueue)
    .compile();
  const app = moduleRef.createNestApplication<NestExpressApplication>({
    bodyParser: false,
    logger: false,
  });
  configureApplication(app, app.get<WorkerConfig>(WORKER_CONFIG));
  await app.init();
  return app;
}

function internalPost(
  app: NestExpressApplication,
  token: string | null = null,
) {
  const agent = request(app.getHttpServer())
    .post(BATCH_PATH)
    .set('Content-Type', 'application/json');
  return token === null ? agent : agent.set('X-Internal-Token', token);
}

describe('thumbnail worker HTTP contract (Step 1)', () => {
  let app: NestExpressApplication;
  const validBatch = fixture('thumbnail-jobs-batch.valid.json');

  beforeAll(async () => {
    app = await createApplication();
  });

  afterAll(async () => {
    await app.close();
  });

  it('accepts the request without an internal token and reaches the queue', async () => {
    const response = await internalPost(app).send(validBatch).expect(503);
    expect(response.body.code).toBe('QUEUE_UNAVAILABLE');
  });

  it('ignores legacy credentials and still validates the batch', async () => {
    const response = await internalPost(app, 'ignored')
      .set('Authorization', 'Bearer malformed')
      .send(fixture('thumbnail-jobs-batch.invalid-job-id.json')).expect(400);
    expect(response.body.code).toBe('VALIDATION_FAILED');
  });

  it('returns 400 for an invalid job UUID', async () => {
    const response = await internalPost(app)
      .send(fixture('thumbnail-jobs-batch.invalid-job-id.json'))
      .expect(400);

    expect(response.body).toMatchObject({
      statusCode: 400,
      code: 'VALIDATION_FAILED',
    });
  });

  it('returns 400 for invalid timestamps', async () => {
    const response = await internalPost(app)
      .send(fixture('thumbnail-jobs-batch.invalid-timestamp.json'))
      .expect(400);

    expect(response.body).toMatchObject({
      statusCode: 400,
      code: 'VALIDATION_FAILED',
    });
  });

  describe.each(['readExpiresAt', 'jobDeadlineAt'])(
    '%s timestamp validation',
    (field) => {
      it.each([
        '2030-01-15',
        '2030-01-15T10:05:00',
        '2030-01-15T10:05:00+0700',
        '2030-01-15T24:00:00Z',
        '2030-02-30T10:05:00Z',
        '2030-01-15T10:05:00+25:00',
      ])('returns 400 for %s', async (timestamp) => {
        const batch = structuredClone(validBatch);
        batch.jobs[0][field] = timestamp;

        const response = await internalPost(app).send(batch).expect(400);

        expect(response.body).toMatchObject({
          statusCode: 400,
          code: 'VALIDATION_FAILED',
          details: expect.arrayContaining([
            expect.stringContaining(`jobs.0.${field}:`),
          ]),
        });
      });
    },
  );

  it.each(['2030-01-15T10:05:00Z', '2030-01-15T17:05:00.125+07:00'])(
    'accepts full timestamps with a timezone: %s',
    async (timestamp) => {
      const batch = structuredClone(validBatch);
      batch.jobs[0].readExpiresAt = timestamp;
      batch.jobs[0].jobDeadlineAt = timestamp;

      const response = await internalPost(app).send(batch).expect(503);

      expect(response.body.code).toBe('QUEUE_UNAVAILABLE');
    },
  );

  it('accepts the shared fixture adapted to the local MediaMTX base URL', async () => {
    const baseUrl = 'http://localhost:8898';
    const localApp = await createApplication({
      MEDIA_WORKER_MEDIAMTX_BASE_URL: baseUrl,
    });

    try {
      const batch = structuredClone(validBatch);
      for (const job of batch.jobs) {
        job.playbackUrl = new URL(
          `/live/${String(job.sessionId)}/index.m3u8`,
          baseUrl,
        ).href;
      }

      const response = await internalPost(localApp).send(batch).expect(503);

      expect(response.body.code).toBe('QUEUE_UNAVAILABLE');
    } finally {
      await localApp.close();
    }
  });

  it('returns 400 for missing fields and wrong types', async () => {
    const response = await internalPost(app)
      .send(fixture('thumbnail-jobs-batch.invalid-schema.json'))
      .expect(400);

    expect(response.body).toMatchObject({
      statusCode: 400,
      code: 'VALIDATION_FAILED',
    });
  });

  it('returns 400 for unknown fields', async () => {
    const response = await internalPost(app)
      .send({
        ...validBatch,
        unexpected: true,
      })
      .expect(400);

    expect(response.body).toMatchObject({
      statusCode: 400,
      code: 'VALIDATION_FAILED',
    });
  });

  it('returns 400 for a playback URL outside the configured MediaMTX origin', async () => {
    const response = await internalPost(app)
      .send(fixture('thumbnail-jobs-batch.invalid-playback-url.json'))
      .expect(400);

    expect(response.body).toMatchObject({
      statusCode: 400,
      code: 'INVALID_PLAYBACK_URL',
    });
  });

  it('returns 400 when the batch exceeds the configured limit', async () => {
    const thirdJob = {
      ...validBatch.jobs[0],
      jobId: '55555555-5555-4555-8555-555555555555',
    };
    const response = await internalPost(app)
      .send({ jobs: [...validBatch.jobs, thirdJob] })
      .expect(400);

    expect(response.body).toMatchObject({
      statusCode: 400,
      code: 'BATCH_TOO_LARGE',
    });
  });

  it('returns 413 before validation when the body is too large', async () => {
    const response = await internalPost(app)
      .send({
        jobs: validBatch.jobs,
        padding: 'x'.repeat(9000),
      })
      .expect(413);

    expect(response.body).toMatchObject({
      statusCode: 413,
      code: 'REQUEST_BODY_TOO_LARGE',
    });
  });

  it('returns 400 for malformed JSON', async () => {
    const response = await internalPost(app).send('{"jobs": [').expect(400);

    expect(response.body).toMatchObject({
      statusCode: 400,
      code: 'INVALID_JSON',
    });
  });

  it('validates a valid batch but does not fake queue acceptance', async () => {
    const response = await internalPost(app).send(validBatch).expect(503);

    expect(response.body).toMatchObject({
      statusCode: 503,
      code: 'QUEUE_UNAVAILABLE',
    });
    expect(response.body).not.toHaveProperty('acceptedJobIds');
    expect(response.body).not.toHaveProperty('rejectedJobs');
    expect(JSON.stringify(response.body)).not.toContain(
      'fixture-read-token-not-a-jwt',
    );
  });

  it('reports liveness without dependencies', async () => {
    const response = await request(app.getHttpServer())
      .get('/health/live')
      .expect(200);

    expect(response.body).toMatchObject({
      status: 'ok',
      service: 'media-worker',
    });
    expect(typeof response.body.uptimeSeconds).toBe('number');
  });

  it('reports readiness as unavailable when the queue is unavailable', async () => {
    const response = await request(app.getHttpServer())
      .get('/health/ready')
      .expect(503);

    expect(response.body).toEqual({
      status: 'not_ready',
      checks: {
        configuration: 'ok',
        queue: { status: 'down', reason: 'redis_unavailable' },
      },
    });
  });
});
