import { Module } from '@nestjs/common';
import { WorkerConfigModule } from './common/config/worker-config.module';
import { HealthModule } from './health/health.module';
import { ThumbnailJobsModule } from './thumbnail-jobs/thumbnail-jobs.module';

@Module({
  imports: [WorkerConfigModule, ThumbnailJobsModule, HealthModule],
})
export class AppModule {}
