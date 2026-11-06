import { Module } from '@nestjs/common';
import { ThumbnailCallbackModule } from '../callback/thumbnail-callback.module';
import { ThumbnailImageModule } from './thumbnail-image.module';
import { ThumbnailPipelineProcessor } from './thumbnail-pipeline.processor';
import { THUMBNAIL_PROCESSOR } from './thumbnail-processor.port';

@Module({
  imports: [ThumbnailImageModule, ThumbnailCallbackModule],
  providers: [
    ThumbnailPipelineProcessor,
    {
      provide: THUMBNAIL_PROCESSOR,
      useExisting: ThumbnailPipelineProcessor,
    },
  ],
  exports: [THUMBNAIL_PROCESSOR],
})
export class ThumbnailProcessorModule {}
