import type { ThumbnailJobInput } from '../common/contracts/thumbnail-contract';

/**
 * Pipeline entry point implemented in Step 5. A processor receives one queued
 * job, checks token/deadline, extracts a JPEG, uploads it and sends a callback.
 */
export interface ThumbnailProcessor {
  process(job: ThumbnailJobInput): Promise<void>;
}
