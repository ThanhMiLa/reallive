import type {
  ThumbnailCallbackResponse,
  ThumbnailResult,
} from '../common/contracts/thumbnail-contract';

/**
 * One-shot callback client implemented in Step 6. It must not retry after a
 * timeout or non-2xx response.
 */
export interface ThumbnailCallbackClient {
  send(result: ThumbnailResult): Promise<ThumbnailCallbackResponse>;
}
