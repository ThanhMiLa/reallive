import { decodeCursor, encodeCursor } from './utils/chat-cursor';
import { text, uuid } from '../common/chat-error';
import { RoomQueue } from './utils/room-queue';

const id = '462c912c-ee66-4477-9852-bed13d377297';

describe('chat boundaries', () => {
  it('counts Unicode code points and preserves literal HTML', () => {
    expect(text('😀'.repeat(500))).toHaveLength(1000);
    expect(() => text('😀'.repeat(501))).toThrow();
    expect(() => text('   ')).toThrow();
    expect(text('  <script>alert(1)</script>  ')).toBe(
      '<script>alert(1)</script>',
    );
  });
  it('rejects malformed identifiers and cross-room or malformed cursors', () => {
    expect(uuid(id.toUpperCase())).toBe(id);
    expect(() => uuid('room')).toThrow();
    const cursor = encodeCursor(id, 51);
    expect(decodeCursor(cursor, id)).toBe(51);
    expect(() => decodeCursor(cursor, 'another-room')).toThrow();
    expect(() => decodeCursor('!', id)).toThrow();
    expect(() => decodeCursor(encodeCursor(id, -1), id)).toThrow();
  });
  it('serializes a room, recovers from failure and does not block other rooms', async () => {
    const queue = new RoomQueue();
    const order: string[] = [];
    let release!: () => void;
    const first = queue.run('a', async () => {
      await new Promise<void>((r) => {
        release = r;
      });
      order.push('first');
      throw new Error('test');
    });
    const second = queue.run('a', async () => {
      order.push('second');
    });
    await queue.run('b', async () => {
      order.push('other');
    });
    expect(order).toEqual(['other']);
    release();
    await expect(first).rejects.toThrow('test');
    await second;
    expect(order).toEqual(['other', 'first', 'second']);
  });
});
