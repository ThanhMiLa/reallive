import { useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import {
  AlertCircle,
  Ban,
  CheckCircle2,
  Clock,
  Coins,
  Loader2,
  LogIn,
  RefreshCw,
  XCircle,
} from 'lucide-react'
import { Link, useSearchParams } from 'react-router-dom'
import { paymentApi } from '@/api/payment.api'
import { Button } from '@/components/ui/Button'
import { formatCoins, formatVnd, getPaymentErrorMessage } from '@/lib/payment'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse } from '@/types/auth.types'
import type { DepositStatusResponse } from '@/types/payment.types'

export const VNPAY_POLL_INTERVAL_MS = 1000
export const VNPAY_POLL_MAX_DURATION_MS = 10_000

const ctaPrimaryClass =
  'inline-flex items-center justify-center gap-2 rounded-lg bg-[#ff2b42] hover:bg-[#e02237] px-4 py-2.5 text-sm font-extrabold text-white shadow-lg transition motion-reduce:transition-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42]'

const ctaSecondaryClass =
  'inline-flex items-center justify-center gap-2 rounded-lg border border-[#1f1f26] bg-[#101014] hover:bg-[#18181f] px-4 py-2.5 text-sm font-medium text-gray-200 transition motion-reduce:transition-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42]'

function getStatusErrorText(error: unknown): string {
  if (isAxiosError<ApiResponse>(error)) {
    const status = error.response?.status
    const code = error.response?.data?.code
    if (status === 404 || code === 1008) {
      return 'Deposit order not found or you do not have permission to view it.'
    }
  }
  return getPaymentErrorMessage(
    error,
    'Unable to verify your payment status right now. Please try again.',
  )
}

