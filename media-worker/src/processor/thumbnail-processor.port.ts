import type { ThumbnailJobInput } from '../common/contracts/thumbnail-contract';

/**
 * Pipeline entry point for one queued job. A processor checks the job lifetime,
 * extracts a JPEG, uploads it and sends exactly one callback.
 */
export const THUMBNAIL_PROCESSOR = Symbol('THUMBNAIL_PROCESSOR');

export interface ThumbnailProcessor {
  process(job: ThumbnailJobInput, signal?: AbortSignal): Promise<void>;
}
