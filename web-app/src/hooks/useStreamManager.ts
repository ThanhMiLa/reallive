import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { toast } from 'sonner'
import { streamApi } from '@/api/stream.api'
import { channelApi } from '@/api/channel.api'
import type { ApiResponse } from '@/types/auth.types'
import type { SaveStreamSetupRequest, StreamMetadata, StreamKeyCredentials, StreamSession } from '@/types/stream.types'

const setupKey = (userId: string) => ['stream', 'setup', userId] as const
const currentKey = (userId: string) => ['stream', 'current', userId] as const
const metadataKey = (userId: string, id: string) => ['stream', 'metadata', userId, id] as const

function ownerMetadata(session: StreamSession): StreamMetadata {
  return { ...session, currentViewerCount: session.status === 'ENDED' ? 0 : null }
}

export function streamErrorMessage(error: unknown) {
  if (isAxiosError<ApiResponse>(error)) {
    const status = error.response?.status
    if (status === 401) return 'Your session has expired. Please log in again.'
    if (status === 403) return error.response?.data?.message || 'You do not have permission for this action.'
    if (status === 400 || status === 409) return error.response?.data?.message || 'Check your stream information.'
    if (status === 503) return 'The media service is unavailable. Please try again.'
  }
  return 'Unable to connect. Please try again.'
}

function useStreamSetupQuery(userId: string) {
  return useQuery({
    queryKey: setupKey(userId),
    queryFn: ({ signal }) => streamApi.setup(signal),
    retry: false,
    refetchOnWindowFocus: false,
  })
}

function useStreamCurrentQuery(userId: string, poll: boolean) {
  return useQuery({
    queryKey: currentKey(userId),
    queryFn: ({ signal }) => streamApi.current(signal),
    retry: false,
    refetchInterval: poll
      ? (query) =>
          isAxiosError(query.state.error) &&
          [401, 403].includes(query.state.error.response?.status || 0)
            ? false
            : 3000
      : false,
    refetchIntervalInBackground: false,
  })
}

function useAbortableRunner<T extends string>() {
  const [pending, setPending] = useState<T | null>(null)
  const active = useRef(true)
  const busy = useRef(false)
  const request = useRef<AbortController | null>(null)

  useEffect(() => {
    active.current = true
    return () => {
      active.current = false
      request.current?.abort()
    }
  }, [])

  const run = useCallback(
    async (
      kind: T,
      action: (signal: AbortSignal) => Promise<void>,
      formatError: (error: unknown) => string = streamErrorMessage,
    ) => {
      if (busy.current) return false
      busy.current = true
      const controller = new AbortController()
      request.current = controller
      setPending(kind)
      try {
        await action(controller.signal)
        return active.current && !controller.signal.aborted
      } catch (error) {
        if (active.current) toast.error(formatError(error))
        return false
      } finally {
        busy.current = false
        if (active.current) setPending(null)
      }
    },
    [],
  )

  return { pending, active, run }
}

export function useStreamManager(userId: string) {
  const queryClient = useQueryClient()
  const [lastEndedAt, setLastEndedAt] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const { pending, active, run } = useAbortableRunner<'save'>()

  const setup = useStreamSetupQuery(userId)
  const current = useStreamCurrentQuery(userId, true)
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
        [401, 403, 404].includes(query.state.error.response?.status || 0))
        ? false
        : 3000,
    refetchIntervalInBackground: false,
    initialData: current.data ? ownerMetadata(current.data) : undefined,
  })
  const session: StreamMetadata | null =
    metadata.data || (current.data ? ownerMetadata(current.data) : null)
  const previousSession = useRef<StreamMetadata | null>(null)
  useEffect(() => {
    const previous = previousSession.current
    previousSession.current = session
    if (session?.status === 'ENDED' || (!session && previous)) {
      void Promise.resolve().then(() => {
        if (active.current) setLastEndedAt(session?.endedAt || new Date().toISOString())
      })
    } else if (session) {
      void Promise.resolve().then(() => {
        if (active.current) setLastEndedAt(null)
      })
    }
  }, [active, session])
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => {
      window.clearInterval(timer)
      void queryClient.cancelQueries({ queryKey: ['stream', 'metadata', userId] })
    }
  }, [queryClient, userId])

  const refreshMetadataQuery = metadata.refetch
  const refreshMetadata = useCallback(async () => {
    const result = await refreshMetadataQuery({ cancelRefetch: true })
    return result.isError ? null : result.data || null
  }, [refreshMetadataQuery])

  const saveSetup = (details: SaveStreamSetupRequest) =>
    run('save', async (signal) => {
      const saved = await streamApi.saveSetup(details, signal)
      if (!active.current) return
      queryClient.setQueryData(setupKey(userId), saved)
      if (session && session.status !== 'ENDED') {
        queryClient.setQueryData(metadataKey(userId, session.id), { ...session, ...details })
      }
      toast.success('Stream information saved')
    })

  return {
    setup,
    current,
    categories,
    metadata,
    session,
    id,
    pending,
    lastEndedAt,
    now,
    refreshMetadata,
    saveSetup,
  }
}

export function useStreamKeyManager(userId: string) {
  const [resetDialogOpen, setResetDialogOpen] = useState(false)
  const [credentials, setCredentials] = useState<StreamKeyCredentials | null>(null)
  const { pending, active, run } = useAbortableRunner<'key'>()

  const setup = useStreamSetupQuery(userId)
  const current = useStreamCurrentQuery(userId, false)

  const resetStreamKey = () =>
    run(
      'key',
      async (signal) => {
        setCredentials(null)
        const issued = await streamApi.issueKey(signal)
        if (!active.current) return
        setCredentials(issued)
        setResetDialogOpen(false)
        await Promise.all([setup.refetch(), current.refetch()])
        toast.success('Stream key reset. Reveal and copy it into OBS now.')
      },
      (error) => {
        setResetDialogOpen(false)
        if (
          isAxiosError(error) &&
          error.response?.status === 400 &&
          (!setup.data?.categoryId || !setup.data?.title?.trim())
        ) {
          return 'Save a stream title and category in Stream Manager before resetting your stream key.'
        }
        return streamErrorMessage(error)
      },
    )

  const requestReset = () => {
    if (setup.data?.hasStreamKey) {
      setResetDialogOpen(true)
    } else {
      void resetStreamKey()
    }
  }

  return {
    setup,
    current,
    pending,
    resetDialogOpen,
    setResetDialogOpen,
    credentials,
    resetStreamKey,
    requestReset,
  }
}
