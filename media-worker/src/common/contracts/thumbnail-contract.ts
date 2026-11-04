export interface ThumbnailJobInput {
  jobId: string;
  sessionId: string;
  playbackUrl: string;
  readToken: string;
  readExpiresAt: string;
  jobDeadlineAt: string;
}

export const THUMBNAIL_REJECTION_QUEUE_FULL = 'QUEUE_FULL' as const;

export interface RejectedThumbnailJob {
  jobId: string;
  reason: typeof THUMBNAIL_REJECTION_QUEUE_FULL;
}

export interface ThumbnailBatchAcceptedResponse {
  acceptedJobIds: string[];
  rejectedJobs: RejectedThumbnailJob[];
}

export interface ThumbnailResult {
  jobId: string;
  sessionId: string;
  objectKey: string;
  contentType: 'image/jpeg';
  sizeBytes: number;
  width: number;
  height: number;
}

export type ThumbnailCallbackOutcome = 'APPLIED' | 'IGNORED';

export interface ThumbnailCallbackResponse {
  outcome: ThumbnailCallbackOutcome;
}
