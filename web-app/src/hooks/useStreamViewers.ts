import { useCallback, useEffect, useMemo, useRef } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { streamApi } from '@/api/stream.api'
import type { ApiResponse } from '@/types/auth.types'
import type { StreamViewerItem } from '@/types/stream.types'

const viewersKey = (userId: string, sessionId?: string) => ['stream', 'viewers', userId, sessionId ?? ''] as const

export function useStreamViewers(
  userId: string,
  sessionId: string | undefined,
  active: boolean,
  currentViewerCount: number | null,
) {
  const queryClient = useQueryClient()
  const query = useInfiniteQuery({
    queryKey: viewersKey(userId, sessionId),
    enabled: Boolean(sessionId && active),
    initialPageParam: '0',
    queryFn: ({ pageParam, signal }) => streamApi.viewers(sessionId!, { cursor: pageParam, size: 50 }, signal),
    getNextPageParam: (lastPage) => lastPage.hasMore && lastPage.nextCursor ? lastPage.nextCursor : undefined,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  const refresh = useCallback(
    () => queryClient.resetQueries({ queryKey: viewersKey(userId, sessionId), exact: true }),
    [queryClient, userId, sessionId],
  )

  const { data, error, isLoadingError } = query
  const isSyncing = isAxiosError<ApiResponse>(error) &&
    error.response?.status === 503 && error.response.data?.code === 1016
  const previous = useRef<{ userId: string; sessionId: string | undefined; count: number | null } | null>(null)
  useEffect(() => {
    const prior = previous.current
    previous.current = active ? { userId, sessionId, count: currentViewerCount } : null
    if (
      active && sessionId && isLoadingError && isSyncing &&
      prior?.userId === userId && prior.sessionId === sessionId &&
      prior.count === null && currentViewerCount !== null
    ) {
      void refresh()
    }
  }, [active, currentViewerCount, isLoadingError, isSyncing, refresh, sessionId, userId])

  const items = useMemo(() => {
    const byUserId = new Map<string, StreamViewerItem>()
    for (const page of data?.pages ?? []) {
      for (const item of page.items) {
        if (!byUserId.has(item.userId)) byUserId.set(item.userId, item)
      }
    }
    return Array.from(byUserId.values())
  }, [data])

  return { ...query, items, isSyncing, refresh }
}
