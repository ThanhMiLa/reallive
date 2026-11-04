export interface UploadThumbnailRequest {
  sessionId: string;
  jobId: string;
  filePath: string;
  width: number;
  height: number;
  sizeBytes: number;
}

export interface StoredThumbnail {
  objectKey: string;
  contentType: 'image/jpeg';
  sizeBytes: number;
  width: number;
  height: number;
}

/**
 * R2/S3 upload adapter implemented in Step 5. Object key is immutable:
 * livestream-thumbnails/{sessionId}/{jobId}.jpg.
 */
export interface ThumbnailStorage {
  upload(request: UploadThumbnailRequest): Promise<StoredThumbnail>;
}
