import { Inject, Injectable } from '@nestjs/common';
import {
  THUMBNAIL_QUEUE,
  type ThumbnailQueue,
} from '../queue/thumbnail-queue.port';

export interface LivenessResponse {
  status: 'ok';
  service: 'media-worker';
  uptimeSeconds: number;
}

export interface ReadinessResponse {
  status: 'ready' | 'not_ready';
  checks: {
    configuration: 'ok';
    queue: {
      status: 'up' | 'down';
      reason?: 'not_integrated';
    };
  };
}

@Injectable()
export class HealthService {
  constructor(
    @Inject(THUMBNAIL_QUEUE) private readonly queue: ThumbnailQueue,
  ) {}

  liveness(): LivenessResponse {
    return {
      status: 'ok',
      service: 'media-worker',
      uptimeSeconds: Math.floor(process.uptime()),
    };
  }

  async readiness(): Promise<ReadinessResponse> {
    let queueReady = false;
    try {
      queueReady = await this.queue.isReady();
    } catch {
      queueReady = false;
    }

    return {
      status: queueReady ? 'ready' : 'not_ready',
      checks: {
        configuration: 'ok',
        queue: queueReady
          ? { status: 'up' }
          : { status: 'down', reason: 'not_integrated' },
      },
    };
  }
}
