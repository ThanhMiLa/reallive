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

export function useStreamManager(userId: string) {
  const queryClient = useQueryClient()
  const [pending, setPending] = useState<'save' | 'key' | null>(null)
  const [resetDialogOpen, setResetDialogOpen] = useState(false)
  const [credentials, setCredentials] = useState<StreamKeyCredentials | null>(null)
  const [lastEndedAt, setLastEndedAt] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const active = useRef(true)
  const busy = useRef(false)
  const request = useRef<AbortController | null>(null)

  const setup = useQuery({
    queryKey: setupKey(userId),
    queryFn: ({ signal }) => streamApi.setup(signal),
    retry: false,
    refetchOnWindowFocus: false,
  })
  const current = useQuery({
    queryKey: currentKey(userId),
    queryFn: ({ signal }) => streamApi.current(signal),
    retry: false,
    refetchInterval: (query) => isAxiosError(query.state.error) &&
      [401, 403].includes(query.state.error.response?.status || 0) ? false : 3000,
    refetchIntervalInBackground: false,
  })
  const categories = useQuery({
    queryKey: ['categories'], queryFn: ({ signal }) => channelApi.getCategories(signal), staleTime: 600_000,
  })
  const id = current.data?.id
  const metadata = useQuery({
    queryKey: metadataKey(userId, id || ''),
    queryFn: ({ signal }) => streamApi.metadata(id!, signal),
    enabled: Boolean(id),
    retry: false,
    refetchInterval: (query) => query.state.data?.status === 'ENDED' ||
      (isAxiosError(query.state.error) && [401, 403, 404].includes(query.state.error.response?.status || 0)) ? false : 3000,
    refetchIntervalInBackground: false,
    initialData: current.data ? ownerMetadata(current.data) : undefined,
  })
  const session: StreamMetadata | null = metadata.data || (current.data ? ownerMetadata(current.data) : null)
  const previousSession = useRef<StreamMetadata | null>(null)
  useEffect(() => {
    const previous = previousSession.current
    previousSession.current = session
    if (session?.status === 'ENDED' || (!session && previous)) {
      void Promise.resolve().then(() => {
        if (active.current) setLastEndedAt(session?.endedAt || new Date().toISOString())
      })
    } else if (session) {
      void Promise.resolve().then(() => { if (active.current) setLastEndedAt(null) })
    }
  }, [session])
  useEffect(() => {
    active.current = true
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => {
      active.current = false
      request.current?.abort()
      window.clearInterval(timer)
      void queryClient.cancelQueries({ queryKey: ['stream', 'metadata', userId] })
    }
  }, [queryClient, userId])

  const refreshMetadataQuery = metadata.refetch
  const refreshMetadata = useCallback(async () => {
    const result = await refreshMetadataQuery({ cancelRefetch: true })
    return result.isError ? null : result.data || null
  }, [refreshMetadataQuery])

  const run = async (kind: 'save' | 'key', action: (signal: AbortSignal) => Promise<void>) => {
    if (busy.current) return false
    busy.current = true
    const controller = new AbortController()
    request.current = controller
    setPending(kind)
    try {
      await action(controller.signal)
      return active.current && !controller.signal.aborted
    } catch (error) {
      if (active.current) toast.error(streamErrorMessage(error))
      return false
    } finally {
      busy.current = false
      if (active.current) setPending(null)
    }
  }

  const saveSetup = (details: SaveStreamSetupRequest) => run('save', async (signal) => {
    const saved = await streamApi.saveSetup(details, signal)
    if (!active.current) return
    queryClient.setQueryData(setupKey(userId), saved)
    if (session && session.status !== 'ENDED') {
      queryClient.setQueryData(metadataKey(userId, session.id), { ...session, ...details })
    }
    toast.success('Stream information saved')
  })

  const resetStreamKey = () => run('key', async (signal) => {
    setCredentials(null)
    const issued = await streamApi.issueKey(signal)
    if (!active.current) return
    setCredentials(issued)
    setResetDialogOpen(false)
    await Promise.all([setup.refetch(), current.refetch()])
    toast.success('Copy your stream key now. It will not be shown again.')
  })

  return {
    setup, current, categories, metadata, session, id, pending, resetDialogOpen, setResetDialogOpen,
    lastEndedAt, now, refreshMetadata, saveSetup, resetStreamKey, credentials,
    dismissCredentials: () => setCredentials(null),
  }
}
