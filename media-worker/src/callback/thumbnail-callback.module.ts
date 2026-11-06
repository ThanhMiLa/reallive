import { Module } from '@nestjs/common';
import { WorkerConfigModule } from '../common/config/worker-config.module';
import { THUMBNAIL_CALLBACK } from './thumbnail-callback.port';
import { ThumbnailCallbackClientImpl } from './thumbnail-callback.client';

@Module({
  imports: [WorkerConfigModule],
  providers: [
    ThumbnailCallbackClientImpl,
    { provide: THUMBNAIL_CALLBACK, useExisting: ThumbnailCallbackClientImpl },
  ],
  exports: [THUMBNAIL_CALLBACK],
})
export class ThumbnailCallbackModule {}
