import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { isAxiosError } from 'axios'
import {
  AlertCircle,
  Coins,
  Gift,
  RefreshCw,
  X,
} from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { useDonateGift } from '@/hooks/useDonateGift'
import { useVirtualGifts } from '@/hooks/useVirtualGifts'
import { useWalletBalance } from '@/hooks/useWalletBalance'
import { formatCoins } from '@/lib/payment'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse } from '@/types/auth.types'
import type { VirtualGift } from '@/types/payment.types'
import type { StreamStatus } from '@/types/stream.types'

interface StreamDonateControlProps {
  sessionId: string
  status?: StreamStatus | string
  ownerUserId?: string
  onStreamNotLive?: () => void
}

function GiftArtwork({
  imageUrl,
  name,
  size = 'md',
}: {
  imageUrl: string
  name: string
  size?: 'sm' | 'md'
}) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const trimmedUrl = imageUrl?.trim() || ''
  const showFallback = !trimmedUrl || failedUrl === trimmedUrl
  const boxClass = size === 'sm' ? 'w-5 h-5' : 'w-8 h-8'
  const iconClass = size === 'sm' ? 'w-3.5 h-3.5' : 'w-5 h-5'

  if (showFallback) {
    return (
      <span
        aria-hidden="true"
        data-gift-fallback="true"
        className={`${boxClass} inline-flex shrink-0 items-center justify-center rounded-lg bg-[#ff2b42]/15 text-[#ff2b42]`}
      >
        <Gift className={iconClass} />
      </span>
    )
  }

  return (
    <img
      src={trimmedUrl}
      alt={name}
      onError={() => setFailedUrl(trimmedUrl)}
      className={`${boxClass} shrink-0 object-contain select-none`}
    />
  )
}

