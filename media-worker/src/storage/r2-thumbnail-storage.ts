import { Inject, Injectable, OnApplicationShutdown } from '@nestjs/common';
import { PutObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import {
  WORKER_CONFIG,
  type WorkerConfig,
} from '../common/config/worker-config';
import type {
  StoredThumbnail,
  ThumbnailStorage,
  UploadThumbnailRequest,
} from './thumbnail-storage.port';

@Injectable()
export class R2ThumbnailStorage
  implements ThumbnailStorage, OnApplicationShutdown
{
  private readonly client?: S3Client;
  private readonly active = new Map<AbortController, Promise<void>>();
  private stopping = false;
  constructor(@Inject(WORKER_CONFIG) private readonly config: WorkerConfig) {
    const s = config.storage;
    if (s.endpoint && s.bucket && s.accessKeyId && s.secretAccessKey)
      this.client = new S3Client({
        endpoint: s.endpoint,
        region: s.region,
        forcePathStyle: true,
        credentials: {
          accessKeyId: s.accessKeyId,
          secretAccessKey: s.secretAccessKey,
        },
        maxAttempts: 1,
        requestChecksumCalculation: 'WHEN_REQUIRED',
        responseChecksumValidation: 'WHEN_REQUIRED',
      });
  }
  async upload(request: UploadThumbnailRequest): Promise<StoredThumbnail> {
    if (!this.client || !this.config.storage.bucket)
      throw new Error('R2_NOT_CONFIGURED');
    if (this.stopping || request.signal?.aborted)
      throw new Error('UPLOAD_ABORTED');
    const uuid =
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
    if (
      !uuid.test(request.sessionId) ||
      !uuid.test(request.jobId) ||
      !Number.isSafeInteger(request.width) ||
      request.width < 1 ||
      !Number.isSafeInteger(request.height) ||
      request.height < 1 ||
      !Number.isSafeInteger(request.sizeBytes) ||
      request.sizeBytes < 3 ||
      request.sizeBytes > this.config.job.maxJpegBytes
    )
      throw new Error('INVALID_UPLOAD_INPUT');
    const timeout = Math.min(
      this.config.timeouts.uploadMs,
      request.timeoutMs ?? this.config.timeouts.uploadMs,
    );
    if (!Number.isFinite(timeout) || timeout <= 0)
      throw new Error('UPLOAD_TIMEOUT');
    const abort = new AbortController();
    let timedOut = false;
    let finish!: () => void;
    this.active.set(
      abort,
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
    );
    const onAbort = () => abort.abort();
    request.signal?.addEventListener('abort', onAbort, { once: true });
    if (request.signal?.aborted) abort.abort();
    const timer = setTimeout(() => {
      timedOut = true;
      abort.abort();
    }, timeout);
    const ensure = () => {
      if (abort.signal.aborted)
        throw new Error(timedOut ? 'UPLOAD_TIMEOUT' : 'UPLOAD_ABORTED');
    };
    let file: Awaited<ReturnType<typeof open>> | undefined;
    try {
      ensure();
      try {
        file = await open(
          request.filePath,
          constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0),
        );
      } catch {
        throw new Error('UPLOAD_FILE_READ_FAILED');
      }
      const stat = await file.stat();
      if (
        !stat.isFile() ||
        stat.size !== request.sizeBytes ||
        stat.size > this.config.job.maxJpegBytes
      )
        throw new Error('INVALID_UPLOAD_INPUT');
      const buffer = Buffer.alloc(request.sizeBytes + 1);
      let offset = 0;
      while (offset < buffer.length) {
        ensure();
        const { bytesRead } = await file.read(
          buffer,
          offset,
          buffer.length - offset,
          null,
        );
        if (!bytesRead) break;
        offset += bytesRead;
      }
      ensure();
      if (
        offset !== request.sizeBytes ||
        buffer[0] !== 0xff ||
        buffer[1] !== 0xd8 ||
        buffer[2] !== 0xff
      )
        throw new Error('INVALID_UPLOAD_INPUT');
      await file.close();
      file = undefined;
      ensure();
      const objectKey = `livestream-thumbnails/${request.sessionId}/${request.jobId}.jpg`;
      try {
        await this.client.send(
          new PutObjectCommand({
            Bucket: this.config.storage.bucket,
            Key: objectKey,
            Body: buffer.subarray(0, offset),
            ContentType: 'image/jpeg',
            ContentLength: offset,
          }),
          { abortSignal: abort.signal },
        );
      } catch {
        ensure();
        throw new Error('R2_UPLOAD_FAILED');
      }
      ensure();
      return {
        objectKey,
        contentType: 'image/jpeg',
        sizeBytes: offset,
        width: request.width,
        height: request.height,
      };
    } catch (error) {
      if (
        error instanceof Error &&
        [
          'UPLOAD_TIMEOUT',
          'UPLOAD_ABORTED',
          'UPLOAD_FILE_READ_FAILED',
          'INVALID_UPLOAD_INPUT',
          'R2_UPLOAD_FAILED',
        ].includes(error.message)
      )
        throw error;
      throw new Error('UPLOAD_FILE_READ_FAILED');
    } finally {
      try {
        if (file) await file.close();
      } finally {
        clearTimeout(timer);
        request.signal?.removeEventListener('abort', onAbort);
        this.active.delete(abort);
        finish();
      }
    }
  }
  async onApplicationShutdown(): Promise<void> {
    this.stopping = true;
    const running = [...this.active.entries()];
    for (const [controller] of running) controller.abort();
    await Promise.all(running.map(([, done]) => done));
    this.client?.destroy();
  }
}
