import { Injectable } from '@nestjs/common';
import type {
  ThumbnailBatchAcceptedResponse,
  ThumbnailJobInput,
} from '../common/contracts/thumbnail-contract';
import { QueueUnavailableError } from './queue-unavailable.error';
import type { ThumbnailQueue } from './thumbnail-queue.port';

@Injectable()
export class UnavailableThumbnailQueue implements ThumbnailQueue {
  isReady(): Promise<boolean> {
    return Promise.resolve(false);
  }

  enqueueBatch(
    _jobs: readonly ThumbnailJobInput[],
  ): Promise<ThumbnailBatchAcceptedResponse> {
    return Promise.reject(
      new QueueUnavailableError(
        'Thumbnail queue is not integrated until Step 4',
      ),
    );
  }
}
