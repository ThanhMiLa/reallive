import { Injectable } from '@nestjs/common';
import type { HistoryPage } from '../dto/chat-history.dto';
import type { Message } from '../dto/chat-message.dto';
import type { HistoryPageBounds } from '../interfaces/chat-history.interface';
import type { CreateMessageInput } from '../interfaces/create-message.interface';
import type { StoredMessage } from '../schemas/chat-message.schema';
import { encodeCursor } from '../utils/chat-cursor';

@Injectable()
export class ChatMapper {
  toDocument(
    input: CreateMessageInput,
    id: string,
    sequence: number,
    createdAt: Date,
  ): StoredMessage {
    return {
      _id: id,
      stream_id: input.sessionId,
      channel_id: input.channelId,
      user_id: input.userId,
      client_message_id: input.clientMessageId,
      sequence,
      content: input.content,
      sender_snapshot: {
        display_name: input.snapshot.displayName,
        avatar_url: input.snapshot.avatarUrl,
      },
      created_at: createdAt,
    };
  }

  toMessage(document: StoredMessage): Message {
    return {
      id: document._id,
      cursor: encodeCursor(document.stream_id, document.sequence),
      streamId: document.stream_id,
      channelId: document.channel_id,
      userId: document.user_id,
      clientMessageId: document.client_message_id,
      sequence: document.sequence,
      content: document.content,
      sender: {
        displayName: document.sender_snapshot.display_name,
        avatarUrl: document.sender_snapshot.avatar_url,
      },
      createdAt: document.created_at.toISOString(),
    };
  }

  toHistoryPage(
    streamId: string,
    documents: StoredMessage[],
    bounds: HistoryPageBounds,
  ): HistoryPage {
    return {
      items: documents.map((document) => this.toMessage(document)),
      hasOlder: bounds.hasOlder,
      hasNewer: bounds.hasNewer,
      olderCursor:
        bounds.olderSequence === null
          ? null
          : encodeCursor(streamId, bounds.olderSequence),
      newerCursor: encodeCursor(streamId, bounds.newerSequence),
      watermark: encodeCursor(streamId, bounds.through),
    };
  }
}
