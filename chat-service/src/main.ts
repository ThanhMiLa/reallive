import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import { ChatDatabaseService } from './chat/services/chat-database.service';
import { ChatConfig } from './common/chat-config';
import { ChatExceptionFilter } from './common/chat-error';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  const config = app.get(ChatConfig);
  app.enableCors({ origin: config.origins, credentials: true });
  app.useGlobalFilters(new ChatExceptionFilter());
  app.enableShutdownHooks();
  await app.get(ChatDatabaseService).ready();
  await app.listen(config.port, config.host);
}

void bootstrap();
