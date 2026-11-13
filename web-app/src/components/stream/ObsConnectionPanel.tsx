import { useState } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/Button'
import type { StreamSetup } from '@/types/stream.types'

interface Props {
  setup: StreamSetup
  resetPending: boolean
  onRequestReset: () => void
}

export function ObsConnectionPanel({ setup, resetPending, onRequestReset }: Props) {
  const [showKey, setShowKey] = useState(false)

  const copy = async (value: string, label: string) => {
    try {
      await navigator.clipboard.writeText(value)
      toast.success(`${label} copied`)
    } catch {
      toast.error(`Could not copy ${label.toLowerCase()}. Select the value and copy it manually.`)
    }
  }

  const ready = setup.status === 'ACTIVE' && setup.publishReady && setup.serverUrl && setup.streamKey

  return (
    <section className="rounded-2xl border border-brand-border bg-brand-card p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-outfit text-lg font-bold text-white">OBS connection</h2>
        {setup.generation > 0 && (
          <span className="rounded-full border border-brand-border px-2.5 py-0.5 text-xs text-gray-400">
            Key gen #{setup.generation}
          </span>
        )}
      </div>

      {setup.status === 'DRAFT' && (
        <div className="mt-4 rounded-xl border border-brand-border bg-[#0b0b0e] p-4 text-sm text-gray-300">
          Save a stream title and category above to generate your persistent Server URL and Stream
          Key.
        </div>
      )}

      {(setup.status === 'PROVISIONING' || (setup.status === 'ACTIVE' && !setup.publishReady)) && (
        <div
          role="status"
          className="mt-4 rounded-xl border border-brand-border bg-[#0b0b0e] p-4 text-sm text-gray-300"
        >
          Provisioning your ingest path… Your OBS Server URL and Stream Key will appear here
          shortly.
        </div>
      )}

      {setup.status === 'ROTATING' && (
        <div
          role="status"
          className="mt-4 rounded-xl border border-amber-800 bg-amber-950/30 p-4 text-sm text-amber-200"
        >
          Resetting your stream key and ending any active broadcast… Your new Stream Key will appear
          automatically.
        </div>
      )}

      {setup.status === 'BLOCKED' && (
        <div
          role="alert"
          className="mt-4 rounded-xl border border-red-800/80 bg-red-950/30 p-4 text-sm text-red-200"
        >
          {setup.blockedReason || 'Streaming access is currently blocked for your channel.'}
        </div>
      )}

      {ready && (
        <div className="mt-5 space-y-4">
          <div>
            <label
              htmlFor="obs-server"
              className="mb-1.5 block text-xs font-semibold text-gray-300"
            >
              Server URL
            </label>
            <div className="flex gap-2">
              <input
                id="obs-server"
                value={setup.serverUrl!}
                readOnly
                onFocus={(e) => e.currentTarget.select()}
                className="min-w-0 flex-1 rounded-lg border border-brand-border bg-black px-3 py-2 text-sm text-gray-200"
              />
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => void copy(setup.serverUrl!, 'Server URL')}
              >
                Copy
              </Button>
            </div>
          </div>

          <div>
            <label htmlFor="obs-key" className="mb-1.5 block text-xs font-semibold text-gray-300">
              Stream key
            </label>
            <div className="flex gap-2">
              <input
                id="obs-key"
                type={showKey ? 'text' : 'password'}
                value={setup.streamKey!}
                readOnly
                onFocus={(e) => e.currentTarget.select()}
                className="min-w-0 flex-1 rounded-lg border border-brand-border bg-black px-3 py-2 text-sm text-gray-200"
              />
              <Button
                type="button"
                variant="secondary"
                size="sm"
                aria-label={showKey ? 'Hide stream key' : 'Show stream key'}
                onClick={() => setShowKey((prev) => !prev)}
              >
                {showKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </Button>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => void copy(setup.streamKey!, 'Stream key')}
              >
                Copy
              </Button>
            </div>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-3 pt-1">
            <p className="text-xs leading-5 text-gray-400">
              Your stream key is permanent across broadcasts until you reset it.
            </p>
            <Button
              type="button"
              variant="danger"
              size="sm"
              isLoading={resetPending}
              disabled={resetPending}
              onClick={onRequestReset}
            >
              Reset stream key
            </Button>
          </div>
        </div>
      )}

      <div className="mt-6 border-t border-brand-border pt-5 space-y-5">
        <div>
          <h3 className="text-sm font-semibold text-white">Go live with OBS</h3>
          <ol className="mt-3 list-decimal space-y-2 pl-5 text-sm leading-5 text-gray-400">
            <li>Open OBS settings and select Stream.</li>
            <li>Choose Custom, then paste the Server URL and Stream Key.</li>
            <li>Click Start Streaming in OBS — your broadcast goes live automatically.</li>
          </ol>
        </div>
        <div className="border-t border-brand-border/60 pt-4">
          <h3 className="text-sm font-semibold text-white">Reconnect &amp; End Stream</h3>
          <p className="mt-2 text-sm leading-5 text-gray-400">
            Click Stop Streaming in OBS to finish your broadcast. After a 60-second reconnect
            window, the session ends automatically (starting again afterward opens a new session).
          </p>
        </div>
      </div>
    </section>
  )
}
