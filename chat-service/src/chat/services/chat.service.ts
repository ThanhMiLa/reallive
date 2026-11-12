import { Injectable } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { ChatError, text, unavailable, uuid } from '../../common/chat-error';
import { ChatAuthService } from './chat-auth.service';
import { ChatAccessService } from './chat-access.service';
import { ChatHistoryService } from './chat-history.service';
import { ChatClients } from '../../clients/chat-clients.service';
import { ChatRepository } from '../repositories/chat.repository';
import { ChatMapper } from '../mappers/chat.mapper';
import { RoomQueue } from '../utils/room-queue';
import type { HistoryPage, HistoryQuery } from '../dto/chat-history.dto';
import type { Message } from '../dto/chat-message.dto';
import type { CreateMessageInput } from '../interfaces/create-message.interface';

@Injectable()
export class ChatService {
  constructor(
    private readonly auth: ChatAuthService,
    private readonly access: ChatAccessService,
    private readonly clients: ChatClients,
    private readonly repository: ChatRepository,
    private readonly queue: RoomQueue,
    private readonly historyService: ChatHistoryService,
    private readonly mapper: ChatMapper,
  ) {}

  async history(
    cookie: string | undefined,
    id: unknown,
    query: HistoryQuery,
  ): Promise<HistoryPage> {
    const sessionId = uuid(id);
    const principal = await this.auth.authenticate(cookie);
    await this.access.require(sessionId, principal.userId);
    const page = await this.historyService.history(sessionId, query);
    await this.auth.verify(principal.token);
    await this.access.require(sessionId, principal.userId);
    return page;
  }

  async watermark(sessionId: string): Promise<number> {
    const counter = await this.repository.findCounterBySessionId(sessionId);
    return counter?.value ?? 0;
  }

  async send(
    token: string,
    input: {
      sessionId?: unknown;
      clientMessageId?: unknown;
      content?: unknown;
    },
    deliver: (message: Message) => void,
  ): Promise<Message> {
    const sessionId = uuid(input.sessionId);
    const clientMessageId = uuid(input.clientMessageId);
    const content = text(input.content);
    return this.queue.run(sessionId, async () => {
      const principal = await this.auth.verify(token);
      await this.access.require(sessionId, principal.userId, true);
      const snapshot = await this.clients.profile(principal.userId);
      await this.auth.verify(token);
      const grant = await this.access.require(
        sessionId,
        principal.userId,
        true,
      );
      const result = await this.persist({
        sessionId,
        channelId: grant.context.channelId,
        userId: principal.userId,
        clientMessageId,
        content,
        snapshot,
      });
      if (result.created) deliver(result.message);
      return result.message;
    });
  }

  private async persist(
    input: CreateMessageInput,
  ): Promise<{ message: Message; created: boolean }> {
    const id = randomUUID();
    const result = await this.repository.withTransaction(async (session) => {
      const old = await this.repository.findByClientMessageId(
        input.sessionId,
        input.userId,
        input.clientMessageId,
        session,
      );
      if (old) {
        if (old.content !== input.content)
          throw new ChatError(
            'CHAT_MESSAGE_CONFLICT',
            409,
            'Message identifier was already used for different content',
          );
        return { document: old, created: false };
      }
      const counter = await this.repository.incrementCounter(
        input.sessionId,
        session,
      );
      if (!counter || !Number.isSafeInteger(counter.value)) throw unavailable();
      const document = this.mapper.toDocument(
        input,
        id,
        counter.value,
        new Date(),
      );
      await this.repository.insertMessage(document, session);
      return { document, created: true };
    });
    return {
      message: this.mapper.toMessage(result.document),
      created: result.created,
    };
  }
}
