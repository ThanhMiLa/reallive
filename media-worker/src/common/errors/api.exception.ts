import { HttpException, HttpStatus } from '@nestjs/common';

export class ApiException extends HttpException {
  constructor(
    statusCode: number,
    readonly code: string,
    message: string,
    readonly details?: readonly string[],
  ) {
    super({ statusCode, code, message, details }, statusCode);
  }
}

export function badRequest(
  code: string,
  message: string,
  details?: readonly string[],
): ApiException {
  return new ApiException(HttpStatus.BAD_REQUEST, code, message, details);
}

export function unauthorized(message: string): ApiException {
  return new ApiException(
    HttpStatus.UNAUTHORIZED,
    'INVALID_INTERNAL_TOKEN',
    message,
  );
}

export function serviceUnavailable(
  code: string,
  message: string,
): ApiException {
  return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
}
