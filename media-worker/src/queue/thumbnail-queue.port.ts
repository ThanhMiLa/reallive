import type {
  ThumbnailBatchAcceptedResponse,
  ThumbnailJobInput,
} from '../common/contracts/thumbnail-contract';

export const THUMBNAIL_QUEUE = Symbol('THUMBNAIL_QUEUE');

export interface ThumbnailQueue {
  isReady(): Promise<boolean>;
  enqueueBatch(
    jobs: readonly ThumbnailJobInput[],
  ): Promise<ThumbnailBatchAcceptedResponse>;
}
