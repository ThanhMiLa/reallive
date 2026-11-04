import { isAllowedMediamtxPlaybackUrl } from './mediamtx-playback-url';

const BASE = 'http://media-server:8888';
const SESSION = '22222222-2222-4222-8222-222222222222';
const VALID = `${BASE}/live/${SESSION}/index.m3u8`;

describe('isAllowedMediamtxPlaybackUrl', () => {
  it('accepts the exact configured MediaMTX origin and session path', () => {
    expect(isAllowedMediamtxPlaybackUrl(VALID, SESSION, BASE)).toBe(true);
    expect(isAllowedMediamtxPlaybackUrl(VALID, SESSION, `${BASE}/`)).toBe(true);
  });

  it('rejects a different host, port, protocol or session path', () => {
    expect(
      isAllowedMediamtxPlaybackUrl(
        `http://media-server:8888/live/33333333-3333-4333-8333-333333333333/index.m3u8`,
        SESSION,
        BASE,
      ),
    ).toBe(false);
    expect(
      isAllowedMediamtxPlaybackUrl(
        `http://127.0.0.1:8888/live/${SESSION}/index.m3u8`,
        SESSION,
        BASE,
      ),
    ).toBe(false);
    expect(
      isAllowedMediamtxPlaybackUrl(
        `${BASE}:9999/live/${SESSION}/index.m3u8`,
        SESSION,
        BASE,
      ),
    ).toBe(false);
    expect(
      isAllowedMediamtxPlaybackUrl(
        `https://media-server:8888/live/${SESSION}/index.m3u8`,
        SESSION,
        BASE,
      ),
    ).toBe(false);
    expect(
      isAllowedMediamtxPlaybackUrl(
        `${BASE}/live/${SESSION}/index.m3u8/extra`,
        SESSION,
        BASE,
      ),
    ).toBe(false);
  });

  it('rejects prefix attacks and non-origin URLs', () => {
    expect(
      isAllowedMediamtxPlaybackUrl(
        `http://media-server:8888.evil.example/live/${SESSION}/index.m3u8`,
        SESSION,
        BASE,
      ),
    ).toBe(false);
    expect(
      isAllowedMediamtxPlaybackUrl(
        `http://user:password@media-server:8888/live/${SESSION}/index.m3u8`,
        SESSION,
        BASE,
      ),
    ).toBe(false);
    expect(
      isAllowedMediamtxPlaybackUrl(`${VALID}?token=arbitrary`, SESSION, BASE),
    ).toBe(false);
    expect(
      isAllowedMediamtxPlaybackUrl(`${VALID}#fragment`, SESSION, BASE),
    ).toBe(false);
    expect(isAllowedMediamtxPlaybackUrl('not-a-url', SESSION, BASE)).toBe(
      false,
    );
    expect(
      isAllowedMediamtxPlaybackUrl(
        'file:///tmp/live/index.m3u8',
        SESSION,
        BASE,
      ),
    ).toBe(false);
  });

  it('rejects a base URL with a path', () => {
    expect(
      isAllowedMediamtxPlaybackUrl(
        `http://media-server:8888/live/live/${SESSION}/index.m3u8`,
        SESSION,
        `${BASE}/live`,
      ),
    ).toBe(false);
  });
});
