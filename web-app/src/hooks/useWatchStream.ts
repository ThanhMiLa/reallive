import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { streamApi } from '@/api/stream.api'
import { useAuthStore } from '@/stores/auth.store'
import type {
  StreamDetailsCategorySummary,
  StreamDetailsChannelSummary,
  StreamMetadata,
} from '@/types/stream.types'

const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export const watchMetadataKey = (userId: string, sessionId: string) =>
  ['stream', 'metadata', userId, sessionId] as const

export const watchDetailsKey = (
  userId: string,
  sessionId: string,
  categoryId: string | null,
) => ['stream', 'details', userId, sessionId, categoryId] as const

function getHttpStatus(error: unknown): number | null {
  if (isAxiosError(error)) {
    return error.response?.status ?? null
  }
  return null
}

function isBlockingError(error: unknown): boolean {
  const status = getHttpStatus(error)
  return status !== null && [401, 403, 404].includes(status)
}

export function useWatchStream(rawSessionId: string) {
  const sessionId = rawSessionId.toLowerCase()
  const queryClient = useQueryClient()
  const { user, isAuthenticated, isCheckingAuth, isAuthModalOpen, openAuthModal } =
    useAuthStore()
  const userId = isAuthenticated && user?.userId ? user.userId : ''
  const isValidUuid = UUID_REGEX.test(sessionId)

  const [accessErrorState, setAccessErrorState] = useState<{
    sessionId: string
    userId: string
    error: unknown
  } | null>(null)

  const [cachedChannel, setCachedChannel] = useState<{
    sessionId: string
    channelId: string
    channel: StreamDetailsChannelSummary | null
  } | null>(null)

  const activeAccessError =
    accessErrorState &&
    accessErrorState.sessionId === sessionId &&
    accessErrorState.userId === userId
      ? accessErrorState.error
      : null

  const accessErrorStatus = getHttpStatus(activeAccessError)
  const isAccessBlocking =
    accessErrorStatus !== null && [401, 403, 404].includes(accessErrorStatus)

  const canQuery =
    !isCheckingAuth && isAuthenticated && Boolean(userId) && isValidUuid

  const hasBlockingDetailsError = (categoryId: string | null) =>
    isBlockingError(
      queryClient.getQueryState(watchDetailsKey(userId, sessionId, categoryId))?.error,
    )

  const metadata = useQuery({
    queryKey: watchMetadataKey(userId, sessionId),
    queryFn: ({ signal }) => streamApi.metadata(sessionId, signal),
    enabled: (query) =>
      canQuery &&
      !isAccessBlocking &&
      !hasBlockingDetailsError(query.state.data?.categoryId ?? null),
    retry: false,
    refetchInterval: (query) => {
      if (
        isAccessBlocking ||
        hasBlockingDetailsError(query.state.data?.categoryId ?? null)
      ) return false
      if (query.state.data?.status === 'ENDED') return false
      const errStatus = getHttpStatus(query.state.error)
      if (errStatus !== null && [401, 403, 404].includes(errStatus)) {
        return false
      }
      return 3000
    },
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: (query) => {
      if (
        isAccessBlocking ||
        hasBlockingDetailsError(query.state.data?.categoryId ?? null)
      ) return false
      if (query.state.data?.status === 'ENDED') return false
      const errStatus = getHttpStatus(query.state.error)
      if (errStatus !== null && [401, 403, 404].includes(errStatus)) {
        return false
      }
      return true
    },
  })

  const metadataErrorStatus = metadata.isError
    ? getHttpStatus(metadata.error)
    : null
  const isMetadataBlocking =
    metadataErrorStatus !== null &&
    [401, 403, 404].includes(metadataErrorStatus)

  const session: StreamMetadata | null = metadata.data ?? null
  const currentCategoryId = session?.categoryId ?? null

  const details = useQuery({
    queryKey: watchDetailsKey(userId, sessionId, currentCategoryId),
    queryFn: ({ signal }) => streamApi.details(sessionId, signal),
    enabled:
      canQuery &&
      Boolean(session) &&
      !isAccessBlocking &&
      !isMetadataBlocking,
    retry: false,
    staleTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    refetchInterval: false,
  })

  const detailsErrorStatus = details.isError
    ? getHttpStatus(details.error)
    : null
  const isDetailsUnauthorized = detailsErrorStatus === 401
  const isDetailsBlocking = isBlockingError(details.error)

  // Remember the latest channel summary for the same session & channelId so category changes don't blank out channel info
  if (
    details.data &&
    session &&
    details.data.id === sessionId &&
    details.data.channelId === session.channelId &&
    (cachedChannel?.sessionId !== sessionId ||
      cachedChannel?.channelId !== session.channelId ||
      cachedChannel?.channel !== details.data.channel)
  ) {
    setCachedChannel({
      sessionId,
      channelId: session.channelId,
      channel: details.data.channel,
    })
  }

  const blockingStatus: 401 | 403 | 404 | null =
    accessErrorStatus === 401 ||
    metadataErrorStatus === 401 ||
    isDetailsUnauthorized
      ? 401
      : accessErrorStatus === 403 ||
          metadataErrorStatus === 403 ||
          detailsErrorStatus === 403
        ? 403
        : accessErrorStatus === 404 ||
            metadataErrorStatus === 404 ||
            detailsErrorStatus === 404
          ? 404
          : null

  const activeRef = useRef(true)
  useLayoutEffect(() => {
    activeRef.current = true
    return () => {
      activeRef.current = false
    }
  }, [sessionId, userId])

  const refetchMetadata = metadata.refetch
  const refreshMetadata = useCallback(async (): Promise<StreamMetadata | null> => {
    const result = await refetchMetadata({ cancelRefetch: true })
    if (!activeRef.current) return null
    return result.isError ? null : (result.data ?? null)
  }, [refetchMetadata])

  const handleAccessError = useCallback(
    (error: unknown) => {
      if (!activeRef.current) return
      const status = getHttpStatus(error)
      if (status !== null && [401, 403, 404].includes(status)) {
        setAccessErrorState({ sessionId, userId, error })
        if (status === 401) {
          openAuthModal('login')
        }
      }
    },
    [openAuthModal, sessionId, userId, setAccessErrorState],
  )

  // Open login modal automatically when unauthenticated or when a 401 occurs
  const promptedAuthRef = useRef<string | null>(null)
  useEffect(() => {
    if (isCheckingAuth || !isValidUuid) return
    if (!isAuthenticated) {
      const promptKey = `guest:${sessionId}`
      if (promptedAuthRef.current !== promptKey) {
        promptedAuthRef.current = promptKey
        openAuthModal('login')
      }
    } else if (blockingStatus === 401) {
      const promptKey = `401:${userId}:${sessionId}`
      if (promptedAuthRef.current !== promptKey) {
        promptedAuthRef.current = promptKey
        openAuthModal('login')
      }
    } else {
      promptedAuthRef.current = null
    }
  }, [
    blockingStatus,
    isAuthenticated,
    isCheckingAuth,
    isValidUuid,
    openAuthModal,
    sessionId,
    userId,
  ])

  // Resume queries when auth modal closes and user is authenticated
  const wasAuthModalOpen = useRef(false)
  useEffect(() => {
    if (wasAuthModalOpen.current && !isAuthModalOpen && isAuthenticated) {
      if (accessErrorStatus === 401) {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setAccessErrorState(null)
      }
      if (metadataErrorStatus === 401) {
        void metadata.refetch()
      }
      if (isDetailsUnauthorized) {
        void details.refetch()
      }
    }
    wasAuthModalOpen.current = isAuthModalOpen
  }, [
    accessErrorStatus,
    details,
    isAuthModalOpen,
    isAuthenticated,
    isDetailsUnauthorized,
    metadata,
    metadataErrorStatus,
  ])

  // Immediate metadata fetch when tab becomes visible
  useEffect(() => {
    if (!canQuery) return
    const handleVisibilityChange = () => {
      if (
        document.visibilityState === 'visible' &&
        !isAccessBlocking &&
        !isMetadataBlocking &&
        !isDetailsBlocking &&
        session?.status !== 'ENDED'
      ) {
        void metadata.refetch()
      }
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)
    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [
    canQuery,
    isAccessBlocking,
    isMetadataBlocking,
    isDetailsBlocking,
    metadata,
    session?.status,
  ])

  // Cleanup queries when leaving page, changing session, or logging out
  useEffect(() => {
    return () => {
      void queryClient.cancelQueries({
        queryKey: ['stream', 'metadata', userId, sessionId],
      })
      void queryClient.cancelQueries({
        queryKey: ['stream', 'details', userId, sessionId],
      })
    }
  }, [queryClient, sessionId, userId])

  const retryAll = useCallback(() => {
    setAccessErrorState(null)
    void metadata.refetch()
    if (session && details.isError) {
      void details.refetch()
    }
  }, [details, metadata, session, setAccessErrorState])

  const retryDetails = useCallback(() => {
    void details.refetch()
  }, [details])

  const channel: StreamDetailsChannelSummary | null =
    details.data && details.data.id === sessionId
      ? details.data.channel
      : cachedChannel &&
          cachedChannel.sessionId === sessionId &&
          cachedChannel.channelId === session?.channelId
        ? cachedChannel.channel
        : null

  const category: StreamDetailsCategorySummary | null =
    details.data &&
    details.data.id === sessionId &&
    details.data.categoryId === currentCategoryId
      ? details.data.category
      : null

  const canPlay =
    canQuery &&
    !blockingStatus &&
    session?.status === 'LIVE' &&
    session.id === sessionId

  const statusRefreshError =
    metadata.isError &&
    Boolean(session) &&
    session?.status !== 'ENDED' &&
    !blockingStatus

  return {
    sessionId,
    isValidUuid,
    isCheckingAuth,
    isAuthenticated,
    openAuthModal,
    metadata,
    details,
    session,
    channel,
    category,
    canPlay,
    blockingStatus,
    statusRefreshError,
    refreshMetadata,
    handleAccessError,
    retryAll,
    retryDetails,
  }
}
