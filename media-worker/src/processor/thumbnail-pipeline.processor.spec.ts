import { randomUUID } from 'node:crypto';
import { loadWorkerConfig } from '../common/config/worker-config';
import type {
  ThumbnailJobInput,
  ThumbnailResult,
} from '../common/contracts/thumbnail-contract';
import type {
  ThumbnailCallbackClient,
  ThumbnailCallbackOptions,
} from '../callback/thumbnail-callback.port';
import type { StoredThumbnail } from '../storage/thumbnail-storage.port';
import type { ThumbnailImageService } from './thumbnail-image.service';
import { ThumbnailPipelineProcessor } from './thumbnail-pipeline.processor';

function config() {
  return loadWorkerConfig({
  });
}

function job(deadlineMs = 120_000): ThumbnailJobInput {
  const sessionId = randomUUID();
  return {
    jobId: randomUUID(),
    sessionId,
    playbackUrl: `http://media-server:8888/live/${sessionId}/index.m3u8`,
    readToken: 'test-token',
    readExpiresAt: new Date(Date.now() + 120_000).toISOString(),
    jobDeadlineAt: new Date(Date.now() + deadlineMs).toISOString(),
  };
}

function stored(input: ThumbnailJobInput): StoredThumbnail {
  return {
    objectKey: `livestream-thumbnails/${input.sessionId}/${input.jobId}.jpg`,
    contentType: 'image/jpeg',
    sizeBytes: 48210,
    width: 640,
    height: 360,
  };
}

function processor(options: {
  image?: Partial<ThumbnailImageService>;
  callback?: Partial<ThumbnailCallbackClient>;
}) {
  const create = options.image?.create ?? jest.fn();
  const send = options.callback?.send ?? jest.fn();
  const instance = new ThumbnailPipelineProcessor(
    config(),
    { ...options.image, create } as unknown as ThumbnailImageService,
    { ...options.callback, send } as unknown as ThumbnailCallbackClient,
  );
  return { instance, create, send };
}

describe('ThumbnailPipelineProcessor', () => {
  it('captures, uploads and sends the exact callback payload', async () => {
    const input = job();
    const image = stored(input);
    const { instance, create, send } = processor({
      image: { create: jest.fn().mockResolvedValue(image) },
      callback: { send: jest.fn().mockResolvedValue({ outcome: 'APPLIED' }) },
    });

    await instance.process(input);

    expect(create).toHaveBeenCalledWith(input, undefined);
    expect(send).toHaveBeenCalledTimes(1);
    const result = send.mock.calls[0][0] as ThumbnailResult;
    expect(result).toEqual({
      jobId: input.jobId,
      sessionId: input.sessionId,
      objectKey: image.objectKey,
      contentType: 'image/jpeg',
      sizeBytes: 48210,
      width: 640,
      height: 360,
    });
    const sendOptions = send.mock.calls[0][1] as ThumbnailCallbackOptions;
    expect(sendOptions.timeoutMs).toBeGreaterThan(0);
    expect(sendOptions.timeoutMs).toBeLessThanOrEqual(120_000);
  });

  it('treats IGNORED as a completed job', async () => {
    const input = job();
    const { instance, send } = processor({
      image: { create: jest.fn().mockResolvedValue(stored(input)) },
      callback: { send: jest.fn().mockResolvedValue({ outcome: 'IGNORED' }) },
    });

    await expect(instance.process(input)).resolves.toBeUndefined();
    expect(send).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['capture failure', 'CAPTURE_TIMEOUT'],
    ['upload failure', 'R2_UPLOAD_FAILED'],
  ])('does not call back after a %s', async (_label, code) => {
    const { instance, send } = processor({
      image: { create: jest.fn().mockRejectedValue(new Error(code)) },
    });

    await expect(instance.process(job())).rejects.toThrow(code);
    expect(send).not.toHaveBeenCalled();
  });

  it('does not call back when the deadline expires during upload', async () => {
    const input = job(30);
    const { instance, send } = processor({
      image: {
        create: jest.fn().mockImplementation(async () => {
          await new Promise((resolve) => setTimeout(resolve, 60));
          return stored(input);
        }),
      },
    });

    await expect(instance.process(input)).rejects.toThrow(
      'JOB_DEADLINE_EXPIRED',
    );
    expect(send).not.toHaveBeenCalled();
  });

  it('caps the callback timeout by the remaining job time', async () => {
    const input = job(10_000);
    const { instance, send } = processor({
      image: { create: jest.fn().mockResolvedValue(stored(input)) },
      callback: { send: jest.fn().mockResolvedValue({ outcome: 'APPLIED' }) },
    });

    await instance.process(input);

    const options = send.mock.calls[0][1] as ThumbnailCallbackOptions;
    expect(options.timeoutMs).toBeGreaterThan(0);
    expect(options.timeoutMs).toBeLessThanOrEqual(10_000);
    expect(options.timeoutMs).toBeLessThanOrEqual(5_000);
  });

  it('propagates a callback failure without sending again', async () => {
    const input = job();
    const send = jest
      .fn()
      .mockRejectedValue(new Error('CALLBACK_TIMEOUT'));
    const { instance } = processor({
      image: { create: jest.fn().mockResolvedValue(stored(input)) },
      callback: { send },
    });

    await expect(instance.process(input)).rejects.toThrow('CALLBACK_TIMEOUT');
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('does not start capture when the shutdown signal is already aborted', async () => {
    const abort = new AbortController();
    abort.abort();
    const { instance, create } = processor({});

    await expect(instance.process(job(), abort.signal)).rejects.toThrow(
      'PIPELINE_ABORTED',
    );
    expect(create).not.toHaveBeenCalled();
  });
});
