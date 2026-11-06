import { Inject, Injectable, Logger } from '@nestjs/common';
import type { ThumbnailJobInput } from '../common/contracts/thumbnail-contract';
import { ThumbnailCaptureService } from '../ffmpeg/thumbnail-capture.service';
import {
  THUMBNAIL_STORAGE,
  type ThumbnailStorage,
  type StoredThumbnail,
} from '../storage/thumbnail-storage.port';
@Injectable()
export class ThumbnailImageService {
  private readonly logger = new Logger(ThumbnailImageService.name);
  constructor(
    private readonly capture: ThumbnailCaptureService,
    @Inject(THUMBNAIL_STORAGE) private readonly storage: ThumbnailStorage,
  ) {}
  async create(
    job: ThumbnailJobInput,
    signal?: AbortSignal,
  ): Promise<StoredThumbnail> {
    const image = await this.capture.capture(job, signal);
    try {
      if (signal?.aborted) throw new Error('UPLOAD_ABORTED');
      const remaining = Date.parse(job.jobDeadlineAt) - Date.now();
      if (remaining <= 0) throw new Error('UPLOAD_TIMEOUT');
      return await this.storage.upload({
        sessionId: job.sessionId,
        jobId: job.jobId,
        filePath: image.filePath,
        width: image.width,
        height: image.height,
        sizeBytes: image.sizeBytes,
        timeoutMs: remaining,
        signal,
      });
    } finally {
      try {
        await this.capture.release(image);
      } catch {
        this.logger.warn(`Temporary cleanup failed for job ${job.jobId}`);
      }
    }
  }
}
