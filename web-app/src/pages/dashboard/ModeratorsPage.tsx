import { useEffect, useId, useLayoutEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, UserRound } from 'lucide-react'
import { Link } from 'react-router-dom'
import {
  channelModerationApi,
  parseChannelModerationError,
} from '@/api/channel-moderation.api'
import { StreamAuthGate } from '@/components/stream/StreamPageStates'
import { Button } from '@/components/ui/Button'
import {
  channelModerationKeys,
  useOwnChannelForModeration,
} from '@/hooks/useChannelModeration'
import { useAuthStore } from '@/stores/auth.store'
import type {
  ModeratorAssignment,
  ModeratorCandidate,
  ModeratorEligibility,
  ModeratorProfile,
} from '@/types/channel-moderation.types'
import type { OwnChannel, PageResponse } from '@/types/channel.types'

const PAGE_SIZE = 20
const panel = 'rounded-xl border border-brand-border bg-brand-card p-5'
const focusRing = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42]'

const eligibilityMessages: Record<Exclude<ModeratorEligibility, 'ELIGIBLE'>, string> = {
  CHANNEL_OWNER: 'This user owns the channel.',
  ALREADY_MODERATOR: 'This user is already a moderator.',
  CHANNEL_BANNED: 'This user is banned on this channel.',
  ACCOUNT_INACTIVE: 'This account is inactive.',
}

const assignmentErrors: Partial<Record<number, Exclude<ModeratorEligibility, 'ELIGIBLE'>>> = {
  1027: 'CHANNEL_OWNER',
  1028: 'ALREADY_MODERATOR',
  1029: 'CHANNEL_BANNED',
  1030: 'ACCOUNT_INACTIVE',
}

function moderatorError(error: unknown, fallback: string) {
  const { status, code } = parseChannelModerationError(error)
  const eligibility = code === null ? undefined : assignmentErrors[code]
  const message = status === 403
    ? 'You do not have permission to manage moderators for this channel.'
    : status === 401
      ? 'Your session has expired. Please log in again.'
      : eligibility
        ? eligibilityMessages[eligibility]
        : status === null || status >= 500
          ? `${fallback} Please try again.`
          : fallback
  return { status, code, eligibility, message }
}

function profileName({ displayName }: ModeratorProfile) {
  return displayName || 'Unknown user'
}

function Profile({ profile }: { profile: ModeratorProfile }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  return (
    <span className="flex min-w-0 items-center gap-3">
      <span className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-full border border-brand-border bg-[#16181c]">
        {profile.avatarUrl && profile.avatarUrl !== failedUrl ? (
          <img src={profile.avatarUrl} alt="" className="h-full w-full object-cover"
            onError={() => setFailedUrl(profile.avatarUrl)} />
        ) : (
          <UserRound aria-hidden="true" className="h-4 w-4 text-gray-400" />
        )}
      </span>
      <span className="min-w-0 wrap-anywhere">
        <span className="block font-semibold text-white">{profileName(profile)}</span>
        {!profile.displayName && (
          <span className="block text-xs text-gray-400">{profile.userId}</span>
        )}
      </span>
    </span>
  )
}

function ModeratorDialog({ title, pending, onClose, children }: {
  title: string
  pending: boolean
  onClose: () => void
  children: ReactNode
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  useLayoutEffect(() => {
    const dialog = ref.current!
    const opener = document.activeElement
    dialog.showModal()
    dialog.querySelector<HTMLElement>('[data-initial-focus]')?.focus()
    return () => {
      dialog.close()
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus()
    }
  }, [])

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault()
        if (!pending) onClose()
      }}
      onKeyDown={(event) => {
        if (event.key !== 'Tab') return
        const controls = event.currentTarget.querySelectorAll<HTMLElement>(
          'button:not(:disabled), input:not(:disabled), a[href]',
        )
        const first = controls[0]
        const last = controls[controls.length - 1]
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault()
          last?.focus()
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault()
          first?.focus()
        }
      }}
      className="m-auto max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-md overflow-y-auto rounded-2xl border border-brand-border bg-[#101014] p-5 text-white backdrop:bg-black/80 sm:p-6"
    >
      <h2 id={titleId} className="font-outfit text-xl font-bold">{title}</h2>
      {children}
    </dialog>
  )
}

