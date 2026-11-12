import { Injectable } from '@nestjs/common';

@Injectable()
export class RoomQueue {
  private readonly tails = new Map<string, Promise<unknown>>();

  run<T>(id: string, action: () => Promise<T>): Promise<T> {
    const previous = this.tails.get(id) ?? Promise.resolve();
    const next = previous.catch(() => undefined).then(action);
    this.tails.set(id, next);
    void next
      .finally(() => {
        if (this.tails.get(id) === next) this.tails.delete(id);
      })
      .catch(() => undefined);
    return next;
  }
}
