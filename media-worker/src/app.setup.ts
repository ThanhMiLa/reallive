import type { NestExpressApplication } from '@nestjs/platform-express';
import type { WorkerConfig } from './common/config/worker-config';
import { ApiExceptionFilter } from './common/errors/api-exception.filter';
import { createJsonBodyParser } from './common/http/json-body-parser';
import { createValidationPipe } from './common/http/validation';

export function configureApplication(
  app: NestExpressApplication,
  config: WorkerConfig,
): void {
  app.disable('x-powered-by');
  app.use(createJsonBodyParser(config.http.maxBodyBytes));
  app.useGlobalPipes(createValidationPipe());
  app.useGlobalFilters(new ApiExceptionFilter());
}
