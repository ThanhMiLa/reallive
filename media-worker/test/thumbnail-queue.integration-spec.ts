import { Test } from '@nestjs/testing';
import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';
import { AppModule } from '../src/app.module';
import { configureApplication } from '../src/app.setup';
import { WORKER_CONFIG } from '../src/common/config/worker-config';
import { randomUUID } from 'node:crypto';
import { GenericContainer, type StartedTestContainer } from 'testcontainers';
import { Queue } from 'bullmq';
import { BullmqThumbnailQueue } from '../src/queue/bullmq-thumbnail-queue';
import {
  loadWorkerConfig,
  type WorkerConfig,
} from '../src/common/config/worker-config';
import type { ThumbnailJobInput } from '../src/common/contracts/thumbnail-contract';
import type { ThumbnailProcessor } from '../src/processor/thumbnail-processor.port';

let redis: StartedTestContainer;
let config: WorkerConfig;
let queue: BullmqThumbnailQueue;
let inspect: Queue<ThumbnailJobInput>;

function job(
  id = randomUUID(),
  expires = Date.now() + 120_000,
): ThumbnailJobInput {
  const sessionId = randomUUID();
  return {
    jobId: id,
    sessionId,
    playbackUrl: `http://media-server:8888/live/${sessionId}/index.m3u8`,
    readToken: 'test-token',
    readExpiresAt: new Date(expires).toISOString(),
    jobDeadlineAt: new Date(expires).toISOString(),
  };
}

async function until(
  check: () => Promise<boolean>,
  timeout = 10000,
): Promise<void> {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await check()) return;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error('condition timed out');
}

beforeAll(async () => {
  redis = await new GenericContainer('redis:7.4-alpine')
    .withExposedPorts(6379)
    .start();
});
afterAll(async () => {
  await redis?.stop();
});

beforeEach(async () => {
  config = loadWorkerConfig({
    REDIS_HOST: redis.getHost(),
    REDIS_PORT: String(redis.getMappedPort(6379)),
    MEDIA_WORKER_QUEUE_PREFIX: `thumbnail-test-${randomUUID()}`,
    MEDIA_WORKER_MAX_PENDING_JOBS: '2',
  });
  queue = new BullmqThumbnailQueue(config);
  queue.onModuleInit();
  await until(() => queue.isReady());
  inspect = new Queue('livestream-thumbnails', {
    prefix: config.queue.prefix,
    connection: { host: redis.getHost(), port: redis.getMappedPort(6379) },
  });
});
afterEach(async () => {
  await queue.onApplicationShutdown();
  await inspect.obliterate({ force: true });
  await inspect.close();
});

test('retains jobs and deduplicates while full, including concurrent batches', async () => {
  const a = job();
  const b = job();
  const c = job();
  const [first, second] = await Promise.all([
    queue.enqueueBatch([a, a, b]),
    queue.enqueueBatch([c]),
  ]);
  expect(first.acceptedJobIds).toEqual([a.jobId, b.jobId]);
  expect(second.rejectedJobs).toEqual([
    { jobId: c.jobId, reason: 'QUEUE_FULL' },
  ]);
  expect((await queue.enqueueBatch([a])).acceptedJobIds).toEqual([a.jobId]);
  expect((await inspect.getJob(a.jobId))?.data).toEqual(a);
  expect((await inspect.getJobCounts('wait')).wait).toBe(2);
});

test('keeps jobs after service restart', async () => {
  const a = job();
  await queue.enqueueBatch([a]);
  await queue.onApplicationShutdown();
  queue = new BullmqThumbnailQueue(config);
  queue.onModuleInit();
  await until(() => queue.isReady());
  expect((await queue.enqueueBatch([a])).acceptedJobIds).toEqual([a.jobId]);
  expect(await inspect.getJob(a.jobId)).toBeDefined();
});

test('fails startup if consumer is enabled without a pipeline', () => {
  config.queue.processorEnabled = true;
  expect(() => new BullmqThumbnailQueue(config)).toThrow('no pipeline');
});

