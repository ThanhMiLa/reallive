import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  AlertCircle,
  Check,
  Coins,
  CreditCard,
  LogIn,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react'
import { toast } from 'sonner'
import { paymentApi } from '@/api/payment.api'
import { Button } from '@/components/ui/Button'
import { useWalletBalance } from '@/hooks/useWalletBalance'
import {
  formatCoins,
  formatVnd,
  getPaymentErrorMessage,
  isSafeRedirectUrl,
} from '@/lib/payment'
import { useAuthStore } from '@/stores/auth.store'
import type { DepositPackage } from '@/types/payment.types'

function PackageCard({
  pkg,
  isSelected,
  disabled,
  onSelect,
}: {
  pkg: DepositPackage
  isSelected: boolean
  disabled: boolean
  onSelect: () => void
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={isSelected}
      disabled={disabled}
      onClick={onSelect}
      className={`group relative flex flex-col justify-between rounded-xl border p-5 text-left transition-colors duration-200 motion-reduce:transition-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42] cursor-pointer disabled:cursor-not-allowed disabled:opacity-60 ${
        isSelected
          ? 'border-[#ff2b42] bg-[#141016] shadow-[0_0_20px_rgba(255,43,66,0.2)]'
          : 'border-[#1f1f26] bg-[#101014] hover:border-[#343442] hover:bg-[#14141a]'
      }`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <div
            className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-lg border ${
              isSelected
                ? 'border-[#ff2b42]/40 bg-[#ff2b42]/15 text-[#ff2b42]'
                : 'border-[#1f1f26] bg-[#18181f] text-amber-400'
            }`}
          >
            <Coins className="h-5 w-5" aria-hidden="true" />
          </div>
          <div className="min-w-0">
            <p className="text-xl font-extrabold text-white font-outfit tabular-nums">
              {formatCoins(pkg.coinAmount)}
            </p>
            <p className="text-xs font-medium text-gray-400">Coins</p>
          </div>
        </div>
        <span
          aria-hidden="true"
          className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full border transition-colors motion-reduce:transition-none ${
            isSelected
              ? 'border-[#ff2b42] bg-[#ff2b42] text-white'
              : 'border-[#2f2f3b] bg-[#08080a] text-transparent'
          }`}
        >
          <Check className="h-3 w-3 stroke-[3]" />
        </span>
      </div>

      <div className="mt-5 flex items-baseline justify-between gap-2 border-t border-[#1f1f26] pt-3.5">
        <span className="truncate text-xs text-gray-400">{pkg.displayName}</span>
        <span className="shrink-0 text-sm font-bold text-gray-100 tabular-nums">
          {formatVnd(pkg.amountVnd)}
        </span>
      </div>
    </button>
  )
}