function VnpayReturnStatus({
  orderCode,
  userId,
}: {
  orderCode: string
  userId?: string
}) {
  const queryClient = useQueryClient()
  const [deposit, setDeposit] = useState<DepositStatusResponse | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [timedOut, setTimedOut] = useState(false)
  const [isLoading, setIsLoading] = useState(true)
  const [pollAttempt, setPollAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    let timeoutId: ReturnType<typeof setTimeout> | null = null
    const startedAt = Date.now()

    const pollStatus = async () => {
      try {
        const result = await paymentApi.getDepositStatus(orderCode)
        if (cancelled) return

        setDeposit(result)
        setError(null)
        setIsLoading(false)

        if (result.status === 'PAID') {
          if (userId) {
            void queryClient.invalidateQueries({ queryKey: ['wallet', userId] })
          }
          return
        }

        if (result.status === 'PENDING') {
          if (Date.now() - startedAt >= VNPAY_POLL_MAX_DURATION_MS) {
            setTimedOut(true)
            return
          }
          timeoutId = setTimeout(() => {
            if (!cancelled) {
              void pollStatus()
            }
          }, VNPAY_POLL_INTERVAL_MS)
        }
      } catch (cause) {
        if (cancelled) return
        setError(cause)
        setIsLoading(false)
      }
    }

    void pollStatus()

    return () => {
      cancelled = true
      if (timeoutId !== null) {
        clearTimeout(timeoutId)
      }
    }
  }, [orderCode, userId, queryClient, pollAttempt])

  const handleCheckAgain = () => {
    setIsLoading(true)
    setError(null)
    setTimedOut(false)
    setPollAttempt((prev) => prev + 1)
  }

  if (error && !isLoading) {
    return (
      <section
        aria-live="polite"
        className="rounded-xl border border-red-500/30 bg-[#08080a] p-6 sm:p-8 space-y-5"
      >
        <div className="flex items-start gap-3">
          <AlertCircle className="mt-0.5 h-6 w-6 shrink-0 text-red-400" aria-hidden="true" />
          <div className="space-y-1.5">
            <h2 className="text-lg font-bold text-white font-outfit">
              Unable to verify payment
            </h2>
            <p role="alert" className="text-sm text-red-400">
              {getStatusErrorText(error)}
            </p>
            <p className="text-xs text-gray-400">Order reference: #{orderCode}</p>
          </div>
        </div>
        <div className="flex flex-wrap gap-3 pt-2">
          <Button
            type="button"
            onClick={handleCheckAgain}
            leftIcon={<RefreshCw className="h-4 w-4" />}
          >
            Check again
          </Button>
          <Link to="/wallet" className={ctaSecondaryClass}>
            Back to Wallet
          </Link>
        </div>
      </section>
    )
  }

  if (isLoading || (deposit?.status === 'PENDING' && !timedOut)) {
    return (
      <section
        role="status"
        aria-live="polite"
        className="rounded-xl border border-[#1f1f26] bg-[#08080a] p-6 sm:p-8 space-y-4"
      >
        <div className="flex items-center gap-3">
          <Loader2
            className="h-6 w-6 shrink-0 text-[#ff2b42] animate-spin motion-reduce:animate-none"
            aria-hidden="true"
          />
          <div>
            <h2 className="text-lg font-bold text-white font-outfit">
              Confirming your payment with VNPAY...
            </h2>
            <p className="mt-1 text-sm text-gray-400">
              Order #{orderCode} • Verifying transaction status with RealLive.
            </p>
          </div>
        </div>
      </section>
    )
  }

  if (deposit?.status === 'PAID') {
    return (
      <section
        aria-live="polite"
        className="rounded-xl border border-emerald-500/30 bg-[#08080a] p-6 sm:p-8 space-y-6"
      >
        <div className="flex items-start gap-3">
          <CheckCircle2
            className="mt-0.5 h-7 w-7 shrink-0 text-emerald-400"
            aria-hidden="true"
          />
          <div className="space-y-1">
            <h2 className="text-xl font-extrabold text-white font-outfit">
              Payment successful
            </h2>
            <p className="text-sm text-gray-300">
              Your coins have been credited to your RealLive wallet.
            </p>
          </div>
        </div>

        <dl className="grid gap-4 rounded-xl border border-[#1f1f26] bg-[#101014] p-4 sm:grid-cols-3">
          <div>
            <dt className="text-xs text-gray-400">Coins credited</dt>
            <dd className="mt-1 flex items-center gap-1.5 text-lg font-extrabold text-emerald-400 font-outfit tabular-nums">
              <Coins className="h-4 w-4" aria-hidden="true" />
              +{formatCoins(deposit.coinAmount)} Coins
            </dd>
          </div>
          <div>
            <dt className="text-xs text-gray-400">Updated wallet balance</dt>
            <dd className="mt-1 text-lg font-extrabold text-white font-outfit tabular-nums">
              {formatCoins(deposit.walletBalance)} Coins
            </dd>
          </div>
          <div>
            <dt className="text-xs text-gray-400">Order code</dt>
            <dd className="mt-1 text-sm font-semibold text-gray-200 tabular-nums">
              #{deposit.orderCode}
            </dd>
          </div>
          <div>
            <dt className="text-xs text-gray-400">Amount paid</dt>
            <dd className="mt-1 text-sm font-medium text-gray-200 tabular-nums">
              {formatVnd(deposit.amountVnd)}
            </dd>
          </div>
          {deposit.providerTransactionNo && (
            <div>
              <dt className="text-xs text-gray-400">VNPAY transaction</dt>
              <dd className="mt-1 text-sm font-medium text-gray-200 tabular-nums">
                {deposit.providerTransactionNo}
              </dd>
            </div>
          )}
        </dl>

        <div className="flex flex-wrap gap-3">
          <Link to="/wallet" className={ctaPrimaryClass}>
            Back to Wallet
          </Link>
          <Link to="/profile" className={ctaSecondaryClass}>
            View Profile
          </Link>
        </div>
      </section>
    )
  }

  if (deposit?.status === 'CANCELLED') {
    return (
      <section
        aria-live="polite"
        className="rounded-xl border border-amber-500/30 bg-[#08080a] p-6 sm:p-8 space-y-5"
      >
        <div className="flex items-start gap-3">
          <Ban className="mt-0.5 h-6 w-6 shrink-0 text-amber-400" aria-hidden="true" />
          <div className="space-y-1.5">
            <h2 className="text-xl font-bold text-white font-outfit">
              Payment cancelled
            </h2>
            <p className="text-sm text-gray-300">
              Your VNPAY transaction for order #{deposit.orderCode} was cancelled. No
              coins were added to your wallet.
            </p>
          </div>
        </div>
        <div className="flex flex-wrap gap-3">
          <Link to="/wallet" className={ctaPrimaryClass}>
            Try again
          </Link>
          <Link to="/profile" className={ctaSecondaryClass}>
            View Profile
          </Link>
        </div>
      </section>
    )
  }

  if (deposit?.status === 'FAILED') {
    return (
      <section
        aria-live="polite"
        className="rounded-xl border border-red-500/30 bg-[#08080a] p-6 sm:p-8 space-y-5"
      >
        <div className="flex items-start gap-3">
          <XCircle className="mt-0.5 h-6 w-6 shrink-0 text-red-400" aria-hidden="true" />
          <div className="space-y-1.5">
            <h2 className="text-xl font-bold text-white font-outfit">
              Payment failed
            </h2>
            <p className="text-sm text-gray-300">
              VNPAY could not complete order #{deposit.orderCode}.
              {deposit.providerResponseCode
                ? ` Response code: ${deposit.providerResponseCode}.`
                : ''}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap gap-3">
          <Link to="/wallet" className={ctaPrimaryClass}>
            Try again
          </Link>
          <Link to="/profile" className={ctaSecondaryClass}>
            View Profile
          </Link>
        </div>
      </section>
    )
  }

  return (
    <section
      aria-live="polite"
      className="rounded-xl border border-amber-500/30 bg-[#08080a] p-6 sm:p-8 space-y-5"
    >
      <div className="flex items-start gap-3">
        <Clock className="mt-0.5 h-6 w-6 shrink-0 text-amber-400" aria-hidden="true" />
        <div className="space-y-1.5">
          <h2 className="text-xl font-bold text-white font-outfit">
            Payment verification in progress
          </h2>
          <p className="text-sm text-gray-300">
            Your payment for order #{orderCode} is still being verified. Your coins
            will be credited automatically once VNPAY confirms the transaction.
          </p>
        </div>
      </div>
      <div className="flex flex-wrap gap-3">
        <Button
          type="button"
          onClick={handleCheckAgain}
          leftIcon={<RefreshCw className="h-4 w-4" />}
        >
          Check again
        </Button>
        <Link to="/wallet" className={ctaSecondaryClass}>
          Back to Wallet
        </Link>
      </div>
    </section>
  )
}