test('processor skips expired jobs and does not retry failures', async () => {
  const expired = job(randomUUID(), Date.now() - 1000);
  const valid = job();
  const calls: string[] = [];
  const processor: ThumbnailProcessor = {
    process: async (input) => {
      calls.push(input.jobId);
      throw new Error('pipeline failure');
    },
  };
  const consumerConfig = {
    ...config,
    queue: { ...config.queue, processorEnabled: true },
  };
  const consumer = new BullmqThumbnailQueue(consumerConfig, processor);
  consumer.onModuleInit();
  try {
    await queue.enqueueBatch([expired, valid]);
    await until(
      async () => (await inspect.getJobCounts('failed')).failed === 2,
    );
    expect(calls).toEqual([valid.jobId]);
    expect((await inspect.getJob(expired.jobId))?.failedReason).toBe(
      'READ_TOKEN_EXPIRED',
    );
    expect((await queue.enqueueBatch([valid])).acceptedJobIds).toEqual([
      valid.jobId,
    ]);
    expect(calls).toHaveLength(1);
  } finally {
    await consumer.onApplicationShutdown();
  }
});

test('limits processor concurrency and retains completed jobs', async () => {
  config.queue.maxPendingJobs = 4;
  let running = 0;
  let maximum = 0;
  const releases: Array<() => void> = [];
  const processor: ThumbnailProcessor = {
    process: async () => {
      running++;
      maximum = Math.max(maximum, running);
      await new Promise<void>((resolve) => releases.push(resolve));
      running--;
    },
  };
  const consumer = new BullmqThumbnailQueue(
    { ...config, queue: { ...config.queue, processorEnabled: true } },
    processor,
  );
  consumer.onModuleInit();
  try {
    const jobs = [job(), job(), job()];
    await queue.enqueueBatch(jobs);
    await until(async () => releases.length === 2);
    expect(maximum).toBe(2);
    releases.shift()!();
    await until(async () => releases.length === 2);
    while (releases.length) releases.shift()!();
    await until(
      async () => (await inspect.getJobCounts('completed')).completed === 3,
    );
    expect((await queue.enqueueBatch([jobs[0]])).acceptedJobIds).toEqual([
      jobs[0].jobId,
    ]);
    expect(
      (await inspect.getJob(jobs[0].jobId))?.opts.removeOnComplete,
    ).toEqual({ age: 86400 });
  } finally {
    while (releases.length) releases.shift()!();
    await consumer.onApplicationShutdown();
  }
});

test('rejects new jobs after shutdown', async () => {
  await queue.onApplicationShutdown();
  await expect(queue.enqueueBatch([job()])).rejects.toThrow(
    'Thumbnail queue is not available',
  );
  expect(await queue.isReady()).toBe(false);
});

test('preserves a partial batch and accepts retry with the same IDs', async () => {
  const a = job();
  const b = job();
  const internal = (queue as unknown as { queue: Queue<ThumbnailJobInput> })
    .queue;
  const originalAdd = internal.add.bind(internal);
  let count = 0;
  const spy = jest
    .spyOn(internal, 'add')
    .mockImplementation(async (...args) => {
      count++;
      if (count === 2) throw new Error('connection lost');
      return originalAdd(...args);
    });
  try {
    await expect(queue.enqueueBatch([a, b])).rejects.toThrow(
      'Thumbnail queue is not available',
    );
    expect(await inspect.getJob(a.jobId)).toBeDefined();
    spy.mockRestore();
    expect((await queue.enqueueBatch([a, b])).acceptedJobIds).toEqual([
      a.jobId,
      b.jobId,
    ]);
    expect((await inspect.getJobCounts('wait')).wait).toBe(2);
  } finally {
    spy.mockRestore();
  }
});