export function WalletCreditsPage() {
  const { user, isAuthenticated, isCheckingAuth, openAuthModal } = useAuthStore()
  const [selectedPackageCode, setSelectedPackageCode] = useState<string | null>(null)
  const [checkoutError, setCheckoutError] = useState<string | null>(null)
  const [isCreatingOrder, setIsCreatingOrder] = useState(false)

  const walletQuery = useWalletBalance()
  const packagesQuery = useQuery({
    queryKey: ['deposit-packages'],
    queryFn: () => paymentApi.getDepositPackages(),
    enabled: isAuthenticated && !isCheckingAuth && Boolean(user?.userId),
    refetchOnReconnect: false,
  })

  const packages = packagesQuery.data ?? []
  const effectivePackage =
    packages.find((pkg) => pkg.packageCode === selectedPackageCode) ?? packages[0] ?? null

  const handleCheckout = async () => {
    if (!effectivePackage || isCreatingOrder) return
    setCheckoutError(null)
    setIsCreatingOrder(true)
    try {
      const order = await paymentApi.createVnpayDeposit(effectivePackage.packageCode)
      if (!isSafeRedirectUrl(order.paymentUrl)) {
        const message = 'Received an invalid payment redirect URL from the server.'
        setCheckoutError(message)
        toast.error(message)
        setIsCreatingOrder(false)
        return
      }
      window.location.assign(order.paymentUrl)
    } catch (error) {
      const message = getPaymentErrorMessage(
        error,
        'Unable to create VNPAY payment order. Please try again.',
      )
      setCheckoutError(message)
      toast.error(message)
      setIsCreatingOrder(false)
    }
  }

  return (
    <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-5xl space-y-7 pb-10">
        <div>
          <h1 className="text-3xl font-extrabold tracking-tight text-white sm:text-4xl font-outfit">
            Wallet &amp; Coins
          </h1>
          <p className="mt-1.5 text-sm text-gray-400">
            Top up your RealLive coin balance securely via VNPAY.
          </p>
        </div>

        {isCheckingAuth ? (
          <p role="status" className="text-sm text-gray-400">
            Checking your session…
          </p>
        ) : !isAuthenticated ? (
          <div className="space-y-4 rounded-xl border border-[#1f1f26] bg-[#08080a] p-6">
            <p className="text-sm text-gray-300">
              Log in to view your wallet balance and buy coins.
            </p>
            <Button
              onClick={() => openAuthModal('login')}
              leftIcon={<LogIn className="h-4 w-4" />}
            >
              Log in
            </Button>
          </div>
        ) : (
          <>
            <section
              aria-labelledby="wallet-balance-summary"
              className="rounded-xl border border-[#1f1f26] bg-[#08080a] p-5 sm:p-7"
            >
              <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
                <div className="space-y-1">
                  <div className="flex items-center gap-2 text-xs font-medium uppercase tracking-wider text-gray-400">
                    <Coins className="h-4 w-4 text-[#ff2b42]" aria-hidden="true" />
                    <h2 id="wallet-balance-summary">Available balance</h2>
                  </div>

                  {walletQuery.isPending ? (
                    <div className="pt-1">
                      <div
                        role="status"
                        aria-label="Loading wallet balance"
                        className="h-9 w-40 rounded-lg bg-[#14141a] animate-pulse motion-reduce:animate-none"
                      />
                    </div>
                  ) : walletQuery.isError ? (
                    <div className="space-y-2 pt-1">
                      <p role="alert" className="text-sm text-red-400">
                        {getPaymentErrorMessage(
                          walletQuery.error,
                          'Unable to load wallet balance right now.',
                        )}
                      </p>
                    </div>
                  ) : (
                    <p className="text-3xl font-extrabold text-white font-outfit tabular-nums">
                      {formatCoins(walletQuery.data.balance)}{' '}
                      <span className="text-base font-semibold text-amber-400">
                        Coins
                      </span>
                    </p>
                  )}
                </div>

                <div className="flex flex-wrap items-center gap-2">
                  <Button
                    type="button"
                    size="sm"
                    variant="secondary"
                    aria-label="Refresh balance"
                    isLoading={walletQuery.isFetching}
                    onClick={() => {
                      void walletQuery.refetch()
                    }}
                    leftIcon={<RefreshCw className="h-3.5 w-3.5" />}
                  >
                    {walletQuery.isError ? 'Retry' : 'Refresh'}
                  </Button>
                </div>
              </div>
            </section>

            <section
              aria-labelledby="coin-packages-heading"
              className="rounded-xl border border-[#1f1f26] bg-[#08080a] p-5 sm:p-7 space-y-6"
            >
              <div className="flex flex-col gap-1 border-b border-[#1f1f26] pb-4 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <h2
                    id="coin-packages-heading"
                    className="text-lg font-bold text-white font-outfit"
                  >
                    Choose a coin package
                  </h2>
                  <p className="text-xs text-gray-400">
                    Fixed coin packages with instant credit upon payment confirmation.
                  </p>
                </div>
              </div>

              {packagesQuery.isPending ? (
                <div
                  role="status"
                  aria-label="Loading coin packages"
                  className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
                >
                  {Array.from({ length: 5 }).map((_, index) => (
                    <div
                      key={index}
                      className="h-36 rounded-xl border border-[#1f1f26] bg-[#101014] animate-pulse motion-reduce:animate-none"
                    />
                  ))}
                </div>
              ) : packagesQuery.isError ? (
                <div className="space-y-4 rounded-xl border border-red-500/30 bg-red-500/10 p-5">
                  <div className="flex items-start gap-2.5 text-sm text-red-400">
                    <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
                    <p role="alert">
                      {getPaymentErrorMessage(
                        packagesQuery.error,
                        'Unable to load coin packages. Please try again.',
                      )}
                    </p>
                  </div>
                  <Button
                    type="button"
                    size="sm"
                    variant="secondary"
                    isLoading={packagesQuery.isFetching}
                    onClick={() => {
                      void packagesQuery.refetch()
                    }}
                  >
                    Try again
                  </Button>
                </div>
              ) : packages.length === 0 ? (
                <div className="rounded-xl border border-[#1f1f26] bg-[#101014] py-10 text-center">
                  <p className="text-sm text-gray-400">
                    No coin packages are available right now.
                  </p>
                </div>
              ) : (
                <div
                  role="radiogroup"
                  aria-label="Coin packages"
                  className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
                >
                  {packages.map((pkg) => (
                    <PackageCard
                      key={pkg.packageCode}
                      pkg={pkg}
                      isSelected={effectivePackage?.packageCode === pkg.packageCode}
                      disabled={isCreatingOrder}
                      onSelect={() => {
                        setSelectedPackageCode(pkg.packageCode)
                        setCheckoutError(null)
                      }}
                    />
                  ))}
                </div>
              )}

              {checkoutError && (
                <p
                  role="alert"
                  className="rounded-lg border border-red-500/30 bg-red-500/10 p-3 text-sm text-red-400"
                >
                  {checkoutError}
                </p>
              )}

              <div className="flex flex-col gap-4 rounded-xl border border-[#1f1f26] bg-[#101014] p-4 sm:flex-row sm:items-center sm:justify-between sm:p-5">
                <div className="min-w-0 space-y-1.5">
                  <div className="flex flex-wrap items-center gap-2 text-xs text-gray-400">
                    <span className="inline-flex items-center gap-1.5 rounded-md border border-[#2a2a35] bg-[#18181f] px-2 py-0.5 font-semibold text-gray-200">
                      <CreditCard className="h-3.5 w-3.5 text-[#ff2b42]" aria-hidden="true" />
                      VNPAY
                    </span>
                    <span className="inline-flex items-center gap-1 text-gray-400">
                      <ShieldCheck className="h-3.5 w-3.5 text-emerald-400" aria-hidden="true" />
                      ATM / QR / International Card
                    </span>
                  </div>
                  {effectivePackage ? (
                    <p className="text-sm text-gray-300">
                      Selected:{' '}
                      <span className="font-bold text-white">
                        {effectivePackage.displayName}
                      </span>{' '}
                      <span className="text-gray-400">
                        ({formatCoins(effectivePackage.coinAmount)} Coins)
                      </span>
                    </p>
                  ) : (
                    <p className="text-sm text-gray-400">
                      Select a coin package to continue.
                    </p>
                  )}
                </div>

                <div className="flex flex-wrap items-center justify-between gap-4 sm:justify-end">
                  <div className="text-left sm:text-right">
                    <p className="text-xs text-gray-400">Total payment</p>
                    <p className="text-xl font-extrabold text-white font-outfit tabular-nums">
                      {effectivePackage ? formatVnd(effectivePackage.amountVnd) : '—'}
                    </p>
                  </div>
                  <Button
                    type="button"
                    size="lg"
                    isLoading={isCreatingOrder}
                    disabled={!effectivePackage || packagesQuery.isPending || packagesQuery.isError}
                    onClick={handleCheckout}
                  >
                    Pay with VNPAY
                  </Button>
                </div>
              </div>
            </section>
          </>
        )}
      </div>
    </main>
  )
}
