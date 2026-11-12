import { Injectable } from '@nestjs/common';
import { ChatConfig } from '../common/chat-config';
import { ChatError, unavailable, uuidPattern } from '../common/chat-error';
import type {
  Permission,
  SessionContext,
  Snapshot,
} from '../chat/interfaces/chat.types';

@Injectable()
export class ChatClients {
  constructor(private readonly config: ChatConfig) {}

  private async request<T>(
    base: string,
    path: string,
    body?: unknown,
  ): Promise<T> {
    try {
      const response = await fetch(`${base.replace(/\/$/, '')}${path}`, {
        method: body === undefined ? 'GET' : 'POST',
        redirect: 'error',
        headers: {
          'Content-Type': 'application/json',
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(900),
      });
      if (response.status === 404)
        throw new ChatError(
          'CHAT_NOT_FOUND',
          404,
          'Stream or channel not found',
        );
      if (!response.ok) throw unavailable();
      const envelope = (await response.json()) as {
        status?: number;
        code?: number;
        result?: T;
      };
      if (envelope.status !== 200 || envelope.code !== 1000 || !envelope.result)
        throw unavailable();
      return envelope.result;
    } catch (error) {
      if (error instanceof ChatError) throw error;
      throw unavailable();
    }
  }

  async session(sessionId: string): Promise<SessionContext> {
    const value = await this.request<SessionContext>(
      this.config.streamUrl,
      `/internal/api/streams/${sessionId}/chat-context`,
    );
    if (
      value.sessionId !== sessionId ||
      typeof value.channelId !== 'string' ||
      !uuidPattern.test(value.channelId) ||
      !['PUBLIC', 'PRIVATE', 'UNLISTED'].includes(value.visibility) ||
      !['READY', 'LIVE', 'RECONNECTING', 'ENDED'].includes(value.status)
    )
      throw unavailable();
    return value;
  }

  async permission(channelId: string, userId: string): Promise<Permission> {
    const value = await this.request<Permission & { channelId: string }>(
      this.config.channelUrl,
      `/internal/api/channels/${channelId}/chat-access`,
      { userId },
    );
    if (
      value.channelId !== channelId ||
      value.userId !== userId ||
      [value.canView, value.canRead, value.canSend].some(
        (v) => typeof v !== 'boolean',
      ) ||
      (value.canRead && !value.canView) ||
      (value.canSend && !value.canRead)
    )
      throw unavailable();
    return value;
  }

  async profile(userId: string): Promise<Snapshot> {
    const value = await this.request<Snapshot>(
      this.config.userUrl,
      `/internal/api/users/${userId}/chat-profile`,
    );
    if (
      value.userId !== userId ||
      typeof value.displayName !== 'string' ||
      !value.displayName.trim() ||
      (value.avatarUrl !== null && typeof value.avatarUrl !== 'string')
    )
      throw unavailable();
    return value;
  }
}
