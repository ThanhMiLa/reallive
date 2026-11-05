import 'reflect-metadata';
import { Logger } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { AppModule } from './app.module';
import { configureApplication } from './app.setup';
import {
  ConfigurationError,
  WORKER_CONFIG,
  type WorkerConfig,
} from './common/config/worker-config';

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create<NestExpressApplication>(AppModule, {
    bodyParser: false,
  });
  const config = app.get<WorkerConfig>(WORKER_CONFIG);

  configureApplication(app, config);
  app.enableShutdownHooks();
  await app.listen(config.http.port, '0.0.0.0');

  const logger = new Logger('Bootstrap');
  logger.log(`media-worker listening on port ${config.http.port}`);
  logger.log(
    `Allowed MediaMTX source: ${config.http.mediamtxBaseUrl}/live/{sessionId}/index.m3u8`,
  );
  logger.log(
    `Batch limit ${config.http.maxBatchSize} jobs; body limit ${config.http.maxBodyBytes} bytes`,
  );
}

bootstrap().catch((error: unknown) => {
  const logger = new Logger('Bootstrap');
  logger.error(
    error instanceof ConfigurationError
      ? error.message
      : 'media-worker failed to start',
  );
  process.exitCode = 1;
});
