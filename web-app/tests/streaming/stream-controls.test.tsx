import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { useRef } from 'react'
import Hls from 'hls.js'
import { StreamControls } from '@/components/stream/StreamControls'
import { getSessionElapsed, useSessionElapsed } from '@/components/stream/useSessionElapsed'
import type { SessionClock } from '@/components/stream/useSessionElapsed'

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers() })

function mountControls() {
  const hls = {
    latestLevelDetails: { fragmentStart: 100, edge: 130 },
    liveSyncPosition: 129,
    config: { liveMaxLatencyDuration: 4 }, on: vi.fn(), off: vi.fn(),
  } as unknown as Hls
  vi.spyOn(HTMLMediaElement.prototype, 'seekable', 'get').mockReturnValue({
    length: 1, start: () => 100, end: () => 130,
  })
  const play = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
  function Player() {
    const videoRef = useRef<HTMLVideoElement>(null)
    const containerRef = useRef<HTMLDivElement>(null)
    return <div ref={containerRef}><video ref={videoRef} /><StreamControls videoRef={videoRef} containerRef={containerRef} hls={hls} failed={false} onNotice={() => {}} /></div>
  }
  const view = render(<Player />)
  return { ...view, hls, play, video: view.container.querySelector('video')! }
}

describe('live player controls', () => {
  it('has no seek bar or negative time labels and retains volume and live controls', () => {
    mountControls()
    expect(screen.queryByLabelText('Seek stream')).toBeNull()
    expect(screen.getAllByRole('slider')).toHaveLength(1)
    expect(screen.getByLabelText('Volume')).toBeTruthy()
    expect(screen.getByLabelText('Go to live')).toBeTruthy()
    expect(screen.queryByText(/-\d\d:\d\d:\d\d/)).toBeNull()
  })

  it('resumes near the current HLS edge instead of replaying the paused position', () => {
    const { video, hls, play } = mountControls()
    video.currentTime = 105
    fireEvent.click(screen.getByLabelText('Play'))
    expect(video.currentTime).toBe(129)
    expect(hls.config.liveMaxLatencyDuration).toBe(4)
    expect(play).toHaveBeenCalledOnce()
  })
})

describe('session duration clock', () => {
  const sample: SessionClock = {
    startedAt: '2026-11-08T00:00:00Z', serverTime: '2026-11-08T04:00:00Z',
    endedAt: null, receivedAt: Date.parse('2026-11-09T00:00:00Z'),
  }

  it('uses server time rather than the viewer clock and freezes at endedAt', () => {
    expect(getSessionElapsed(sample, sample.receivedAt)).toBe(14400)
    expect(getSessionElapsed(sample, sample.receivedAt + 61000)).toBe(14461)
    expect(getSessionElapsed({ ...sample, endedAt: '2026-11-08T02:00:00Z' }, sample.receivedAt + 999000)).toBe(7200)
    expect(getSessionElapsed({ ...sample, startedAt: null }, sample.receivedAt)).toBeNull()
  })

  it('ticks each second, catches up after tab suspension, and survives reload and polling', () => {
    vi.useFakeTimers()
    vi.setSystemTime(sample.receivedAt)
    function Clock({ clock }: { clock: SessionClock }) {
      return <span aria-label="Elapsed">{useSessionElapsed(clock)}</span>
    }
    const view = render(<Clock clock={sample} />)
    expect(screen.getByLabelText('Elapsed').textContent).toBe('14400')
    act(() => vi.advanceTimersByTime(1000))
    expect(screen.getByLabelText('Elapsed').textContent).toBe('14401')
    act(() => { vi.setSystemTime(sample.receivedAt + 61000); fireEvent(document, new Event('visibilitychange')) })
    expect(screen.getByLabelText('Elapsed').textContent).toBe('14461')
    view.unmount()
    const remount = render(<Clock clock={sample} />)
    expect(screen.getByLabelText('Elapsed').textContent).toBe('14461')
    remount.rerender(<Clock clock={{ ...sample, serverTime: '2026-11-08T04:01:01Z', receivedAt: sample.receivedAt + 61000 }} />)
    expect(screen.getByLabelText('Elapsed').textContent).toBe('14461')
  })
})
