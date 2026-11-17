import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link, useSearchParams } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import {
  AlertCircle,
  Ban,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Loader2,
  RefreshCw,
  ShieldAlert,
  ShieldBan,
  UserRound,
} from 'lucide-react'
import { toast } from 'sonner'
import { parseChannelModerationError } from '@/api/channel-moderation.api'
import { StreamAuthGate } from '@/components/stream/StreamPageStates'
import { Button } from '@/components/ui/Button'
import {
  channelModerationKeys,
  DEFAULT_BANS_PAGE_SIZE,
  useBannedUsersQuery,
  useChannelBanMutation,
  useChannelModerationContext,
  useChannelUnbanMutation,
  useOwnChannelForModeration,
} from '@/hooks/useChannelModeration'
import { useAuthStore } from '@/stores/auth.store'
import {
  BAN_TYPE_OPTIONS,
  getBanTypeLabel,
  type BanType,
  type BannedUserItem,
  type BannedUsersPage as BannedUsersPageData,
} from '@/types/channel-moderation.types'

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

function BannedUserAvatar({ avatarUrl }: { avatarUrl: string | null }) {
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
  headerAction,
  children,
}: {
  headerAction?: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-5xl space-y-6">
        <header className="flex flex-wrap items-start justify-between gap-4">
          <div className="space-y-1.5">
            <h1 className="font-outfit text-2xl font-bold text-white sm:text-3xl">
              Banned users
            </h1>
            <p className="text-sm text-gray-400">
              Manage active channel bans and change restriction levels.
            </p>
          </div>
          {headerAction}
        </header>
        {children}
      </div>
    </main>
  )
}

const DROPDOWN_PADDING = 8
const DROPDOWN_GAP = 6
const DROPDOWN_FALLBACK_WIDTH = 240
const DROPDOWN_FALLBACK_HEIGHT = 240

