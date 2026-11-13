import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { toast } from 'sonner'
import { streamApi } from '@/api/stream.api'
import { channelApi } from '@/api/channel.api'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse } from '@/types/auth.types'
import type {
  SaveStreamSetupRequest,
  StreamMetadata,
  StreamOperation,
  StreamSession,
  StreamSetup,
} from '@/types/stream.types'

const setupKey = (userId: string) => ['stream', 'setup', userId] as const
const currentKey = (userId: string) => ['stream', 'current', userId] as const
const metadataKey = (userId: string, id: string) => ['stream', 'metadata', userId, id] as const
const operationKey = (userId: string, opId: string) => ['stream', 'operation', userId, opId] as const

function ownerMetadata(session: StreamSession): StreamMetadata {
  return {
    ...session,
    currentViewerCount: session.status === 'ENDED' ? 0 : null,
  }
}

function isAuthStatus(error: unknown, statuses = [401, 403]) {
  return isAxiosError(error) && statuses.includes(error.response?.status || 0)
}

export function streamErrorMessage(error: unknown) {
  if (isAxiosError<ApiResponse>(error)) {
    const status = error.response?.status
    if (status === 401) return 'Your session has expired. Please log in again.'
    if (status === 403)
      return error.response?.data?.message || 'You do not have permission for this action.'
    if (status === 409) return 'The stream state changed. Refresh and try again.'
    if (status === 400) return error.response?.data?.message || 'Check your stream information.'
    if (status === 503) return 'The service is unavailable. Please try again.'
  }
  return 'Unable to connect. Please try again.'
}

