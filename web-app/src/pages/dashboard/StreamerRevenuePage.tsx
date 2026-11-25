import { Link } from 'react-router-dom'
import {
  AlertCircle,
  Coins,
  LogIn,
  Radio,
  RefreshCw,
} from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { useStreamerWallet } from '@/hooks/useStreamerWallet'
import { formatCoins, getPaymentErrorMessage } from '@/lib/payment'
import { hasStreamerRole } from '@/lib/utils'
import { useAuthStore } from '@/stores/auth.store'

export function StreamerRevenuePage() {
  const { user, isAuthenticated, isCheckingAuth, openAuthModal } = useAuthStore()
  const isStreamer = hasStreamerRole(user)
  const walletQuery = useStreamerWallet()

  return (
    <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-5xl space-y-7 pb-10">
        <div>
          <h1 className="text-3xl font-extrabold tracking-tight text-white sm:text-4xl font-outfit">
            Donation Statistics
          </h1>
        </div>

        {isCheckingAuth ? (
          <p role="status" className="text-sm text-gray-400">
            Checking your session…
          </p>
        ) : !isAuthenticated ? (
          <div className="space-y-4 rounded-xl border border-[#1f1f26] bg-[#08080a] p-6">
            <p className="text-sm text-gray-300">
              Log in to view your streamer wallet balance.
            </p>
            <Button
              onClick={() => openAuthModal('login')}
              leftIcon={<LogIn className="h-4 w-4" />}
              className="focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42]"
            >
              Log in
            </Button>
          </div>
        ) : !isStreamer ? (
          <div className="space-y-4 rounded-xl border border-[#1f1f26] bg-[#08080a] p-6">
            <div className="flex items-start gap-3">
              <Radio className="mt-0.5 h-5 w-5 shrink-0 text-[#ff2b42]" aria-hidden="true" />
              <div className="space-y-1">
                <h2 className="text-base font-bold text-white font-outfit">
                  Streamer account required
                </h2>
                <p className="text-sm text-gray-400">
                  Register as a streamer to set up your streamer wallet and receive viewer donations.
                </p>
              </div>
            </div>
            <Link
              to="/become-streamer"
              className="inline-flex items-center justify-center rounded-lg bg-[#ff2b42] px-4 py-2 text-sm font-extrabold text-white shadow-lg transition hover:bg-[#e02237] hover:shadow-red-500/25 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42]"
            >
              Become a streamer
            </Link>
          </div>
        ) : walletQuery.isPending ? (
          <section
            aria-label="Loading streamer wallet"
            className="rounded-xl border border-[#1f1f26] bg-[#08080a] p-5 sm:p-7"
          >
            <div className="space-y-3">
              <div
                role="status"
                aria-label="Loading streamer wallet balance"
                className="h-4 w-44 rounded bg-[#101014] animate-pulse motion-reduce:animate-none"
              />
              <div className="h-9 w-48 rounded-lg bg-[#101014] animate-pulse motion-reduce:animate-none" />
              <p className="text-xs text-gray-400">Loading your streamer wallet…</p>
            </div>
          </section>
        ) : walletQuery.isError ? (
          <section
            aria-label="Streamer wallet error"
            className="space-y-4 rounded-xl border border-red-500/30 bg-red-500/10 p-5 sm:p-6"
          >
            <div className="flex items-start gap-2.5 text-sm text-red-400">
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              <p role="alert">
                {getPaymentErrorMessage(
                  walletQuery.error,
                  'Unable to load your streamer wallet right now.',
                )}
              </p>
            </div>
            <Button
              type="button"
              size="sm"
              variant="secondary"
              isLoading={walletQuery.isFetching}
              onClick={() => {
                void walletQuery.refetch()
              }}
              leftIcon={<RefreshCw className="h-3.5 w-3.5" />}
              className="focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42]"
            >
              Try again
            </Button>
          </section>
        ) : (
          <section
            aria-labelledby="streamer-wallet-balance-heading"
            className="rounded-xl border border-[#1f1f26] bg-[#08080a] p-5 sm:p-7"
          >
            <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
              <div className="space-y-2">
                <div className="flex items-center gap-2 text-xs font-medium uppercase tracking-wider text-gray-400">
                  <Coins className="h-4 w-4 text-[#ff2b42]" aria-hidden="true" />
                  <h2 id="streamer-wallet-balance-heading">Streamer Wallet Balance</h2>
                </div>

                <p className="text-3xl font-extrabold text-white font-outfit tabular-nums">
                  {formatCoins(walletQuery.data.availableBalance)}{' '}
                  <span className="text-base font-semibold text-amber-400">
                    Coins
                  </span>
                </p>
              </div>

              <div className="flex flex-wrap items-center gap-2">
                <Button
                  type="button"
                  size="sm"
                  variant="secondary"
                  aria-label="Refresh streamer wallet balance"
                  isLoading={walletQuery.isFetching}
                  onClick={() => {
                    void walletQuery.refetch()
                  }}
                  leftIcon={<RefreshCw className="h-3.5 w-3.5" />}
                  className="focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42]"
                >
                  Refresh
                </Button>
              </div>
            </div>
          </section>
        )}
      </div>
    </main>
  )
}
