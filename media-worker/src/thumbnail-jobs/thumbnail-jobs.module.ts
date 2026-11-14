import { Module } from '@nestjs/common';
import { QueueModule } from '../queue/queue.module';
import { ThumbnailJobsController } from './thumbnail-jobs.controller';
import { ThumbnailJobsService } from './thumbnail-jobs.service';

@Module({
  imports: [QueueModule],
  controllers: [ThumbnailJobsController],
  providers: [ThumbnailJobsService],
})
export class ThumbnailJobsModule {}
