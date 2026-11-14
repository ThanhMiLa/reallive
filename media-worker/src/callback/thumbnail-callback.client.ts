import { Inject, Injectable, OnApplicationShutdown } from '@nestjs/common';
import {
  WORKER_CONFIG,
  type WorkerConfig,
} from '../common/config/worker-config';
import type {
  ThumbnailCallbackResponse,
  ThumbnailResult,
} from '../common/contracts/thumbnail-contract';
import type {
  ThumbnailCallbackClient,
  ThumbnailCallbackOptions,
} from './thumbnail-callback.port';

const CALLBACK_PATH = '/stream/internal/api/thumbnail-results';
const MAX_RESPONSE_BYTES = 16 * 1024;
const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SAFE_ERRORS = new Set([
  'CALLBACK_REJECTED',
  'CALLBACK_TIMEOUT',
  'CALLBACK_ABORTED',
  'CALLBACK_INVALID_RESPONSE',
  'CALLBACK_FAILED',
  'INVALID_CALLBACK_RESULT',
]);

@Injectable()
export class ThumbnailCallbackClientImpl
  implements ThumbnailCallbackClient, OnApplicationShutdown
{
  private readonly active = new Map<AbortController, Promise<void>>();
  private stopping = false;

  constructor(@Inject(WORKER_CONFIG) private readonly config: WorkerConfig) {}

  async send(
    result: ThumbnailResult,
    options: ThumbnailCallbackOptions = {},
  ): Promise<ThumbnailCallbackResponse> {
    if (this.stopping || options.signal?.aborted)
      throw new Error('CALLBACK_ABORTED');
    this.validateResult(result);

    const timeout = Math.min(
      this.config.timeouts.callbackMs,
      options.timeoutMs ?? this.config.timeouts.callbackMs,
    );
    if (!Number.isFinite(timeout) || timeout <= 0)
      throw new Error('CALLBACK_TIMEOUT');

    const endpoint = new URL(
      CALLBACK_PATH,
      this.config.callback.baseUrl.endsWith('/')
        ? this.config.callback.baseUrl
        : `${this.config.callback.baseUrl}/`,
    ).toString();
    const body = JSON.stringify(result);
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
    options.signal?.addEventListener('abort', onAbort, { once: true });
    if (options.signal?.aborted) abort.abort();
    const timer = setTimeout(() => {
      timedOut = true;
      abort.abort();
    }, timeout);
    const ensure = () => {
      if (abort.signal.aborted)
        throw new Error(timedOut ? 'CALLBACK_TIMEOUT' : 'CALLBACK_ABORTED');
    };

    try {
      ensure();
      let response: Response;
      try {
        response = await fetch(endpoint, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body,
          redirect: 'error',
          signal: abort.signal,
        });
      } catch {
        ensure();
        throw new Error('CALLBACK_FAILED');
      }
      ensure();
      if (response.status !== 200) throw new Error('CALLBACK_REJECTED');
      const payload = await this.readResponse(response, ensure);
      if (payload.outcome !== 'APPLIED' && payload.outcome !== 'IGNORED')
        throw new Error('CALLBACK_INVALID_RESPONSE');
      ensure();
      return { outcome: payload.outcome };
    } catch (error) {
      if (error instanceof Error && SAFE_ERRORS.has(error.message))
        throw error;
      ensure();
      throw new Error('CALLBACK_FAILED');
    } finally {
      // Also cancels unread bodies when status/content-type validation exits early.
      abort.abort();
      clearTimeout(timer);
      options.signal?.removeEventListener('abort', onAbort);
      this.active.delete(abort);
      finish();
    }
  }

  async onApplicationShutdown(): Promise<void> {
    this.stopping = true;
    const running = [...this.active.entries()];
    for (const [controller] of running) controller.abort();
    await Promise.all(running.map(([, done]) => done));
  }

  private validateResult(result: ThumbnailResult): void {
    if (
      !UUID.test(result.jobId) ||
      !UUID.test(result.sessionId) ||
      result.objectKey !==
        `livestream-thumbnails/${result.sessionId}/${result.jobId}.jpg` ||
      result.contentType !== 'image/jpeg' ||
      !Number.isSafeInteger(result.sizeBytes) ||
      result.sizeBytes < 3 ||
      result.sizeBytes > this.config.job.maxJpegBytes ||
      !Number.isSafeInteger(result.width) ||
      result.width < 1 ||
      !Number.isSafeInteger(result.height) ||
      result.height < 1
    )
      throw new Error('INVALID_CALLBACK_RESULT');
  }

  private async readResponse(
    response: Response,
    ensure: () => void,
  ): Promise<{ outcome?: unknown }> {
    const contentType = response.headers.get('content-type') ?? '';
    if (!contentType.toLowerCase().startsWith('application/json'))
      throw new Error('CALLBACK_INVALID_RESPONSE');
    if (!response.body) throw new Error('CALLBACK_INVALID_RESPONSE');
    const reader = response.body.getReader();
    const chunks: Buffer[] = [];
    let size = 0;
    try {
      for (;;) {
        ensure();
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > MAX_RESPONSE_BYTES) {
          await reader.cancel().catch(() => undefined);
          throw new Error('CALLBACK_INVALID_RESPONSE');
        }
        chunks.push(Buffer.from(value));
      }
    } finally {
      reader.releaseLock();
    }
    ensure();
    try {
      return JSON.parse(Buffer.concat(chunks).toString('utf8')) as {
        outcome?: unknown;
      };
    } catch {
      throw new Error('CALLBACK_INVALID_RESPONSE');
    }
  }
}
