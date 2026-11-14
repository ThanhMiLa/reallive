import { lazy, Suspense } from 'react'
import { Link } from 'react-router-dom'
import { isAxiosError } from 'axios'
import { MessageSquare, MonitorPlay, Radio } from 'lucide-react'
import { StreamChatPanel } from '@/components/chat/StreamChatPanel'
import { StreamInfoPanel } from '@/components/stream/StreamInfoPanel'
import { StreamAuthGate, StreamSetupFallback } from '@/components/stream/StreamPageStates'
import { StreamViewerCount } from '@/components/stream/StreamViewerCount'
import { useSessionElapsed, type SessionClock } from '@/components/stream/useSessionElapsed'
import { useAuthStore } from '@/stores/auth.store'
import { useStreamManager } from '@/hooks/useStreamManager'

const StreamPlayer = lazy(() =>
  import('@/components/stream/StreamPlayer').then((module) => ({ default: module.StreamPlayer })),
)

function formatDuration(seconds: number) {
  const value = Math.max(0, Math.floor(seconds))
  return [Math.floor(value / 3600), Math.floor(value / 60) % 60, value % 60]
    .map((part) => String(part).padStart(2, '0'))
    .join(':')
}

function SessionLiveDuration({ sessionClock }: { sessionClock: SessionClock }) {
  const elapsed = useSessionElapsed(sessionClock)
  return <>{elapsed == null ? '—' : formatDuration(elapsed)}</>
}

