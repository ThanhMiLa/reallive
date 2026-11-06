import type { ThumbnailJobInput } from '../common/contracts/thumbnail-contract';

/**
 * Pipeline entry point implemented in Steps 6–7. A processor receives one queued
 * job, checks token/deadline, extracts a JPEG, uploads it and sends a callback.
 */
export const THUMBNAIL_PROCESSOR = Symbol('THUMBNAIL_PROCESSOR');

export interface ThumbnailProcessor {
  process(job: ThumbnailJobInput): Promise<void>;
}
