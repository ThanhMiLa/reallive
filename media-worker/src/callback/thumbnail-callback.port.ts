import type {
  ThumbnailCallbackResponse,
  ThumbnailResult,
} from '../common/contracts/thumbnail-contract';

export const THUMBNAIL_CALLBACK = Symbol('THUMBNAIL_CALLBACK');

export interface ThumbnailCallbackOptions {
  signal?: AbortSignal;
  timeoutMs?: number;
}

/**
 * Sends one thumbnail result to Stream Service. The client performs exactly one
 * HTTP attempt; callers must not retry after a timeout or transport failure.
 */
export interface ThumbnailCallbackClient {
  send(
    result: ThumbnailResult,
    options?: ThumbnailCallbackOptions,
  ): Promise<ThumbnailCallbackResponse>;
}
