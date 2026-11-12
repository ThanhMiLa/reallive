import type { Snapshot } from './chat.types';

export interface CreateMessageInput {
  sessionId: string;

  channelId: string;

  userId: string;

  clientMessageId: string;

  content: string;

  snapshot: Snapshot;
}
