import { Schema } from 'mongoose';

export interface Counter {
  _id: string;

  value: number;
}

export const ChatCounterSchema = new Schema(
  {
    _id: String,
    value: { type: Number, required: true },
  },
  {
    collection: 'chat_counters',
    versionKey: false,
    autoIndex: false,
    autoCreate: false,
  },
);
