import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { AlertCircle, Ban, Check, Loader2, RefreshCw } from 'lucide-react'
import { toast } from 'sonner'
import { parseChannelModerationError } from '@/api/channel-moderation.api'
import {
  useChannelBanMutation,
  useChannelUnbanMutation,
  useTargetBanState,
} from '@/hooks/useChannelModeration'
import {
  BAN_TYPE_OPTIONS,
  getBanTypeLabel,
  type BanType,
} from '@/types/channel-moderation.types'

export interface ChannelModerationTarget {
  userId: string
  displayName: string
  avatarUrl: string | null
}

interface ChannelUserBanMenuProps {
  viewerId: string
  channelId: string
  target: ChannelModerationTarget
  getAnchorElement: () => HTMLButtonElement | null
  getScrollContainer: () => HTMLDivElement | null
  onClose: (restoreFocus?: boolean) => void
}

const VIEWPORT_PADDING = 8
const MENU_GAP = 6
const FALLBACK_WIDTH = 272
const FALLBACK_HEIGHT = 280

function isAnchorVisibleInContainer(
  anchor: HTMLElement | null,
  container: HTMLElement | null,
): boolean {
  if (!anchor || !anchor.isConnected) return false
  if (!container) return true
  const containerRect = container.getBoundingClientRect()
  const anchorRect = anchor.getBoundingClientRect()
  if (containerRect.height <= 0 || anchorRect.height <= 0) return true
  return (
    anchorRect.bottom > containerRect.top &&
    anchorRect.top < containerRect.bottom
  )
}

function MenuAvatar({
  url,
  name,
}: {
  url: string | null
  name: string
}) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const initial = name.trim().charAt(0).toUpperCase() || '?'
  return url && failedUrl !== url ? (
    <img
      src={url}
      onError={() => setFailedUrl(url)}
      alt=""
      className="h-8 w-8 shrink-0 rounded-full object-cover border border-[#1f1f26]"
    />
  ) : (
    <span
      aria-hidden="true"
      className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-[#25252d] text-xs font-semibold text-gray-200 border border-[#1f1f26]"
    >
      {initial}
    </span>
  )
}

function banSuccessToastMessage(
  previousBanType: BanType | null,
  nextBanType: BanType | null,
): string {
  if (!nextBanType) return 'Channel ban updated'
  if (previousBanType) {
    return `Updated ban to ${getBanTypeLabel(nextBanType)}`
  }
  switch (nextBanType) {
    case 'CHAT_BAN':
      return 'User banned from chat'
    case 'LIVE_VIEW_BAN':
      return 'User banned from livestream'
    case 'FULL_BAN':
      return 'User given a full channel ban'
  }
}

