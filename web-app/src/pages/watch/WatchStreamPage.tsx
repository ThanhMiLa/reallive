import { lazy, Suspense, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  AlertCircle,
  Lock,
  Radio,
  RefreshCw,
  Tv,
} from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { StreamViewerCount } from '@/components/stream/StreamViewerCount'
import { useWatchStream } from '@/hooks/useWatchStream'
import { StreamChatPanel } from '@/components/chat/StreamChatPanel'

const StreamPlayer = lazy(() =>
  import('@/components/stream/StreamPlayer').then((module) => ({
    default: module.StreamPlayer,
  })),
)

function ChannelAvatar({
  name,
  avatarUrl,
  isLive,
}: {
  name: string
  avatarUrl: string | null
  isLive: boolean
}) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const trimmedUrl = avatarUrl?.trim() || null
  const hasAvatar = Boolean(trimmedUrl && failedUrl !== trimmedUrl)
  const initial = name.trim().charAt(0).toUpperCase() || 'L'

  return (
    <div className="relative flex-shrink-0">
      <div
        className={`w-12 h-12 sm:w-14 sm:h-14 rounded-full p-0.5 ${
          isLive
            ? 'bg-[#e91916]'
            : 'bg-[#1f1f26]'
        }`}
      >
        {hasAvatar ? (
          <img
            src={trimmedUrl!}
            alt={name}
            onError={() => setFailedUrl(trimmedUrl)}
            className="w-full h-full rounded-full object-cover bg-[#101014] border-2 border-[#08080a]"
          />
        ) : (
          <div className="w-full h-full rounded-full bg-gradient-to-tr from-rose-600 to-indigo-600 flex items-center justify-center text-base sm:text-lg font-bold text-white border-2 border-[#08080a]">
            {initial}
          </div>
        )}
      </div>

      {isLive && (
        <span className="absolute -bottom-1.5 left-1/2 -translate-x-1/2 bg-[#e91916] text-white text-[10px] font-extrabold uppercase tracking-wider px-1.5 py-0.2 rounded shadow">
          LIVE
        </span>
      )}
    </div>
  )
}

