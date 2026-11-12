import { Injectable } from '@nestjs/common';
import { ChatError, unavailable } from '../../common/chat-error';
import { ChatClients } from '../../clients/chat-clients.service';
import type { Permission, SessionContext } from '../interfaces/chat.types';

interface Grant {
  context: SessionContext;

  permission: Permission;

  expiresAt: number;
}

@Injectable()
export class ChatAccessService {
  private readonly grants = new Map<string, Grant>();

  constructor(private readonly clients: ChatClients) {}

  key(sessionId: string, userId: string): string {
    return `${sessionId}:${userId}`;
  }

  peek(sessionId: string, userId: string): Grant | undefined {
    const value = this.grants.get(this.key(sessionId, userId));
    return value && value.expiresAt > Date.now() ? value : undefined;
  }

  forget(sessionId: string, userId: string): void {
    this.grants.delete(this.key(sessionId, userId));
  }

  async refresh(sessionId: string, userId: string): Promise<void> {
    const started = Date.now();
    for (const [key, grant] of this.grants)
      if (grant.expiresAt <= started) this.grants.delete(key);
    try {
      const context = await this.clients.session(sessionId);
      if (context.visibility !== 'PUBLIC')
        throw new ChatError('CHAT_FORBIDDEN', 403, 'Stream is not accessible');
      if (!['LIVE', 'RECONNECTING'].includes(context.status))
        throw new ChatError('CHAT_CLOSED', 409, 'Chat is not open');
      const permission = await this.clients.permission(
        context.channelId,
        userId,
      );
      if (Date.now() >= started + 1750) throw unavailable();
      this.grants.set(this.key(sessionId, userId), {
        context,
        permission,
        expiresAt: started + 1750,
      });
    } catch (error) {
      this.forget(sessionId, userId);
      throw error;
    }
  }

  async require(
    sessionId: string,
    userId: string,
    send = false,
  ): Promise<Grant> {
    let grant = this.peek(sessionId, userId);
    if (!grant) {
      await this.refresh(sessionId, userId);
      grant = this.peek(sessionId, userId);
    }
    if (!grant) throw unavailable();
    if (!grant.permission.canRead)
      throw new ChatError('CHAT_FORBIDDEN', 403, 'You cannot access this chat');
    if (send && !grant.permission.canSend)
      throw new ChatError(
        'CHAT_BANNED',
        403,
        'You cannot send messages in this channel',
      );
    return grant;
  }
}
