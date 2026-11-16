import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import {
  AlertCircle,
  Ban,
  ChevronLeft,
  ChevronRight,
  Loader2,
  RefreshCw,
  ShieldAlert,
  ShieldBan,
  UserRound,
} from 'lucide-react'
import { toast } from 'sonner'
import { parseChatModerationError } from '@/api/chat-moderation.api'
import { StreamAuthGate } from '@/components/stream/StreamPageStates'
import { Button } from '@/components/ui/Button'
import {
  chatModerationKeys,
  DEFAULT_BANS_PAGE_SIZE,
  useBannedUsersQuery,
  useChatModerationContext,
  useOwnChannelForModeration,
  useUnbanChatUserMutation,
} from '@/hooks/useChatModeration'
import { useAuthStore } from '@/stores/auth.store'
import type { BanType, BannedUserItem, BannedUsersPage as BannedUsersPageData } from '@/types/chat-moderation.types'

function formatBannedAt(createdAt: string): string {
  const date = new Date(createdAt)
  if (Number.isNaN(date.getTime())) return createdAt
  return date.toLocaleString()
}

function banTypeBadge(banType: BanType): {
  label: string
  className: string
} {
  switch (banType) {
    case 'CHAT_BAN':
      return {
        label: 'Chat ban',
        className: 'border-amber-500/35 bg-amber-500/10 text-amber-200',
      }
    case 'LIVE_VIEW_BAN':
      return {
        label: 'Livestream ban',
        className: 'border-red-500/35 bg-red-500/10 text-red-200',
      }
    case 'FULL_BAN':
      return {
        label: 'Full ban',
        className: 'border-rose-500/45 bg-rose-500/15 text-rose-200',
      }
  }
}

function BannedUserAvatar({
  avatarUrl,
}: {
  avatarUrl: string | null
}) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const trimmedUrl = avatarUrl?.trim() || null
  const showImage = Boolean(trimmedUrl && failedUrl !== trimmedUrl)

  return (
    <div className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-full border border-[#23232d] bg-[#16181c]">
      {showImage ? (
        <img
          src={trimmedUrl!}
          alt=""
          onError={() => setFailedUrl(trimmedUrl)}
          className="h-full w-full object-cover"
        />
      ) : (
        <UserRound aria-hidden="true" className="h-4 w-4 text-gray-400" />
      )}
    </div>
  )
}

function PageShell({
  channelName,
  children,
}: {
  channelName?: string
  children: React.ReactNode
}) {
  return (
    <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-5xl space-y-6">
        <header className="space-y-1.5">
          <div className="flex flex-wrap items-center gap-2.5">
            <h1 className="font-outfit text-2xl font-bold text-white sm:text-3xl">
              Banned users
            </h1>
            {channelName && (
              <span className="rounded-full border border-[#23232d] bg-[#101014] px-3 py-0.5 text-xs font-medium text-gray-300">
                {channelName}
              </span>
            )}
          </div>
          <p className="text-sm text-gray-400">
            Users with a chat ban can still read chat and watch the livestream,
            but cannot send messages.
          </p>
        </header>
        {children}
      </div>
    </main>
  )
}

