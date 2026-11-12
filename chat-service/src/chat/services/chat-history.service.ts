import { Injectable } from '@nestjs/common';
import { invalid } from '../../common/chat-error';
import type { HistoryPage, HistoryQuery } from '../dto/chat-history.dto';
import type { HistoryRange } from '../interfaces/chat-history.interface';
import { ChatMapper } from '../mappers/chat.mapper';
import { ChatRepository } from '../repositories/chat.repository';
import { decodeCursor } from '../utils/chat-cursor';

@Injectable()
export class ChatHistoryService {
  private readonly pageSize = 50;

  constructor(
    private readonly repository: ChatRepository,
    private readonly mapper: ChatMapper,
  ) {}

  async history(streamId: string, query: HistoryQuery): Promise<HistoryPage> {
    if (query.before !== undefined && query.after !== undefined)
      invalid('Use before or after, not both');
    const counter = await this.repository.findCounterBySessionId(streamId);
    const range = this.parseRange(streamId, query, counter?.value ?? 0);
    const direction = range.after === undefined ? -1 : 1;
    const rows = await this.repository.findMessages(
      streamId,
      range,
      direction,
      this.pageSize,
    );
    if (direction === -1) rows.reverse();

    // Empty boundary pages must still permit navigating back into retained history.
    const first =
      rows[0]?.sequence ??
      (range.after === undefined
        ? (range.before ?? 0)
        : Math.min(range.after, range.through) + 1);
    const last =
      rows.at(-1)?.sequence ??
      (range.before === undefined
        ? Math.min(range.after ?? range.through, range.through)
        : Math.max(0, range.before - 1));
    const [hasOlder, hasNewer] = await Promise.all([
      this.repository.existsBefore(streamId, first, range.through),
      this.repository.existsAfter(streamId, last, range.through),
    ]);
    return this.mapper.toHistoryPage(streamId, rows, {
      olderSequence: hasOlder || rows.length ? first : null,
      newerSequence: last,
      through: range.through,
      hasOlder,
      hasNewer,
    });
  }

  private parseRange(
    streamId: string,
    query: HistoryQuery,
    head: number,
  ): HistoryRange {
    const through =
      query.through === undefined
        ? head
        : decodeCursor(query.through, streamId);
    if (through > head) invalid('Cursor is ahead of this stream');
    return {
      through,
      before:
        query.before === undefined
          ? undefined
          : decodeCursor(query.before, streamId),
      after:
        query.after === undefined
          ? undefined
          : decodeCursor(query.after, streamId),
    };
  }
}
