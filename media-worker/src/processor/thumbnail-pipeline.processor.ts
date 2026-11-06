import { Inject, Injectable, Logger } from '@nestjs/common';
import {
  WORKER_CONFIG,
  type WorkerConfig,
} from '../common/config/worker-config';
import type {
  ThumbnailJobInput,
  ThumbnailResult,
} from '../common/contracts/thumbnail-contract';
import {
  THUMBNAIL_CALLBACK,
  type ThumbnailCallbackClient,
} from '../callback/thumbnail-callback.port';
import type { ThumbnailProcessor } from './thumbnail-processor.port';
import { ThumbnailImageService } from './thumbnail-image.service';

@Injectable()
export class ThumbnailPipelineProcessor implements ThumbnailProcessor {
  private readonly logger = new Logger(ThumbnailPipelineProcessor.name);

  constructor(
    @Inject(WORKER_CONFIG) private readonly config: WorkerConfig,
    private readonly image: ThumbnailImageService,
    @Inject(THUMBNAIL_CALLBACK)
    private readonly callback: ThumbnailCallbackClient,
  ) {}

  async process(job: ThumbnailJobInput, signal?: AbortSignal): Promise<void> {
    if (signal?.aborted) throw new Error('PIPELINE_ABORTED');
    const stored = await this.image.create(job, signal);
    if (signal?.aborted) throw new Error('PIPELINE_ABORTED');
    if (
      stored.objectKey !==
      `livestream-thumbnails/${job.sessionId}/${job.jobId}.jpg`
    )
      throw new Error('INVALID_STORED_THUMBNAIL');

    const remaining = Date.parse(job.jobDeadlineAt) - Date.now();
    if (!Number.isFinite(remaining) || remaining <= 0)
      throw new Error('JOB_DEADLINE_EXPIRED');
    const result: ThumbnailResult = {
      jobId: job.jobId,
      sessionId: job.sessionId,
      objectKey: stored.objectKey,
      contentType: stored.contentType,
      sizeBytes: stored.sizeBytes,
      width: stored.width,
      height: stored.height,
    };
    try {
      const response = await this.callback.send(result, {
        signal,
        timeoutMs: Math.min(this.config.timeouts.callbackMs, remaining),
      });
      this.logger.log(
        `Thumbnail job ${job.jobId} callback completed with ${response.outcome}`,
      );
    } catch (error) {
      const code =
        error instanceof Error && error.message.startsWith('CALLBACK_')
          ? error.message
          : 'CALLBACK_FAILED';
      this.logger.warn(`Thumbnail job ${job.jobId} callback failed (${code})`);
      throw error;
    }
  }
}