function BannedUsersContent({ userId }: { userId: string }) {
  const queryClient = useQueryClient()
  const [pageState, setPageState] = useState<{
    channelId: string
    page: number
  }>({ channelId: '', page: 0 })
  const [actionError, setActionError] = useState<string | null>(null)

  const ownChannelQuery = useOwnChannelForModeration(userId)
  const channel = ownChannelQuery.data
  const channelId = channel?.id ?? ''

  const contextQuery = useChatModerationContext(userId, channelId)
  const context = contextQuery.data
  const canViewBans = Boolean(context?.canViewBans)
  const canUnban = Boolean(context?.canUnban)

  const page = pageState.channelId === channelId ? pageState.page : 0
  const setPageForChannel = (nextPage: number) => {
    setPageState({ channelId, page: Math.max(0, nextPage) })
  }

  const bansQuery = useBannedUsersQuery(
    userId,
    channelId,
    page,
    DEFAULT_BANS_PAGE_SIZE,
    Boolean(channelId && canViewBans),
  )
  const unbanMutation = useUnbanChatUserMutation(userId, channelId)

  if (ownChannelQuery.isPending) {
    return (
      <PageShell>
        <div
          role="status"
          className="flex items-center gap-3 rounded-xl border border-brand-border bg-brand-card p-6 text-sm text-gray-300"
        >
          <Loader2 className="h-4 w-4 animate-spin text-[#ff2b42] motion-reduce:animate-none" />
          <span>Loading channel and permissions…</span>
        </div>
      </PageShell>
    )
  }

  if (ownChannelQuery.isError) {
    const parsed = parseChatModerationError(
      ownChannelQuery.error,
      'Unable to load your channel.',
    )
    if (parsed.status === 404) {
      return (
        <PageShell>
          <section className="rounded-xl border border-brand-border bg-brand-card p-8 text-center">
            <ShieldBan className="mx-auto mb-3 h-9 w-9 text-gray-500" />
            <h2 className="font-outfit text-lg font-bold text-white">
              No channel available
            </h2>
            <p className="mx-auto mt-1.5 max-w-md text-sm text-gray-400">
              You need an active channel to view and manage banned users.
            </p>
            <div className="mt-5 flex flex-wrap justify-center gap-3">
              <Link
                to="/dashboard"
                className="inline-flex items-center rounded-lg border border-[#23232d] bg-[#101014] px-4 py-2 text-xs font-semibold text-gray-200 transition hover:bg-[#18181f] hover:text-white"
              >
                Back to Stream Manager
              </Link>
            </div>
          </section>
        </PageShell>
      )
    }

    return (
      <PageShell>
        <section
          role="alert"
          className="rounded-xl border border-brand-border bg-brand-card p-6 space-y-4"
        >
          <div className="flex items-start gap-3">
            <AlertCircle className="mt-0.5 h-5 w-5 shrink-0 text-rose-400" />
            <div className="space-y-1">
              <h2 className="font-outfit text-base font-bold text-white">
                Channel unavailable
              </h2>
              <p className="text-sm text-gray-400">{parsed.message}</p>
            </div>
          </div>
          <div className="flex flex-wrap gap-3">
            {parsed.isUnauthorized && (
              <Button
                onClick={() => useAuthStore.getState().openAuthModal('login')}
              >
                Log in
              </Button>
            )}
            <Button
              variant="secondary"
              onClick={() => void ownChannelQuery.refetch()}
            >
              Retry
            </Button>
          </div>
        </section>
      </PageShell>
    )
  }

  if (!channel || !channelId) {
    return (
      <PageShell>
        <section className="rounded-xl border border-brand-border bg-brand-card p-8 text-center">
          <h2 className="font-outfit text-lg font-bold text-white">
            No channel available
          </h2>
          <p className="mt-1.5 text-sm text-gray-400">
            Could not determine your channel.
          </p>
        </section>
      </PageShell>
    )
  }

  if (contextQuery.isPending) {
    return (
      <PageShell channelName={channel.name}>
        <div
          role="status"
          className="flex items-center gap-3 rounded-xl border border-brand-border bg-brand-card p-6 text-sm text-gray-300"
        >
          <Loader2 className="h-4 w-4 animate-spin text-[#ff2b42] motion-reduce:animate-none" />
          <span>Checking moderation permissions…</span>
        </div>
      </PageShell>
    )
  }

  if (contextQuery.isError) {
    const parsed = parseChatModerationError(
      contextQuery.error,
      'Unable to verify moderation permissions.',
    )
    return (
      <PageShell channelName={channel.name}>
        <section
          role="alert"
          className="rounded-xl border border-brand-border bg-brand-card p-6 space-y-4"
        >
          <div className="flex items-start gap-3">
            <AlertCircle className="mt-0.5 h-5 w-5 shrink-0 text-rose-400" />
            <div className="space-y-1">
              <h2 className="font-outfit text-base font-bold text-white">
                Unable to load permissions
              </h2>
              <p className="text-sm text-gray-400">{parsed.message}</p>
            </div>
          </div>
          <div className="flex flex-wrap gap-3">
            {parsed.isUnauthorized && (
              <Button
                onClick={() => useAuthStore.getState().openAuthModal('login')}
              >
                Log in
              </Button>
            )}
            <Button
              variant="secondary"
              onClick={() => void contextQuery.refetch()}
            >
              Retry
            </Button>
          </div>
        </section>
      </PageShell>
    )
  }

  if (!canViewBans) {
    return (
      <PageShell channelName={channel.name}>
        <section
          role="alert"
          className="rounded-xl border border-brand-border bg-brand-card p-8 text-center"
        >
          <ShieldAlert className="mx-auto mb-3 h-9 w-9 text-amber-400" />
          <h2 className="font-outfit text-lg font-bold text-white">
            Permission required
          </h2>
          <p className="mx-auto mt-1.5 max-w-md text-sm text-gray-400">
            You do not have permission to view banned users for this channel.
          </p>
          <div className="mt-5 flex justify-center">
            <Link
              to="/dashboard"
              className="inline-flex items-center rounded-lg border border-[#23232d] bg-[#101014] px-4 py-2 text-xs font-semibold text-gray-200 transition hover:bg-[#18181f] hover:text-white"
            >
              Back to Stream Manager
            </Link>
          </div>
        </section>
      </PageShell>
    )
  }

  async function handleRefresh() {
    setActionError(null)
    const [, bansResult] = await Promise.all([
      contextQuery.refetch(),
      bansQuery.refetch(),
    ])
    if (
      bansResult.data &&
      bansResult.data.content.length === 0 &&
      page > 0
    ) {
      const prevValid =
        bansResult.data.totalPages > 0
          ? Math.min(page - 1, bansResult.data.totalPages - 1)
          : 0
      setPageForChannel(prevValid)
    }
  }

  async function handleUnban(item: BannedUserItem) {
    if (item.banType !== 'CHAT_BAN' || !canUnban) return
    setActionError(null)
    try {
      const result = await unbanMutation.unbanUser(item.userId)
      if (!result) return
      toast.success('User unbanned from chat')
      const refreshed = queryClient.getQueryData<BannedUsersPageData>(
        chatModerationKeys.bansPage(
          userId,
          channelId,
          page,
          DEFAULT_BANS_PAGE_SIZE,
        ),
      )
      if (refreshed && refreshed.content.length === 0 && page > 0) {
        const prevValid =
          refreshed.totalPages > 0
            ? Math.min(page - 1, refreshed.totalPages - 1)
            : 0
        setPageForChannel(prevValid)
      }
    } catch (error) {
      const parsed = parseChatModerationError(
        error,
        'Unable to unban this user.',
      )
      toast.error(parsed.message)
      setActionError(parsed.message)
    }
  }

  const data = bansQuery.data
  const items = data?.content ?? []
  const currentPage = data?.page ?? page
  const totalPages = data?.totalPages ?? 0
  const totalElements = data?.totalElements ?? 0

  return (
    <PageShell channelName={channel.name}>
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-brand-border bg-brand-card px-4 py-3">
        <div className="text-xs text-gray-400">
          {data ? (
            <span>
              {totalElements === 1
                ? '1 banned user'
                : `${totalElements.toLocaleString()} banned users`}
            </span>
          ) : (
            <span>Channel moderation list</span>
          )}
        </div>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          disabled={bansQuery.isFetching}
          onClick={() => void handleRefresh()}
          leftIcon={
            <RefreshCw
              className={`h-3.5 w-3.5 ${
                bansQuery.isFetching ? 'animate-spin motion-reduce:animate-none' : ''
              }`}
            />
          }
        >
          Refresh
        </Button>
      </div>

      {actionError && (
        <div
          role="alert"
          className="flex items-start gap-2.5 rounded-xl border border-red-500/35 bg-red-500/10 px-4 py-3 text-sm text-red-200"
        >
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-red-400" />
          <span className="min-w-0 flex-1 break-words">{actionError}</span>
        </div>
      )}

      {bansQuery.isPending ? (
        <div
          role="status"
          className="flex items-center gap-3 rounded-xl border border-brand-border bg-brand-card p-6 text-sm text-gray-300"
        >
          <Loader2 className="h-4 w-4 animate-spin text-[#ff2b42] motion-reduce:animate-none" />
          <span>Loading banned users…</span>
        </div>
      ) : bansQuery.isError ? (
        <section
          role="alert"
          className="rounded-xl border border-brand-border bg-brand-card p-6 space-y-4"
        >
          <div className="flex items-start gap-3">
            <AlertCircle className="mt-0.5 h-5 w-5 shrink-0 text-rose-400" />
            <div className="space-y-1">
              <h2 className="font-outfit text-base font-bold text-white">
                Unable to load banned users
              </h2>
              <p className="text-sm text-gray-400">
                {
                  parseChatModerationError(
                    bansQuery.error,
                    'Unable to fetch banned users.',
                  ).message
                }
              </p>
            </div>
          </div>
          <div className="flex flex-wrap gap-3">
            {parseChatModerationError(bansQuery.error).isUnauthorized && (
              <Button
                onClick={() => useAuthStore.getState().openAuthModal('login')}
              >
                Log in
              </Button>
            )}
            <Button variant="secondary" onClick={() => void bansQuery.refetch()}>
              Retry
            </Button>
          </div>
        </section>
      ) : items.length === 0 ? (
        <section className="rounded-xl border border-brand-border bg-brand-card p-10 text-center">
          <Ban className="mx-auto mb-3 h-9 w-9 text-gray-500" />
          <h2 className="font-outfit text-lg font-bold text-white">
            No banned users
          </h2>
          <p className="mt-1.5 text-sm text-gray-400">
            Users banned from this channel’s chat will appear here.
          </p>
        </section>
      ) : (
        <>
          <div className="overflow-hidden rounded-xl border border-brand-border bg-brand-card">
            <div
              aria-hidden="true"
              className="hidden border-b border-brand-border bg-[#0b0b0f] px-4 py-3 text-xs font-semibold uppercase tracking-wider text-gray-400 md:grid md:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1.3fr)_minmax(0,1.4fr)] md:items-center md:gap-4"
            >
              <span>User</span>
              <span>Ban type</span>
              <span>Banned at</span>
              <span className="text-right">Action</span>
            </div>

            <ul
              aria-label="Banned users list"
              className="divide-y divide-brand-border"
            >
              {items.map((item) => {
                const displayName =
                  item.displayName?.trim() || 'Unavailable user'
                const badge = banTypeBadge(item.banType)
                const isRowPending = unbanMutation.isUserPending(item.userId)
                const isChatBan = item.banType === 'CHAT_BAN'

                return (
                  <li
                    key={item.userId}
                    aria-label={`Banned user ${displayName}`}
                    className="grid min-w-0 grid-cols-1 gap-3 p-4 transition-colors hover:bg-[#101014]/60 md:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1.3fr)_minmax(0,1.4fr)] md:items-center md:gap-4"
                  >
                    <div className="flex min-w-0 items-center gap-3">
                      <BannedUserAvatar avatarUrl={item.avatarUrl} />
                      <div className="min-w-0 flex-1">
                        <p className="break-words text-sm font-semibold text-white md:truncate">
                          {displayName}
                        </p>
                        <p
                          title={item.userId}
                          className="break-all font-mono text-xs text-gray-400 md:truncate"
                        >
                          {item.userId}
                        </p>
                      </div>
                    </div>

                    <div className="flex items-center justify-between gap-2 md:block">
                      <span className="text-[11px] font-semibold uppercase tracking-wider text-gray-500 md:hidden">
                        Ban type
                      </span>
                      <span
                        className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold ${badge.className}`}
                      >
                        {badge.label}
                      </span>
                    </div>

                    <div className="flex items-center justify-between gap-2 text-xs text-gray-300 md:block">
                      <span className="text-[11px] font-semibold uppercase tracking-wider text-gray-500 md:hidden">
                        Banned at
                      </span>
                      <time dateTime={item.createdAt}>
                        {formatBannedAt(item.createdAt)}
                      </time>
                    </div>

                    <div className="border-t border-brand-border pt-3 md:border-t-0 md:pt-0 md:text-right">
                      {isChatBan ? (
                        canUnban ? (
                          <Button
                            type="button"
                            variant="secondary"
                            size="sm"
                            className="w-full md:w-auto"
                            isLoading={isRowPending}
                            disabled={isRowPending}
                            onClick={() => void handleUnban(item)}
                          >
                            {isRowPending ? 'Unbanning…' : 'Unban'}
                          </Button>
                        ) : (
                          <span className="text-xs text-gray-400">
                            No permission to unban
                          </span>
                        )
                      ) : (
                        <div className="inline-flex flex-col md:items-end md:text-right">
                          <span className="text-xs font-medium text-amber-200">
                            Cannot unban here
                          </span>
                          <span className="text-[11px] text-gray-400">
                            Chat unban cannot remove this ban level.
                          </span>
                        </div>
                      )}
                    </div>
                  </li>
                )
              })}
            </ul>
          </div>

          {/* Pagination */}
          <nav
            aria-label="Banned users pagination"
            className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-brand-border bg-brand-card px-4 py-3 text-xs text-gray-300"
          >
            <span>
              Page {currentPage + 1} of {Math.max(1, totalPages)}
            </span>
            <div className="flex items-center gap-2">
              <Button
                type="button"
                variant="secondary"
                size="sm"
                disabled={currentPage <= 0 || bansQuery.isFetching}
                onClick={() => setPageForChannel(currentPage - 1)}
                leftIcon={<ChevronLeft className="h-3.5 w-3.5" />}
              >
                Previous
              </Button>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                disabled={
                  currentPage + 1 >= totalPages || bansQuery.isFetching
                }
                onClick={() => setPageForChannel(currentPage + 1)}
                rightIcon={<ChevronRight className="h-3.5 w-3.5" />}
              >
                Next
              </Button>
            </div>
          </nav>
        </>
      )}
    </PageShell>
  )
}

export function BannedUsersPage() {
  return (
    <StreamAuthGate
      title="Banned users"
      loginMessage="Log in to manage banned users."
    >
      {(userId) => <BannedUsersContent key={userId} userId={userId} />}
    </StreamAuthGate>
  )
}
