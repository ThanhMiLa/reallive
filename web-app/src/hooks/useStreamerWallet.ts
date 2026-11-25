import { useQuery } from '@tanstack/react-query'
import { paymentApi } from '@/api/payment.api'
import { profileStaleTime } from '@/lib/query-client'
import { hasStreamerRole } from '@/lib/utils'
import { useAuthStore } from '@/stores/auth.store'

export function useStreamerWallet() {
  const { user, isAuthenticated, isCheckingAuth } = useAuthStore()
  const isStreamer = hasStreamerRole(user)

  return useQuery({
    queryKey: ['streamer-wallet', user?.userId],
    queryFn: paymentApi.createStreamerWallet,
    enabled: isAuthenticated && !isCheckingAuth && Boolean(user?.userId) && isStreamer,
    staleTime: profileStaleTime,
    retry: false,
    refetchOnReconnect: false,
  })
}
