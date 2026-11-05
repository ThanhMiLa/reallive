import { Module } from '@nestjs/common';
import { THUMBNAIL_QUEUE } from './thumbnail-queue.port';
import { BullmqThumbnailQueue } from './bullmq-thumbnail-queue';

@Module({
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
