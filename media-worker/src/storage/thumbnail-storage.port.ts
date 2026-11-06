export interface UploadThumbnailRequest {
  sessionId: string;
  jobId: string;
  filePath: string;
  width: number;
  height: number;
  sizeBytes: number;
  signal?: AbortSignal;
  timeoutMs?: number;
}

export interface StoredThumbnail {
  objectKey: string;
  contentType: 'image/jpeg';
  sizeBytes: number;
  width: number;
  height: number;
}

/**
 * R2/S3 upload adapter implemented in Step 6. Object key is immutable:
 * livestream-thumbnails/{sessionId}/{jobId}.jpg.
 */
export const THUMBNAIL_STORAGE = Symbol('THUMBNAIL_STORAGE');

export interface ThumbnailStorage {
  upload(request: UploadThumbnailRequest): Promise<StoredThumbnail>;
}