function AddModeratorDialog({ channelId, pending, actionError, clearError, onAdd, onClose }: {
  channelId: string
  pending: boolean
  actionError: ReturnType<typeof moderatorError> | null
  clearError: () => void
  onAdd: (userId: string) => void
  onClose: () => void
}) {
  const inputId = useId()
  const request = useRef<AbortController | null>(null)
  const [displayName, setDisplayName] = useState('')
  const [results, setResults] = useState<PageResponse<ModeratorCandidate> | null>(null)
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null)
  const [searchError, setSearchError] = useState('')
  const [searching, setSearching] = useState(false)

  useEffect(() => () => request.current?.abort(), [])

  async function search(page: number) {
    const term = displayName.trim()
    if (!term || pending) return
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setResults(null)
    setSelectedUserId(null)
    setSearchError('')
    clearError()
    setSearching(true)
    try {
      const result = await channelModerationApi.searchModeratorCandidates(channelId, term, page, PAGE_SIZE, controller.signal)
      if (!controller.signal.aborted) {
        setResults(result)
        setSelectedUserId(result.content.length === 1 ? result.content[0].userId : null)
      }
    } catch (error) {
      if (controller.signal.aborted) return
      setSearchError(moderatorError(error, 'Unable to search for users.').message)
    } finally {
      if (!controller.signal.aborted) setSearching(false)
    }
  }

  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void search(0)
  }

  const candidate = results?.content.find((result) => result.userId === selectedUserId)
  const eligibility = actionError?.eligibility ?? candidate?.eligibility

  return (
    <ModeratorDialog title="Add moderator" pending={pending} onClose={onClose}>
      <form onSubmit={submitSearch} className="mt-4">
        <label htmlFor={inputId} className="text-sm font-medium">Display name</label>
        <div className="mt-2 flex gap-2">
          <input
            id={inputId}
            data-initial-focus
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            disabled={pending}
            maxLength={100}
            value={displayName}
            onChange={(event) => {
              request.current?.abort()
              setDisplayName(event.target.value)
              setResults(null)
              setSelectedUserId(null)
              setSearchError('')
              setSearching(false)
              clearError()
            }}
            className={`min-w-0 flex-1 rounded-lg border border-brand-border bg-[#16181c] px-3 py-2 text-sm ${focusRing}`}
          />
          <Button type="submit" variant="secondary" className={focusRing} disabled={!displayName.trim() || pending || searching}>
            Search
          </Button>
        </div>
      </form>
      {searching && <p role="status" className="mt-4 text-sm text-gray-300">Searching…</p>}
      {results && results.content.length === 0 && (
        <p role="status" className="mt-4 text-sm text-gray-300">No users match that display name.</p>
      )}
      {results && results.content.length > 0 && (
        <ul aria-label="Matching users" className="mt-4 divide-y divide-brand-border rounded-lg border border-brand-border">
          {results.content.map((result) => (
            <li key={result.userId}>
              <button
                type="button"
                disabled={pending}
                aria-pressed={selectedUserId === result.userId}
                aria-label={`Select ${result.displayName}`}
                className={`w-full rounded-lg p-3 text-left aria-pressed:bg-[#21212a] ${focusRing}`}
                onClick={() => {
                  setSelectedUserId(result.userId)
                  clearError()
                }}
              >
                <Profile profile={result} />
              </button>
            </li>
          ))}
        </ul>
      )}
      {results && results.totalPages > 1 && (
        <nav aria-label="Candidate pagination" className="mt-3 flex flex-wrap items-center justify-end gap-2">
          <Button type="button" variant="secondary" disabled={pending || results.page === 0}
            onClick={() => void search(results.page - 1)}>Previous</Button>
          <span className="text-xs text-gray-400">Page {results.page + 1} of {results.totalPages}</span>
          <Button type="button" variant="secondary" disabled={pending || results.page + 1 >= results.totalPages}
            onClick={() => void search(results.page + 1)}>Next</Button>
        </nav>
      )}
      {(searchError || actionError || (eligibility && eligibility !== 'ELIGIBLE')) && (
        <p role="alert" className="mt-4 text-sm text-rose-300">
          {searchError || actionError?.message || (eligibility !== 'ELIGIBLE' && eligibility && eligibilityMessages[eligibility])}
          {eligibility === 'CHANNEL_BANNED' && (
            <>
              {' '}
              <Link className={`underline ${focusRing}`}
                to={`/dashboard/chat-bans?channelId=${encodeURIComponent(channelId)}`}>
                Banned users
              </Link>
            </>
          )}
        </p>
      )}
      <div className="mt-6 flex flex-wrap justify-end gap-2">
        <Button type="button" variant="secondary" className={focusRing} aria-disabled={pending || undefined} onClick={() => { if (!pending) onClose() }}>
          Cancel
        </Button>
        <Button type="button" className={focusRing}
          disabled={pending || !candidate || eligibility !== 'ELIGIBLE' || actionError?.status === 403 || actionError?.status === 401}
          onClick={() => candidate && onAdd(candidate.userId)}>
          {pending ? 'Assigning…' : 'Make moderator'}
        </Button>
      </div>
    </ModeratorDialog>
  )
}

