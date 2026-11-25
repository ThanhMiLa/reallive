import { useQuery } from '@tanstack/react-query'
import { paymentApi } from '@/api/payment.api'

export const VIRTUAL_GIFTS_QUERY_KEY = ['virtual-gifts'] as const

export function useVirtualGifts({ enabled = true } = {}) {
  return useQuery({
    queryKey: VIRTUAL_GIFTS_QUERY_KEY,
    queryFn: ({ signal }) => paymentApi.getVirtualGifts(signal),
    enabled,
    staleTime: 5 * 60_000,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
}
