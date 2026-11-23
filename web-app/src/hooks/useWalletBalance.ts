import { useQuery } from '@tanstack/react-query'
import { paymentApi } from '@/api/payment.api'
import { profileStaleTime } from '@/lib/query-client'
import { useAuthStore } from '@/stores/auth.store'

export function useWalletBalance() {
  const { user, isAuthenticated, isCheckingAuth } = useAuthStore()

  return useQuery({
    queryKey: ['wallet', user?.userId],
    queryFn: () => paymentApi.getWalletBalance(),
    enabled: isAuthenticated && !isCheckingAuth && Boolean(user?.userId),
    staleTime: profileStaleTime,
    refetchOnReconnect: false,
  })
}