function ModeratorPageState({ message, error, onRetry }: {
  message: string
  error?: ReturnType<typeof moderatorError>
  onRetry?: () => void
}) {
  return (
    <div className={panel}>
      <p className="text-sm text-gray-300" role={error ? 'alert' : 'status'}>{message}</p>
      {error?.status === 401 && (
        <Button className={`mt-4 ${focusRing}`} onClick={() => useAuthStore.getState().openAuthModal('login')}>
          Log in
        </Button>
      )}
      {onRetry && error?.status !== 403 && error?.status !== 401 && (
        <Button variant="secondary" className={`mt-4 ${focusRing}`} onClick={onRetry}>Retry</Button>
      )}
    </div>
  )
}

function ModeratorList({ actorId, channel }: { actorId: string; channel: OwnChannel }) {
  const channelId = channel.id
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [dialog, setDialog] = useState<'add' | ModeratorAssignment | null>(null)
  const [notice, setNotice] = useState('')
  const addButtonRef = useRef<HTMLButtonElement>(null)
  const listKey = channelModerationKeys.moderatorsByChannel(actorId, channelId)
  const list = useQuery({
    queryKey: [...listKey, page, PAGE_SIZE],
    queryFn: ({ signal }) => channelModerationApi.getModerators(channelId, page, PAGE_SIZE, signal),
    retry: false,
  })
  const mutation = useMutation({
    mutationFn: async ({ action, userId }: { action: 'add' | 'remove'; userId: string }) => {
      if (action === 'add') await channelModerationApi.addModerator(channelId, userId)
      else await channelModerationApi.removeModerator(channelId, userId)
    },
    retry: false,
  })
  const actionError = mutation.isError
    ? moderatorError(mutation.error, 'Unable to update the moderator assignment.')
    : null

  async function update(action: 'add' | 'remove', userId: string) {
    if (mutation.isPending) return
    let message = action === 'add' ? 'Moderator added.' : 'Moderator removed.'
    try {
      await mutation.mutateAsync({ action, userId })
    } catch (error) {
      const { code } = parseChannelModerationError(error)
      if (action === 'add' && code === 1028) message = eligibilityMessages.ALREADY_MODERATOR
      else if (action === 'remove' && code === 1031) message = 'This moderator was already removed.'
      else return
    }

    if (action === 'remove') {
      if (page > 0 && list.data?.content.length === 1) setPage(page - 1)
      requestAnimationFrame(() => addButtonRef.current?.focus())
    }
    setDialog(null)
    setNotice(message)
    void queryClient.invalidateQueries({ queryKey: listKey })
  }

  if (list.isPending) return <ModeratorPageState message="Loading moderators…" />
  if (list.isError) {
    const error = moderatorError(list.error, 'Unable to load moderators.')
    return <ModeratorPageState message={error.message} error={error} onRetry={() => void list.refetch()} />
  }

  const { content, totalPages } = list.data
  if (!list.isFetching && page >= Math.max(1, totalPages) && dialog === null) {
    setPage(Math.max(0, totalPages - 1))
    return null
  }

  function openDialog(nextDialog: 'add' | ModeratorAssignment) {
    setNotice('')
    mutation.reset()
    setDialog(nextDialog)
  }

  return (
    <>
      <div className="flex justify-end">
        <Button ref={addButtonRef} className={focusRing} onClick={() => openDialog('add')}
          leftIcon={<Plus aria-hidden="true" className="h-4 w-4" />}>
          Add moderator
        </Button>
      </div>
      <p role="status" className={notice ? 'text-sm text-gray-300' : 'sr-only'}>{notice}</p>
      {content.length === 0 ? (
        <ModeratorPageState message="No moderators yet." />
      ) : (
        <ul aria-label="Channel moderators" className="divide-y divide-brand-border rounded-xl border border-brand-border bg-brand-card">
          {content.map((item) => (
            <li key={item.userId} className="grid items-center gap-x-3 gap-y-2 p-4 sm:grid-cols-[minmax(0,1fr)_minmax(0,auto)_auto]">
              <Profile profile={item} />
              <p className="text-xs text-gray-400">Assigned <time dateTime={item.assignedAt}>
                {new Date(item.assignedAt).toLocaleDateString()}
              </time></p>
              <Button variant="secondary" size="sm" className={`justify-self-start ${focusRing}`}
                aria-label={`Remove ${profileName(item)}`} onClick={() => openDialog(item)}>
                Remove
              </Button>
            </li>
          ))}
        </ul>
      )}
      {totalPages > 1 && (
        <nav aria-label="Moderators pagination" className="flex flex-wrap items-center justify-end gap-3">
          <Button variant="secondary" size="sm" className={focusRing} disabled={page === 0}
            onClick={() => setPage(page - 1)}>Previous</Button>
          <span className="text-sm text-gray-400">Page {page + 1} of {totalPages}</span>
          <Button variant="secondary" size="sm" className={focusRing} disabled={page + 1 >= totalPages}
            onClick={() => setPage(page + 1)}>Next</Button>
        </nav>
      )}
      {dialog === 'add' && (
        <AddModeratorDialog channelId={channelId} pending={mutation.isPending} actionError={actionError}
          clearError={mutation.reset} onAdd={(userId) => void update('add', userId)} onClose={() => setDialog(null)} />
      )}
      {dialog && dialog !== 'add' && (
        <ModeratorDialog title="Remove moderator?" pending={mutation.isPending} onClose={() => setDialog(null)}>
          <p className="mt-3 wrap-anywhere text-sm text-gray-300">
            Remove <strong>{profileName(dialog)}</strong> as a moderator for <strong>{channel.name}</strong>?
          </p>
          {actionError && <p role="alert" className="mt-4 text-sm text-rose-300">{actionError.message}</p>}
          <div className="mt-6 flex flex-wrap justify-end gap-2">
            <Button type="button" variant="secondary" className={focusRing} data-initial-focus
              aria-disabled={mutation.isPending || undefined} onClick={() => { if (!mutation.isPending) setDialog(null) }}>Cancel</Button>
            <Button type="button" variant="danger" className={focusRing}
              disabled={mutation.isPending || actionError?.status === 403 || actionError?.status === 401}
              onClick={() => void update('remove', dialog.userId)}>
              {mutation.isPending ? 'Removing…' : 'Remove moderator'}
            </Button>
          </div>
        </ModeratorDialog>
      )}
    </>
  )
}

function OwnChannelModerators({ actorId }: { actorId: string }) {
  const channel = useOwnChannelForModeration(actorId)

  if (channel.isPending) return <ModeratorPageState message="Loading your channel…" />
  if (channel.isError) {
    const error = moderatorError(channel.error, 'Unable to load your channel.')
    return error.status === 404
      ? <ModeratorPageState message="No channel available." />
      : <ModeratorPageState message={error.message} error={error} onRetry={() => void channel.refetch()} />
  }
  if (!channel.data?.id) return <ModeratorPageState message="No channel available." />
  if (channel.data.status !== 'ACTIVE') {
    return <ModeratorPageState message="Your channel must be active to manage moderators." />
  }
  return <ModeratorList key={channel.data.id} actorId={actorId} channel={channel.data} />
}

export function ModeratorsPage() {
  return (
    <StreamAuthGate title="Moderators" loginMessage="Log in to manage your channel’s moderators.">
      {(actorId) => (
        <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
          <div className="mx-auto max-w-5xl space-y-5">
            <h1 className="font-outfit text-2xl font-bold text-white sm:text-3xl">Moderators</h1>
            <OwnChannelModerators key={actorId} actorId={actorId} />
          </div>
        </main>
      )}
    </StreamAuthGate>
  )
}
