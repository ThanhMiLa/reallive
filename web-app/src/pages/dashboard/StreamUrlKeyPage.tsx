import { useEffect, useRef } from 'react'
import { ObsConnectionPanel } from '@/components/stream/ObsConnectionPanel'
import { StreamAuthGate, StreamSetupFallback } from '@/components/stream/StreamPageStates'
import { Button } from '@/components/ui/Button'
import { useStreamKeyManager } from '@/hooks/useStreamManager'

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

function StreamUrlKeyContent({ userId }: { userId: string }) {
  const {
    setup,
    current,
    pending,
    resetDialogOpen,
    setResetDialogOpen,
    credentials,
    resetStreamKey,
    requestReset,
  } = useStreamKeyManager(userId)

  if (setup.isPending || (setup.isError && !setup.data)) {
    return (
      <StreamSetupFallback
        title="Stream URL & Key"
        isPending={setup.isPending}
        isError={setup.isError}
        error={setup.error}
        onRetry={() => void setup.refetch()}
      />
    )
  }

  const streamConfig = setup.data!
  const status = current.data?.status
  const activeSession = status === 'LIVE' || status === 'RECONNECTING'

  return (
    <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-5xl space-y-6">
        <header className="flex flex-wrap items-center justify-between gap-4">
          <h1 className="font-outfit text-2xl font-bold text-white sm:text-3xl">
            Stream URL &amp; Key
          </h1>
          {activeSession && (
            <span
              role="status"
              className={`rounded-full border px-3 py-1 text-xs font-semibold ${
                status === 'LIVE'
                  ? 'border-red-500/40 bg-red-500/15 text-red-300'
                  : 'border-amber-500/40 bg-amber-500/15 text-amber-200'
              }`}
            >
              {status}
            </span>
          )}
        </header>

        <ObsConnectionPanel
          setup={streamConfig}
          credentials={credentials}
          resetPending={pending === 'key'}
          onRequestReset={requestReset}
        />

        {resetDialogOpen && (
          <ResetKeyDialog
            pending={pending === 'key'}
            isStreaming={activeSession}
            onClose={() => setResetDialogOpen(false)}
            onConfirm={() => void resetStreamKey()}
          />
        )}
      </div>
    </main>
  )
}

export function StreamUrlKeyPage() {
  return (
    <StreamAuthGate
      title="Stream URL & Key"
      loginMessage="Log in to manage your stream URL and key."
    >
      {(userId) => <StreamUrlKeyContent key={userId} userId={userId} />}
    </StreamAuthGate>
  )
}
