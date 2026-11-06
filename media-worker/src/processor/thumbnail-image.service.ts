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
    const captureStarted = Date.now();
    const image = await this.capture.capture(job, signal);
    this.logger.log(`Thumbnail job ${job.jobId} captured; captureMs=${Date.now() - captureStarted}`);
    try {
      if (signal?.aborted) throw new Error('UPLOAD_ABORTED');
      const remaining = Date.parse(job.jobDeadlineAt) - Date.now();
      if (remaining <= 0) throw new Error('UPLOAD_TIMEOUT');
      const uploadStarted = Date.now();
      const stored = await this.storage.upload({
        sessionId: job.sessionId,
        jobId: job.jobId,
        filePath: image.filePath,
        width: image.width,
        height: image.height,
        sizeBytes: image.sizeBytes,
        timeoutMs: remaining,
        signal,
      });
      this.logger.log(`Thumbnail job ${job.jobId} uploaded; uploadMs=${Date.now() - uploadStarted}`);
      return stored;
    } finally {
      try {
        await this.capture.release(image);
      } catch {
        this.logger.warn(`Temporary cleanup failed for job ${job.jobId}`);
      }
    }
  }
}
