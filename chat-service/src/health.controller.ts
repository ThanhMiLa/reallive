import { Controller, Get } from '@nestjs/common';
import { ChatDatabaseService } from './chat/services/chat-database.service';
import { ChatAuthService } from './chat/services/chat-auth.service';
import { unavailable } from './common/chat-error';

@Controller('internal/health')
export class HealthController {
  constructor(
    private readonly database: ChatDatabaseService,
    private readonly auth: ChatAuthService,
  ) {}

  @Get('live')
  live() {
    return { status: 'up' };
  }

  @Get('ready')
  async ready() {
    try {
      if (this.auth.redis.status === 'wait') await this.auth.redis.connect();
      await Promise.all([this.database.ready(), this.auth.redis.ping()]);
      return { status: 'up' };
    } catch {
      throw unavailable();
    }
  }
}
