import { Injectable } from '@nestjs/common';
import { unavailable } from '../../common/chat-error';
import { ChatRepository } from '../repositories/chat.repository';

@Injectable()
export class ChatDatabaseService {
  constructor(private readonly repository: ChatRepository) {}

  async ready(): Promise<void> {
    if (!this.repository.isConnected()) throw unavailable();
    if (!(await this.repository.getReplicaSetName()))
      throw new Error('Chat requires a MongoDB replica set');
    const indexes = await this.repository.getMessageIndexes();
    for (const required of [
      { stream_id: 1, sequence: 1 },
      { stream_id: 1, user_id: 1, client_message_id: 1 },
    ]) {
      if (
        !indexes.some(
          (index) =>
            index.unique &&
            JSON.stringify(index.key) === JSON.stringify(required),
        )
      )
        throw new Error(
          'Required chat indexes are missing; run npm run db:indexes',
        );
    }
  }
}
