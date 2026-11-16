import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { AlertCircle, Ban, Loader2, RefreshCw, ShieldAlert } from 'lucide-react'
import { toast } from 'sonner'
import { parseChatModerationError } from '@/api/chat-moderation.api'
import {
  useBanChatUserMutation,
  useTargetBanStatus,
} from '@/hooks/useChatModeration'
import type { BanType } from '@/types/chat-moderation.types'

export interface ChatModerationTarget {
  userId: string
  displayName: string
  avatarUrl: string | null
}

interface ChatUserBanMenuProps {
  viewerId: string
  channelId: string
  target: ChatModerationTarget
  getAnchorElement: () => HTMLButtonElement | null
  getScrollContainer: () => HTMLDivElement | null
  onClose: (restoreFocus?: boolean) => void
}

const VIEWPORT_PADDING = 8
const MENU_GAP = 6
const FALLBACK_WIDTH = 256
const FALLBACK_HEIGHT = 180

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

function higherBanLabel(banType: Exclude<BanType, 'CHAT_BAN'>): string {
  return banType === 'LIVE_VIEW_BAN' ? 'Livestream ban' : 'Full ban'
}

export function ChatUserBanMenu({
  viewerId,
  channelId,
  target,
  getAnchorElement,
  getScrollContainer,
  onClose,
}: ChatUserBanMenuProps) {
  const menuId = useId()
  const menuRef = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<{ top: number; left: number }>({
    top: VIEWPORT_PADDING,
    left: VIEWPORT_PADDING,
  })
  const [mutationError, setMutationError] = useState<string | null>(null)

  const targetBanQuery = useTargetBanStatus(viewerId, channelId, target.userId)
  const banMutation = useBanChatUserMutation(viewerId, channelId)

  const isChecking = targetBanQuery.isPending || targetBanQuery.isFetching
  const banType = targetBanQuery.data?.banType ?? null
  const isMutating = banMutation.isPending

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
    banType,
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
  }, [isChecking, banType, targetBanQuery.isError])

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

  async function handleBan() {
    if (isChecking || isMutating || banType !== null) return
    setMutationError(null)
    try {
      const result = await banMutation.mutateBan(target.userId)
      if (!result) return
      toast.success('User banned from chat')
      onClose(true)
    } catch (error) {
      const parsed = parseChatModerationError(
        error,
        'Unable to ban user from chat.',
      )
      toast.error(parsed.message)
      if (parsed.isForbidden) {
        onClose(false)
        return
      }
      setMutationError(parsed.message)
    }
  }

  const fetchErrorMessage = targetBanQuery.isError
    ? parseChatModerationError(
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
      className="z-50 w-64 max-w-[calc(100vw-16px)] rounded-xl border border-[#2a2a35] bg-[#101014] p-3 text-gray-100 shadow-2xl outline-none focus-visible:outline-2 focus-visible:outline-[#ff2b42]"
    >
      <div className="flex items-center gap-2.5 border-b border-[#1f1f26] pb-2.5">
        <MenuAvatar url={target.avatarUrl} name={target.displayName} />
        <div className="min-w-0 flex-1">
          <p className="truncate text-xs font-semibold text-white">
            {target.displayName}
          </p>
          <p className="truncate text-[10px] text-gray-400">Chat moderation</p>
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
        ) : banType === null ? (
          <button
            type="button"
            role="menuitem"
            disabled={isMutating}
            onClick={() => void handleBan()}
            className="flex w-full items-center gap-2 rounded-lg bg-[#ff2b42] px-3 py-2 text-xs font-semibold text-white transition hover:bg-[#e02237] disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white cursor-pointer"
          >
            {isMutating ? (
              <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin motion-reduce:animate-none" />
            ) : (
              <Ban className="h-3.5 w-3.5 shrink-0" />
            )}
            <span>{isMutating ? 'Banning…' : 'Ban from chat'}</span>
          </button>
        ) : banType === 'CHAT_BAN' ? (
          <button
            type="button"
            role="menuitem"
            aria-disabled="true"
            disabled
            className="flex w-full cursor-not-allowed items-center gap-2 rounded-lg border border-[#1f1f26] bg-[#16181c] px-3 py-2 text-left text-xs font-medium text-gray-400"
          >
            <Ban className="h-3.5 w-3.5 shrink-0 text-amber-400" />
            <span>Already banned from chat</span>
          </button>
        ) : (
          <div className="space-y-1.5 rounded-lg border border-amber-500/30 bg-amber-500/10 p-2.5 text-xs">
            <div className="flex items-center gap-1.5 font-semibold text-amber-200">
              <ShieldAlert className="h-3.5 w-3.5 shrink-0 text-amber-400" />
              <span>{higherBanLabel(banType)}</span>
            </div>
            <p className="text-[11px] leading-relaxed text-amber-100/90">
              This user has an active{' '}
              {banType === 'LIVE_VIEW_BAN' ? 'livestream' : 'full'} ban and
              cannot be managed with chat ban.
            </p>
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
