import { Module } from '@nestjs/common';
import { WorkerConfigModule } from '../common/config/worker-config.module';
import { FFMPEG_RUNNER } from './ffmpeg-runner.port';
import { FfmpegRunnerImpl } from './ffmpeg-runner';
import { ThumbnailCaptureService } from './thumbnail-capture.service';

@Module({
  imports: [WorkerConfigModule],
  providers: [
    FfmpegRunnerImpl,
    { provide: FFMPEG_RUNNER, useExisting: FfmpegRunnerImpl },
    ThumbnailCaptureService,
  ],
  exports: [ThumbnailCaptureService, FFMPEG_RUNNER],
})
export class FfmpegModule {}
