import { Module } from '@nestjs/common';
import { THUMBNAIL_QUEUE } from './thumbnail-queue.port';
import { UnavailableThumbnailQueue } from './unavailable-thumbnail-queue';

@Module({
  providers: [
    UnavailableThumbnailQueue,
    {
      provide: THUMBNAIL_QUEUE,
      useExisting: UnavailableThumbnailQueue,
    },
  ],
  exports: [THUMBNAIL_QUEUE],
})
export class QueueModule {}