export function ChannelUserBanMenu({
  viewerId,
  channelId,
  target,
  getAnchorElement,
  getScrollContainer,
  onClose,
}: ChannelUserBanMenuProps) {
  const menuId = useId()
  const menuRef = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<{ top: number; left: number }>({
    top: VIEWPORT_PADDING,
    left: VIEWPORT_PADDING,
  })
  const [pendingBanType, setPendingBanType] = useState<BanType | null>(null)
  const [mutationError, setMutationError] = useState<string | null>(null)

  const targetBanQuery = useTargetBanState(viewerId, channelId, target.userId)
  const banMutation = useChannelBanMutation(viewerId, channelId)
  const unbanMutation = useChannelUnbanMutation(viewerId, channelId)
  const actionInFlight = useRef(false)

  const isChecking = targetBanQuery.isPending || targetBanQuery.isFetching
  const targetState = targetBanQuery.data
  const currentBanType = targetState?.banType ?? null
  const allowedBanTypes = targetState?.allowedBanTypes ?? []
  const unbanPending = unbanMutation.isUserPending(target.userId)
  const isMutating = banMutation.isUserPending(target.userId) || unbanPending

  // Position menu relative to anchor and clamp within viewport
  useLayoutEffect(() => {
    const updatePosition = () => {
      const anchor = getAnchorElement()
      if (!anchor || !anchor.isConnected) return

      const anchorRect = anchor.getBoundingClientRect()
      const menuRect = menuRef.current?.getBoundingClientRect()
      const menuWidth =
        menuRect && menuRect.width > 0 ? menuRect.width : FALLBACK_WIDTH
      const menuHeight =
        menuRect && menuRect.height > 0 ? menuRect.height : FALLBACK_HEIGHT

      const viewportWidth = window.innerWidth || 1024
      const viewportHeight = window.innerHeight || 768

      const maxLeft = Math.max(
        VIEWPORT_PADDING,
        viewportWidth - menuWidth - VIEWPORT_PADDING,
      )
      const left = Math.max(
        VIEWPORT_PADDING,
        Math.min(anchorRect.left, maxLeft),
      )

      const belowTop = anchorRect.bottom + MENU_GAP
      const aboveTop = anchorRect.top - MENU_GAP - menuHeight
      const fitsBelow =
        belowTop + menuHeight <= viewportHeight - VIEWPORT_PADDING
      const fitsAbove = aboveTop >= VIEWPORT_PADDING

      let top = belowTop
      if (!fitsBelow && fitsAbove) {
        top = aboveTop
      } else if (!fitsBelow) {
        top = Math.max(
          VIEWPORT_PADDING,
          viewportHeight - menuHeight - VIEWPORT_PADDING,
        )
      }

      setPosition({ top, left })
    }

    updatePosition()

    const container = getScrollContainer()
    const handleScroll = () => {
      const anchor = getAnchorElement()
      if (!isAnchorVisibleInContainer(anchor, container)) {
        onClose(false)
        return
      }
      updatePosition()
    }

    const handleResize = () => {
      const anchor = getAnchorElement()
      if (!isAnchorVisibleInContainer(anchor, container)) {
        onClose(false)
        return
      }
      updatePosition()
    }

    container?.addEventListener('scroll', handleScroll, { passive: true })
    window.addEventListener('resize', handleResize)
    return () => {
      container?.removeEventListener('scroll', handleScroll)
      window.removeEventListener('resize', handleResize)
    }
  }, [
    target.userId,
    isChecking,
    currentBanType,
    targetBanQuery.isError,
    mutationError,
    getAnchorElement,
    getScrollContainer,
    onClose,
  ])

  // Focus menu on open; move focus to first enabled menuitem once ready
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
  }, [target.userId])

  useEffect(() => {
    const menu = menuRef.current
    if (!menu || isChecking) return
    if (document.activeElement === menu) {
      const firstItem = menu.querySelector<HTMLButtonElement>(
        '[role="menuitem"]:not(:disabled)',
      )
      firstItem?.focus()
    }
  }, [isChecking, currentBanType, targetBanQuery.isError])

  // Close on Escape or outside pointer interaction
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
      (item) => item === document.activeElement,
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

  async function handleSelectBanType(banType: BanType) {
    if (
      isChecking ||
      isMutating ||
      actionInFlight.current ||
      currentBanType === banType ||
      !allowedBanTypes.includes(banType)
    ) {
      return
    }
    actionInFlight.current = true
    setMutationError(null)
    setPendingBanType(banType)
    try {
      const result = await banMutation.banUser({
        targetUserId: target.userId,
        banType,
      })
      if (!result) return
      toast.success(banSuccessToastMessage(currentBanType, result.banType))
      onClose(true)
    } catch (error) {
      const parsed = parseChannelModerationError(
        error,
        'Unable to update channel ban.',
      )
      toast.error(parsed.message)
      setMutationError(parsed.message)
    } finally {
      actionInFlight.current = false
      setPendingBanType(null)
    }
  }

  async function handleUnban() {
    if (isChecking || isMutating || actionInFlight.current || !currentBanType || !targetState?.canUnban) return
    actionInFlight.current = true
    setMutationError(null)
    try {
      const result = await unbanMutation.unbanUser(target.userId)
      if (!result) return
      toast.success('User unbanned')
      onClose(true)
    } catch (error) {
      const parsed = parseChannelModerationError(error, 'Unable to unban user.')
      toast.error(parsed.message)
      setMutationError(parsed.message)
    } finally {
      actionInFlight.current = false
    }
  }

  const fetchErrorMessage = targetBanQuery.isError
    ? parseChannelModerationError(
        targetBanQuery.error,
        'Unable to check ban status.',
      ).message
    : null

  return createPortal(
    <div
      ref={menuRef}
      id={menuId}
      role="menu"
      tabIndex={-1}
      aria-label={`Moderation options for ${target.displayName}`}
      onKeyDown={handleMenuKeyDown}
      style={{
        position: 'fixed',
        top: `${position.top}px`,
        left: `${position.left}px`,
      }}
      className="z-50 w-72 max-w-[calc(100vw-16px)] rounded-xl border border-[#2a2a35] bg-[#101014] p-3 text-gray-100 shadow-2xl outline-none focus-visible:outline-2 focus-visible:outline-[#ff2b42]"
    >
      <div className="flex items-center gap-2.5 border-b border-[#1f1f26] pb-2.5">
        <MenuAvatar url={target.avatarUrl} name={target.displayName} />
        <div className="min-w-0 flex-1">
          <p className="truncate text-xs font-semibold text-white">
            {target.displayName}
          </p>
        </div>
      </div>

      <div className="pt-2.5 space-y-2">
        {isChecking ? (
          <div
            role="status"
            className="flex items-center gap-2 rounded-lg border border-[#1f1f26] bg-[#14141a] px-3 py-2 text-xs text-gray-300"
          >
            <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-gray-400 motion-reduce:animate-none" />
            <span>Checking…</span>
          </div>
        ) : targetBanQuery.isError ? (
          <div className="space-y-2">
            <div
              role="alert"
              className="flex items-start gap-2 rounded-lg border border-red-500/30 bg-red-500/10 px-2.5 py-2 text-xs text-red-200"
            >
              <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-red-400" />
              <span className="min-w-0 break-words">{fetchErrorMessage}</span>
            </div>
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                setMutationError(null)
                void targetBanQuery.refetch()
              }}
              className="flex w-full items-center justify-center gap-1.5 rounded-lg border border-[#2a2a35] bg-[#16181c] px-3 py-2 text-xs font-semibold text-gray-200 hover:bg-[#202327] hover:text-white focus-visible:outline-2 focus-visible:outline-[#ff2b42] cursor-pointer"
            >
              <RefreshCw className="h-3.5 w-3.5" />
              Retry
            </button>
          </div>
        ) : (
          <div className="space-y-1.5">
            {BAN_TYPE_OPTIONS.map((option) => {
              const isCurrent = currentBanType === option.value
              const isAllowed = allowedBanTypes.includes(option.value)
              const isOptionPending =
                isMutating && pendingBanType === option.value
              const isDisabled = isMutating || isCurrent || !isAllowed

              return (
                <button
                  key={option.value}
                  type="button"
                  role="menuitem"
                  disabled={isDisabled}
                  aria-disabled={isDisabled}
                  onClick={() => void handleSelectBanType(option.value)}
                  className={`flex w-full items-start gap-2 rounded-lg px-3 py-2 text-left text-xs transition focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[#ff2b42] ${
                    isCurrent
                      ? 'cursor-not-allowed border border-amber-500/30 bg-amber-500/10 text-amber-200'
                      : isAllowed
                        ? 'cursor-pointer border border-[#262632] bg-[#16181c] font-semibold text-gray-100 hover:border-[#ff2b42]/60 hover:bg-[#ff2b42] hover:text-white disabled:cursor-not-allowed disabled:opacity-60'
                        : 'cursor-not-allowed border border-[#1f1f26] bg-[#121216] text-gray-500'
                  }`}
                >
                  {isOptionPending ? (
                    <Loader2 className="mt-0.5 h-3.5 w-3.5 shrink-0 animate-spin motion-reduce:animate-none" />
                  ) : isCurrent ? (
                    <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-300" />
                  ) : (
                    <Ban
                      className={`mt-0.5 h-3.5 w-3.5 shrink-0 ${
                        isAllowed ? 'text-[#ff2b42]' : 'text-gray-600'
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
                      <span className="mt-0.5 block text-[11px] font-normal text-gray-400">
                        You do not have permission to switch to this level.
                      </span>
                    )}
                  </span>
                </button>
              )
            })}
            {currentBanType && (
              <button
                type="button"
                role="menuitem"
                disabled={isMutating || !targetState?.canUnban}
                aria-disabled={isMutating || !targetState?.canUnban}
                onClick={() => void handleUnban()}
                className="flex w-full items-center gap-2 rounded-lg border border-[#262632] bg-[#16181c] px-3 py-2 text-left text-xs font-semibold text-gray-100 transition hover:border-emerald-500/60 hover:bg-emerald-950 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[#ff2b42] disabled:cursor-not-allowed disabled:opacity-50"
              >
                {unbanPending ? <Loader2 className="h-3.5 w-3.5 animate-spin motion-reduce:animate-none" /> : <Check className="h-3.5 w-3.5 text-emerald-400" />}
                Unban
              </button>
            )}
          </div>
        )}

        {mutationError && (
          <div
            role="alert"
            className="flex items-start gap-2 rounded-lg border border-red-500/30 bg-red-500/10 px-2.5 py-2 text-xs text-red-200"
          >
            <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-red-400" />
            <span className="min-w-0 break-words">{mutationError}</span>
          </div>
        )}
      </div>
    </div>,
    document.body,
  )
}
