import { Module } from '@nestjs/common';
import { FfmpegModule } from '../ffmpeg/ffmpeg.module';
import { StorageModule } from '../storage/storage.module';
import { ThumbnailImageService } from './thumbnail-image.service';
@Module({
  imports: [FfmpegModule, StorageModule],
  providers: [ThumbnailImageService],
  exports: [ThumbnailImageService],
})
export class ThumbnailImageModule {}
