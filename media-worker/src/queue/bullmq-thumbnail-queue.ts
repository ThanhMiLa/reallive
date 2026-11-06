import {
  Inject,
  Injectable,
  Logger,
  OnApplicationShutdown,
  OnModuleInit,
  Optional,
} from '@nestjs/common';
import { Queue, Worker } from 'bullmq';
import Redis from 'ioredis';
import {
  WORKER_CONFIG,
  type WorkerConfig,
} from '../common/config/worker-config';
import type {
  ThumbnailBatchAcceptedResponse,
  ThumbnailJobInput,
} from '../common/contracts/thumbnail-contract';
import {
  THUMBNAIL_PROCESSOR,
  type ThumbnailProcessor,
} from '../processor/thumbnail-processor.port';
import { QueueUnavailableError } from './queue-unavailable.error';
import type { ThumbnailQueue } from './thumbnail-queue.port';

const QUEUE_NAME = 'livestream-thumbnails';
const STATES = [
  'wait',
  'active',
  'delayed',
  'prioritized',
  'paused',
  'waiting-children',
] as const;

@Injectable()
export class BullmqThumbnailQueue
  implements ThumbnailQueue, OnModuleInit, OnApplicationShutdown
{
  private readonly logger = new Logger(BullmqThumbnailQueue.name);
  private readonly producer: Redis;
  private readonly queue: Queue<ThumbnailJobInput>;
  private readonly active = new Set<AbortController>();
  private worker?: Worker<ThumbnailJobInput>;
  private stopping = false;
  private serial: Promise<void> = Promise.resolve();

  constructor(
    @Inject(WORKER_CONFIG) private readonly config: WorkerConfig,
    @Optional()
    @Inject(THUMBNAIL_PROCESSOR)
    private readonly processor?: ThumbnailProcessor,
  ) {
    if (config.queue.processorEnabled && !processor)
      throw new Error(
        'Thumbnail processor enabled but no pipeline is registered',
      );
    const { redis, connectTimeoutMs, commandTimeoutMs, prefix } = config.queue;
    this.producer = new Redis({
      ...redis,
      connectTimeout: connectTimeoutMs,
      commandTimeout: commandTimeoutMs,
      maxRetriesPerRequest: 1,
      enableOfflineQueue: false,
      lazyConnect: true,
    });
    this.producer.on('error', () =>
      this.logger.warn('Redis producer connection unavailable'),
    );
    this.queue = new Queue(QUEUE_NAME, { connection: this.producer, prefix });
    this.queue.on('error', () =>
      this.logger.warn('Thumbnail queue unavailable'),
    );
  }

  onModuleInit(): void {
    if (!this.config.queue.processorEnabled) {
      this.logger.log(
        'Thumbnail consumer disabled',
      );
      return;
    }
    if (!this.processor)
      throw new Error(
        'Thumbnail processor enabled but no pipeline is registered',
      );
    const { redis, prefix, concurrency, retentionMs } = this.config.queue;
    this.worker = new Worker<ThumbnailJobInput>(
      QUEUE_NAME,
      async (job) => {
        const startedAt = Date.now();
        this.logger.log(
          `Thumbnail job ${job.id} started; queueWaitMs=${Math.max(0, startedAt - job.timestamp)}`,
        );
        if (this.stopping) throw new Error('THUMBNAIL_PIPELINE_FAILED');
        if (Date.parse(job.data.readExpiresAt) <= Date.now())
          throw new Error('READ_TOKEN_EXPIRED');
        if (Date.parse(job.data.jobDeadlineAt) <= Date.now())
          throw new Error('JOB_DEADLINE_EXPIRED');
        const abort = new AbortController();
        this.active.add(abort);
        try {
          await this.processor!.process(job.data, abort.signal);
        } catch {
          throw new Error('THUMBNAIL_PIPELINE_FAILED');
        } finally {
          this.logger.log(
            `Thumbnail job ${job.id} finished; processingMs=${Date.now() - startedAt}`,
          );
          this.active.delete(abort);
        }
      },
      {
        connection: { ...redis, maxRetriesPerRequest: null },
        prefix,
        concurrency,
        maxStalledCount: 0,
        removeOnComplete: { age: retentionMs / 1000 },
        removeOnFail: { age: retentionMs / 1000 },
      },
    );
    this.worker.on('error', () =>
      this.logger.warn('Thumbnail consumer connection unavailable'),
    );
    this.worker.on('failed', (job) => {
      const reason = [
        'READ_TOKEN_EXPIRED',
        'JOB_DEADLINE_EXPIRED',
        'THUMBNAIL_PIPELINE_FAILED',
      ].includes(job?.failedReason ?? '')
        ? job?.failedReason
        : 'WORKER_FAILURE';
      this.logger.warn(
        `Thumbnail job failed: ${job?.id ?? 'unknown'} (${reason})`,
      );
    });
  }

  isStopping(): boolean {
    return this.stopping;
  }

  async isReady(): Promise<boolean> {
    if (this.stopping) return false;
    try {
      return (await this.producer.ping()) === 'PONG';
    } catch {
      return false;
    }
  }

  async enqueueBatch(
    jobs: readonly ThumbnailJobInput[],
  ): Promise<ThumbnailBatchAcceptedResponse> {
    if (this.stopping) throw new QueueUnavailableError();
    const deadline = Date.now() + this.config.queue.batchTimeoutMs;
    const work = this.serial.then(async () => {
      if (this.stopping || Date.now() >= deadline)
        throw new QueueUnavailableError();
      const response: ThumbnailBatchAcceptedResponse = {
        acceptedJobIds: [],
        rejectedJobs: [],
      };
      const seen = new Set<string>();
      for (const job of jobs) {
        if (this.stopping || Date.now() >= deadline)
          throw new QueueUnavailableError();
        if (seen.has(job.jobId)) continue;
        seen.add(job.jobId);
        if (await this.queue.getJob(job.jobId)) {
          response.acceptedJobIds.push(job.jobId);
          continue;
        }
        const counts = await this.queue.getJobCounts(...STATES);
        const count = Object.values(counts).reduce((total, n) => total + n, 0);
        if (count >= this.config.queue.maxPendingJobs) {
          response.rejectedJobs.push({
            jobId: job.jobId,
            reason: 'QUEUE_FULL',
          });
          continue;
        }
        if (this.stopping || Date.now() >= deadline)
          throw new QueueUnavailableError();
        await this.queue.add('generate-thumbnail', job, {
          jobId: job.jobId,
          attempts: 1,
          removeOnComplete: { age: this.config.queue.retentionMs / 1000 },
          removeOnFail: { age: this.config.queue.retentionMs / 1000 },
        });
        response.acceptedJobIds.push(job.jobId);
      }
      if (Date.now() >= deadline) throw new QueueUnavailableError();
      return response;
    });
    this.serial = work.then(
      () => undefined,
      () => undefined,
    );
    let timer: NodeJS.Timeout | undefined;
    try {
      return await Promise.race([
        work,
        new Promise<never>((_, reject) => {
          timer = setTimeout(
            () => reject(new QueueUnavailableError()),
            Math.max(0, deadline - Date.now()),
          );
        }),
      ]);
    } catch {
      throw new QueueUnavailableError();
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  async onApplicationShutdown(): Promise<void> {
    this.stopping = true;
    const closingWorker = this.worker?.close();
    for (const abort of this.active) abort.abort();
    const shutdown = async () => {
      await this.serial;
      await closingWorker;
      await this.queue.close();
      this.producer.disconnect();
    };
    let timer: NodeJS.Timeout | undefined;
    await Promise.race([
      shutdown(),
      new Promise<void>((resolve) => {
        timer = setTimeout(() => {
          this.worker?.disconnect().catch(() => undefined);
          this.producer.disconnect();
          resolve();
        }, this.config.queue.shutdownTimeoutMs);
      }),
    ]);
    if (timer) clearTimeout(timer);
  }
}
