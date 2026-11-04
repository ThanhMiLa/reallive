import { Inject, Injectable } from '@nestjs/common';
import type {
  ThumbnailBatchAcceptedResponse,
  ThumbnailJobInput,
} from '../common/contracts/thumbnail-contract';
import {
  WORKER_CONFIG,
  type WorkerConfig,
} from '../common/config/worker-config';
import { badRequest, serviceUnavailable } from '../common/errors/api.exception';
import { QueueUnavailableError } from '../queue/queue-unavailable.error';
import {
  THUMBNAIL_QUEUE,
  type ThumbnailQueue,
} from '../queue/thumbnail-queue.port';
import { isAllowedMediamtxPlaybackUrl } from './mediamtx-playback-url';

@Injectable()
export class ThumbnailJobsService {
  constructor(
    @Inject(WORKER_CONFIG) private readonly config: WorkerConfig,
    @Inject(THUMBNAIL_QUEUE) private readonly queue: ThumbnailQueue,
  ) {}

  async acceptBatch(
    jobs: readonly ThumbnailJobInput[],
  ): Promise<ThumbnailBatchAcceptedResponse> {
    if (jobs.length > this.config.http.maxBatchSize) {
      throw badRequest(
        'BATCH_TOO_LARGE',
        `Batch exceeds the maximum of ${this.config.http.maxBatchSize} jobs`,
      );
    }

    for (const [index, job] of jobs.entries()) {
      if (
        !isAllowedMediamtxPlaybackUrl(
          job.playbackUrl,
          job.sessionId,
          this.config.http.mediamtxBaseUrl,
        )
      ) {
        throw badRequest('INVALID_PLAYBACK_URL', 'playbackUrl is not allowed', [
          `jobs.${index}.playbackUrl: must match the configured MediaMTX base URL and /live/{sessionId}/index.m3u8`,
        ]);
      }
    }

    try {
      return await this.queue.enqueueBatch(jobs);
    } catch (error) {
      if (error instanceof QueueUnavailableError) {
        throw serviceUnavailable(
          'QUEUE_UNAVAILABLE',
          'Thumbnail queue is not available',
        );
      }
      throw error;
    }
  }
}