function Dashboard({ userId }: { userId: string }) {
  const {
    setup,
    current,
    categories,
    metadata,
    session,
    id,
    pending,
    lastEndedAt,
    now,
    refreshMetadata,
    saveSetup,
  } = useStreamManager(userId)

  if (setup.isPending || (setup.isError && !setup.data)) {
    return (
      <StreamSetupFallback
        title="Stream Manager"
        isPending={setup.isPending}
        isError={setup.isError}
        error={setup.error}
        onRetry={() => void setup.refetch()}
      />
    )
  }

  const streamConfig = setup.data!
  const status = session?.status
  const ended = status === 'ENDED'
  const activeSession = status === 'LIVE' || status === 'RECONNECTING'
  const authExpired = isAxiosError(metadata.error) && metadata.error.response?.status === 401
  const sessionNotFound = isAxiosError(metadata.error) && metadata.error.response?.status === 404
  const serverNow = session
    ? Date.parse(session.serverTime) +
      Math.max(0, now - (metadata.dataUpdatedAt || current.dataUpdatedAt))
    : now
  const countdown = session?.reconnectDeadlineAt
    ? formatDuration((Date.parse(session.reconnectDeadlineAt) - serverNow) / 1000).slice(3)
    : null

  const offlineHeading = streamConfig.status === 'BLOCKED' ? 'Streaming unavailable'
    : !streamConfig.hasStreamKey ? 'Complete stream setup'
      : ended || lastEndedAt ? 'Stream ended · Waiting for OBS' : 'Waiting for OBS'
  const offlineDescription = streamConfig.status === 'BLOCKED'
    ? streamConfig.blockedReason || 'Streaming access is currently blocked.'
    : !streamConfig.hasStreamKey
      ? streamConfig.categoryId
        ? 'Your stream information is saved. Generate a stream key in Stream URL & Key to connect OBS.'
        : 'Set your stream information on the right, then generate a stream key in Stream URL & Key.'
      : ended || lastEndedAt
        ? `Previous broadcast ended ${new Date(session?.endedAt || lastEndedAt!).toLocaleString()}. Start streaming in OBS when ready.`
        : 'Start streaming in OBS to go live automatically.'

  const statusLabel = status === 'LIVE' ? 'LIVE' : status === 'RECONNECTING' ? 'RECONNECTING' : 'OFFLINE'
  const statusBadgeClass =
    status === 'LIVE'
      ? 'border-red-500/40 bg-red-500/15 text-red-300'
      : status === 'RECONNECTING'
        ? 'border-amber-500/40 bg-amber-500/15 text-amber-200'
        : 'border-brand-border bg-brand-card text-gray-300'

  return (
    <main className="min-w-0 flex-1 p-2 sm:p-2.5">
      <div className="mx-auto max-w-[1920px] space-y-2">
        {metadata.isError && session && !ended && (
          <div
            role="alert"
            className="rounded-lg border border-amber-800 bg-amber-950/30 p-3 text-sm text-amber-200"
          >
            {isAxiosError(metadata.error) && metadata.error.response?.status === 401 ? (
              <>
                Your session has expired.{' '}
                <button
                  type="button"
                  className="underline"
                  onClick={() => useAuthStore.getState().openAuthModal('login')}
                >
                  Log in
                </button>
              </>
            ) : (
              <>
                Unable to refresh stream status.{' '}
                <button type="button" className="underline" onClick={() => void refreshMetadata()}>
                  Try again
                </button>
              </>
            )}
          </div>
        )}

        {status === 'RECONNECTING' && (
          <div
            role="status"
            className="rounded-lg border border-amber-800 bg-amber-950/30 p-4 text-sm text-amber-200"
          >
            OBS disconnected. Waiting for the stream to reconnect
            {countdown ? ` · ${countdown} remaining` : ''}.
          </div>
        )}

        <div className="grid min-w-0 grid-cols-1 items-start gap-2 xl:grid-cols-[minmax(0,1fr)_minmax(260px,320px)_minmax(216px,252px)]">
          {/* Column 1: Livestream Preview & Session Info */}
          <div className="min-w-0 space-y-2 transform-gpu">
            <section className="overflow-hidden rounded-xl border border-brand-border bg-brand-card">
              <div className="flex items-center gap-2 border-b border-brand-border px-4 py-3">
                <MonitorPlay className="h-4 w-4 text-gray-400" />
                <h2 className="font-outfit text-sm font-bold text-white">Stream Preview</h2>
              </div>
              {authExpired || sessionNotFound ? (
                <div className="flex aspect-video items-center justify-center bg-[#08080a] p-6 text-center text-gray-400">
                  {authExpired ? 'Log in to resume the preview.' : 'Stream session is unavailable.'}
                </div>
              ) : status === 'LIVE' && id ? (
                <Suspense
                  fallback={
                    <div className="flex aspect-video items-center justify-center bg-[#08080a] text-gray-400">
                      Loading player…
                    </div>
                  }
                >
                  <StreamPlayer
                    key={id}
                    sessionId={id}
                    sessionClock={{
                      startedAt: session!.startedAt,
                      serverTime: session!.serverTime,
                      endedAt: session!.endedAt,
                      receivedAt: metadata.dataUpdatedAt || current.dataUpdatedAt,
                    }}
                    refreshMetadata={refreshMetadata}
                  />
                </Suspense>
              ) : (
                <div className="flex aspect-video flex-col items-center justify-center bg-[#08080a] p-6 text-center">
                  <p className="font-outfit text-lg font-semibold text-white">
                    {status === 'RECONNECTING' ? 'Waiting for the stream to reconnect' : offlineHeading}
                  </p>
                  <p className="mt-2 max-w-md text-sm leading-6 text-gray-400">
                    {status === 'RECONNECTING'
                      ? 'Reconnect using OBS within 60 seconds to continue this session.'
                      : offlineDescription}
                  </p>
                  {status !== 'RECONNECTING' && !streamConfig.hasStreamKey && streamConfig.status !== 'BLOCKED' && (
                    <Link
                      to="/dashboard/stream-key"
                      className="mt-4 inline-flex items-center rounded-lg border border-brand-border bg-[#101014] px-3.5 py-2 text-xs font-semibold text-gray-200 hover:bg-[#18181f] hover:text-white transition"
                    >
                      Go to Stream URL &amp; Key
                    </Link>
                  )}
                </div>
              )}
            </section>

            <section
              aria-label="Session Info"
              className="rounded-xl border border-brand-border bg-brand-card"
            >
              <div className="flex items-center gap-2 border-b border-brand-border px-4 py-3">
                <Radio className="h-4 w-4 text-gray-400" />
                <h2 className="font-outfit text-sm font-bold text-white">Session Info</h2>
              </div>
              <div className="grid grid-cols-3 divide-x divide-brand-border">
                <div className="px-4 py-3">
                  <span
                    className={`inline-block rounded border px-2 py-0.5 text-[11px] font-semibold ${statusBadgeClass}`}
                  >
                    {statusLabel}
                  </span>
                  <p className="mt-1.5 text-xs text-gray-400">Session</p>
                </div>
                <div className="px-4 py-3">
                  <p className="text-sm font-bold tabular-nums text-white">
                    {activeSession && session?.currentViewerCount != null
                      ? session.currentViewerCount.toLocaleString()
                      : '—'}
                  </p>
                  <p className="mt-1 text-xs text-gray-400">Viewers</p>
                </div>
                <div className="px-4 py-3">
                  <p className="text-sm font-bold tabular-nums text-white">
                    {activeSession && session ? (
                      <SessionLiveDuration
                        sessionClock={{
                          startedAt: session.startedAt,
                          serverTime: session.serverTime,
                          endedAt: session.endedAt,
                          receivedAt: metadata.dataUpdatedAt || current.dataUpdatedAt,
                        }}
                      />
                    ) : (
                      '—'
                    )}
                  </p>
                  <p className="mt-1 text-xs text-gray-400">Time Live</p>
                </div>
              </div>
            </section>
          </div>

          {/* Column 2: Chat */}
          <aside
            aria-label="Stream chat"
            className="flex h-[520px] min-w-0 flex-col overflow-hidden transform-gpu rounded-xl border border-brand-border bg-brand-card xl:sticky xl:top-2.5 xl:h-[calc(100dvh-1.25rem)] xl:min-h-[540px]"
          >
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-brand-border px-4 py-3">
              <div className="flex items-center gap-2">
                <MessageSquare className="h-4 w-4 text-gray-400" />
                <h2 className="font-outfit text-sm font-bold text-white">Stream Chat</h2>
              </div>
              {session && (
                <StreamViewerCount
                  count={session.currentViewerCount}
                  status={status}
                  unavailable={metadata.isPending || metadata.isError}
                />
              )}
            </div>
            {id && activeSession ? (
              <StreamChatPanel
                embedded
                sessionId={id}
                status={status}
                streamerUserId={session?.ownerUserId ?? userId}
              />
            ) : (
              <div
                role="status"
                className="flex flex-1 items-center justify-center bg-[#08080a] p-5 text-center text-sm text-gray-400"
              >
                {status === 'ENDED' || lastEndedAt
                  ? 'This stream has ended. Chat will open automatically when a new stream starts.'
                  : 'Chat opens automatically when you start streaming in OBS.'}
              </div>
            )}
          </aside>

          {/* Column 3: Stream Information */}
          <div className="min-w-0 xl:sticky xl:top-2.5">
            <StreamInfoPanel
              initial={{
                title: streamConfig.title || '',
                categoryId: streamConfig.categoryId || '',
              }}
              categories={categories.data || []}
              categoriesLoading={categories.isPending}
              categoriesError={categories.isError}
              onRetryCategories={() => void categories.refetch()}
              configStatus={streamConfig.status}
              pending={pending === 'save'}
              onSubmit={saveSetup}
            />
          </div>
        </div>
      </div>
    </main>
  )
}

export function StreamerDashboardPage() {
  return (
    <StreamAuthGate title="Stream Manager" loginMessage="Log in to manage your stream.">
      {(userId) => <Dashboard key={userId} userId={userId} />}
    </StreamAuthGate>
  )
}