export function VnpayReturnPage() {
  const { user, isAuthenticated, isCheckingAuth, openAuthModal } = useAuthStore()
  const [searchParams] = useSearchParams()
  const txnRef = searchParams.get('vnp_TxnRef')?.trim() ?? ''
  const isValidOrderCode = /^\d+$/.test(txnRef)

  return (
    <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-3xl space-y-7 pb-10">
        <div>
          <h1 className="text-3xl font-extrabold tracking-tight text-white sm:text-4xl font-outfit">
            Payment Status
          </h1>
        </div>

        {isCheckingAuth ? (
          <p role="status" className="text-sm text-gray-400">
            Checking your session…
          </p>
        ) : !isAuthenticated ? (
          <div className="space-y-4 rounded-xl border border-[#1f1f26] bg-[#08080a] p-6">
            <p className="text-sm text-gray-300">
              Log in to verify your VNPAY payment status.
            </p>
            <Button
              onClick={() => openAuthModal('login')}
              leftIcon={<LogIn className="h-4 w-4" />}
            >
              Log in
            </Button>
          </div>
        ) : !isValidOrderCode ? (
          <section className="rounded-xl border border-red-500/30 bg-[#08080a] p-6 sm:p-8 space-y-5">
            <div className="flex items-start gap-3">
              <AlertCircle
                className="mt-0.5 h-6 w-6 shrink-0 text-red-400"
                aria-hidden="true"
              />
              <div className="space-y-1.5">
                <h2 className="text-lg font-bold text-white font-outfit">
                  Missing payment reference
                </h2>
                <p role="alert" className="text-sm text-red-400">
                  We could not find a valid order reference (vnp_TxnRef) in the return
                  URL.
                </p>
              </div>
            </div>
            <div>
              <Link to="/wallet" className={ctaPrimaryClass}>
                Back to Wallet
              </Link>
            </div>
          </section>
        ) : (
          <VnpayReturnStatus key={txnRef} orderCode={txnRef} userId={user?.userId} />
        )}
      </div>
    </main>
  )
}
