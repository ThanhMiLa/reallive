import { useState, type ReactNode } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import type { StreamSetup, StreamKeyCredentials } from '@/types/stream.types'

interface Props {
  setup: StreamSetup
  credentials: StreamKeyCredentials | null
  resetPending: boolean
  onRequestReset: () => void
}

const MASKED_PLACEHOLDER = '•'.repeat(44)

function CredentialCard({
  id,
  title,
  value,
  visible,
  revealLabel,
  hideLabel,
  onToggleVisibility,
  action,
  footer,
}: {
  id: string
  title: string
  value: string | null
  visible: boolean
  revealLabel: string
  hideLabel: string
  onToggleVisibility: () => void
  action?: ReactNode
  footer?: ReactNode
}) {
  const hasValue = value !== null
  const isRevealed = hasValue && visible

  return (
    <section className="rounded-xl border border-brand-border bg-[#08080a]">
      <div className="border-b border-brand-border px-5 py-3.5">
        <h2 className="font-outfit text-base font-bold text-white">{title}</h2>
      </div>
      <div className="p-5">
        <div className="flex flex-wrap items-center gap-3 sm:flex-nowrap">
          <div className="relative flex min-w-0 flex-1 items-center">
            <label htmlFor={id} className="sr-only">
              {title}
            </label>
            <input
              id={id}
              type={isRevealed ? 'text' : 'password'}
              value={value ?? ''}
              placeholder={hasValue ? undefined : MASKED_PLACEHOLDER}
              readOnly
              onFocus={(e) => {
                if (isRevealed) e.currentTarget.select()
              }}
              className="w-full rounded-lg border border-brand-border bg-black py-2.5 pl-3.5 pr-11 text-sm text-gray-100 placeholder:tracking-[0.2em] placeholder:text-gray-300 focus:border-[#ff2b42] focus:outline-none focus:ring-1 focus:ring-[#ff2b42]"
            />
            <button
              type="button"
              aria-label={isRevealed ? hideLabel : revealLabel}
              aria-pressed={isRevealed}
              onClick={onToggleVisibility}
              className="absolute right-2 rounded-md p-1.5 text-gray-400 transition hover:bg-white/5 hover:text-white focus-visible:outline-2 focus-visible:outline-[#ff2b42] cursor-pointer"
            >
              {isRevealed ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
          {action}
        </div>
        {footer}
      </div>
    </section>
  )
}

export function ObsConnectionPanel({
  setup,
  credentials,
  resetPending,
  onRequestReset,
}: Props) {
  const [revealedUrlFor, setRevealedUrlFor] = useState<string | null>(null)
  const [revealedKeyFor, setRevealedKeyFor] = useState<string | null>(null)
  const [unavailableHint, setUnavailableHint] = useState(false)

  const isUrlVisible = Boolean(credentials && revealedUrlFor === credentials.streamKey)
  const isKeyVisible = Boolean(credentials && revealedKeyFor === credentials.streamKey)

  const handleToggleUrl = () => {
    if (!credentials) {
      setUnavailableHint(true)
      return
    }
    setUnavailableHint(false)
    setRevealedUrlFor(isUrlVisible ? null : credentials.streamKey)
  }

  const handleToggleKey = () => {
    if (!credentials) {
      setUnavailableHint(true)
      return
    }
    setUnavailableHint(false)
    setRevealedKeyFor(isKeyVisible ? null : credentials.streamKey)
  }

  const keyGuidance = credentials
    ? 'Your new Stream URL and Stream Key are ready. Click the eye icon to reveal and copy them into OBS before leaving this page.'
    : unavailableHint
      ? setup.hasStreamKey
        ? 'Existing credentials cannot be viewed again after reload. Click Reset to generate a new Stream URL and Stream Key.'
        : 'No Stream URL or Stream Key has been generated yet. Click Reset to generate one.'
      : setup.hasStreamKey
        ? 'Your key works across broadcasts. Click Reset to issue a new Stream URL and Stream Key.'
        : 'Click Reset to generate your Stream URL and Stream Key.'

  return (
    <div className="space-y-4">
      {setup.status === 'BLOCKED' && (
        <p
          role="alert"
          className="rounded-xl border border-red-500/30 bg-red-500/10 px-4 py-3 text-sm text-red-400"
        >
          {setup.blockedReason || 'Streaming access is blocked.'}
        </p>
      )}

      <CredentialCard
        id="obs-server"
        title="Stream URL"
        value={credentials?.serverUrl ?? null}
        visible={isUrlVisible}
        revealLabel="Show stream URL"
        hideLabel="Hide stream URL"
        onToggleVisibility={handleToggleUrl}
      />

      <CredentialCard
        id="obs-key"
        title="Stream Key"
        value={credentials?.streamKey ?? null}
        visible={isKeyVisible}
        revealLabel="Show stream key"
        hideLabel="Hide stream key"
        onToggleVisibility={handleToggleKey}
        action={
          <Button
            type="button"
            variant="secondary"
            onClick={() => {
              setUnavailableHint(false)
              onRequestReset()
            }}
            isLoading={resetPending}
            disabled={resetPending || setup.status === 'BLOCKED'}
            className="shrink-0 border-[#2f3336] bg-[#18181f] px-4 py-2.5 text-white hover:bg-[#23232e]"
          >
            Reset
          </Button>
        }
        footer={
          <p
            role={unavailableHint && !credentials ? 'status' : undefined}
            className={`mt-2.5 text-xs ${
              unavailableHint && !credentials ? 'text-amber-300' : 'text-gray-400'
            }`}
          >
            {keyGuidance}
          </p>
        }
      />

      <section className="rounded-xl border border-brand-border bg-[#08080a] p-5 space-y-5">
        <div>
          <h3 className="text-sm font-semibold text-white">Go live with OBS</h3>
          <ol className="mt-3 list-decimal space-y-2 pl-5 text-sm leading-5 text-gray-400">
            <li>Open OBS settings and select Stream.</li>
            <li>Choose Custom, then paste the Stream URL and Stream Key.</li>
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
      </section>
    </div>
  )
}
