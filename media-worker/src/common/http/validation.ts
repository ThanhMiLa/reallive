import { ValidationError, ValidationPipe } from '@nestjs/common';
import { badRequest } from '../errors/api.exception';

function collectDetails(
  errors: readonly ValidationError[],
  parent = '',
): string[] {
  const details: string[] = [];

  for (const error of errors) {
    const path = parent ? `${parent}.${error.property}` : error.property;
    if (error.constraints) {
      for (const message of Object.values(error.constraints)) {
        details.push(`${path}: ${message}`);
      }
    }
    if (error.children && error.children.length > 0) {
      details.push(...collectDetails(error.children, path));
    }
  }

  return details;
}

export function createValidationPipe(): ValidationPipe {
  return new ValidationPipe({
    transform: true,
    whitelist: true,
    forbidNonWhitelisted: true,
    transformOptions: { enableImplicitConversion: false },
    validationError: { target: false, value: false },
    exceptionFactory: (errors: ValidationError[]) =>
      badRequest(
        'VALIDATION_FAILED',
        'Request validation failed',
        collectDetails(errors),
      ),
  });
}
