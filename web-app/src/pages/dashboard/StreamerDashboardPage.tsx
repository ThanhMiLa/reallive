import { lazy, Suspense, useEffect, useRef } from 'react'
import { isAxiosError } from 'axios'
import { Button } from '@/components/ui/Button'
import { StreamChatPanel } from '@/components/chat/StreamChatPanel'
import { ObsConnectionPanel } from '@/components/stream/ObsConnectionPanel'
import { StreamInfoForm } from '@/components/stream/StreamInfoForm'
import { StreamViewerCount } from '@/components/stream/StreamViewerCount'
import { useAuthStore } from '@/stores/auth.store'
import { streamErrorMessage, useStreamManager } from '@/hooks/useStreamManager'

const StreamPlayer = lazy(() =>
  import('@/components/stream/StreamPlayer').then((module) => ({ default: module.StreamPlayer })),
)

function formatDuration(seconds: number) {
  const value = Math.max(0, Math.floor(seconds))
  return [Math.floor(value / 3600), Math.floor(value / 60) % 60, value % 60]
    .map((part) => String(part).padStart(2, '0'))
    .join(':')
}

function ResetKeyDialog({
  pending,
  isStreaming,
  onClose,
  onConfirm,
}: {
  pending: boolean
  isStreaming: boolean
  onClose: () => void
  onConfirm: () => void
}) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const node = ref.current
    node?.showModal()
    return () => node?.close()
  }, [])
  return (
    <dialog
      ref={ref}
      aria-labelledby="reset-key-title"
      onCancel={(event) => {
        event.preventDefault()
        if (!pending) onClose()
      }}
      className="m-auto w-[calc(100%-2rem)] max-w-md rounded-2xl border border-brand-border bg-[#101014] p-6 text-white backdrop:bg-black/80"
    >
      <h2 id="reset-key-title" className="font-outfit text-xl font-bold">
        Reset stream key?
      </h2>
      <p className="mt-3 text-sm leading-6 text-gray-400">
        {isStreaming
          ? 'Your active broadcast will end immediately, OBS will be disconnected, and your current stream key will stop working. You will need to paste the new key into OBS.'
          : 'Your current stream key will stop working immediately. You will need to paste the new key into OBS before your next broadcast.'}
      </p>
      <div className="mt-6 flex justify-end gap-3">
        <Button type="button" variant="secondary" disabled={pending} onClick={onClose}>
          Cancel
        </Button>
        <Button type="button" variant="danger" isLoading={pending} onClick={onConfirm}>
          {pending ? 'Resetting…' : 'Reset stream key'}
        </Button>
      </div>
    </dialog>
  )
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
    resetDialogOpen,
    setResetDialogOpen,
    lastEndedAt,
    now,
    refreshMetadata,
    saveSetup,
    resetStreamKey,
  } = useStreamManager(userId)

  if (setup.isPending)
    return (
      <main className="p-8">
        <h1 className="font-outfit text-3xl font-bold">Stream Manager</h1>
        <p className="mt-8 text-gray-400" role="status">
          Loading your stream setup…
        </p>
      </main>
    )

  if (setup.isError && !setup.data)
    return (
      <main className="p-8">
        <h1 className="font-outfit text-3xl font-bold">Stream Manager</h1>
        <p className="mt-6 text-gray-400">{streamErrorMessage(setup.error)}</p>
        <div className="mt-4 flex gap-3">
          {isAxiosError(setup.error) && setup.error.response?.status === 401 && (
            <Button onClick={() => useAuthStore.getState().openAuthModal('login')}>Log in</Button>
          )}
          <Button variant="secondary" onClick={() => void setup.refetch()}>
            Try again
          </Button>
        </div>
      </main>
    )

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

  const offlineHeading =
    streamConfig.status === 'DRAFT'
      ? 'Complete stream setup'
      : streamConfig.status === 'PROVISIONING'
        ? 'Preparing your stream setup'
        : streamConfig.status === 'ROTATING'
          ? 'Resetting stream key'
          : streamConfig.status === 'BLOCKED'
            ? 'Streaming unavailable'
            : ended || lastEndedAt
              ? 'Stream ended · Waiting for OBS'
              : 'Waiting for OBS'

  const offlineDescription =
    streamConfig.status === 'DRAFT'
      ? 'Save your stream title and category to generate your permanent OBS credentials.'
      : streamConfig.status === 'PROVISIONING'
        ? 'Setting up your ingest path. Your stream key will be ready shortly.'
        : streamConfig.status === 'ROTATING'
          ? 'Issuing a new stream key and retiring the previous one.'
          : streamConfig.status === 'BLOCKED'
            ? streamConfig.blockedReason || 'Streaming access is currently blocked.'
            : ended || lastEndedAt
              ? `Previous broadcast ended ${new Date(session?.endedAt || lastEndedAt!).toLocaleString()}. Start streaming in OBS when you are ready for a new session.`
              : 'Start streaming in OBS to go live and view your broadcast preview.'

  return (
    <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-[1600px] space-y-6">
        <header className="flex flex-wrap items-center justify-between gap-4">
          <h1 className="font-outfit text-2xl font-bold text-white sm:text-3xl">
            Stream Manager
          </h1>
          <span
            role="status"
            className={`rounded-full border px-3 py-1 text-xs font-semibold ${
              status === 'LIVE'
                ? 'border-red-500/40 bg-red-500/15 text-red-300'
                : status === 'RECONNECTING'
                  ? 'border-amber-500/40 bg-amber-500/15 text-amber-200'
                  : 'border-brand-border bg-brand-card text-gray-300'
            }`}
          >
            {status === 'LIVE' ? 'LIVE' : status === 'RECONNECTING' ? 'RECONNECTING' : 'OFFLINE'}
          </span>
        </header>

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

        <div className="grid min-w-0 grid-cols-1 items-start gap-6 xl:grid-cols-[minmax(0,2fr)_minmax(320px,1fr)]">
          <div className="min-w-0 space-y-6 transform-gpu">
            <section className="overflow-hidden rounded-2xl border border-brand-border bg-brand-card">
              {authExpired || sessionNotFound ? (
                <div className="flex aspect-video items-center justify-center p-6 text-center text-gray-400">
                  {authExpired ? 'Log in to resume the preview.' : 'Stream session is unavailable.'}
                </div>
              ) : status === 'LIVE' && id ? (
                <Suspense
                  fallback={
                    <div className="flex aspect-video items-center justify-center text-gray-400">
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
                <div
                  className={`flex flex-col items-center justify-center bg-[#08080a] p-6 text-center ${activeSession ? 'aspect-video' : 'min-h-[200px]'}`}
                >
                  <p className="font-outfit text-lg font-semibold text-white">
                    {status === 'RECONNECTING' ? 'Waiting for the stream to reconnect' : offlineHeading}
                  </p>
                  <p className="mt-2 max-w-md text-sm leading-6 text-gray-400">
                    {status === 'RECONNECTING'
                      ? 'Reconnect using OBS within 60 seconds to continue this session.'
                      : offlineDescription}
                  </p>
                </div>
              )}
            </section>

            <StreamInfoForm
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

            <ObsConnectionPanel
              setup={streamConfig}
              resetPending={pending === 'reset' || streamConfig.status === 'ROTATING'}
              onRequestReset={() => setResetDialogOpen(true)}
            />
          </div>

          <aside
            aria-label="Stream chat"
            className="flex h-[520px] min-w-0 flex-col overflow-hidden transform-gpu rounded-2xl border border-brand-border bg-brand-card xl:sticky xl:top-6 xl:h-[600px]"
          >
            <div className="flex flex-wrap items-center gap-3 border-b border-brand-border px-5 py-3.5">
              <h2 className="font-outfit text-sm font-bold text-gray-400">Stream Chat</h2>
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
        </div>

        {resetDialogOpen && (
          <ResetKeyDialog
            pending={pending === 'reset'}
            isStreaming={activeSession}
            onClose={() => setResetDialogOpen(false)}
            onConfirm={() => void resetStreamKey()}
          />
        )}
      </div>
    </main>
  )
}

export function StreamerDashboardPage() {
  const { user, isAuthenticated, isCheckingAuth, openAuthModal } = useAuthStore()
  if (isCheckingAuth)
    return (
      <main className="p-8">
        <h1 className="font-outfit text-3xl font-bold">Stream Manager</h1>
        <p className="mt-8 text-gray-400" role="status">
          Checking your session…
        </p>
      </main>
    )
  if (!isAuthenticated || !user)
    return (
      <main className="p-8">
        <h1 className="font-outfit text-3xl font-bold">Stream Manager</h1>
        <p className="mt-6 text-gray-400">Log in to manage your stream.</p>
        <Button className="mt-4" onClick={() => openAuthModal('login')}>
          Log in
        </Button>
      </main>
    )
  return <Dashboard key={user.userId} userId={user.userId} />
}
