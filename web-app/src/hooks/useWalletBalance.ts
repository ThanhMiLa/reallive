import { useQuery } from '@tanstack/react-query'
import { paymentApi } from '@/api/payment.api'
import { profileStaleTime } from '@/lib/query-client'
import { useAuthStore } from '@/stores/auth.store'

export function useWalletBalance({ createIfMissing = false } = {}) {
  const { user, isAuthenticated, isCheckingAuth } = useAuthStore()
  const walletKey = ['wallet', user?.userId]

  return useQuery({
    queryKey: createIfMissing ? [...walletKey, 'create'] : walletKey,
    queryFn: createIfMissing ? paymentApi.createWallet : paymentApi.getWalletBalance,
    enabled: isAuthenticated && !isCheckingAuth && Boolean(user?.userId),
    staleTime: profileStaleTime,
    refetchOnMount: createIfMissing ? 'always' : true,
    refetchOnReconnect: false,
  })
}
