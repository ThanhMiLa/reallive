import { Inject, Injectable, Logger } from '@nestjs/common';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import { join, resolve, dirname, basename } from 'node:path';
import {
  WORKER_CONFIG,
  type WorkerConfig,
} from '../common/config/worker-config';
import type { ThumbnailJobInput } from '../common/contracts/thumbnail-contract';
import { isAllowedMediamtxPlaybackUrl } from '../thumbnail-jobs/mediamtx-playback-url';
import {
  FFMPEG_RUNNER,
  type ExtractedThumbnail,
  type FfmpegRunner,
} from './ffmpeg-runner.port';

export interface CapturedThumbnail extends ExtractedThumbnail {
  directory: string;
}
@Injectable()
export class ThumbnailCaptureService {
  private readonly logger = new Logger(ThumbnailCaptureService.name);
  constructor(
    @Inject(WORKER_CONFIG) private readonly config: WorkerConfig,
    @Inject(FFMPEG_RUNNER) private readonly runner: FfmpegRunner,
  ) {}
  async capture(
    job: ThumbnailJobInput,
    signal?: AbortSignal,
  ): Promise<CapturedThumbnail> {
    const uuid =
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
    if (
      !uuid.test(job.jobId) ||
      !uuid.test(job.sessionId) ||
      !isAllowedMediamtxPlaybackUrl(
        job.playbackUrl,
        job.sessionId,
        this.config.http.mediamtxBaseUrl,
      )
    )
      throw new Error('INVALID_JOB');
    const expiry = Date.parse(job.readExpiresAt);
    const deadline = Date.parse(job.jobDeadlineAt);
    if (
      !Number.isFinite(expiry) ||
      !Number.isFinite(deadline) ||
      expiry <= Date.now() ||
      deadline <= Date.now()
    )
      throw new Error('JOB_EXPIRED');
    const root = resolve(this.config.job.tempDir);
    await mkdir(root, { recursive: true, mode: 0o700 });
    const directory = await mkdtemp(join(root, `thumbnail-${job.jobId}-`));
    try {
      const image = await this.runner.extractThumbnail({
        playbackUrl: job.playbackUrl,
        readToken: job.readToken,
        outputPath: join(directory, 'thumbnail.jpg'),
        width: this.config.job.width,
        height: this.config.job.height,
        timeoutMs: Math.min(
          this.config.timeouts.ffmpegMs,
          deadline - Date.now(),
        ),
        signal,
      });
      if (signal?.aborted) throw new Error('CAPTURE_ABORTED');
      return { ...image, directory };
    } catch (error) {
      await rm(directory, { recursive: true, force: true }).catch(() =>
        this.logger.warn(`Temporary cleanup failed for job ${job.jobId}`),
      );
      throw error;
    }
  }
  async release(image: CapturedThumbnail): Promise<void> {
    const directory = resolve(image.directory);
    if (
      dirname(directory) !== resolve(this.config.job.tempDir) ||
      !/^thumbnail-[0-9a-f-]+-[a-zA-Z0-9]+$/.test(basename(directory)) ||
      resolve(image.filePath) !== join(directory, 'thumbnail.jpg')
    )
      throw new Error('INVALID_TEMP_DIRECTORY');
    await rm(directory, { recursive: true, force: true });
  }
}