export function WatchStreamPage() {
  const { sessionId: rawSessionId = '' } = useParams<{ sessionId: string }>()
  const {
    sessionId,
    isValidUuid,
    isCheckingAuth,
    isAuthenticated,
    openAuthModal,
    metadata,
    details,
    session,
    channel,
    category,
    canPlay,
    blockingStatus,
    statusRefreshError,
    refreshMetadata,
    handleAccessError,
    retryAll,
    retryDetails,
  } = useWatchStream(rawSessionId)

  if (isCheckingAuth) {
    return (
      <main className="min-h-0 min-w-0 flex-1 overflow-y-auto overscroll-contain p-6 sm:p-8 flex items-center justify-center select-none">
        <div role="status" className="flex flex-col items-center gap-3 text-center">
          <RefreshCw className="w-7 h-7 animate-spin text-[#ff2b42]" />
          <p className="text-sm text-gray-400">Checking your session…</p>
        </div>
      </main>
    )
  }

  if (!isValidUuid) {
    return (
      <main className="min-h-0 min-w-0 flex-1 overflow-y-auto overscroll-contain p-6 sm:p-8 flex items-center justify-center select-none">
        <div
          role="alert"
          className="max-w-md w-full rounded-2xl border border-[#1f1f26] bg-[#08080a] p-8 text-center"
        >
          <AlertCircle className="w-10 h-10 text-rose-500 mx-auto mb-3" />
          <h1 className="text-lg font-bold text-white font-outfit">
            Stream session not found
          </h1>
          <p className="text-xs text-gray-400 mt-1.5 mb-5">
            The stream link is invalid or no longer available.
          </p>
          <Link
            to="/browse/livestreams"
            className="inline-flex items-center justify-center rounded-lg bg-[#ff2b42] hover:bg-[#e02438] text-white text-xs font-semibold px-4 py-2.5 transition"
          >
            Browse live streams
          </Link>
        </div>
      </main>
    )
  }

  if (!isAuthenticated || blockingStatus === 401) {
    return (
      <main className="min-h-0 min-w-0 flex-1 overflow-y-auto overscroll-contain p-6 sm:p-8 flex items-center justify-center select-none">
        <div className="max-w-md w-full rounded-2xl border border-[#1f1f26] bg-[#08080a] p-8 text-center">
          <Lock className="w-10 h-10 text-[#ff2b42] mx-auto mb-3" />
          <h1 className="text-lg font-bold text-white font-outfit">
            Sign in to watch this stream
          </h1>
          <p className="text-xs text-gray-400 mt-1.5 mb-5">
            Please log in with your RealLive account to watch this broadcast.
          </p>
          <div className="flex items-center justify-center gap-3">
            <Button onClick={() => openAuthModal('login')}>Log in</Button>
          </div>
        </div>
      </main>
    )
  }

  if (blockingStatus === 403) {
    return (
      <main className="min-h-0 min-w-0 flex-1 overflow-y-auto overscroll-contain p-6 sm:p-8 flex items-center justify-center select-none">
        <div
          role="alert"
          className="max-w-md w-full rounded-2xl border border-[#1f1f26] bg-[#08080a] p-8 text-center"
        >
          <Lock className="w-10 h-10 text-rose-500 mx-auto mb-3" />
          <h1 className="text-lg font-bold text-white font-outfit">
            Stream unavailable
          </h1>
          <p className="text-xs text-gray-400 mt-1.5 mb-5">
            You do not have permission to view this stream session.
          </p>
          <div className="flex items-center justify-center gap-3">
            <Button variant="secondary" onClick={retryAll}>
              Try again
            </Button>
            <Link
              to="/browse/livestreams"
              className="inline-flex items-center justify-center rounded-lg bg-[#ff2b42] hover:bg-[#e02438] text-white text-xs font-semibold px-4 py-2.5 transition"
            >
              Browse live streams
            </Link>
          </div>
        </div>
      </main>
    )
  }

  if (blockingStatus === 404) {
    return (
      <main className="min-h-0 min-w-0 flex-1 overflow-y-auto overscroll-contain p-6 sm:p-8 flex items-center justify-center select-none">
        <div
          role="alert"
          className="max-w-md w-full rounded-2xl border border-[#1f1f26] bg-[#08080a] p-8 text-center"
        >
          <AlertCircle className="w-10 h-10 text-rose-500 mx-auto mb-3" />
          <h1 className="text-lg font-bold text-white font-outfit">
            Stream session not found
          </h1>
          <p className="text-xs text-gray-400 mt-1.5 mb-5">
            This stream session does not exist or is no longer available.
          </p>
          <div className="flex items-center justify-center gap-3">
            <Button variant="secondary" onClick={retryAll}>
              Try again
            </Button>
            <Link
              to="/browse/livestreams"
              className="inline-flex items-center justify-center rounded-lg bg-[#ff2b42] hover:bg-[#e02438] text-white text-xs font-semibold px-4 py-2.5 transition"
            >
              Browse live streams
            </Link>
          </div>
        </div>
      </main>
    )
  }

  if (metadata.isPending && !session) {
    return (
      <main className="watch-stream-scroll min-h-0 min-w-0 flex-1 overflow-y-auto overscroll-contain xl:overflow-hidden select-none">
        <div className="flex min-h-full flex-col xl:h-full xl:min-h-0 xl:flex-row">
          <div
            role="status"
            aria-label="Loading stream"
            className="watch-stream-scroll min-h-0 min-w-0 flex-1 p-4 sm:p-6 space-y-4 xl:overflow-y-auto xl:overscroll-contain"
          >
            <div className="w-full aspect-video rounded-xl bg-[#101014] border border-[#1f1f26] animate-pulse" />
            <div className="flex items-start gap-4 pt-1">
              <div className="w-12 h-12 sm:w-14 sm:h-14 rounded-full bg-[#14141a] animate-pulse flex-shrink-0" />
              <div className="flex-1 space-y-2.5 py-1">
                <div className="h-4 w-40 bg-[#14141a] rounded animate-pulse" />
                <div className="h-4 w-64 bg-[#14141a] rounded animate-pulse" />
                <div className="h-5 w-24 bg-[#14141a] rounded animate-pulse" />
              </div>
            </div>
          </div>
          <StreamChatPanel sessionId={sessionId} onAccessError={handleAccessError} />
        </div>
      </main>
    )
  }

  if (metadata.isError && !session) {
    return (
      <main className="min-h-0 min-w-0 flex-1 overflow-y-auto overscroll-contain p-6 sm:p-8 flex items-center justify-center select-none">
        <div
          role="alert"
          className="max-w-md w-full rounded-2xl border border-[#1f1f26] bg-[#08080a] p-8 text-center"
        >
          <AlertCircle className="w-10 h-10 text-rose-500 mx-auto mb-3" />
          <h1 className="text-lg font-bold text-white font-outfit">
            Unable to load livestream
          </h1>
          <p className="text-xs text-gray-400 mt-1.5 mb-5">
            Could not fetch stream information from the server. Please check your
            connection and try again.
          </p>
          <Button onClick={retryAll}>Try again</Button>
        </div>
      </main>
    )
  }

  const status = session?.status
  const isLive = status === 'LIVE'
  const channelName = channel?.name?.trim() || 'Unknown Channel'
  const streamTitle = session?.title?.trim() || 'Untitled Stream'
  const categoryName = category?.name?.trim() || null
  const categoryLinkId = category?.id || session?.categoryId || null
  const showDetailsSkeleton = details.isPending && !channel && !details.isError

  return (
    <main className="watch-stream-scroll min-h-0 min-w-0 flex-1 overflow-y-auto overscroll-contain xl:overflow-hidden select-none bg-[#000000]">
      <div className="flex min-h-full flex-col xl:h-full xl:min-h-0 xl:flex-row">
        {/* Main Video + Stream Info Column */}
        <div className="watch-stream-scroll min-h-0 min-w-0 flex-1 p-4 sm:p-6 space-y-4 xl:overflow-y-auto xl:overscroll-contain">
          {/* Non-fatal status polling error alert (keeps video playing) */}
          {statusRefreshError && (
            <div
              role="alert"
              className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-amber-800/80 bg-amber-950/30 px-4 py-2.5 text-xs sm:text-sm text-amber-200"
            >
              <span>Unable to refresh stream status.</span>
              <button
                type="button"
                onClick={() => void refreshMetadata()}
                className="font-semibold underline hover:text-white cursor-pointer"
              >
                Try again
              </button>
            </div>
          )}

          {/* Video Player / Lifecycle Stage */}
          <section className="w-full overflow-hidden rounded-xl border border-[#1f1f26] bg-[#08080a] shadow-2xl">
            {canPlay ? (
              <Suspense
                fallback={
                  <div className="flex aspect-video items-center justify-center text-gray-400">
                    Loading player…
                  </div>
                }
              >
                <StreamPlayer
                  key={sessionId}
                  sessionId={sessionId}
                  sessionClock={{
                    startedAt: session!.startedAt,
                    serverTime: session!.serverTime,
                    endedAt: session!.endedAt,
                    receivedAt: metadata.dataUpdatedAt,
                  }}
                  mode="viewer"
                  refreshMetadata={refreshMetadata}
                  onAccessError={handleAccessError}
                />
              </Suspense>
            ) : (
              <div
                role="status"
                className="aspect-video w-full flex flex-col items-center justify-center p-6 text-center bg-[#08080a]"
              >
                {status === 'READY' ? (
                  <>
                    <div className="w-12 h-12 rounded-full bg-amber-500/10 border border-amber-500/30 flex items-center justify-center mb-3">
                      <Tv className="w-6 h-6 text-amber-400" />
                    </div>
                    <h2 className="text-lg sm:text-xl font-bold text-white font-outfit">
                      Stream has not started yet
                    </h2>
                    <p className="text-xs sm:text-sm text-gray-400 mt-1.5 max-w-md">
                      Waiting for the streamer to start broadcasting. The video
                      will appear automatically once the stream goes live.
                    </p>
                  </>
                ) : status === 'RECONNECTING' ? (
                  <>
                    <div className="w-12 h-12 rounded-full bg-amber-500/10 border border-amber-500/30 flex items-center justify-center mb-3">
                      <RefreshCw className="w-6 h-6 text-amber-400 animate-spin" />
                    </div>
                    <h2 className="text-lg sm:text-xl font-bold text-white font-outfit">
                      Waiting for the stream to reconnect
                    </h2>
                    <p className="text-xs sm:text-sm text-gray-400 mt-1.5 max-w-md">
                      The broadcast connection was interrupted. Playback will
                      resume automatically if the streamer reconnects in time.
                    </p>
                  </>
                ) : (
                  <>
                    <div className="w-12 h-12 rounded-full bg-white/5 border border-[#1f1f26] flex items-center justify-center mb-3">
                      <Radio className="w-6 h-6 text-gray-400" />
                    </div>
                    <h2 className="text-lg sm:text-xl font-bold text-white font-outfit">
                      This stream has ended
                    </h2>
                    <p className="text-xs sm:text-sm text-gray-400 mt-1.5 max-w-md">
                      {session?.endedAt
                        ? `Ended ${new Date(session.endedAt).toLocaleString()}`
                        : 'Thanks for watching! This broadcast has finished.'}
                    </p>
                  </>
                )}
              </div>
            )}
          </section>

          {/* Below Video: Channel Avatar, Channel Name, Stream Title, Category */}
          <section
            aria-label="Stream information"
            className="flex flex-col gap-3 pt-1"
          >
            <div className="flex items-start gap-3.5 sm:gap-4 min-w-0">
              {showDetailsSkeleton ? (
                <div className="w-12 h-12 sm:w-14 sm:h-14 rounded-full bg-[#14141a] animate-pulse flex-shrink-0" />
              ) : (
                <ChannelAvatar
                  name={channelName}
                  avatarUrl={channel?.avatarUrl ?? null}
                  isLive={isLive}
                />
              )}

              <div className="flex-1 min-w-0">
                <div className="flex min-w-0 items-center gap-3">
                  {/* Channel Name */}
                  {showDetailsSkeleton ? (
                    <div className="h-5 w-40 max-w-full bg-[#14141a] rounded animate-pulse" />
                  ) : (
                    <h2 className="min-w-0 truncate text-base sm:text-lg font-extrabold text-white tracking-tight font-outfit">
                      {channelName}
                    </h2>
                  )}
                  <div className="shrink-0">
                    <StreamViewerCount
                      count={session?.currentViewerCount}
                      status={status}
                      unavailable={metadata.isPending || metadata.isError}
                      variant="watch"
                    />
                  </div>
                </div>

                {/* Stream Session Title */}
                <h1 className="text-sm sm:text-base font-medium text-gray-200 mt-0.5 break-words">
                  {streamTitle}
                </h1>

                {/* Category Badge / Link */}
                {showDetailsSkeleton ? (
                  <div className="h-7 w-24 bg-[#14141a] rounded animate-pulse mt-2" />
                ) : (
                  categoryName && (
                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      {categoryLinkId ? (
                        <Link
                          to={`/browse/categories/${encodeURIComponent(categoryLinkId)}`}
                          className="inline-block text-sm font-semibold text-emerald-400 hover:text-emerald-300 bg-[#14141a] hover:bg-[#1a1a22] border border-[#1f1f26] px-3 py-1 rounded transition-colors"
                        >
                          {categoryName}
                        </Link>
                      ) : (
                        <span className="inline-block text-sm font-semibold text-emerald-400 bg-[#14141a] border border-[#1f1f26] px-3 py-1 rounded">
                          {categoryName}
                        </span>
                      )}
                    </div>
                  )
                )}
              </div>
            </div>

            {/* Details fetch error (non-blocking: video continues playing) */}
            {details.isError && (
              <div
                role="alert"
                className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-[#1f1f26] bg-[#08080a] px-4 py-2.5 text-xs text-gray-300"
              >
                <div className="flex items-center gap-2">
                  <AlertCircle className="w-4 h-4 text-rose-500 flex-shrink-0" />
                  <span>
                    Could not load channel and category details.
                  </span>
                </div>
                <button
                  type="button"
                  onClick={retryDetails}
                  disabled={details.isFetching}
                  className="inline-flex items-center gap-1.5 text-[#ff2b42] hover:underline font-semibold cursor-pointer disabled:opacity-50"
                >
                  <RefreshCw
                    className={`w-3.5 h-3.5 ${details.isFetching ? 'animate-spin' : ''}`}
                  />
                  <span>Retry</span>
                </button>
              </div>
            )}
          </section>
        </div>

        {/* Right Side Chat UI Placeholder */}
        <StreamChatPanel sessionId={sessionId} status={session?.status} streamerUserId={session?.ownerUserId} onAccessError={handleAccessError} />
      </div>
    </main>
  )
}
