import type { Message } from './chat-message.dto';

export interface HistoryQuery {
  before?: string;

  after?: string;

  through?: string;
}

export interface HistoryPage {
  items: Message[];

  olderCursor: string | null;

  newerCursor: string | null;

  hasOlder: boolean;

  hasNewer: boolean;

  watermark: string;
}
