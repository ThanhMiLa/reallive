import {
  ArgumentsHost,
  Catch,
  ExceptionFilter,
  HttpException,
  Logger,
} from '@nestjs/common';
import type { Response } from 'express';

export class ChatError extends HttpException {
  constructor(
    readonly code: string,
    status: number,
    message: string,
    readonly retryable = false,
  ) {
    super({ status, code, message, retryable, result: null }, status);
  }
}

export function invalid(message = 'Invalid chat request'): never {
  throw new ChatError('CHAT_REQUEST_INVALID', 400, message);
}

export function unavailable(): ChatError {
  return new ChatError(
    'CHAT_UNAVAILABLE',
    503,
    'Chat is temporarily unavailable',
    true,
  );
}

export function failure(error: unknown) {
  const e = error instanceof ChatError ? error : unavailable();
  return {
    ok: false as const,
    error: {
      code: e.code,
      status: e.getStatus(),
      message: e.message,
      retryable: e.retryable,
    },
  };
}

export const success = <T>(data: T) => ({ ok: true as const, data });

export const uuidPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function uuid(value: unknown): string {
  if (typeof value !== 'string' || !uuidPattern.test(value))
    invalid('Invalid identifier');
  return value.toLowerCase();
}

export function text(value: unknown): string {
  if (typeof value !== 'string') invalid('Message must be text');
  const trimmed = value.trim();
  if (!trimmed || Array.from(trimmed).length > 500)
    invalid('Message must contain 1 to 500 characters');
  return trimmed;
}

@Catch()
export class ChatExceptionFilter implements ExceptionFilter {
  catch(error: unknown, host: ArgumentsHost) {
    const response = host.switchToHttp().getResponse<Response>();
    const problem = failure(error).error;
    if (!(error instanceof ChatError))
      new Logger('ChatHttp').error({
        event: 'chat.request.failed',
        errorType: error instanceof Error ? error.name : 'unknown',
      });
    response
      .status(problem.status)
      .json({ ...problem, result: null, timestamp: new Date().toISOString() });
  }
}
