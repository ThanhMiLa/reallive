import type Hls from 'hls.js'

export type Range = { start: number; end: number }
export type Timeline = { edge: number; ranges: Range[]; start: number; sync: number | null }

export function setLiveLatency(hls: Hls | null, seconds: number) {
  if (hls) hls.config.liveMaxLatencyDuration = seconds
}

export function readTimeline(hls: Hls | null, video: HTMLVideoElement): Timeline | null {
  const details = hls?.latestLevelDetails
  const start = details?.fragmentStart
  const edge = details?.edge
  if (start == null || edge == null || !Number.isFinite(start) || !Number.isFinite(edge) || edge <= start) return null
  const ranges: Range[] = []
  for (let i = 0; i < video.seekable.length; i++) {
    const from = Math.max(start, video.seekable.start(i))
    const to = Math.min(edge, video.seekable.end(i))
    if (Number.isFinite(from) && Number.isFinite(to) && to > from) ranges.push({ start: from, end: to })
  }
  if (!ranges.length) return null
  const sync = hls?.liveSyncPosition
  return { edge, start: ranges[0].start, ranges, sync: sync != null && Number.isFinite(sync) ? sync : null }
}

// Avoid the exact end of a seekable range, which may be outside buffered media.
export function clampToRanges(value: number, ranges: Range[]): number | null {
  if (!Number.isFinite(value) || !ranges.length) return null
  let nearest: number | null = null
  let distance = Infinity
  for (const range of ranges) {
    const end = Math.max(range.start, range.end - Math.min(0.05, (range.end - range.start) / 2))
    const candidate = Math.max(range.start, Math.min(end, value))
    if (Math.abs(candidate - value) < distance) {
      nearest = candidate
      distance = Math.abs(candidate - value)
    }
  }
  return nearest
}
