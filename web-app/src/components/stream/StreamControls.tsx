import { useCallback, useEffect, useRef, useState } from 'react'
import type { RefObject } from 'react'
import Hls from 'hls.js'
import { Maximize, Minimize, Pause, Play, Volume2, VolumeX } from 'lucide-react'
import { clampToRanges, readTimeline, setLiveLatency } from './streamTimeline'
import type { Timeline } from './streamTimeline'
import './stream-controls.css'

type Props = {
  videoRef: RefObject<HTMLVideoElement | null>
  containerRef: RefObject<HTMLDivElement | null>
  hls: Hls | null
  failed: boolean
  duration?: string
  onNotice: (message: string) => void
}

export function StreamControls({ videoRef, containerRef, hls, failed, onNotice, duration = '--:--:--' }: Props) {
  const [timeline, setTimeline] = useState<Timeline | null>(null)
  const [time, setTime] = useState(0)
  const [paused, setPaused] = useState(true)
  const [waiting, setWaiting] = useState(false)
  const [muted, setMuted] = useState(false)
  const [volume, setVolume] = useState(1)
  const [fullscreen, setFullscreen] = useState(false)
  const [visible, setVisible] = useState(true)
  const [focused, setFocused] = useState(false)
  const pendingResume = useRef(false)
  const lastAudibleVolume = useRef(1)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const resumeAtLive = useCallback(() => {
    const video = videoRef.current
    if (!video || !hls || failed) return false
    const latest = readTimeline(hls, video)
    if (!latest || latest.sync == null) return false
    const target = clampToRanges(latest.sync, latest.ranges)
    if (target == null) return false
    pendingResume.current = false
    setLiveLatency(hls, 4)
    video.currentTime = target
    void video.play().catch(() => onNotice('Could not resume playback. Press play to try again.'))
    return true
  }, [failed, hls, onNotice, videoRef])

  const update = useCallback(() => {
    const video = videoRef.current
    if (!video) return
    if (pendingResume.current && resumeAtLive()) {
      onNotice('The earlier position is no longer available. Returned to live.')
    }
    setTimeline(readTimeline(hls, video))
    setTime(video.currentTime)
    setPaused(video.paused)
    setMuted(video.muted)
    setVolume(video.volume)
    if (video.volume > 0) lastAudibleVolume.current = video.volume
  }, [hls, onNotice, resumeAtLive, videoRef])

  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    pendingResume.current = false
    const events = ['timeupdate', 'progress', 'durationchange', 'loadedmetadata', 'play', 'pause', 'seeking', 'seeked', 'volumechange', 'canplay']
    events.forEach((event) => video.addEventListener(event, update))
    const wait = () => setWaiting(true)
    const playing = () => { setWaiting(false); update() }
    video.addEventListener('waiting', wait)
    video.addEventListener('playing', playing)
    const visibility = () => { if (!document.hidden) update() }
    document.addEventListener('visibilitychange', visibility)
    const playlist = () => update()
    hls?.on(Hls.Events.LEVEL_UPDATED, playlist)
    update()
    return () => {
      pendingResume.current = false
      events.forEach((event) => video.removeEventListener(event, update))
      video.removeEventListener('waiting', wait)
      video.removeEventListener('playing', playing)
      document.removeEventListener('visibilitychange', visibility)
      hls?.off?.(Hls.Events.LEVEL_UPDATED, playlist)
    }
  }, [hls, update, videoRef])

  useEffect(() => {
    const change = () => setFullscreen(document.fullscreenElement === containerRef.current)
    document.addEventListener('fullscreenchange', change)
    return () => document.removeEventListener('fullscreenchange', change)
  }, [containerRef])

  const reveal = useCallback(() => {
    setVisible(true)
    if (timer.current) clearTimeout(timer.current)
    if (!paused && !failed && !focused) timer.current = setTimeout(() => setVisible(false), 3000)
  }, [paused, failed, focused])
  useEffect(() => {
    if (timer.current) clearTimeout(timer.current)
    if (!paused && !failed && !focused) timer.current = setTimeout(() => setVisible(false), 3000)
    return () => { if (timer.current) clearTimeout(timer.current) }
  }, [paused, failed, focused])
  useEffect(() => {
    const container = containerRef.current
    if (!container) return
    container.addEventListener('pointermove', reveal)
    container.addEventListener('touchstart', reveal)
    container.addEventListener('keydown', reveal)
    return () => {
      container.removeEventListener('pointermove', reveal)
      container.removeEventListener('touchstart', reveal)
      container.removeEventListener('keydown', reveal)
    }
  }, [containerRef, reveal])

  const setManual = () => {
    pendingResume.current = false
    setLiveLatency(hls, Infinity)
  }
  const goLive = () => {
    if (!resumeAtLive()) return false
    update()
    return true
  }
  const togglePlay = () => {
    const video = videoRef.current
    if (!video) return
    if (!video.paused) { setManual(); video.pause(); return }
    if (goLive()) return
    void video.play().catch(() => {})
  }
  const live = !!timeline && timeline.sync != null && !paused && !waiting && time >= timeline.sync - 2
  const button = 'stream-control-button'

  return (
    <div
      className={`stream-controls ${visible || focused || paused || failed ? '' : 'stream-controls-hidden'}`}
      onPointerMove={reveal}
      onTouchStart={reveal}
      onKeyDown={reveal}
      onFocus={() => { setFocused(true); reveal() }}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) {
          setFocused(false)
          reveal()
        }
      }}
    >
      <div className="stream-control-row">
        <button type="button" className={button} onClick={togglePlay} aria-label={paused ? 'Play' : 'Pause'}>{paused ? <Play /> : <Pause />}</button>
        <button
          type="button"
          className={button}
          onClick={() => {
            const video = videoRef.current
            if (!video) return
            if (video.muted || video.volume === 0) {
              if (video.volume === 0) video.volume = lastAudibleVolume.current
              video.muted = false
            } else {
              video.muted = true
            }
            update()
          }}
          aria-label={muted || volume === 0 ? 'Unmute' : 'Mute'}
        >
          {muted || volume === 0 ? <VolumeX /> : <Volume2 />}
        </button>
        <input className="stream-volume" aria-label="Volume" type="range" min="0" max="1" step="0.05" value={muted ? 0 : volume} onChange={(event) => { const video = videoRef.current; if (video) { video.volume = Number(event.target.value); video.muted = video.volume === 0 } }} />
        <span className="stream-session-duration" aria-label="Stream duration">{duration}</span>
        <div className="stream-control-spacer" />
        <button type="button" className={`stream-live ${live ? 'stream-live-active' : ''}`} disabled={!timeline || timeline.sync == null} onClick={goLive} aria-label="Go to live"><span /> LIVE</button>
        {typeof document !== 'undefined' && document.fullscreenEnabled && <button type="button" className={button} aria-label={fullscreen ? 'Exit fullscreen' : 'Enter fullscreen'} onClick={() => { if (fullscreen) void document.exitFullscreen().catch(() => {}); else void containerRef.current?.requestFullscreen().catch(() => {}) }}>{fullscreen ? <Minimize /> : <Maximize />}</button>}
      </div>
    </div>
  )
}
