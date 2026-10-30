import { lazy, Suspense, useEffect, useRef } from 'react'
import { isAxiosError } from 'axios'
import { Button } from '@/components/ui/Button'
import { ObsConnectionPanel } from '@/components/stream/ObsConnectionPanel'
import { StreamInfoForm } from '@/components/stream/StreamInfoForm'
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

function EndDialog({
  ready,
  pending,
  onClose,
  onConfirm,
}: {
  ready: boolean
  pending: boolean
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
      aria-labelledby="end-title"
      onCancel={(event) => {
        event.preventDefault()
        if (!pending) onClose()
      }}
      className="m-auto w-[calc(100%-2rem)] max-w-md rounded-2xl border border-brand-border bg-[#101014] p-6 text-white backdrop:bg-black/80"
    >
      <h2 id="end-title" className="font-outfit text-xl font-bold">
        {ready ? 'Cancel this session?' : 'End this stream?'}
      </h2>
      <p className="mt-3 text-sm leading-6 text-gray-400">
        {ready
          ? 'This session will be closed. You can create another session later.'
          : 'Viewers will stop receiving the broadcast. You will need a new session to stream again.'}
      </p>
      <div className="mt-6 flex justify-end gap-3">
        <Button type="button" variant="secondary" disabled={pending} onClick={onClose}>
          Keep session
        </Button>
        <Button type="button" isLoading={pending} onClick={onConfirm}>
          {pending ? 'Ending…' : ready ? 'Cancel session' : 'End stream'}
        </Button>
      </div>
    </dialog>
  )
}

