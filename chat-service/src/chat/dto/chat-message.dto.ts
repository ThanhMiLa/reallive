export interface Message {
  id: string;

  cursor: string;

  streamId: string;

  channelId: string;

  userId: string;

  clientMessageId: string;

  sequence: number;

  content: string;

  sender: { displayName: string; avatarUrl: string | null };

  createdAt: string;
}
