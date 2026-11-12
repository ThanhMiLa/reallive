export interface HistoryRange {
  through: number;

  before?: number;

  after?: number;
}

export interface HistoryPageBounds {
  olderSequence: number | null;

  newerSequence: number;

  through: number;

  hasOlder: boolean;

  hasNewer: boolean;
}
