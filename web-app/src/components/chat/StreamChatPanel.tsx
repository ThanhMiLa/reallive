import { useLayoutEffect, useRef, useState } from 'react'
import { useVirtualizer } from '@tanstack/react-virtual'
import { MessageSquare, RefreshCw, Send } from 'lucide-react'
import { useStreamChat } from './useStreamChat'
import { useAuthStore } from '@/stores/auth.store'

function Avatar({ url, name }: { url: string | null; name: string }) {
  const [failed, setFailed] = useState<string | null>(null)
  return url && failed !== url
    ? <img src={url} onError={() => setFailed(url)} alt="" className="h-7 w-7 shrink-0 rounded-full object-cover" />
    : <span aria-hidden="true" className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-[#25252d] text-xs text-gray-300">{name.charAt(0).toUpperCase()}</span>
}

export function StreamChatPanel({ sessionId, status, streamerUserId, onAccessError, embedded = false }: {
  sessionId: string; status?: string; streamerUserId?: string; onAccessError?: (error: unknown) => void; embedded?: boolean
}) {
  const viewerId = useAuthStore(state => state.user?.userId ?? '')
  const active = status === 'LIVE' || status === 'RECONNECTING'
  // A keyed child discards transient compose/connection state on room or lifecycle changes.
  return <ChatRoom key={`${viewerId}:${sessionId}:${active}`} sessionId={sessionId} viewerId={viewerId} streamerUserId={streamerUserId} status={status} active={active} onAccessError={onAccessError} embedded={embedded} />
}

function ChatRoom({ sessionId, viewerId, streamerUserId, status, active, onAccessError, embedded }: {
  sessionId: string; viewerId: string; streamerUserId?: string; status?: string; active: boolean; onAccessError?: (error: unknown) => void; embedded: boolean
}) {
  const chat = useStreamChat(sessionId, active, onAccessError)
  const scrollRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const anchor = useRef<{ id: string; offset: number } | null>(null)
  const lastScrollTop = useRef(0)
  const prevTailId = useRef<string | null>(null)
  const prevFollowLive = useRef(chat.followLive)
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState<{ id: string; content: string } | null>(null)
  const [sending, setSending] = useState(false)
  const [sendError, setSendError] = useState<string | null>(null)
  // eslint-disable-next-line react-hooks/incompatible-library -- The virtualizer intentionally reads the live scroll viewport.
  const virtualizer = useVirtualizer({
    count: chat.messages.length, getScrollElement: () => scrollRef.current,
    estimateSize: index => {
      const msg = chat.messages[index]
      if (!msg) return 52
      const bordered = (Boolean(streamerUserId) && msg.userId === streamerUserId) || (Boolean(viewerId) && msg.userId === viewerId)
      return bordered ? 68 : 52
    },
    overscan: 12, getItemKey: index => chat.messages[index].id,
  })
  const tailId = chat.messages.at(-1)?.id ?? null
  useLayoutEffect(() => {
    if (anchor.current) {
      const index = chat.messages.findIndex(message => message.id === anchor.current!.id)
      if (index >= 0 && scrollRef.current) {
        const position = virtualizer.getOffsetForIndex(index, 'start')
        if (position) virtualizer.scrollToOffset(position[0] + anchor.current.offset)
      }
      anchor.current = null
    } else if (chat.followLive && chat.messages.length && (tailId !== prevTailId.current || !prevFollowLive.current)) {
      virtualizer.scrollToIndex(chat.messages.length - 1, { align: 'end' })
    }
    prevTailId.current = tailId
    prevFollowLive.current = chat.followLive
  }, [chat.messages, tailId, chat.followLive, virtualizer])

  function rememberAnchor() {
    const element = scrollRef.current
    const first = virtualizer.getVirtualItems().find(item => item.end > (element?.scrollTop ?? 0))
    if (first && element) anchor.current = { id: chat.messages[first.index].id, offset: element.scrollTop - first.start }
  }
  async function older() {
    if (!chat.hasPreviousPage || chat.isFetching) return
    chat.setFollowing(false)
    rememberAnchor()
    await chat.fetchPreviousPage()
  }
  async function newer() {
    if (!chat.hasNextPage || chat.isFetching) return
    rememberAnchor()
    await chat.fetchNextPage()
  }
  async function submit(retry = false) {
    if (!active || !chat.joined || !chat.canSend || sending) return
    if (retry ? !pending : Boolean(pending)) return
    const content = retry && pending ? pending.content : draft.trim()
    if (!content || Array.from(content).length > 500) return
    const request = retry && pending ? pending : { id: crypto.randomUUID(), content }
    setPending(request); setSending(true); setSendError(null)
    try {
      await chat.send(request.content, request.id)
      setPending(null); setDraft('')
      inputRef.current?.focus()
    }
    catch (error) { setSendError(error instanceof Error ? error.message : 'Unable to send message.') }
    finally { setSending(false) }
  }
  const closed = chat.problem?.code === 'CHAT_CLOSED'
  const characters = Array.from(draft.trim()).length
  const inputDisabled = !active || !chat.joined || !chat.canSend
  const sendBusy = sending || Boolean(pending)
  const disabled = inputDisabled || sendBusy
  const connecting = active && !closed && !chat.joined && (!chat.problem || chat.problem.retryable)
  const content = (
    <>
      {!embedded && (
        <div className="flex h-12 shrink-0 items-center gap-2 border-b border-[#1f1f26] px-4">
          <MessageSquare className="h-4 w-4 text-gray-400" /><h2 className="font-outfit text-sm font-bold text-gray-200">Chat</h2>
          {connecting && <RefreshCw aria-label="Connecting chat" className="ml-auto h-3.5 w-3.5 animate-spin text-gray-500" />}
        </div>
      )}
      {embedded && connecting && (
        <div className="flex shrink-0 items-center justify-end border-b border-[#1f1f26] px-4 py-1.5 text-[11px] text-gray-400">
          <RefreshCw aria-label="Connecting chat" className="mr-1.5 h-3 w-3 animate-spin text-gray-500" />
          Connecting…
        </div>
      )}
      {!active || closed ? <div role="status" className="flex flex-1 items-center justify-center p-5 text-center text-sm text-gray-400">{status === 'ENDED' || closed ? 'This stream has ended. Chat is closed.' : 'Chat opens when the stream goes live.'}</div> : <>
        {(chat.problem || chat.isError) && <div role="alert" className="border-b border-[#1f1f26] p-3 text-xs text-amber-300">
          {chat.problem?.message ?? 'Unable to load chat history.'}
          <button type="button" className="ml-2 underline" onClick={() => { if (chat.joined) void chat.refetch(); else chat.reconnect() }}>Retry</button>
        </div>}
          {chat.hasPreviousPage && <button type="button" disabled={chat.isFetching} onClick={() => void older()} className="w-full py-2 text-xs text-gray-400">{chat.isFetchingPreviousPage ? 'Loading…' : 'Load older messages'}</button>}
        <div ref={scrollRef} aria-label="Chat messages" style={{ overflowAnchor: 'none' }} className="min-h-0 flex-1 overflow-y-auto overscroll-contain"
          onScroll={() => {
            const element = scrollRef.current
            if (!element) return
            const currentTop = element.scrollTop
            const delta = currentTop - lastScrollTop.current
            lastScrollTop.current = currentTop
            const distanceFromBottom = element.scrollHeight - currentTop - element.clientHeight
            if (delta < -2 && distanceFromBottom > 8) {
              chat.setFollowing(false)
            } else if (distanceFromBottom <= 24) {
              chat.setFollowing(!chat.hasNextPage)
            }
            if (delta < 0 && currentTop < 120) void older()
            else if (distanceFromBottom <= 24 && chat.hasNextPage && !chat.isFetching) void newer()
          }}>
          {chat.isPending && <p role="status" className="p-4 text-center text-xs text-gray-500">Connecting to chat…</p>}
          {!chat.isPending && !chat.messages.length && !chat.isError && <p className="p-4 text-center text-xs text-gray-500">Start the conversation.</p>}
          <div className="relative w-full" style={{ height: virtualizer.getTotalSize() }}>
            {virtualizer.getVirtualItems().map(item => {
              const message = chat.messages[item.index]
              const isCurrentUser = Boolean(viewerId) && message.userId === viewerId
              const isStreamer = Boolean(streamerUserId) && message.userId === streamerUserId
              const wrapperClass = isStreamer
                ? 'flex items-start gap-2 rounded-lg border border-[#ff2b42] px-2.5 py-2'
                : isCurrentUser
                  ? 'flex items-start gap-2 rounded-lg border border-[#0084ff] px-2.5 py-2'
                  : 'flex items-start gap-2'
              const nameColor = isStreamer ? 'text-green-400' : 'text-white'
              return <div key={message.id} data-index={item.index} ref={virtualizer.measureElement} className="absolute top-0 left-0 w-full px-3 py-2" style={{ transform: `translateY(${item.start}px)` }}>
                <div className={wrapperClass}><Avatar url={message.sender.avatarUrl} name={message.sender.displayName} />
                  <div className="min-w-0 flex-1"><div className="flex items-baseline gap-2"><span className={`truncate text-xs font-semibold ${nameColor}`}>{message.sender.displayName}</span><time dateTime={message.createdAt} className="shrink-0 text-[10px] text-gray-500">{new Date(message.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</time></div>
                    <p className="select-text text-xs leading-relaxed whitespace-pre-wrap break-words text-gray-200">{message.content}</p></div>
                </div>
              </div>
            })}
          </div>
          {chat.hasNextPage && <button type="button" disabled={chat.isFetching} onClick={() => void newer()} className="w-full py-2 text-xs text-gray-400">Load newer messages</button>}
        </div>
        {(!chat.followLive || chat.unread > 0) && <button type="button" onClick={() => void chat.latest()} className="cursor-pointer self-center py-1.5 text-[11px] font-medium text-green-300 transition-colors hover:text-green-200">{chat.unread ? `${chat.unread} new messages · ` : ''}Back to latest</button>}
      </>}
      <form className="shrink-0 border-t border-[#1f1f26] bg-[#050507] p-3" onSubmit={event => { event.preventDefault(); void submit() }}>
        {active && chat.joined && !chat.canSend && <p className="mb-2 text-xs text-amber-300">You can read chat, but cannot send messages in this channel.</p>}
        {sendError && <div role="alert" className="mb-2 text-xs text-amber-300">{sendError}<div className="mt-1 flex gap-3"><button type="button" disabled={sending || !chat.canSend} onClick={() => void submit(true)} className="underline">Retry message</button><button type="button" onClick={() => { setPending(null); setSendError(null) }} className="underline">Dismiss</button></div></div>}
        <div className="flex items-center gap-2"><input ref={inputRef} aria-label="Send a message" placeholder="Send a message" value={draft} onChange={event => setDraft(event.target.value)} disabled={inputDisabled} readOnly={sendBusy} className="min-w-0 flex-1 rounded-lg border border-[#1f1f26] bg-[#101014] px-3 py-2.5 text-xs text-gray-200 placeholder-gray-500 disabled:opacity-50 read-only:opacity-50" />
          <button aria-label="Send chat message" type="submit" disabled={disabled || !characters || characters > 500} className="rounded-lg bg-[#ff2b42] p-2.5 text-white disabled:opacity-40"><Send className="h-4 w-4" /></button></div>
        <p className={`mt-1 text-right text-[10px] ${characters > 500 ? 'text-red-400' : 'text-gray-500'}`}>{characters}/500</p>
      </form>
    </>
  )
  if (embedded) return <div className="flex min-h-0 flex-1 flex-col bg-[#08080a]">{content}</div>
  return <aside aria-label="Stream chat" className="flex h-[440px] min-h-0 w-full shrink-0 flex-col border-t border-[#1f1f26] bg-[#08080a] xl:h-full xl:w-[340px] xl:border-t-0 xl:border-l 2xl:w-[360px]">{content}</aside>
}
