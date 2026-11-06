import { Module } from '@nestjs/common';
import { WorkerConfigModule } from '../common/config/worker-config.module';
import { R2ThumbnailStorage } from './r2-thumbnail-storage';
import { THUMBNAIL_STORAGE } from './thumbnail-storage.port';
@Module({
  imports: [WorkerConfigModule],
  providers: [
    R2ThumbnailStorage,
    { provide: THUMBNAIL_STORAGE, useExisting: R2ThumbnailStorage },
  ],
  exports: [THUMBNAIL_STORAGE],
})
export class StorageModule {}
