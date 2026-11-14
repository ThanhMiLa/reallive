import {
  Body,
  Controller,
  HttpCode,
  HttpStatus,
  Post,
} from '@nestjs/common';
import type { ThumbnailBatchAcceptedResponse } from '../common/contracts/thumbnail-contract';
import { ThumbnailJobsBatchDto } from './dto/thumbnail-jobs-batch.dto';
import { ThumbnailJobsService } from './thumbnail-jobs.service';

@Controller('internal/api/thumbnail-jobs')
export class ThumbnailJobsController {
  constructor(private readonly thumbnailJobsService: ThumbnailJobsService) {}

  @Post('batch')
  @HttpCode(HttpStatus.ACCEPTED)
  acceptBatch(
    @Body() batch: ThumbnailJobsBatchDto,
  ): Promise<ThumbnailBatchAcceptedResponse> {
    return this.thumbnailJobsService.acceptBatch(batch.jobs);
  }
}