export function useStreamManager(userId: string) {
  const queryClient = useQueryClient()
  const authModalOpen = useAuthStore((state) => state.isAuthModalOpen)
  const wasAuthModalOpen = useRef(false)
  const [pending, setPending] = useState<'save' | 'reset' | null>(null)
  const [resetDialogOpen, setResetDialogOpen] = useState(false)
  const [trackedOperationId, setTrackedOperationId] = useState<string | null>(null)
  const [lastEndedAt, setLastEndedAt] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const active = useRef(true)
  const busy = useRef(false)
  const operationAbort = useRef<AbortController | null>(null)
  const latestGenerationRef = useRef(0)
  const resetAttemptIdRef = useRef<string | null>(null)
  const handledOpsRef = useRef<Set<string>>(new Set())

  const setup = useQuery({
    queryKey: setupKey(userId),
    queryFn: ({ signal }) => streamApi.setup(signal),
    retry: false,
    refetchOnWindowFocus: false,
    refetchInterval: (query) => {
      if (isAuthStatus(query.state.error)) return false
      const status = query.state.data?.status
      return status === 'PROVISIONING' || status === 'ROTATING' ||
        (status === 'ACTIVE' && !query.state.data?.publishReady) ? 1000 : false
    },
    refetchIntervalInBackground: false,
  })

  const current = useQuery({
    queryKey: currentKey(userId),
    queryFn: ({ signal }) => streamApi.current(signal),
    retry: false,
    refetchOnWindowFocus: false,
    refetchInterval: (query) => {
      if (isAuthStatus(query.state.error)) return false
      const session = query.state.data
      if (session && session.status !== 'ENDED') return false
      return 3000
    },
    refetchIntervalInBackground: false,
  })

  const categories = useQuery({
    queryKey: ['categories'],
    queryFn: ({ signal }) => channelApi.getCategories(signal),
    staleTime: 600_000,
  })

  const id = current.data?.id
  const metadata = useQuery({
    queryKey: metadataKey(userId, id || ''),
    queryFn: ({ signal }) => streamApi.metadata(id!, signal),
    enabled: Boolean(id),
    retry: false,
    refetchInterval: (query) =>
      query.state.data?.status === 'ENDED' ||
      (isAxiosError(query.state.error) &&
        [401, 404].includes(query.state.error.response?.status || 0))
        ? false
        : 3000,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: (query) =>
      query.state.data?.status !== 'ENDED' &&
      !(
        isAxiosError(query.state.error) &&
        [401, 404].includes(query.state.error.response?.status || 0)
      ),
    initialData: current.data ? ownerMetadata(current.data) : undefined,
  })

  const session: StreamMetadata | null =
    metadata.data || (current.data ? ownerMetadata(current.data) : null)

  const watchedOperationId =
    trackedOperationId ||
    (setup.data?.status === 'PROVISIONING' || setup.data?.status === 'ROTATING' ||
      (setup.data?.status === 'ACTIVE' && !setup.data.publishReady)
      ? setup.data.operationId
      : null)

  const operation = useQuery({
    queryKey: operationKey(userId, watchedOperationId || ''),
    queryFn: ({ signal }) => streamApi.operation(watchedOperationId!, signal),
    enabled: Boolean(watchedOperationId),
    retry: false,
    refetchOnWindowFocus: false,
    refetchInterval: (query) => {
      if (isAuthStatus(query.state.error, [401, 403, 404])) return false
      const status = query.state.data?.status
      return status === 'SUCCEEDED' || status === 'CANCELLED' ? false : 750
    },
    refetchIntervalInBackground: false,
  })

  const sessionRef = useRef(session)
  useLayoutEffect(() => {
    sessionRef.current = session
    if (setup.data && setup.data.generation > latestGenerationRef.current) {
      latestGenerationRef.current = setup.data.generation
    }
  }, [session, setup.data])

  const refreshSetup = setup.refetch
  const refreshCurrent = current.refetch
  const refreshMetadataQuery = metadata.refetch
  const refreshMetadata = useCallback(async () => {
    const result = await refreshMetadataQuery({ cancelRefetch: true })
    return result.isError ? null : result.data || null
  }, [refreshMetadataQuery])

  useEffect(() => {
    active.current = true
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => {
      active.current = false
      operationAbort.current?.abort()
      window.clearInterval(timer)
      void queryClient.cancelQueries({ queryKey: ['stream', 'metadata', userId] })
      if (useAuthStore.getState().user?.userId === userId) return
      for (const kind of ['setup', 'current', 'metadata', 'operation']) {
        const queryKey = ['stream', kind, userId]
        void queryClient.cancelQueries({ queryKey })
        queryClient.removeQueries({ queryKey })
      }
    }
  }, [queryClient, userId])

  useEffect(() => {
    if (wasAuthModalOpen.current && !authModalOpen) {
      if (isAuthStatus(setup.error, [401])) void refreshSetup()
      if (isAuthStatus(current.error, [401])) void refreshCurrent()
      if (isAuthStatus(metadata.error, [401])) void refreshMetadata()
    }
    wasAuthModalOpen.current = authModalOpen
  }, [
    authModalOpen,
    current.error,
    metadata.error,
    refreshCurrent,
    refreshMetadata,
    refreshSetup,
    setup.error,
  ])

  useEffect(() => {
    if (isAxiosError(metadata.error) && metadata.error.response?.status === 404) {
      void refreshCurrent()
    }
  }, [metadata.error, refreshCurrent])

  useEffect(() => {
    if (session?.status === 'ENDED') {
      const endedTimestamp = session.endedAt
      const sessionId = session.id
      void refreshCurrent().then((result) => {
        if (!active.current) return
        if (!result.isError && result.data?.id !== sessionId) {
          setLastEndedAt(endedTimestamp || new Date().toISOString())
        }
      })
    } else if (session && (session.status === 'LIVE' || session.status === 'RECONNECTING')) {
      void Promise.resolve().then(() => {
        if (active.current) setLastEndedAt(null)
      })
    }
  }, [session, refreshCurrent])

  const opData = operation.data
  useEffect(() => {
    if (!opData) return
    if (opData.status !== 'SUCCEEDED' && opData.status !== 'CANCELLED') return
    if (handledOpsRef.current.has(opData.operationId)) return
    handledOpsRef.current.add(opData.operationId)

    void Promise.all([refreshSetup(), refreshCurrent()]).then(() => {
      if (!active.current) return
      if (trackedOperationId === opData.operationId) {
        setTrackedOperationId(null)
      }
      if (opData.operationType === 'RESET_KEY') {
        if (opData.status === 'SUCCEEDED') {
          resetAttemptIdRef.current = null
          setResetDialogOpen(false)
          toast.success('Stream key reset. Update OBS with your new key.')
        } else {
          toast.error(opData.lastError || 'Stream key reset was cancelled.')
        }
      }
    })
  }, [opData, refreshCurrent, refreshSetup, trackedOperationId])

  const run = async (
    kind: NonNullable<typeof pending>,
    fn: (signal: AbortSignal) => Promise<void | boolean>,
  ) => {
    if (busy.current) return false
    busy.current = true
    const controller = new AbortController()
    operationAbort.current = controller
    setPending(kind)
    try {
      const applied = await fn(controller.signal)
      return applied !== false && active.current && !controller.signal.aborted
    } catch (error) {
      if (active.current) toast.error(streamErrorMessage(error))
      return false
    } finally {
      busy.current = false
      if (operationAbort.current === controller) operationAbort.current = null
      if (active.current) setPending(null)
    }
  }

  const saveSetup = (details: SaveStreamSetupRequest) =>
    run('save', async (signal) => {
      const expectedMinGeneration = latestGenerationRef.current
      const saved = await streamApi.saveSetup(details, signal)
      if (!active.current) return false

      const cachedGeneration =
        queryClient.getQueryData<StreamSetup>(setupKey(userId))?.generation ?? 0
      const observedGeneration = Math.max(latestGenerationRef.current, cachedGeneration)
      if (saved.generation < observedGeneration || saved.generation < expectedMinGeneration) {
        await refreshSetup()
        return false
      }

      latestGenerationRef.current = saved.generation
      queryClient.setQueryData<StreamSetup>(setupKey(userId), (previous) =>
        previous && previous.generation > saved.generation ? previous : saved,
      )

      const liveSession = sessionRef.current
      if (liveSession && liveSession.status !== 'ENDED') {
        const metaKey = metadataKey(userId, liveSession.id)
        queryClient.setQueryData<StreamMetadata>(metaKey, (cached) =>
          cached && cached.status !== 'ENDED'
            ? { ...cached, title: details.title, categoryId: details.categoryId }
            : cached,
        )
        queryClient.setQueryData<StreamSession | null>(currentKey(userId), (cached) =>
          cached && cached.id === liveSession.id && cached.status !== 'ENDED'
            ? { ...cached, title: details.title, categoryId: details.categoryId }
            : cached,
        )
        void refreshMetadata()
      }

      if (saved.status === 'BLOCKED') {
        toast.error(saved.blockedReason || 'Streaming is blocked for this channel.')
        return false
      }
      toast.success(
        saved.status === 'PROVISIONING'
          ? 'Stream information saved. Preparing your stream key…'
          : 'Stream information saved',
      )
      return true
    })

  const resetStreamKey = () =>
    run('reset', async (signal) => {
      const attemptKey = resetAttemptIdRef.current || crypto.randomUUID()
      resetAttemptIdRef.current = attemptKey
      let op: StreamOperation
      try {
        op = await streamApi.resetKey(attemptKey, signal)
      } catch (error) {
        if (isAxiosError(error) && [400, 403, 409].includes(error.response?.status || 0)) {
          resetAttemptIdRef.current = null
        }
        throw error
      }
      if (!active.current) return false
      if (op.credentialGeneration > latestGenerationRef.current) {
        latestGenerationRef.current = op.credentialGeneration
      }
      setTrackedOperationId(op.operationId)
      queryClient.setQueryData(operationKey(userId, op.operationId), op)
      if (op.status === 'SUCCEEDED') {
        resetAttemptIdRef.current = null
        setResetDialogOpen(false)
        await Promise.all([refreshSetup(), refreshCurrent()])
        if (active.current) {
          setTrackedOperationId(null)
          toast.success('Stream key reset. Update OBS with your new key.')
        }
      } else {
        setResetDialogOpen(false)
        await refreshSetup()
      }
      return true
    })

  return {
    setup,
    current,
    categories,
    metadata,
    operation,
    session,
    id,
    pending,
    resetDialogOpen,
    setResetDialogOpen,
    lastEndedAt,
    now,
    refreshMetadata,
    saveSetup,
    resetStreamKey,
  }
}
