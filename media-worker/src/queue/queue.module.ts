import { Module } from '@nestjs/common';
import { ThumbnailProcessorModule } from '../processor/thumbnail-processor.module';
import { THUMBNAIL_QUEUE } from './thumbnail-queue.port';
import { BullmqThumbnailQueue } from './bullmq-thumbnail-queue';

@Module({
  imports: [ThumbnailProcessorModule],
  providers: [
    BullmqThumbnailQueue,
    {
      provide: THUMBNAIL_QUEUE,
      useExisting: BullmqThumbnailQueue,
    },
  ],
  exports: [THUMBNAIL_QUEUE],
})
export class QueueModule {}
