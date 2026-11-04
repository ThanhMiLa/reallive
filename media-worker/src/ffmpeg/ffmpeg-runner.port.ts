export interface ExtractThumbnailRequest {
  playbackUrl: string;
  readToken: string;
  outputPath: string;
  width: number;
  height: number;
  timeoutMs: number;
}

export interface ExtractedThumbnail {
  filePath: string;
  width: number;
  height: number;
  sizeBytes: number;
}

/**
 * FFmpeg adapter implemented in Step 5 with spawn() and an argument array.
 */
export interface FfmpegRunner {
  extractThumbnail(
    request: ExtractThumbnailRequest,
  ): Promise<ExtractedThumbnail>;
}