function Dashboard({ userId }: { userId: string }) {
  const {
    current,
    categories,
    metadata,
    session,
    id,
    credentials,
    pending,
    dialog,
    setDialog,
    now,
    refreshMetadata,
    create,
    update,
    getCredentials,
    end,
    newStream,
  } = useStreamManager(userId)

  if (current.isPending)
    return (
      <main className="p-8">
        <h1 className="font-outfit text-3xl font-bold">Stream Manager</h1>
        <p className="mt-8 text-gray-400" role="status">
          Loading your stream session…
        </p>
      </main>
    )
  if (current.isError && !current.data)
    return (
      <main className="p-8">
        <h1 className="font-outfit text-3xl font-bold">Stream Manager</h1>
        <p className="mt-6 text-gray-400">{streamErrorMessage(current.error)}</p>
        <div className="mt-4 flex gap-3">
          {isAxiosError(current.error) && current.error.response?.status === 401 && (
            <Button onClick={() => useAuthStore.getState().openAuthModal('login')}>Log in</Button>
          )}
          <Button variant="secondary" onClick={() => void current.refetch()}>
            Try again
          </Button>
        </div>
      </main>
    )

  const status = session?.status
  const ended = status === 'ENDED'
  const authExpired = isAxiosError(metadata.error) && metadata.error.response?.status === 401
  const sessionNotFound = isAxiosError(metadata.error) && metadata.error.response?.status === 404
  const serverNow = session
    ? Date.parse(session.serverTime) +
      Math.max(0, now - (metadata.dataUpdatedAt || current.dataUpdatedAt))
    : now
  const countdown = session?.reconnectDeadlineAt
    ? formatDuration((Date.parse(session.reconnectDeadlineAt) - serverNow) / 1000).slice(3)
    : null
  const duration = session?.startedAt
    ? formatDuration(
        ((session.endedAt ? Date.parse(session.endedAt) : serverNow) -
          Date.parse(session.startedAt)) /
          1000,
      )
    : null
  return (
    <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-[1600px] space-y-6">
        <header className="flex flex-wrap items-center justify-between gap-4">
          <h1 className="font-outfit text-2xl font-bold text-white sm:text-3xl">
            Stream Manager
          </h1>
          {ended && (
            <Button variant="success" onClick={() => void newStream()}>
              Create new stream
            </Button>
          )}
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
              <div className="flex items-center justify-between border-b border-brand-border px-5 py-3.5">
                <span
                  className={`inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-semibold ${
                    status === 'LIVE'
                      ? 'border-red-500/30 bg-red-500/10 text-red-400'
                      : status === 'RECONNECTING' || status === 'READY'
                        ? 'border-amber-500/30 bg-amber-500/10 text-amber-300'
                        : 'border-brand-border text-gray-400'
                  }`}
                >
                  {status === 'LIVE' && (
                    <span className="h-2 w-2 rounded-full bg-red-500 animate-pulse" />
                  )}
                  {status ? status.charAt(0) + status.slice(1).toLowerCase() : 'No active session'}
                  {duration && <span className="font-normal text-gray-300">{duration}</span>}
                </span>
              </div>
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
                  <StreamPlayer key={id} sessionId={id} refreshMetadata={refreshMetadata} />
                </Suspense>
              ) : (
                <div
                  className={`flex flex-col items-center justify-center bg-[#08080a] p-6 text-center ${session ? 'aspect-video' : 'min-h-[200px]'}`}
                >
                  <p className="font-outfit text-lg font-semibold text-white">
                    {status === 'READY'
                      ? 'Waiting for OBS'
                      : status === 'RECONNECTING'
                        ? 'Waiting for the stream to reconnect'
                        : ended
                          ? 'This stream has ended'
                          : 'No active session'}
                  </p>
                  <p className="mt-2 max-w-sm text-sm leading-6 text-gray-400">
                    {status === 'READY'
                      ? 'Start streaming in OBS to see your preview.'
                      : status === 'RECONNECTING'
                        ? 'Reconnect using OBS to continue this session.'
                        : ended
                          ? session?.endedAt
                            ? `Ended ${new Date(session.endedAt).toLocaleString()}`
                            : ''
                          : 'Create a session to get your OBS connection details.'}
                  </p>
                </div>
              )}
            </section>
            <StreamInfoForm
              key={`info-${session?.id || 'new'}`}
              initial={{ title: session?.title || '', categoryId: session?.categoryId || '' }}
              categories={categories.data || []}
              categoriesLoading={categories.isPending}
              categoriesError={categories.isError}
              onRetryCategories={() => void categories.refetch()}
              editing={Boolean(session)}
              disabled={ended}
              pending={pending === 'create' || pending === 'update'}
              sessionStatus={status}
              onEndStream={() => setDialog(true)}
              endPending={pending === 'end'}
              onSubmit={session ? update : create}
            />
            {session && !ended ? (
              <ObsConnectionPanel
                key={`obs-${session.id}`}
                credentials={credentials?.sessionId === session.id ? credentials : null}
                pending={pending === 'credentials'}
                onGet={() => void getCredentials()}
              />
            ) : (
              <section className="rounded-2xl border border-brand-border bg-brand-card p-5 sm:p-6">
                <h2 className="font-outfit text-lg font-bold text-white">OBS connection</h2>
                <p className="mt-3 text-sm leading-6 text-gray-400">
                  Create a session to get your OBS connection details.
                </p>
              </section>
            )}
          </div>
          <aside
            aria-label="Stream chat"
            className="hidden xl:flex xl:flex-col xl:sticky xl:top-6 h-[600px] min-w-0 transform-gpu rounded-2xl border border-brand-border bg-brand-card p-5"
          >
            <div className="border-b border-brand-border pb-3">
              <h2 className="font-outfit text-sm font-bold text-gray-400">Stream Chat</h2>
            </div>
            <div className="flex flex-1 flex-col items-center justify-center text-center p-4 text-gray-500">
              <p className="text-sm font-medium text-gray-400">Stream Chat</p>
              <p className="mt-1 text-xs text-gray-500">Chat messages will appear here.</p>
            </div>
          </aside>
        </div>
        {dialog && session && !ended && (
          <EndDialog
            ready={status === 'READY'}
            pending={pending === 'end'}
            onClose={() => setDialog(false)}
            onConfirm={() => void end()}
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
