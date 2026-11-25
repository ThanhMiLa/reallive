import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { isAxiosError } from 'axios'
import {
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  Coins,
  Gift,
  Loader2,
  RefreshCw,
} from 'lucide-react'
import { useDonateGift } from '@/hooks/useDonateGift'
import { useVirtualGifts } from '@/hooks/useVirtualGifts'
import { useWalletBalance } from '@/hooks/useWalletBalance'
import { formatCoins } from '@/lib/payment'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse } from '@/types/auth.types'
import type { VirtualGift } from '@/types/payment.types'
import type { StreamStatus } from '@/types/stream.types'

interface StreamGiftBarProps {
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
  const boxClass = size === 'sm' ? 'w-5 h-5' : 'w-9 h-9'
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

export function StreamGiftBar({
  sessionId,
  status,
  ownerUserId,
  onStreamNotLive,
}: StreamGiftBarProps) {
  const trayRef = useRef<HTMLDivElement>(null)
  const userId = useAuthStore((state) => state.user?.userId ?? '')
  const isLive = status === 'LIVE'
  const isOwnStream = Boolean(userId && ownerUserId && userId === ownerUserId)
  const canSendGifts = isLive && !isOwnStream

  const giftsQuery = useVirtualGifts()
  const walletQuery = useWalletBalance()
  const {
    pendingAttempt,
    activeGiftId,
    isSending,
    donationError,
    lastDonation,
    sendGift,
    retryPending,
    dismissRetry,
    showInsufficientCoins,
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

  function handleGiftClick(gift: VirtualGift) {
    if (!canSendGifts || isSending) return
    if (walletLoaded && knownBalance !== null && knownBalance < gift.coinPrice) {
      showInsufficientCoins(gift)
      return
    }
    void sendGift(gift)
  }

  function scrollTray(direction: -1 | 1) {
    trayRef.current?.scrollBy({ left: direction * 220, behavior: 'smooth' })
  }

  const disabledMessage = !isLive
    ? 'Gifts are available while the stream is live.'
    : isOwnStream
      ? 'You cannot send gifts to your own stream.'
      : null

  return (
    <section
      aria-label="Send a gift"
      className="min-w-0 rounded-xl border border-[#1f1f26] bg-[#08080a] p-3 sm:p-3.5"
    >
      {/* Header / Status / Sent Celebration Row */}
      {(lastDonation || disabledMessage) && (
        <div className="mb-2.5 flex flex-wrap items-center justify-between gap-2">
          {lastDonation ? (
            <div
              role="status"
              aria-live="polite"
              className="inline-flex max-w-full items-center gap-2 rounded-full border border-[#ff2b42]/45 bg-[#101014] px-3 py-1 text-xs font-semibold text-white shadow-lg animate-pulse motion-reduce:animate-none"
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
            </div>
          ) : (
            <span />
          )}

          {disabledMessage && (
            <p role="status" className="text-xs text-gray-400">
              {disabledMessage}
            </p>
          )}
        </div>
      )}

      {/* Main Row: Gift Tray (Left) + Wallet & Recharge Strip (Right on desktop, compact row on mobile) */}
      <div className="flex min-w-0 flex-col gap-3 md:flex-row md:items-center">
        <div className="flex min-w-0 flex-1 items-center gap-1.5">
          {giftsQuery.isSuccess && gifts.length > 0 && (
            <button
              type="button"
              aria-label="Scroll gifts left"
              onClick={() => scrollTray(-1)}
              className="hidden sm:inline-flex h-8 w-7 shrink-0 items-center justify-center rounded-lg border border-[#1f1f26] bg-[#101014] text-gray-400 transition-colors hover:border-[#2f3336] hover:text-white motion-reduce:transition-none focus-visible:outline-2 focus-visible:outline-[#ff2b42] cursor-pointer"
            >
              <ChevronLeft className="h-4 w-4" aria-hidden="true" />
            </button>
          )}

          <div
            ref={trayRef}
            className="watch-stream-scroll flex min-w-0 flex-1 items-stretch gap-2 overflow-x-auto py-1 px-0.5 scroll-smooth motion-reduce:scroll-auto"
          >
            {giftsQuery.isPending ? (
              Array.from({ length: 8 }).map((_, index) => (
                <div
                  key={index}
                  aria-hidden="true"
                  className="h-[88px] w-[78px] sm:w-[86px] shrink-0 rounded-xl border border-[#1f1f26] bg-[#101014] animate-pulse motion-reduce:animate-none"
                />
              ))
            ) : giftsQuery.isError ? (
              <div className="flex min-w-0 flex-1 items-center justify-between gap-3 rounded-xl border border-[#1f1f26] bg-[#101014] px-3 py-3 text-xs text-gray-300">
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
              <div className="flex min-w-0 flex-1 items-center justify-center rounded-xl border border-[#1f1f26] bg-[#101014] px-3 py-3 text-xs text-gray-400">
                No gifts available right now.
              </div>
            ) : (
              gifts.map((gift) => {
                const isTileSending = isSending && activeGiftId === gift.id
                const isDisabled = !canSendGifts || isSending
                const coinLabel = gift.coinPrice === 1 ? 'Coin' : 'Coins'

                return (
                  <button
                    key={gift.id}
                    type="button"
                    disabled={isDisabled}
                    aria-label={`Send ${gift.name} for ${formatCoins(gift.coinPrice)} ${coinLabel}`}
                    onClick={() => handleGiftClick(gift)}
                    className="group relative flex w-[78px] sm:w-[86px] shrink-0 flex-col items-center justify-between gap-1.5 rounded-xl border border-[#1f1f26] bg-[#101014] px-2 py-2 text-center transition-colors hover:border-[#ff2b42]/75 hover:bg-[#17171f] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42] disabled:cursor-not-allowed disabled:opacity-45 disabled:hover:border-[#1f1f26] disabled:hover:bg-[#101014] motion-reduce:transition-none cursor-pointer"
                  >
                    {!isDisabled && (
                      <span
                        aria-hidden="true"
                        className="pointer-events-none absolute -top-1.5 right-1 rounded bg-[#ff2b42] px-1.5 py-0.5 text-[9px] font-extrabold uppercase tracking-wider text-white opacity-0 shadow transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100 motion-reduce:transition-none"
                      >
                        Send
                      </span>
                    )}

                    <div className="flex h-9 w-9 items-center justify-center">
                      {isTileSending ? (
                        <Loader2
                          aria-hidden="true"
                          className="h-5 w-5 animate-spin motion-reduce:animate-none text-[#ff2b42]"
                        />
                      ) : (
                        <GiftArtwork imageUrl={gift.imageUrl} name={gift.name} />
                      )}
                    </div>

                    <span className="w-full truncate text-xs font-semibold text-gray-100">
                      {gift.name}
                    </span>

                    <span className="inline-flex items-center gap-1 text-[11px] font-bold text-amber-400 tabular-nums">
                      <Coins className="h-3 w-3 shrink-0" aria-hidden="true" />
                      <span>{formatCoins(gift.coinPrice)}</span>
                    </span>
                  </button>
                )
              })
            )}
          </div>

          {giftsQuery.isSuccess && gifts.length > 0 && (
            <button
              type="button"
              aria-label="Scroll gifts right"
              onClick={() => scrollTray(1)}
              className="hidden sm:inline-flex h-8 w-7 shrink-0 items-center justify-center rounded-lg border border-[#1f1f26] bg-[#101014] text-gray-400 transition-colors hover:border-[#2f3336] hover:text-white motion-reduce:transition-none focus-visible:outline-2 focus-visible:outline-[#ff2b42] cursor-pointer"
            >
              <ChevronRight className="h-4 w-4" aria-hidden="true" />
            </button>
          )}
        </div>

        {/* Wallet & Recharge Strip */}
        <div className="flex shrink-0 items-center justify-between gap-3 border-t border-[#1f1f26] pt-2.5 md:border-t-0 md:border-l md:pt-0 md:pl-3.5">
          <div className="flex items-center gap-2 min-w-0">
            <Coins className="h-4 w-4 shrink-0 text-amber-400" aria-hidden="true" />
            {walletQuery.isPending ? (
              <div
                aria-label="Loading balance"
                className="h-4 w-20 rounded bg-[#14141a] animate-pulse motion-reduce:animate-none"
              />
            ) : walletLoaded && knownBalance !== null ? (
              <span className="text-xs sm:text-sm font-bold text-white tabular-nums whitespace-nowrap">
                {formatCoins(knownBalance)}{' '}
                <span className="font-semibold text-amber-400">
                  {knownBalance === 1 ? 'Coin' : 'Coins'}
                </span>
              </span>
            ) : (
              <div className="flex items-center gap-1.5">
                <span className="text-xs font-semibold text-gray-400 tabular-nums whitespace-nowrap">
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
            className="inline-flex shrink-0 items-center justify-center rounded-lg bg-[#ff2b42] hover:bg-[#e02237] px-3 py-1.5 text-xs font-extrabold text-white transition-colors motion-reduce:transition-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42]"
          >
            Recharge
          </Link>
        </div>
      </div>

      {/* Inline Feedback / Retryable Error / Insufficient Coins Notice */}
      {donationError && (
        <div
          role="alert"
          className="mt-2.5 flex flex-wrap items-center justify-between gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-200"
        >
          <div className="flex min-w-0 items-center gap-2">
            <AlertCircle
              className="h-4 w-4 shrink-0 text-amber-400"
              aria-hidden="true"
            />
            <span>{donationError.message}</span>
          </div>

          <div className="flex shrink-0 items-center gap-3">
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
    </section>
  )
}
