import {
  CanActivate,
  ExecutionContext,
  Inject,
  Injectable,
} from '@nestjs/common';
import type { Request } from 'express';
import { createHash, timingSafeEqual } from 'node:crypto';
import { WORKER_CONFIG, type WorkerConfig } from '../config/worker-config';
import { unauthorized } from '../errors/api.exception';

function digest(value: string): Buffer {
  return createHash('sha256').update(value, 'utf8').digest();
}

export function tokensMatch(provided: string, expected: string): boolean {
  return timingSafeEqual(digest(provided), digest(expected));
}

@Injectable()
export class InternalTokenGuard implements CanActivate {
  constructor(@Inject(WORKER_CONFIG) private readonly config: WorkerConfig) {}

  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<Request>();
    const token = request.header('x-internal-token');

    if (
      typeof token !== 'string' ||
      token.length === 0 ||
      !tokensMatch(token, this.config.http.internalToken)
    ) {
      throw unauthorized('Invalid or missing internal token');
    }

    return true;
  }
}
