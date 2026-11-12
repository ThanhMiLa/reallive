import { invalid } from '../../common/chat-error';
export function encodeCursor(streamId: string, sequence: number): string {
  return Buffer.from(JSON.stringify([1, streamId, sequence])).toString(
    'base64url',
  );
}

export function decodeCursor(value: string, streamId: string): number {
  try {
    if (
      typeof value !== 'string' ||
      value.length > 256 ||
      !/^[A-Za-z0-9_-]+$/.test(value)
    )
      return invalid('Invalid cursor');
    const parsed: unknown = JSON.parse(
      Buffer.from(value, 'base64url').toString(),
    );
    if (
      !Array.isArray(parsed) ||
      parsed.length !== 3 ||
      parsed[0] !== 1 ||
      parsed[1] !== streamId ||
      !Number.isSafeInteger(parsed[2]) ||
      parsed[2] < 0
    )
      return invalid('Invalid cursor');
    return parsed[2] as number;
  } catch {
    return invalid('Invalid cursor');
  }
}
