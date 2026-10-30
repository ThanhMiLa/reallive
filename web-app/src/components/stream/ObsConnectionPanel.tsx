import { toast } from 'sonner'
import { Button } from '@/components/ui/Button'
import type { PublishCredentials } from '@/types/stream.types'

interface Props {
  credentials: PublishCredentials | null
  pending: boolean
  onGet: () => void
}

export function ObsConnectionPanel({ credentials, pending, onGet }: Props) {
  const copy = async (value: string, label: string) => {
    try {
      await navigator.clipboard.writeText(value)
      toast.success(`${label} copied`)
    } catch {
      toast.error(`Could not copy ${label.toLowerCase()}. Select the value and copy it manually.`)
    }
  }
  return (
    <section className="rounded-2xl border border-brand-border bg-brand-card p-5">
      <h2 className="font-outfit text-lg font-bold text-white">OBS connection</h2>
      {credentials ? (
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
                value={credentials.serverUrl}
                readOnly
                onFocus={(e) => e.currentTarget.select()}
                className="min-w-0 flex-1 rounded-lg border border-brand-border bg-black px-3 py-2 text-sm text-gray-200"
              />
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => void copy(credentials.serverUrl, 'Server URL')}
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
                type="password"
                value={credentials.streamKey}
                readOnly
                onFocus={(e) => e.currentTarget.select()}
                className="min-w-0 flex-1 rounded-lg border border-brand-border bg-black px-3 py-2 text-sm text-gray-200"
              />
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => void copy(credentials.streamKey, 'Stream key')}
              >
                Copy
              </Button>
            </div>
          </div>
          <p className="text-xs text-gray-400">
            Expires: {new Date(credentials.publishExpiresAt).toLocaleString()}
          </p>
          <Button type="button" variant="secondary" size="sm" isLoading={pending} onClick={onGet}>
            Get new credentials
          </Button>
          <p className="text-xs leading-5 text-gray-400">
            New credentials must be copied into OBS manually. Previously issued credentials remain
            valid until they expire.
          </p>
        </div>
      ) : (
        <div className="mt-5">
          <p className="mb-3 text-sm text-gray-400">
            Get your OBS credentials to connect this session.
          </p>
          <Button type="button" variant="secondary" isLoading={pending} onClick={onGet}>
            Get OBS credentials
          </Button>
        </div>
      )}
      <div className="mt-6 border-t border-brand-border pt-5 space-y-5">
        <div>
          <h3 className="text-sm font-semibold text-white">Set up OBS</h3>
          <ol className="mt-3 list-decimal space-y-2 pl-5 text-sm leading-5 text-gray-400">
            <li>Open OBS settings and select Stream.</li>
            <li>Choose Custom, then paste the server URL and stream key.</li>
            <li>Click Start Streaming in OBS.</li>
          </ol>
        </div>
        <div className="border-t border-brand-border/60 pt-4">
          <h3 className="text-sm font-semibold text-white">End Stream</h3>
          <ol className="mt-3 list-decimal space-y-2 pl-5 text-sm leading-5 text-gray-400">
            <li>Click &quot;End stream&quot; in Stream Manager.</li>
            <li>Click &quot;Stop Streaming&quot; in OBS.</li>
          </ol>
        </div>
      </div>
    </section>
  )
}
