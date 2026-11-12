import { Schema } from 'mongoose';

export interface StoredMessage {
  _id: string;

  stream_id: string;

  channel_id: string;

  user_id: string;

  client_message_id: string;

  sequence: number;

  content: string;

  sender_snapshot: { display_name: string; avatar_url: string | null };

  created_at: Date;
}

export const ChatMessageSchema = new Schema(
  {
    _id: { type: String, required: true },
    stream_id: { type: String, required: true },
    channel_id: { type: String, required: true },
    user_id: { type: String, required: true },
    client_message_id: { type: String, required: true },
    sequence: { type: Number, required: true },
    content: { type: String, required: true },
    sender_snapshot: {
      display_name: { type: String, required: true },
      avatar_url: { type: String, default: null },
    },
    created_at: { type: Date, required: true },
  },
  {
    collection: 'chat_messages',
    versionKey: false,
    autoIndex: false,
    autoCreate: false,
  },
);

ChatMessageSchema.index({ stream_id: 1, sequence: 1 }, { unique: true });

ChatMessageSchema.index(
  { stream_id: 1, user_id: 1, client_message_id: 1 },
  { unique: true },
);
