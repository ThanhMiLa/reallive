import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { io, type Socket } from 'socket.io-client'
import { AxiosError, isAxiosError } from 'axios'
import { chatApi } from '@/api/chat.api'
import { refreshAccessToken } from '@/api/client'
import { useAuthStore } from '@/stores/auth.store'
import type { ChatAck, ChatJoin, ChatMessage, ChatPage, ChatPageParam, ChatProblem } from '@/types/chat.types'
import { CHAT_MAX_PAGES, markChatGap, mergeChatMessage, type ChatWindow } from './chat-cache'

const API_ORIGIN = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8888'
export const chatKey = (userId: string, sessionId: string) => ['chat', userId, sessionId] as const
const unavailable: ChatProblem = { code: 'CHAT_UNAVAILABLE', status: 503, message: 'Chat is temporarily unavailable.', retryable: true }

export function useStreamChat(sessionId: string, active: boolean, onAccessError?: (error: unknown) => void) {
  const userId = useAuthStore(s => s.isAuthenticated ? s.user?.userId : undefined)
  const client = useQueryClient()
  const key = chatKey(userId ?? '', sessionId)
  const [joined, setJoined] = useState(false)
  const [canSend, setCanSend] = useState(false)
  const [problem, setProblem] = useState<ChatProblem | null>(null)
  const [unread, setUnread] = useState(0)
  const [followLive, setFollowLive] = useState(true)
  const followRef = useRef(true)
  const socketRef = useRef<Socket | null>(null)
  const accessErrorRef = useRef(onAccessError)
  useEffect(() => { accessErrorRef.current = onAccessError }, [onAccessError])

  const feed = useInfiniteQuery<ChatPage, Error, ChatWindow, ReturnType<typeof chatKey>, ChatPageParam>({
    queryKey: key, enabled: Boolean(active && userId && joined),
    initialPageParam: {} as ChatPageParam,
    queryFn: ({ pageParam, signal }) => chatApi.history(sessionId, pageParam, signal),
    getPreviousPageParam: first => first.hasOlder && first.olderCursor ? { before: first.olderCursor } : undefined,
    getNextPageParam: last => last.hasNewer && last.newerCursor ? { after: last.newerCursor } : undefined,
    maxPages: CHAT_MAX_PAGES, staleTime: Infinity, retry: false,
    refetchOnWindowFocus: false, refetchOnReconnect: false, refetchOnMount: false,
  })
  const isFetching = feed.isFetching
  const hasNextPage = feed.hasNextPage
  const fetchNextPage = feed.fetchNextPage
  useEffect(() => {
    if (joined && followLive && hasNextPage && !isFetching && !feed.isError) void fetchNextPage()
  }, [joined, followLive, hasNextPage, isFetching, fetchNextPage, feed.isError])

  useEffect(() => {
    if (!active || !userId) return
    let disposed = false
    let retryTimer: ReturnType<typeof setTimeout> | undefined
    let refreshing = false
    let refreshAttempted = false
    let lastReceived = 0
    const queryKey = chatKey(userId, sessionId)
    const socket = io(API_ORIGIN, { path: '/chat/socket.io', transports: ['websocket'], withCredentials: true, autoConnect: false })
    socketRef.current = socket
    function report(error: ChatProblem) {
      if (disposed) return
      setProblem(error)
      if ([401, 403].includes(error.status)) {
        const failure = new AxiosError(error.message, undefined, undefined, undefined, {
          status: error.status, statusText: error.message, data: error, headers: {}, config: { headers: {} } as never,
        })
        if (error.status === 403) accessErrorRef.current?.(failure)
      }
    }
    async function refresh() {
      if (refreshing || disposed) return
      if (refreshAttempted) { report({ code: 'UNAUTHENTICATED', status: 401, message: 'Sign in again to use chat.', retryable: false }); return }
      refreshAttempted = true
      refreshing = true
      try { await refreshAccessToken(); if (!disposed) socket.connect() }
      catch { report({ code: 'UNAUTHENTICATED', status: 401, message: 'Sign in again to use chat.', retryable: false }) }
      finally { refreshing = false }
    }
    function scheduleJoin() {
      if (retryTimer || disposed) return
      retryTimer = setTimeout(() => { retryTimer = undefined; if (socket.connected) join(); else socket.connect() }, 1500)
    }
    function join() {
      socket.timeout(5000).emit('chat:join', { sessionId }, (error: Error | null, ack: ChatAck<ChatJoin> | undefined) => {
        if (disposed) return
        if (error || !ack) { report(unavailable); scheduleJoin(); return }
        if (!ack.ok) {
          report(ack.error)
          if (ack.error.status === 401) { socket.disconnect(); void refresh() }
          else if (ack.error.retryable) scheduleJoin()
          return
        }
        refreshAttempted = false
        setJoined(true); setCanSend(ack.data.canSend); setProblem(null)
        client.setQueryData<ChatWindow>(queryKey, data => data?.pages.at(-1)?.newerCursor === ack.data.watermark ? data : markChatGap(data))
      })
    }
    function receive(message: ChatMessage) {
      if (disposed || message.streamId !== sessionId) return
      client.setQueryData<ChatWindow>(queryKey, data => mergeChatMessage(data, message, followRef.current))
      if (!followRef.current && message.sequence > lastReceived) setUnread(n => n + 1)
      lastReceived = Math.max(lastReceived, message.sequence)
    }
    socket.on('connect', join)
    socket.on('chat:message', receive)
    socket.on('chat:closed', (error: ChatProblem) => {
      setJoined(false); setCanSend(false)
      void client.cancelQueries({ queryKey })
      client.removeQueries({ queryKey })
      report(error)
      if (error.status === 401) { socket.disconnect(); void refresh() }
      else if (error.retryable) scheduleJoin()
    })
    socket.on('disconnect', () => { if (!disposed) { setJoined(false); setCanSend(false) } })
    socket.on('connect_error', (error: Error & { data?: ChatProblem }) => {
      setJoined(false); report(error.data ?? unavailable)
      if (error.data?.status === 401) void refresh()
      else if (error.data?.retryable || !error.data) scheduleJoin()
    })
    socket.connect()
    return () => {
      disposed = true
      if (retryTimer) clearTimeout(retryTimer)
      socket.emit('chat:leave', { sessionId })
      socket.removeAllListeners(); socket.disconnect(); socketRef.current = null
      void client.cancelQueries({ queryKey })
      client.removeQueries({ queryKey })
    }
  }, [sessionId, userId, active, client])

  const send = useCallback((content: string, clientMessageId: string): Promise<ChatMessage> => new Promise((resolve, reject) => {
    const socket = socketRef.current
    if (!socket?.connected || !joined || !canSend) { reject(new Error('Chat is not ready to send.')); return }
    socket.timeout(5000).emit('chat:send', { sessionId, clientMessageId, content },
      (error: Error | null, ack: ChatAck<ChatMessage> | undefined) => {
        if (error || !ack) { reject(new Error('Delivery is unconfirmed. Retry will reuse the same message ID.')); return }
        if (!ack.ok) { reject(new Error(ack.error.message)); return }
        client.setQueryData<ChatWindow>(chatKey(userId ?? '', sessionId), data => mergeChatMessage(data, ack.data, followRef.current))
        resolve(ack.data)
      })
  }), [joined, canSend, sessionId, userId, client])

  const setFollowing = useCallback((value: boolean) => {
    if (followRef.current !== value) {
      followRef.current = value
      setFollowLive(value)
    }
    if (value) setUnread(n => (n === 0 ? 0 : 0))
  }, [])
  const latest = useCallback(async () => {
    setFollowing(true)
    await client.cancelQueries({ queryKey: chatKey(userId ?? '', sessionId) })
    await client.resetQueries({ queryKey: chatKey(userId ?? '', sessionId) })
  }, [client, sessionId, userId, setFollowing])
  const historyError = feed.error
  useEffect(() => {
    if (isAxiosError(historyError) && [401, 403].includes(historyError.response?.status ?? 0)) accessErrorRef.current?.(historyError)
  }, [historyError])
  const messages = useMemo(
    () => (joined ? (feed.data?.pages ?? []).flatMap(page => page.items) : []),
    [joined, feed.data?.pages],
  )
  return { ...feed, messages, joined, canSend, problem, unread, followLive, setFollowing, latest, send,
    reconnect: () => { socketRef.current?.disconnect(); socketRef.current?.connect() } }
}
