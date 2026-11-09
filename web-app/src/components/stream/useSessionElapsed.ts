import { useEffect, useState } from 'react'

export interface SessionClock {
  startedAt: string | null
  serverTime: string
  endedAt: string | null
  receivedAt: number
}

export function getSessionElapsed(clock: SessionClock, now: number): number | null {
  const start = Date.parse(clock.startedAt ?? '')
  const end = clock.endedAt
    ? Date.parse(clock.endedAt)
    : Date.parse(clock.serverTime) + Math.max(0, now - clock.receivedAt)
  return Number.isFinite(start) && Number.isFinite(end)
    ? Math.max(0, (end - start) / 1000)
    : null
}

export function useSessionElapsed(clock?: SessionClock): number | null {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const tick = () => setNow(Date.now())
    const timer = setInterval(tick, 1000)
    document.addEventListener('visibilitychange', tick)
    return () => {
      clearInterval(timer)
      document.removeEventListener('visibilitychange', tick)
    }
  }, [])
  return clock ? getSessionElapsed(clock, Math.max(now, clock.receivedAt)) : null
}
