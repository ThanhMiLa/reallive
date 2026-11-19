import { useEffect, useRef } from 'react'
import { isAxiosError } from 'axios'
import { Loader2, RefreshCw, Users } from 'lucide-react'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { useChannelModeratorIds } from '@/hooks/useChannelModeration'
import { useStreamViewers } from '@/hooks/useStreamViewers'
import { useAuthStore } from '@/stores/auth.store'
import type { StreamStatus } from '@/types/stream.types'

interface Props {
  userId: string
  sessionId?: string
  channelId?: string
  status?: StreamStatus
  currentViewerCount: number | null
  lastEndedAt: string | null
}

export function StreamViewersPanel({ userId, sessionId, channelId, status, currentViewerCount, lastEndedAt }: Props) {
  const active = status === 'LIVE' || status === 'RECONNECTING'
  const moderators = useChannelModeratorIds(userId, active && sessionId ? channelId : undefined)
  const {
    data, items, isPending, isError, error, isSyncing, isFetching,
    hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage, refresh,
  } = useStreamViewers(userId, sessionId, active, currentViewerCount)
  const scrollContainerRef = useRef<HTMLDivElement>(null)
  const sentinelRef = useRef<HTMLDivElement>(null)
  const pageCount = data?.pages.length

  useEffect(() => {
    const root = scrollContainerRef.current
    const sentinel = sentinelRef.current
    if (
      !active || !hasNextPage || isFetching || isFetchNextPageError ||
      !root || !sentinel || typeof IntersectionObserver === 'undefined'
    ) return

    let observing = true
    const observer = new IntersectionObserver((entries) => {
      if (observing && entries.some((entry) => entry.isIntersecting)) {
        void fetchNextPage({ cancelRefetch: false })
      }
    }, { root, rootMargin: '0px 0px 200px 0px' })
    observer.observe(sentinel)
    return () => {
      observing = false
      observer.disconnect()
    }
    // Recheck visibility after each page, even when it adds no new rows.
  }, [active, fetchNextPage, hasNextPage, isFetching, isFetchNextPageError, pageCount])

  const sessionExpired = isAxiosError(error) && error.response?.status === 401
  const refreshViewers = () => {
    const container = scrollContainerRef.current
    if (container) container.scrollTop = 0
    void refresh()
  }
  const loadMore = () => void fetchNextPage({ cancelRefetch: false })
  const initialError = isError && items.length === 0 && !isFetchNextPageError
  const statusClass = 'flex min-h-full flex-col items-center justify-center gap-2 p-4 text-center text-xs text-gray-400'

  return (
    <section
      aria-label="Active viewers"
      className="flex min-h-[260px] max-h-[420px] min-w-0 flex-1 flex-col overflow-hidden rounded-xl border border-brand-border bg-brand-card xl:max-h-none"
    >
      <div className="flex items-center justify-between gap-2 border-b border-brand-border px-4 py-3">
        <div className="flex min-w-0 items-center gap-2">
          <Users aria-hidden="true" className="h-4 w-4 shrink-0 text-gray-400" />
          <h2 className="font-outfit text-sm font-bold text-white">Viewers</h2>
          {active && (
            <span
              aria-label="Current viewer count"
              className="rounded-full border border-brand-border px-2 py-0.5 text-[11px] tabular-nums text-gray-300"
            >
              {currentViewerCount != null ? currentViewerCount.toLocaleString() : '—'}
            </span>
          )}
        </div>
        <button
          type="button"
          aria-label="Refresh viewers"
          disabled={!active || !sessionId || isFetching}
          onClick={refreshViewers}
          className="shrink-0 cursor-pointer rounded-lg p-1.5 text-gray-400 transition enabled:hover:bg-white/5 enabled:hover:text-white focus-visible:outline-2 focus-visible:outline-[#ff2b42] disabled:cursor-default disabled:opacity-50"
        >
          <RefreshCw aria-hidden="true" className={`h-4 w-4 ${isFetching && !isFetchingNextPage ? 'motion-safe:animate-spin' : ''}`} />
        </button>
      </div>

      <div
        ref={scrollContainerRef}
        role="group"
        aria-label="Viewer list"
        tabIndex={items.length > 0 ? 0 : undefined}
        className="min-h-0 flex-1 overflow-y-auto bg-[#08080a] focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-[#ff2b42]"
      >
        {!active ? (
          <p role="status" className={statusClass}>
            {status === 'ENDED' || lastEndedAt ? 'This stream has ended.' : 'Viewers appear here when your stream is live.'}
          </p>
        ) : isPending ? (
          <div role="status" className={statusClass}>
            <Loader2 aria-hidden="true" className="h-4 w-4 motion-safe:animate-spin" />
            Loading viewers…
          </div>
        ) : initialError ? (
          <div className={statusClass}>
            <p role="status">
              {sessionExpired ? 'Your session has expired.' : isSyncing
                ? 'Viewer list is syncing. Try again in a moment.' : 'Unable to load viewers.'}
            </p>
            {sessionExpired ? (
              <Button type="button" variant="secondary" size="sm" onClick={() => useAuthStore.getState().openAuthModal('login')}>
                Log in
              </Button>
            ) : (
              <Button type="button" variant="secondary" size="sm" disabled={isFetching} onClick={refreshViewers}>
                Try again
              </Button>
            )}
          </div>
        ) : items.length === 0 && !hasNextPage && !isFetchNextPageError ? (
          <div role="status" className={statusClass}>
            <p>No viewers right now.</p>
            {status === 'RECONNECTING' && <p>Viewers may temporarily disappear while reconnecting.</p>}
          </div>
        ) : (
          <>
            <ul aria-label="Viewers">
              {items.map((viewer) => {
                const isModerator = moderators.isSuccess && moderators.data.has(viewer.userId)
                const nameColor = isModerator ? 'text-purple-400 text-shadow-moderator' : 'text-gray-100'
                return (
                  <li key={viewer.userId} className="flex min-w-0 items-center gap-2.5 px-3 py-2">
                    <Avatar url={viewer.avatarUrl} name={viewer.displayName} role={isModerator ? 'moderator' : undefined} />
                    <p className={`min-w-0 flex-1 truncate text-xs font-medium ${nameColor}`}>{viewer.displayName}</p>
                    {isModerator && <span className="sr-only">Channel moderator</span>}
                  </li>
                )
              })}
            </ul>
            {isFetchingNextPage ? (
              <div role="status" className="flex items-center justify-center gap-2 px-3 py-2 text-xs text-gray-400">
                <Loader2 aria-hidden="true" className="h-3.5 w-3.5 motion-safe:animate-spin" />
                Loading more…
              </div>
            ) : isFetchNextPageError ? (
              <div className="flex flex-col items-center gap-2 px-3 py-2 text-center text-xs text-gray-400">
                <p role="status">Couldn't load more viewers.</p>
                <Button type="button" variant="secondary" size="sm" disabled={isFetching} onClick={loadMore}>Retry</Button>
              </div>
            ) : hasNextPage ? (
              <div className="flex justify-center px-3 py-2">
                <Button type="button" variant="secondary" size="sm" disabled={isFetching} onClick={loadMore}>Load more</Button>
              </div>
            ) : null}
            <div ref={sentinelRef} aria-hidden="true" className="h-1" />
          </>
        )}
      </div>
    </section>
  )
}