test('returns unavailable after Redis disconnects', async () => {
  const other = await new GenericContainer('redis:7.4-alpine')
    .withExposedPorts(6379)
    .start();
  const localConfig = loadWorkerConfig({
    REDIS_HOST: other.getHost(),
    REDIS_PORT: String(other.getMappedPort(6379)),
    MEDIA_WORKER_QUEUE_PREFIX: `offline-${randomUUID()}`,
    MEDIA_WORKER_BATCH_TIMEOUT_MS: '1500',
  });
  const localQueue = new BullmqThumbnailQueue(localConfig);
  localQueue.onModuleInit();
  try {
    await until(() => localQueue.isReady());
    await other.stop();
    await until(async () => !(await localQueue.isReady()));
    await expect(localQueue.enqueueBatch([job()])).rejects.toThrow(
      'Thumbnail queue is not available',
    );
  } finally {
    await localQueue.onApplicationShutdown();
    await other.stop().catch(() => undefined);
  }
});

test('HTTP API confirms Redis enqueue and duplicate ID with 202', async () => {
  const moduleRef = await Test.createTestingModule({ imports: [AppModule] })
    .overrideProvider(WORKER_CONFIG)
    .useValue(config)
    .compile();
  const app = moduleRef.createNestApplication<NestExpressApplication>({
    bodyParser: false,
    logger: false,
  });
  configureApplication(app, config);
  await app.init();
  try {
    const a = job();
    await until(
      async () =>
        (await request(app.getHttpServer()).get('/health/ready')).status ===
        200,
    );
    const response = await request(app.getHttpServer())
      .post('/internal/api/thumbnail-jobs/batch')

      .send({ jobs: [a] })
      .expect(202);
    expect(response.body).toEqual({
      acceptedJobIds: [a.jobId],
      rejectedJobs: [],
    });
    expect((await inspect.getJob(a.jobId))?.data).toEqual(a);
    const repeated = await request(app.getHttpServer())
      .post('/internal/api/thumbnail-jobs/batch')

      .send({ jobs: [a] })
      .expect(202);
    expect(repeated.body.acceptedJobIds).toEqual([a.jobId]);
  } finally {
    await app.close();
  }
});

test('a job that loses its lock is marked failed without running pipeline again', async () => {
  let started = 0;
  let release!: () => void;
  const processor: ThumbnailProcessor = {
    process: async () => {
      started++;
      await new Promise<void>((resolve) => {
        release = resolve;
      });
    },
  };
  const consumer = new BullmqThumbnailQueue(
    { ...config, queue: { ...config.queue, processorEnabled: true } },
    processor,
  );
  consumer.onModuleInit();
  const worker = (consumer as unknown as { worker: import('bullmq').Worker })
    .worker;
  worker.opts.lockDuration = 250;
  worker.opts.stalledInterval = 100;
  worker.opts.skipLockRenewal = true;
  try {
    const a = job();
    await queue.enqueueBatch([a]);
    await until(async () => started === 1);
    await until(
      async () =>
        (await inspect.getJob(a.jobId))
          ?.getState()
          .then((state) => state === 'failed') ?? false,
      6000,
    );
    expect(started).toBe(1);
    expect((await queue.enqueueBatch([a])).acceptedJobIds).toEqual([a.jobId]);
  } finally {
    release?.();
    await consumer.onApplicationShutdown();
  }
});

test('does not run the pipeline when only the job deadline has expired', async () => {
  const expired = job();
  expired.jobDeadlineAt = new Date(Date.now() - 1000).toISOString();
  let called = false;
  const consumer = new BullmqThumbnailQueue(
    { ...config, queue: { ...config.queue, processorEnabled: true } },
    {
      process: async () => {
        called = true;
      },
    },
  );
  consumer.onModuleInit();
  try {
    await queue.enqueueBatch([expired]);
    await until(
      async () =>
        (await inspect.getJob(expired.jobId))
          ?.getState()
          .then((state) => state === 'failed') ?? false,
    );
    expect(called).toBe(false);
    expect((await inspect.getJob(expired.jobId))?.failedReason).toBe(
      'JOB_DEADLINE_EXPIRED',
    );
  } finally {
    await consumer.onApplicationShutdown();
  }
});