function RowActionMenu({
  item,
  displayName,
  isPending,
  getAnchorElement,
  onSelectBanType,
  onSelectUnban,
  onClose,
}: {
  item: BannedUserItem
  displayName: string
  isPending: boolean
  getAnchorElement: () => HTMLButtonElement | null
  onSelectBanType: (banType: BanType) => void
  onSelectUnban: () => void
  onClose: (restoreFocus?: boolean) => void
}) {
  const menuId = useId()
  const menuRef = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<{ top: number; left: number }>({
    top: DROPDOWN_PADDING,
    left: DROPDOWN_PADDING,
  })

  useLayoutEffect(() => {
    const updatePosition = () => {
      const anchor = getAnchorElement()
      if (!anchor || !anchor.isConnected) return

      const anchorRect = anchor.getBoundingClientRect()
      const menuRect = menuRef.current?.getBoundingClientRect()
      const menuWidth =
        menuRect && menuRect.width > 0 ? menuRect.width : DROPDOWN_FALLBACK_WIDTH
      const menuHeight =
        menuRect && menuRect.height > 0 ? menuRect.height : DROPDOWN_FALLBACK_HEIGHT

      const viewportWidth = window.innerWidth || 1024
      const viewportHeight = window.innerHeight || 768

      const rightAlignedLeft = anchorRect.right - menuWidth
      const maxLeft = Math.max(
        DROPDOWN_PADDING,
        viewportWidth - menuWidth - DROPDOWN_PADDING,
      )
      const left = Math.max(
        DROPDOWN_PADDING,
        Math.min(rightAlignedLeft > DROPDOWN_PADDING ? rightAlignedLeft : anchorRect.left, maxLeft),
      )

      const belowTop = anchorRect.bottom + DROPDOWN_GAP
      const aboveTop = anchorRect.top - DROPDOWN_GAP - menuHeight
      const fitsBelow =
        belowTop + menuHeight <= viewportHeight - DROPDOWN_PADDING
      const fitsAbove = aboveTop >= DROPDOWN_PADDING

      let top = belowTop
      if (!fitsBelow && fitsAbove) {
        top = aboveTop
      } else if (!fitsBelow) {
        top = Math.max(
          DROPDOWN_PADDING,
          viewportHeight - menuHeight - DROPDOWN_PADDING,
        )
      }

      setPosition({ top, left })
    }

    updatePosition()
    window.addEventListener('resize', updatePosition)
    window.addEventListener('scroll', updatePosition, true)
    return () => {
      window.removeEventListener('resize', updatePosition)
      window.removeEventListener('scroll', updatePosition, true)
    }
  }, [getAnchorElement, item.banType])

  useEffect(() => {
    const menu = menuRef.current
    if (!menu) return
    const firstItem = menu.querySelector<HTMLButtonElement>(
      '[role="menuitem"]:not(:disabled)',
    )
    if (firstItem) {
      firstItem.focus()
    } else {
      menu.focus()
    }
  }, [item.userId])

  useEffect(() => {
    const handlePointerDown = (event: PointerEvent) => {
      const eventTarget = event.target as Node | null
      if (!eventTarget) return
      if (menuRef.current?.contains(eventTarget)) return
      if (getAnchorElement()?.contains(eventTarget)) return
      onClose(false)
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        onClose(true)
      }
    }

    document.addEventListener('pointerdown', handlePointerDown)
    document.addEventListener('keydown', handleKeyDown, true)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
      document.removeEventListener('keydown', handleKeyDown, true)
    }
  }, [getAnchorElement, onClose])

  function handleMenuKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    const menu = menuRef.current
    if (!menu) return

    if (event.key === 'Tab') {
      onClose(false)
      return
    }

    const items = Array.from(
      menu.querySelectorAll<HTMLButtonElement>(
        '[role="menuitem"]:not(:disabled)',
      ),
    )
    if (!items.length) return

    const currentIndex = items.findIndex(
      (element) => element === document.activeElement,
    )
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      const next = currentIndex >= 0 ? (currentIndex + 1) % items.length : 0
      items[next]?.focus()
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      const prev =
        currentIndex >= 0
          ? (currentIndex - 1 + items.length) % items.length
          : items.length - 1
      items[prev]?.focus()
    } else if (event.key === 'Home') {
      event.preventDefault()
      items[0]?.focus()
    } else if (event.key === 'End') {
      event.preventDefault()
      items[items.length - 1]?.focus()
    }
  }

  return createPortal(
    <div
      ref={menuRef}
      id={menuId}
      role="menu"
      tabIndex={-1}
      aria-label={`Moderation actions for ${displayName}`}
      onKeyDown={handleMenuKeyDown}
      style={{
        position: 'fixed',
        top: `${position.top}px`,
        left: `${position.left}px`,
      }}
      className="z-50 w-60 max-w-[calc(100vw-16px)] rounded-xl border border-[#2a2a35] bg-[#101014] p-2 text-gray-100 shadow-2xl outline-none focus-visible:outline-2 focus-visible:outline-[#ff2b42]"
    >
      <div className="space-y-1">
        {BAN_TYPE_OPTIONS.map((option) => {
          const isCurrent = item.banType === option.value
          const isAllowed = item.allowedBanTypes.includes(option.value)
          const isDisabled = isPending || isCurrent || !isAllowed

          return (
            <button
              key={option.value}
              type="button"
              role="menuitem"
              disabled={isDisabled}
              aria-disabled={isDisabled}
              onClick={() => onSelectBanType(option.value)}
              className={`flex w-full items-start gap-2 rounded-lg px-2.5 py-2 text-left text-xs transition focus-visible:outline-2 focus-visible:outline-[#ff2b42] ${
                isCurrent
                  ? 'cursor-not-allowed bg-amber-500/10 text-amber-200'
                  : isAllowed
                    ? 'cursor-pointer font-medium text-gray-200 hover:bg-[#1a1a22] hover:text-white'
                    : 'cursor-not-allowed text-gray-500'
              }`}
            >
              {isCurrent ? (
                <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-300" />
              ) : (
                <Ban
                  className={`mt-0.5 h-3.5 w-3.5 shrink-0 ${
                    isAllowed ? 'text-gray-400' : 'text-gray-600'
                  }`}
                />
              )}
              <span className="min-w-0 flex-1">
                <span className="flex items-center justify-between gap-2">
                  <span>{option.label}</span>
                  {isCurrent && (
                    <span className="text-[10px] font-semibold uppercase tracking-wide text-amber-300">
                      Current
                    </span>
                  )}
                </span>
                {!isCurrent && !isAllowed && (
                  <span className="mt-0.5 block text-[11px] font-normal text-gray-500">
                    No permission to switch
                  </span>
                )}
              </span>
            </button>
          )
        })}
      </div>

      <div className="my-1.5 border-t border-[#1f1f26]" />

      <button
        type="button"
        role="menuitem"
        disabled={isPending || !item.canUnban}
        aria-disabled={isPending || !item.canUnban}
        onClick={onSelectUnban}
        className={`flex w-full items-start gap-2 rounded-lg px-2.5 py-2 text-left text-xs transition focus-visible:outline-2 focus-visible:outline-[#ff2b42] ${
          item.canUnban
            ? 'cursor-pointer font-semibold text-rose-300 hover:bg-rose-500/15 hover:text-rose-200'
            : 'cursor-not-allowed text-gray-500'
        }`}
      >
        <ShieldBan
          className={`mt-0.5 h-3.5 w-3.5 shrink-0 ${
            item.canUnban ? 'text-rose-400' : 'text-gray-600'
          }`}
        />
        <span className="min-w-0 flex-1">
          <span className="block">Unban</span>
          {!item.canUnban && (
            <span className="mt-0.5 block text-[11px] font-normal text-gray-500">
              No permission to unban
            </span>
          )}
        </span>
      </button>
    </div>,
    document.body,
  )
}

