import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { paymentApi } from '@/api/payment.api'
import { VIRTUAL_GIFTS_QUERY_KEY } from '@/hooks/useVirtualGifts'
import { formatCoins } from '@/lib/payment'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse } from '@/types/auth.types'
import type {
  DonateGiftResponse,
  VirtualGift,
  WalletBalance,
} from '@/types/payment.types'

export interface PendingGiftAttempt {
  idempotencyKey: string
  streamId: string
  gift: VirtualGift
}

export interface GiftDonationError {
  message: string
  retryable: boolean
  insufficientCoins: boolean
}

interface UseDonateGiftOptions {
  streamId: string
  onStreamNotLive?: () => void
}

const CELEBRATION_DURATION_MS = 3000

const DEFINITIVE_ERROR_MESSAGES: Record<number, string> = {
  1006: 'Wallet not found. Recharge your wallet to send gifts.',
  1014: 'Stream session not found.',
  1015: 'This stream is no longer live.',
  1016: 'Stream is not accessible.',
  1018: 'You cannot donate to your own stream.',
  1019: 'Virtual gift not found.',
  1020: 'Virtual gift is no longer available.',
  1021: 'Insufficient coin balance.',
  1022: 'This gift request could not be retried. Please send again.',
}

export function useDonateGift({ streamId, onStreamNotLive }: UseDonateGiftOptions) {
  const queryClient = useQueryClient()
  const userId = useAuthStore((state) => state.user?.userId ?? '')

  const [pendingState, setPendingState] = useState<{
    streamId: string
    attempt: PendingGiftAttempt
  } | null>(null)
  const [sendingState, setSendingState] = useState<{
    streamId: string
    giftId: string
  } | null>(null)
  const [errorState, setErrorState] = useState<{
    streamId: string
    error: GiftDonationError
  } | null>(null)
  const [celebrationState, setCelebrationState] = useState<{
    streamId: string
    donation: DonateGiftResponse
    nonce: number
  } | null>(null)

  const inFlightRef = useRef(false)
  const nonceRef = useRef(0)
  const celebrationTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const currentScopeRef = useRef({ streamId, userId })

  useLayoutEffect(() => {
    currentScopeRef.current = { streamId, userId }
    inFlightRef.current = false
  }, [streamId, userId])

  useEffect(() => {
    return () => {
      if (celebrationTimerRef.current !== null) {
        clearTimeout(celebrationTimerRef.current)
        celebrationTimerRef.current = null
      }
    }
  }, [streamId])

  const pendingAttempt =
    pendingState && pendingState.streamId === streamId ? pendingState.attempt : null
  const activeGiftId =
    sendingState && sendingState.streamId === streamId ? sendingState.giftId : null
  const isSending = activeGiftId !== null
  const donationError =
    errorState && errorState.streamId === streamId ? errorState.error : null
  const lastDonation =
    celebrationState && celebrationState.streamId === streamId
      ? celebrationState.donation
      : null

  const executeAttempt = useCallback(
    async (attempt: PendingGiftAttempt) => {
      if (inFlightRef.current || !attempt.streamId) return

      const requestUserId = currentScopeRef.current.userId
      inFlightRef.current = true
      setPendingState({ streamId: attempt.streamId, attempt })
      setSendingState({ streamId: attempt.streamId, giftId: attempt.gift.id })
      setErrorState(null)

      try {
        const result = await paymentApi.donateGift({
          streamId: attempt.streamId,
          giftId: attempt.gift.id,
          idempotencyKey: attempt.idempotencyKey,
        })

        if (requestUserId) {
          queryClient.setQueryData<WalletBalance>(
            ['wallet', requestUserId],
            (previous) =>
              previous
                ? { ...previous, balance: result.remainingBalance }
                : {
                    walletId: '',
                    userId: requestUserId,
                    balance: result.remainingBalance,
                  },
          )
          void queryClient.invalidateQueries({
            queryKey: ['wallet', requestUserId],
            exact: true,
          })
          void queryClient.invalidateQueries({
            queryKey: ['wallet', requestUserId, 'create'],
            exact: true,
          })
        }

        if (currentScopeRef.current.streamId !== attempt.streamId) return

        setPendingState(null)
        setErrorState(null)

        const nextNonce = nonceRef.current + 1
        nonceRef.current = nextNonce
        setCelebrationState({
          streamId: attempt.streamId,
          donation: result,
          nonce: nextNonce,
        })

        if (celebrationTimerRef.current !== null) {
          clearTimeout(celebrationTimerRef.current)
        }
        celebrationTimerRef.current = setTimeout(() => {
          setCelebrationState((previous) =>
            previous && previous.nonce === nextNonce ? null : previous,
          )
          celebrationTimerRef.current = null
        }, CELEBRATION_DURATION_MS)
      } catch (error) {
        if (currentScopeRef.current.streamId !== attempt.streamId) return

        if (isAxiosError<ApiResponse>(error) && error.response) {
          const { status, data } = error.response
          if (status >= 400 && status < 500) {
            setPendingState(null)

            const code = data?.code
            if (code === 1015) {
              onStreamNotLive?.()
            } else if (code === 1020) {
              void queryClient.invalidateQueries({ queryKey: VIRTUAL_GIFTS_QUERY_KEY })
            }

            const insufficientCoins = code === 1006 || code === 1021
            const message =
              (code !== undefined && DEFINITIVE_ERROR_MESSAGES[code]) ||
              (status === 401
                ? 'Your session has expired. Please log in again.'
                : data?.message || 'Unable to send gift.')

            setErrorState({
              streamId: attempt.streamId,
              error: {
                message,
                retryable: false,
                insufficientCoins,
              },
            })
            return
          }

          setErrorState({
            streamId: attempt.streamId,
            error: {
              message:
                data?.message || 'Unable to send gift right now. Please try again.',
              retryable: true,
              insufficientCoins: false,
            },
          })
          return
        }

        setErrorState({
          streamId: attempt.streamId,
          error: {
            message: 'Network error while sending gift. Please try again.',
            retryable: true,
            insufficientCoins: false,
          },
        })
      } finally {
        if (currentScopeRef.current.streamId === attempt.streamId) {
          inFlightRef.current = false
          setSendingState((previous) =>
            previous?.streamId === attempt.streamId ? null : previous,
          )
        }
      }
    },
    [onStreamNotLive, queryClient],
  )

  const sendGift = useCallback(
    async (gift: VirtualGift) => {
      if (!streamId || inFlightRef.current) return
      const attempt: PendingGiftAttempt = {
        idempotencyKey: crypto.randomUUID(),
        streamId,
        gift,
      }
      await executeAttempt(attempt)
    },
    [executeAttempt, streamId],
  )

  const retryPending = useCallback(async () => {
    if (!pendingAttempt || inFlightRef.current) return
    await executeAttempt(pendingAttempt)
  }, [executeAttempt, pendingAttempt])

  const dismissRetry = useCallback(() => {
    setPendingState((previous) =>
      previous?.streamId === streamId ? null : previous,
    )
    setErrorState((previous) =>
      previous?.streamId === streamId ? null : previous,
    )
  }, [streamId])

  const showInsufficientCoins = useCallback(
    (gift: VirtualGift) => {
      if (!streamId || inFlightRef.current) return
      const coinLabel = gift.coinPrice === 1 ? 'Coin' : 'Coins'
      setPendingState((previous) =>
        previous?.streamId === streamId ? null : previous,
      )
      setErrorState({
        streamId,
        error: {
          message: `Insufficient coins to send ${gift.name} (${formatCoins(gift.coinPrice)} ${coinLabel}).`,
          retryable: false,
          insufficientCoins: true,
        },
      })
    },
    [streamId],
  )

  return {
    pendingAttempt,
    activeGiftId,
    isSending,
    donationError,
    lastDonation,
    sendGift,
    retryPending,
    dismissRetry,
    showInsufficientCoins,
  }
}
