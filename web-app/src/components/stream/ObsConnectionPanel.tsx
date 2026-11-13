import { useState } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/Button'
import type { StreamSetup, StreamKeyCredentials } from '@/types/stream.types'

interface Props {
  setup: StreamSetup
  credentials: StreamKeyCredentials | null
  onCredentialsCopied: () => void
  resetPending: boolean
  onRequestReset: () => void
}

export function ObsConnectionPanel({ setup, credentials, onCredentialsCopied, resetPending, onRequestReset }: Props) {
  const [showKey, setShowKey] = useState(false)

  const copy = async (value: string, label: string) => {
    try {
      await navigator.clipboard.writeText(value)
      toast.success(`${label} copied`)
      if (label === 'Stream key') onCredentialsCopied()
    } catch {
      toast.error(`Could not copy ${label.toLowerCase()}. Select the value and copy it manually.`)
    }
  }

  const ready = credentials !== null

  return (
    <section className="rounded-2xl border border-brand-border bg-brand-card p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-outfit text-lg font-bold text-white">OBS connection</h2>
      </div>
      <p className="mt-3 text-sm text-gray-400">
        {setup.categoryId
          ? 'Your key works across broadcasts. Copy it when generated; it cannot be shown again.'
          : 'Save a stream title and category before generating your stream key.'}
      </p>
      {setup.status === 'BLOCKED' && <p role="alert">{setup.blockedReason || 'Streaming access is blocked.'}</p>}
      <Button type="button" className="mt-4" onClick={onRequestReset} isLoading={resetPending}
        disabled={resetPending || !setup.categoryId || setup.status === 'BLOCKED'}>
        {setup.hasStreamKey ? 'Generate new stream key' : 'Generate stream key'}
      </Button>

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
                value={credentials!.serverUrl}
                readOnly
                onFocus={(e) => e.currentTarget.select()}
                className="min-w-0 flex-1 rounded-lg border border-brand-border bg-black px-3 py-2 text-sm text-gray-200"
              />
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => void copy(credentials!.serverUrl, 'Server URL')}
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
                value={credentials!.streamKey}
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
                onClick={() => void copy(credentials!.streamKey, 'Stream key')}
              >
                Copy
              </Button>
            </div>
          </div>

          <p className="text-xs text-gray-400">Copy the key into OBS. These credentials disappear after copying the key or leaving this page.</p>
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