function BannedUsersContent({ userId }: { userId: string }) {
  const queryClient = useQueryClient()
  const [searchParams] = useSearchParams()
  const requestedChannelId = searchParams.get('channelId')?.trim() || ''

  const [pageState, setPageState] = useState<{
    channelId: string
    page: number
  }>({ channelId: '', page: 0 })
  const [actionErrorState, setActionErrorState] = useState<{
    channelId: string
    message: string | null
  }>({ channelId: '', message: null })
  const [openMenu, setOpenMenu] = useState<{
    scope: string
    userId: string
  } | null>(null)
  const triggerRefs = useRef(new Map<string, HTMLButtonElement | null>())

  const ownChannelQuery = useOwnChannelForModeration(
    userId,
    !requestedChannelId,
  )
  const channelId = requestedChannelId || ownChannelQuery.data?.id || ''

  const contextQuery = useChannelModerationContext(userId, channelId)
  const context = contextQuery.data
  const canViewBans = Boolean(context?.canViewBans)

  const page = pageState.channelId === channelId ? pageState.page : 0
  const setPageForChannel = (nextPage: number) => {
    setPageState({ channelId, page: Math.max(0, nextPage) })
  }

  const actionError =
    actionErrorState.channelId === channelId ? actionErrorState.message : null
  const setActionError = (message: string | null) => {
    setActionErrorState({ channelId, message })
  }

  const dropdownScope = `${userId}:${channelId}:${page}`
  const activeMenuUserId =
    openMenu && openMenu.scope === dropdownScope ? openMenu.userId : null

  const bansQuery = useBannedUsersQuery(
    userId,
    channelId,
    page,
    DEFAULT_BANS_PAGE_SIZE,
    Boolean(channelId && canViewBans),
  )
  const banMutation = useChannelBanMutation(userId, channelId)
  const unbanMutation = useChannelUnbanMutation(userId, channelId)

  const getActiveTriggerElement = useCallback(
    () => (activeMenuUserId ? triggerRefs.current.get(activeMenuUserId) ?? null : null),
    [activeMenuUserId],
  )

  const handleCloseActionMenu = useCallback(
    (restoreFocus = false) => {
      const targetId = activeMenuUserId
      setOpenMenu(null)
      if (restoreFocus && targetId) {
        const trigger = triggerRefs.current.get(targetId)
        if (trigger && trigger.isConnected) {
          queueMicrotask(() => {
            if (trigger.isConnected) trigger.focus()
          })
        }
      }
    },
    [activeMenuUserId],
  )

  if (!requestedChannelId && ownChannelQuery.isPending) {
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

  if (!requestedChannelId && ownChannelQuery.isError) {
    const parsed = parseChannelModerationError(
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
              You need an active channel or a channel moderation link to view
              and manage banned users.
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

  if (!channelId) {
    return (
      <PageShell>
        <section className="rounded-xl border border-brand-border bg-brand-card p-8 text-center">
          <h2 className="font-outfit text-lg font-bold text-white">
            No channel available
          </h2>
          <p className="mt-1.5 text-sm text-gray-400">
            Could not determine the channel to manage.
          </p>
        </section>
      </PageShell>
    )
  }

  if (contextQuery.isPending) {
    return (
      <PageShell>
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
    const parsed = parseChannelModerationError(
      contextQuery.error,
      'Unable to verify moderation permissions.',
    )
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
      <PageShell>
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

  async function handleChangeBanLevel(
    item: BannedUserItem,
    nextBanType: BanType,
  ) {
    if (
      item.banType === nextBanType ||
      !item.allowedBanTypes.includes(nextBanType)
    ) {
      return
    }
    setOpenMenu(null)
    setActionError(null)
    try {
      const result = await banMutation.banUser({
        targetUserId: item.userId,
        banType: nextBanType,
      })
      if (!result) return
      toast.success(
        result.banType
          ? `Updated ban to ${getBanTypeLabel(result.banType)}`
          : 'Channel ban updated',
      )
    } catch (error) {
      const parsed = parseChannelModerationError(
        error,
        'Unable to update ban level.',
      )
      toast.error(parsed.message)
      setActionError(parsed.message)
    }
  }

  async function handleUnban(item: BannedUserItem) {
    if (!item.canUnban) return
    setOpenMenu(null)
    setActionError(null)
    try {
      const result = await unbanMutation.unbanUser(item.userId)
      if (!result) return
      toast.success('User unbanned')
      const refreshed = queryClient.getQueryData<BannedUsersPageData>(
        channelModerationKeys.bansPage(
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
      const parsed = parseChannelModerationError(
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
  const activeMenuItem = activeMenuUserId
    ? items.find((item) => item.userId === activeMenuUserId) ?? null
    : null

  return (
    <PageShell
      headerAction={
        <Button
          type="button"
          variant="secondary"
          size="sm"
          disabled={bansQuery.isFetching}
          onClick={() => void handleRefresh()}
          leftIcon={
            <RefreshCw
              className={`h-3.5 w-3.5 ${
                bansQuery.isFetching
                  ? 'animate-spin motion-reduce:animate-none'
                  : ''
              }`}
            />
          }
        >
          Refresh
        </Button>
      }
    >
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
                  parseChannelModerationError(
                    bansQuery.error,
                    'Unable to fetch banned users.',
                  ).message
                }
              </p>
            </div>
          </div>
          <div className="flex flex-wrap gap-3">
            {parseChannelModerationError(bansQuery.error).isUnauthorized && (
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
            Users with an active ban in this channel will appear here.
          </p>
        </section>
      ) : (
        <div className="overflow-hidden rounded-xl border border-brand-border bg-brand-card">
          <div
            aria-hidden="true"
            className="hidden border-b border-brand-border bg-[#0b0b0f] px-4 py-3 text-xs font-semibold uppercase tracking-wider text-gray-400 md:grid md:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1.3fr)_minmax(0,1fr)] md:items-center md:gap-4"
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
              const isRowPending =
                banMutation.isUserPending(item.userId) ||
                unbanMutation.isUserPending(item.userId)
              const isMenuOpen = activeMenuUserId === item.userId

              return (
                <li
                  key={item.userId}
                  aria-label={`Banned user ${displayName}`}
                  className="grid min-w-0 grid-cols-1 gap-3 p-4 transition-colors hover:bg-[#101014]/60 md:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1.3fr)_minmax(0,1fr)] md:items-center md:gap-4"
                >
                  <div className="flex min-w-0 items-center gap-3">
                    <BannedUserAvatar avatarUrl={item.avatarUrl} />
                    <div className="min-w-0 flex-1">
                      <p className="break-words text-sm font-semibold text-white md:truncate">
                        {displayName}
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
                    <Button
                      ref={(node) => {
                        triggerRefs.current.set(item.userId, node)
                      }}
                      type="button"
                      variant="secondary"
                      size="sm"
                      className="w-full md:w-auto"
                      aria-label={`Actions for ${displayName}`}
                      aria-haspopup="menu"
                      aria-expanded={isMenuOpen}
                      isLoading={isRowPending}
                      disabled={isRowPending}
                      rightIcon={
                        !isRowPending ? (
                          <ChevronDown className="h-3.5 w-3.5" />
                        ) : undefined
                      }
                      onClick={() => {
                        setOpenMenu((prev) =>
                          prev &&
                          prev.scope === dropdownScope &&
                          prev.userId === item.userId
                            ? null
                            : { scope: dropdownScope, userId: item.userId },
                        )
                      }}
                    >
                      {isRowPending ? 'Updating…' : 'Actions'}
                    </Button>
                  </div>
                </li>
              )
            })}
          </ul>

          <nav
            aria-label="Banned users pagination"
            className="flex flex-wrap items-center justify-between gap-3 border-t border-brand-border bg-[#0b0b0f]/50 px-4 py-3 text-xs text-gray-300"
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

          {activeMenuItem && (
            <RowActionMenu
              item={activeMenuItem}
              displayName={
                activeMenuItem.displayName?.trim() || 'Unavailable user'
              }
              isPending={
                banMutation.isUserPending(activeMenuItem.userId) ||
                unbanMutation.isUserPending(activeMenuItem.userId)
              }
              getAnchorElement={getActiveTriggerElement}
              onSelectBanType={(banType) =>
                void handleChangeBanLevel(activeMenuItem, banType)
              }
              onSelectUnban={() => void handleUnban(activeMenuItem)}
              onClose={handleCloseActionMenu}
            />
          )}
        </div>
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
