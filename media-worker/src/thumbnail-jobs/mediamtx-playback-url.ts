export function isAllowedMediamtxPlaybackUrl(
  playbackUrl: string,
  sessionId: string,
  configuredBaseUrl: string,
): boolean {
  let source: URL;
  let base: URL;

  try {
    source = new URL(playbackUrl);
    base = new URL(configuredBaseUrl);
  } catch {
    return false;
  }

  if (
    (base.protocol !== 'http:' && base.protocol !== 'https:') ||
    base.username !== '' ||
    base.password !== '' ||
    base.search !== '' ||
    base.hash !== '' ||
    base.pathname !== '/'
  ) {
    return false;
  }

  if (
    (source.protocol !== 'http:' && source.protocol !== 'https:') ||
    source.username !== '' ||
    source.password !== '' ||
    source.search !== '' ||
    source.hash !== ''
  ) {
    return false;
  }

  if (source.origin !== base.origin) {
    return false;
  }

  return source.pathname === `/live/${sessionId}/index.m3u8`;
}
