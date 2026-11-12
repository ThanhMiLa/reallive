import { Injectable } from '@nestjs/common';
import { InjectConnection, InjectModel } from '@nestjs/mongoose';
import { Connection, Model } from 'mongoose';
import type { ClientSession, mongo } from 'mongoose';
import type { StoredMessage } from '../schemas/chat-message.schema';
import type { Counter } from '../schemas/chat-counter.schema';
import type { HistoryRange } from '../interfaces/chat-history.interface';

@Injectable()
export class ChatRepository {
  constructor(
    @InjectConnection() private readonly connection: Connection,
    @InjectModel('ChatMessage') private readonly messages: Model<StoredMessage>,
    @InjectModel('ChatCounter') private readonly counters: Model<Counter>,
  ) {}

  isConnected(): boolean {
    return Boolean(this.connection.db && this.connection.readyState === 1);
  }

  async getReplicaSetName(): Promise<string | undefined> {
    const hello = await this.connection
      .getClient()
      .db('admin')
      .command({ hello: 1 });
    return hello.setName as string | undefined;
  }

  getMessageIndexes(): Promise<mongo.IndexDescriptionInfo[]> {
    return this.messages.collection.indexes();
  }

  withTransaction<T>(
    action: (session: ClientSession) => Promise<T>,
  ): Promise<T> {
    return this.connection.transaction(action, {
      readConcern: { level: 'snapshot' },
      writeConcern: { w: 'majority' },
    });
  }

  findCounterBySessionId(streamId: string): Promise<Counter | null> {
    return this.counters.findById(streamId).lean().exec();
  }

  findByClientMessageId(
    streamId: string,
    userId: string,
    clientMessageId: string,
    session: ClientSession,
  ): Promise<StoredMessage | null> {
    return this.messages
      .findOne({
        stream_id: streamId,
        user_id: userId,
        client_message_id: clientMessageId,
      })
      .session(session)
      .lean()
      .exec();
  }

  incrementCounter(
    streamId: string,
    session: ClientSession,
  ): Promise<Counter | null> {
    return this.counters
      .findByIdAndUpdate(
        streamId,
        { $inc: { value: 1 } },
        { upsert: true, returnDocument: 'after', session },
      )
      .lean()
      .exec();
  }

  async insertMessage(
    document: StoredMessage,
    session: ClientSession,
  ): Promise<void> {
    await this.messages.create([document], { session });
  }

  findMessages(
    streamId: string,
    range: HistoryRange,
    direction: 1 | -1,
    limit: number,
  ): Promise<StoredMessage[]> {
    const sequence: Record<string, number> = { $lte: range.through };
    if (range.before !== undefined) sequence.$lt = range.before;
    if (range.after !== undefined) sequence.$gt = range.after;
    return this.messages
      .find({ stream_id: streamId, sequence })
      .sort({ sequence: direction })
      .limit(limit)
      .lean()
      .exec();
  }

  async existsBefore(
    streamId: string,
    sequence: number,
    through: number,
  ): Promise<boolean> {
    return Boolean(
      await this.messages.exists({
        stream_id: streamId,
        sequence: { $lt: sequence, $lte: through },
      }),
    );
  }

  async existsAfter(
    streamId: string,
    sequence: number,
    through: number,
  ): Promise<boolean> {
    return Boolean(
      await this.messages.exists({
        stream_id: streamId,
        sequence: { $gt: sequence, $lte: through },
      }),
    );
  }
}
