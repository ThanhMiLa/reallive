import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { toast } from 'sonner'
import { streamApi } from '@/api/stream.api'
import { channelApi } from '@/api/channel.api'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse } from '@/types/auth.types'
import type {
  CreateStreamRequest,
  PublishCredentials,
  StreamDetails,
  StreamMetadata,
} from '@/types/stream.types'

const currentKey = (userId: string) => ['stream', 'current', userId] as const
const metadataKey = (userId: string, id: string) => ['stream', 'metadata', userId, id] as const

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
  const [credentials, setCredentials] = useState<PublishCredentials | null>(null)
  const [pending, setPending] = useState<'create' | 'update' | 'credentials' | 'end' | null>(null)
  const [dialog, setDialog] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const active = useRef(true)
  const operation = useRef(false)
  const operationAbort = useRef<AbortController | null>(null)
  const createAttempt = useRef<CreateStreamRequest | null>(null)
  const current = useQuery({
    queryKey: currentKey(userId),
    queryFn: ({ signal }) => streamApi.current(signal),
    retry: false,
    refetchOnWindowFocus: false,
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
    initialData: current.data || undefined,
  })
  const session: StreamMetadata | null = metadata.data || current.data || null
  const sessionRef = useRef(session)
  useLayoutEffect(() => {
    sessionRef.current = session
  }, [session])
  const refreshCurrent = current.refetch
  const refresh = metadata.refetch
  const refreshMetadata = useCallback(async () => {
    const result = await refresh({ cancelRefetch: true })
    return result.isError ? null : result.data || null
  }, [refresh])

  useEffect(() => {
    active.current = true
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => {
      active.current = false
      operationAbort.current?.abort()
      window.clearInterval(timer)
      void queryClient.cancelQueries({ queryKey: ['stream', 'metadata', userId] })
    }
  }, [queryClient, userId])

  useEffect(() => {
    if (wasAuthModalOpen.current && !authModalOpen) {
      if (isAxiosError(current.error) && current.error.response?.status === 401)
        void refreshCurrent()
      if (isAxiosError(metadata.error) && metadata.error.response?.status === 401)
        void refreshMetadata()
    }
    wasAuthModalOpen.current = authModalOpen
  }, [authModalOpen, current.error, metadata.error, refreshCurrent, refreshMetadata])

  useEffect(() => {
    if (isAxiosError(metadata.error) && metadata.error.response?.status === 404) {
      void refreshCurrent().then((result) => {
        if (active.current && !result.isError && !result.data) setCredentials(null)
      })
    }
  }, [metadata.error, refreshCurrent])

  useEffect(() => {
    if (session?.status === 'ENDED') {
      // Clear secrets and obsolete actions once the server confirms the terminal state.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setCredentials(null)
      setDialog(false)
    }
  }, [session?.id, session?.status])

  const run = async (
    kind: NonNullable<typeof pending>,
    fn: (signal: AbortSignal) => Promise<void | boolean>,
  ) => {
    if (operation.current) return false
    operation.current = true
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
      operation.current = false
      if (operationAbort.current === controller) operationAbort.current = null
      if (active.current) setPending(null)
    }
  }
  const create = (details: StreamDetails) =>
    run('create', async (signal) => {
      if (createAttempt.current) {
        const existing = await streamApi.current(signal)
        if (!active.current) return
        if (existing) {
          queryClient.setQueryData(currentKey(userId), existing)
          createAttempt.current = null
          return
        }
      }
      const previous = createAttempt.current
      const request =
        previous && previous.title === details.title && previous.categoryId === details.categoryId
          ? previous
          : { ...details, requestId: crypto.randomUUID() }
      createAttempt.current = request
      try {
        const result = await streamApi.create(request, signal)
        if (!active.current) return
        createAttempt.current = null
        setCredentials(result.publishCredentials)
        queryClient.setQueryData(currentKey(userId), result.session)
        queryClient.setQueryData(metadataKey(userId, result.session.id), result.session)
        toast.success('Stream session created')
      } catch (error) {
        if (isAxiosError(error) && error.response?.status === 409) {
          const existing = await streamApi.current(signal)
          if (active.current && existing) {
            queryClient.setQueryData(currentKey(userId), existing)
            createAttempt.current = null
            return
          }
        }
        throw error
      }
    })
  const update = (details: StreamDetails) =>
    run('update', async (signal) => {
      if (!session || session.status === 'ENDED') return false
      const patch: Partial<StreamDetails> = {}
      if (details.title !== session.title) patch.title = details.title
      if (details.categoryId !== session.categoryId) patch.categoryId = details.categoryId
      if (!Object.keys(patch).length) return
      const saved = await streamApi.update(session.id, patch, signal)
      const key = metadataKey(userId, session.id)
      const isCurrentSession = () =>
        active.current &&
        !signal.aborted &&
        sessionRef.current?.id === session.id &&
        sessionRef.current.status !== 'ENDED' &&
        queryClient.getQueryData<StreamMetadata | null>(currentKey(userId))?.id === session.id &&
        queryClient.getQueryData<StreamMetadata>(key)?.status !== 'ENDED'

      if (!isCurrentSession()) return false
      await queryClient.cancelQueries({ queryKey: key, exact: true })
      if (!isCurrentSession()) return false

      const syncSavedDetails = (queryKey: readonly string[]) => {
        const snapshot = queryClient.getQueryState<StreamMetadata | null>(queryKey)
        if (!snapshot?.data) return
        queryClient.setQueryData<StreamMetadata | null>(
          queryKey,
          (cached) => {
            if (
              !cached ||
              cached.id !== saved.id ||
              cached.status === 'ENDED' ||
              Date.parse(cached.serverTime) > Date.parse(saved.serverTime)
            )
              return cached

            return {
              ...cached,
              ...(patch.title !== undefined ? { title: saved.title } : {}),
              ...(patch.categoryId !== undefined ? { categoryId: saved.categoryId } : {}),
            }
          },
          { updatedAt: snapshot.dataUpdatedAt },
        )
      }

      // PATCH confirms the saved fields. Preserve lifecycle data and its clock anchor.
      syncSavedDetails(key)
      syncSavedDetails(currentKey(userId))
      await refreshMetadata()
      if (!isCurrentSession()) return false
      toast.success('Stream information updated')
    })
  const getCredentials = () =>
    run('credentials', async (signal) => {
      if (!session) return
      const result = await streamApi.credentials(session.id, signal)
      if (
        !active.current ||
        sessionRef.current?.id !== session.id ||
        sessionRef.current.status === 'ENDED'
      )
        return
      setCredentials(result)
    })
  const end = () =>
    run('end', async (signal) => {
      if (!session) return
      let result
      try {
        result = await streamApi.end(session.id, signal)
      } catch (error) {
        if (!active.current) return
        const latest = await refreshMetadata()
        if (latest?.status === 'ENDED') {
          setCredentials(null)
          setDialog(false)
          toast.success('Session ended')
          return
        }
        throw error
      }
      if (!active.current || sessionRef.current?.id !== session.id) return
      await queryClient.cancelQueries({ queryKey: metadataKey(userId, session.id) })
      queryClient.setQueryData(metadataKey(userId, session.id), result)
      setCredentials(null)
      setDialog(false)
      toast.success('Session ended')
    })
  const newStream = async () => {
    const latest = await current.refetch()
    if (!active.current || latest.isError) {
      if (latest.isError) toast.error(streamErrorMessage(latest.error))
      return
    }
    if (latest.data) {
      toast.error('An active session already exists.')
      return
    }
    setCredentials(null)
    createAttempt.current = null
    queryClient.setQueryData(currentKey(userId), null)
  }

  return {
    current,
    categories,
    metadata,
    session,
    id,
    credentials,
    pending,
    dialog,
    setDialog,
    now,
    refreshMetadata,
    create,
    update,
    getCredentials,
    end,
    newStream,
  }
}