export function StreamDonateControl({
  sessionId,
  status,
  ownerUserId,
  onStreamNotLive,
}: StreamDonateControlProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const userId = useAuthStore((state) => state.user?.userId ?? '')
  const isLive = status === 'LIVE'
  const isOwnStream = Boolean(userId && ownerUserId && userId === ownerUserId)
  const canSendGifts = isLive && !isOwnStream

  const [openState, setOpenState] = useState<{
    streamId: string
    open: boolean
  }>({ streamId: sessionId, open: false })
  const [selectionState, setSelectionState] = useState<{
    streamId: string
    giftId: string | null
  }>({ streamId: sessionId, giftId: null })

  const isOpen = openState.streamId === sessionId && openState.open
  const selectedGiftId =
    selectionState.streamId === sessionId ? selectionState.giftId : null

  const giftsQuery = useVirtualGifts({ enabled: isOpen })
  const walletQuery = useWalletBalance()
  const {
    pendingAttempt,
    isSending,
    donationError,
    lastDonation,
    comboCount,
    sendGift,
    retryPending,
    dismissRetry,
    showInsufficientCoins,
    clearCelebration,
  } = useDonateGift({ streamId: sessionId, onStreamNotLive })

  const walletMissing =
    isAxiosError<ApiResponse>(walletQuery.error) &&
    (walletQuery.error.response?.status === 404 ||
      walletQuery.error.response?.data?.code === 1006)
  const walletLoaded = walletQuery.isSuccess || walletMissing
  const knownBalance = walletQuery.isSuccess
    ? walletQuery.data.balance
    : walletMissing
      ? 0
      : null

  const gifts = giftsQuery.data ?? []
  const selectedGift = gifts.find((gift) => gift.id === selectedGiftId) ?? null

  const disabledMessage = !isLive
    ? 'Gifts are available while the stream is live.'
    : isOwnStream
      ? 'You cannot send gifts to your own stream.'
      : null

  useEffect(() => {
    if (!isOpen) return

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !isSending) {
        setOpenState({ streamId: sessionId, open: false })
        setSelectionState({ streamId: sessionId, giftId: null })
      }
    }

    const handleMouseDown = (event: MouseEvent) => {
      if (
        !isSending &&
        containerRef.current &&
        !containerRef.current.contains(event.target as Node)
      ) {
        setOpenState({ streamId: sessionId, open: false })
        setSelectionState({ streamId: sessionId, giftId: null })
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    document.addEventListener('mousedown', handleMouseDown)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      document.removeEventListener('mousedown', handleMouseDown)
    }
  }, [isOpen, isSending, sessionId])

  function handleToggle() {
    if (isOpen) {
      if (isSending) return
      setOpenState({ streamId: sessionId, open: false })
      setSelectionState({ streamId: sessionId, giftId: null })
      return
    }
    if (!canSendGifts) return
    setSelectionState({ streamId: sessionId, giftId: null })
    setOpenState({ streamId: sessionId, open: true })
  }

  function handleClose() {
    if (isSending) return
    setOpenState({ streamId: sessionId, open: false })
    setSelectionState({ streamId: sessionId, giftId: null })
  }

  function handleSelectGift(gift: VirtualGift) {
    if (isSending) return
    if (donationError) {
      dismissRetry()
    }
    if (lastDonation && lastDonation.giftId !== gift.id) {
      clearCelebration()
    }
    setSelectionState({ streamId: sessionId, giftId: gift.id })
  }

  function handleBuyClick() {
    if (!canSendGifts || isSending || !selectedGift) return
    if (
      walletLoaded &&
      knownBalance !== null &&
      knownBalance < selectedGift.coinPrice
    ) {
      showInsufficientCoins(selectedGift)
      return
    }
    void sendGift(selectedGift)
  }

  return (
    <div ref={containerRef} className="relative flex items-center gap-2">
      {lastDonation && !isOpen && (
        <div
          role="status"
          aria-live="polite"
          className="hidden sm:inline-flex max-w-[260px] items-center gap-1.5 rounded-full border border-[#ff2b42]/45 bg-[#101014] px-2.5 py-1 text-xs font-semibold text-white shadow animate-pulse motion-reduce:animate-none"
        >
          <GiftArtwork
            imageUrl={lastDonation.giftImageUrl}
            name={lastDonation.giftName}
            size="sm"
          />
          <span className="truncate">
            Sent {lastDonation.giftName} - {formatCoins(lastDonation.coinPrice)}{' '}
            {lastDonation.coinPrice === 1 ? 'Coin' : 'Coins'}
          </span>
          <span className="shrink-0 rounded-full bg-[#ff2b42] px-1.5 py-0.5 text-[10px] font-extrabold text-white tabular-nums">
            x{comboCount}
          </span>
        </div>
      )}

      <button
        type="button"
        onClick={handleToggle}
        disabled={!canSendGifts}
        aria-expanded={isOpen}
        aria-controls={isOpen ? 'donate-gift-popover' : undefined}
        title={disabledMessage ?? undefined}
        className="inline-flex shrink-0 items-center justify-center gap-1.5 rounded-lg bg-[#ff2b42] hover:bg-[#e02237] px-4 py-2 text-sm font-bold text-white shadow-sm transition-colors motion-reduce:transition-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42] disabled:cursor-not-allowed disabled:opacity-50 cursor-pointer"
      >
        <Gift className="h-4.5 w-4.5 shrink-0" aria-hidden="true" />
        <span>Donate</span>
      </button>

      {isOpen && (
        <div
          id="donate-gift-popover"
          role="dialog"
          aria-label="Send a Gift"
          className="absolute right-0 top-full z-40 mt-2 sm:right-full sm:top-1/2 sm:bottom-auto sm:-translate-y-1/2 sm:mt-0 sm:mr-3 w-[min(22rem,calc(100vw-2rem))] sm:w-[376px] rounded-2xl border border-[#1f1f26] bg-[#0a0a0d] p-3.5 shadow-2xl"
        >
          {/* Header & Close Row */}
          <div className="flex items-center justify-between gap-2 border-b border-[#1f1f26] pb-2.5">
            <div className="flex min-w-0 items-center gap-2">
              <Gift className="h-4 w-4 shrink-0 text-[#ff2b42]" aria-hidden="true" />
              <span className="font-outfit text-sm font-bold text-white truncate">
                {selectedGift
                  ? `${selectedGift.name} · ${formatCoins(selectedGift.coinPrice)} ${selectedGift.coinPrice === 1 ? 'Coin' : 'Coins'}`
                  : 'Send a Gift'}
              </span>

              {lastDonation && selectedGift?.id === lastDonation.giftId && (
                <div
                  role="status"
                  aria-live="polite"
                  className="inline-flex shrink-0 items-center rounded-full bg-[#ff2b42] px-2.5 py-1 text-xs sm:text-sm font-extrabold leading-none text-white tabular-nums"
                >
                  <span className="sr-only">
                    Sent {lastDonation.giftName} - {formatCoins(lastDonation.coinPrice)}{' '}
                    {lastDonation.coinPrice === 1 ? 'Coin' : 'Coins'}
                  </span>
                  <span key={`${lastDonation.donationId}-${comboCount}`}>
                    x{comboCount}
                  </span>
                </div>
              )}
            </div>

            <button
              type="button"
              onClick={handleClose}
              aria-label="Close gift panel"
              className="rounded-lg p-1 text-gray-400 hover:bg-white/5 hover:text-white transition-colors motion-reduce:transition-none focus-visible:outline-2 focus-visible:outline-[#ff2b42] cursor-pointer"
            >
              <X className="h-4 w-4" aria-hidden="true" />
            </button>
          </div>

          {/* Non-live / Self-donation Guard Notice */}
          {disabledMessage && (
            <p
              role="status"
              className="mt-2.5 rounded-lg border border-[#1f1f26] bg-[#101014] px-3 py-2 text-xs text-gray-300"
            >
              {disabledMessage}
            </p>
          )}

          {/* Gift Grid */}
          <div className="mt-3 max-h-56 overflow-y-auto p-0.5">
            {giftsQuery.isPending ? (
              <div className="grid grid-cols-3 sm:grid-cols-4 gap-2">
                {Array.from({ length: 8 }).map((_, index) => (
                  <div
                    key={index}
                    aria-hidden="true"
                    className="h-[88px] rounded-xl border border-[#1f1f26] bg-[#101014] animate-pulse motion-reduce:animate-none"
                  />
                ))}
              </div>
            ) : giftsQuery.isError ? (
              <div className="flex items-center justify-between gap-3 rounded-xl border border-[#1f1f26] bg-[#101014] px-3 py-3 text-xs text-gray-300">
                <span>Unable to load gifts.</span>
                <button
                  type="button"
                  onClick={() => void giftsQuery.refetch()}
                  disabled={giftsQuery.isFetching}
                  className="inline-flex shrink-0 items-center gap-1.5 font-semibold text-[#ff2b42] hover:underline cursor-pointer disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-[#ff2b42] rounded"
                >
                  <RefreshCw
                    className={`h-3.5 w-3.5 ${giftsQuery.isFetching ? 'animate-spin motion-reduce:animate-none' : ''}`}
                    aria-hidden="true"
                  />
                  <span>Retry</span>
                </button>
              </div>
            ) : gifts.length === 0 ? (
              <div className="rounded-xl border border-[#1f1f26] bg-[#101014] px-3 py-4 text-center text-xs text-gray-400">
                No gifts available right now.
              </div>
            ) : (
              <div className="grid grid-cols-3 sm:grid-cols-4 gap-2">
                {gifts.map((gift) => {
                  const isSelected = selectedGift?.id === gift.id
                  const coinLabel = gift.coinPrice === 1 ? 'Coin' : 'Coins'

                  return (
                    <button
                      key={gift.id}
                      type="button"
                      aria-pressed={isSelected}
                      aria-label={`${gift.name}, ${formatCoins(gift.coinPrice)} ${coinLabel}`}
                      onClick={() => handleSelectGift(gift)}
                      className={`flex flex-col items-center justify-between gap-1 rounded-xl border px-1.5 py-1.5 text-center transition-colors motion-reduce:transition-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42] cursor-pointer ${
                        isSelected
                          ? 'border-[#ff2b42] bg-[#ff2b42]/12 ring-2 ring-[#ff2b42]/45'
                          : 'border-[#1f1f26] bg-[#101014] hover:border-[#2f3336] hover:bg-[#17171f]'
                      }`}
                    >
                      <GiftArtwork imageUrl={gift.imageUrl} name={gift.name} />

                      <span className="w-full truncate text-[11px] font-semibold text-gray-100">
                        {gift.name}
                      </span>

                      <span className="inline-flex items-center gap-1 text-[11px] font-bold text-amber-400 tabular-nums">
                        <Coins className="h-3 w-3 shrink-0" aria-hidden="true" />
                        <span>{formatCoins(gift.coinPrice)}</span>
                      </span>
                    </button>
                  )
                })}
              </div>
            )}
          </div>

          {/* Error / Insufficient Coins Notice */}
          {donationError && (
            <div
              role="alert"
              className="mt-3 flex flex-wrap items-center justify-between gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 px-2.5 py-2 text-xs text-amber-200"
            >
              <div className="flex min-w-0 items-center gap-1.5">
                <AlertCircle
                  className="h-4 w-4 shrink-0 text-amber-400"
                  aria-hidden="true"
                />
                <span>{donationError.message}</span>
              </div>

              <div className="flex shrink-0 items-center gap-2.5">
                {donationError.insufficientCoins && (
                  <Link
                    to="/wallet"
                    className="font-bold text-[#ff2b42] hover:text-[#ff596b] underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-[#ff2b42] rounded"
                  >
                    Recharge coins
                  </Link>
                )}
                {donationError.retryable && pendingAttempt && (
                  <button
                    type="button"
                    disabled={isSending}
                    onClick={() => void retryPending()}
                    className="font-semibold text-white underline underline-offset-2 hover:text-amber-100 cursor-pointer disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-[#ff2b42] rounded"
                  >
                    Retry
                  </button>
                )}
                <button
                  type="button"
                  onClick={dismissRetry}
                  className="font-medium text-gray-300 underline underline-offset-2 hover:text-white cursor-pointer focus-visible:outline-2 focus-visible:outline-[#ff2b42] rounded"
                >
                  Dismiss
                </button>
              </div>
            </div>
          )}

          {/* Fixed-height Footer: User credit on left, Buy button opposite when gift selected */}
          <div className="mt-3 flex h-11 items-center justify-between gap-3 border-t border-[#1f1f26] pt-3">
            <div className="flex min-w-0 items-center gap-2 whitespace-nowrap">
              <div className="flex items-center gap-1.5">
                <Coins className="h-4 w-4 shrink-0 text-amber-400" aria-hidden="true" />
                {walletQuery.isPending ? (
                  <div
                    aria-label="Loading balance"
                    className="h-4 w-16 rounded bg-[#1a1a22] animate-pulse motion-reduce:animate-none"
                  />
                ) : walletLoaded && knownBalance !== null ? (
                  <span className="text-xs sm:text-sm font-bold text-white tabular-nums">
                    {formatCoins(knownBalance)}{' '}
                    <span className="font-semibold text-amber-400">
                      {knownBalance === 1 ? 'Coin' : 'Coins'}
                    </span>
                  </span>
                ) : (
                  <div className="flex items-center gap-1.5">
                    <span className="text-xs font-semibold text-gray-400 tabular-nums">
                      — Coins
                    </span>
                    <button
                      type="button"
                      aria-label="Retry loading balance"
                      onClick={() => void walletQuery.refetch()}
                      className="text-gray-400 hover:text-white cursor-pointer focus-visible:outline-2 focus-visible:outline-[#ff2b42] rounded"
                    >
                      <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
                    </button>
                  </div>
                )}
              </div>

              <Link
                to="/wallet"
                className="text-xs font-bold text-[#ff2b42] hover:text-[#ff596b] underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-[#ff2b42] rounded"
              >
                Recharge
              </Link>
            </div>

            {selectedGift ? (
              <Button
                type="button"
                variant="primary"
                size="sm"
                aria-busy={isSending}
                disabled={!canSendGifts}
                onClick={handleBuyClick}
                className="h-8 shrink-0 px-4 text-[15px] leading-none"
              >
                Buy
              </Button>
            ) : (
              <span className="text-[11px] text-gray-500 shrink-0">
                Select a gift
              </span>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
