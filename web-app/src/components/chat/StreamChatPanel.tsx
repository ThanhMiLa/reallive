import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useVirtualizer } from '@tanstack/react-virtual'
import { Loader2, MessageSquare, RefreshCw, Send } from 'lucide-react'
import { Avatar } from '@/components/ui/Avatar'
import {
  ChannelUserBanMenu,
  type ChannelModerationTarget,
} from './ChannelUserBanMenu'
import { isChatBannedError, useStreamChat } from './useStreamChat'
import { useChannelModerationContext } from '@/hooks/useChannelModeration'
import { useAuthStore } from '@/stores/auth.store'

export function StreamChatPanel({ sessionId, channelId, status, streamerUserId, onAccessError, embedded = false }: {
  sessionId: string; channelId?: string; status?: string; streamerUserId?: string; onAccessError?: (error: unknown) => void; embedded?: boolean
}) {
  const viewerId = useAuthStore(state => state.user?.userId ?? '')
  const active = status === 'LIVE' || status === 'RECONNECTING'
  // A keyed child discards transient compose/connection state on room or lifecycle changes.
  return <ChatRoom key={`${viewerId}:${sessionId}:${active}`} sessionId={sessionId} channelId={channelId} viewerId={viewerId} streamerUserId={streamerUserId} status={status} active={active} onAccessError={onAccessError} embedded={embedded} />
}

function ChatRoom({ sessionId, channelId, viewerId, streamerUserId, status, active, onAccessError, embedded }: {
  sessionId: string; channelId?: string; viewerId: string; streamerUserId?: string; status?: string; active: boolean; onAccessError?: (error: unknown) => void; embedded: boolean
}) {
  const chat = useStreamChat(sessionId, active, onAccessError)
  const moderationContextQuery = useChannelModerationContext(viewerId, channelId)
  const moderationContext = moderationContextQuery.data
  const canOpenBanMenu = Boolean(
    channelId && moderationContext &&
    (moderationContext.allowedBanTypes.length > 0 || moderationContext.allowedUnbanTypes.length > 0),
  )
  const ownerId = moderationContext?.ownerUserId || streamerUserId || ''
  const menuScope = `${viewerId}:${sessionId}:${channelId ?? ''}:${canOpenBanMenu}`

  const scrollRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const anchorElementRef = useRef<HTMLButtonElement | null>(null)
  const anchor = useRef<{ id: string; offset: number } | null>(null)
  const lastScrollTop = useRef(0)
  const prevTailId = useRef<string | null>(null)
  const prevFollowLive = useRef(chat.followLive)
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState<{ id: string; content: string } | null>(null)
  const [sending, setSending] = useState(false)
  const [sendError, setSendError] = useState<string | null>(null)
  const [selectedMenu, setSelectedMenu] = useState<{
    scope: string
    target: ChannelModerationTarget
    nonce: number
  } | null>(null)

  const activeMenu =
    selectedMenu && selectedMenu.scope === menuScope ? selectedMenu : null

  useEffect(() => {
    if (activeMenu && anchorElementRef.current && !anchorElementRef.current.isConnected) {
      queueMicrotask(() => {
        setSelectedMenu(null)
      })
    }
  }, [activeMenu, chat.messages])

  const getAnchorElement = useCallback(() => anchorElementRef.current, [])
  const getScrollContainer = useCallback(() => scrollRef.current, [])
  const handleCloseMenu = useCallback((restoreFocus = false) => {
    const trigger = anchorElementRef.current
    setSelectedMenu(null)
    if (restoreFocus && trigger && trigger.isConnected) {
      const container = scrollRef.current
      const containerRect = container?.getBoundingClientRect()
      const triggerRect = trigger.getBoundingClientRect()
      const hasLayout =
        Boolean(containerRect) &&
        containerRect!.height > 0 &&
        triggerRect.height > 0
      const visible =
        !hasLayout ||
        (triggerRect.bottom > containerRect!.top &&
          triggerRect.top < containerRect!.bottom)
      if (visible) {
        queueMicrotask(() => {
          if (trigger.isConnected) trigger.focus()
        })
      }
    }
  }, [])

  // eslint-disable-next-line react-hooks/incompatible-library -- The virtualizer intentionally reads the live scroll viewport.
  const virtualizer = useVirtualizer({
    count: chat.messages.length, getScrollElement: () => scrollRef.current,
    estimateSize: () => 62,
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
    catch (error) {
      if (isChatBannedError(error)) {
        // Definitive rejection: preserve draft without auto-resending and unlock compose state for future access restoration.
        setPending(null)
        setSendError(null)
      } else {
        setSendError(error instanceof Error ? error.message : 'Unable to send message.')
      }
    }
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
          <div className="ml-auto flex items-center gap-2">
            {connecting && <RefreshCw aria-label="Connecting chat" className="h-3.5 w-3.5 animate-spin text-gray-500" />}
          </div>
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
              const isStreamer = Boolean(ownerId) && message.userId === ownerId
              const canModerateSender = canOpenBanMenu && message.userId !== ownerId
              const isMenuOpenForUser =
                activeMenu?.target.userId === message.userId
              const borderClass = isStreamer
                ? 'border-[#ff2b42]'
                : isCurrentUser
                  ? 'border-[#0084ff]'
                  : 'border-transparent'
              const wrapperClass = `flex items-start gap-2 rounded-lg border px-2.5 py-2 ${borderClass}`
              const nameColor = isStreamer ? 'text-green-400' : 'text-white'
              return <div key={message.id} data-index={item.index} ref={virtualizer.measureElement} className="absolute top-0 left-0 w-full px-3 py-1" style={{ transform: `translateY(${item.start}px)` }}>
                <div className={wrapperClass}>
                  {canModerateSender ? (
                    <button
                      type="button"
                      aria-label={`Moderation options for ${message.sender.displayName}`}
                      aria-haspopup="menu"
                      aria-expanded={isMenuOpenForUser}
                      onClick={event => {
                        const button = event.currentTarget
                        if (isMenuOpenForUser && anchorElementRef.current === button) {
                          anchorElementRef.current = null
                          setSelectedMenu(null)
                          return
                        }
                        anchorElementRef.current = button
                        setSelectedMenu(prev => ({
                          scope: menuScope,
                          target: {
                            userId: message.userId,
                            displayName: message.sender.displayName,
                            avatarUrl: message.sender.avatarUrl,
                          },
                          nonce: (prev?.nonce ?? 0) + 1,
                        }))
                      }}
                      className="h-7 w-7 shrink-0 rounded-full border-0 bg-transparent p-0 cursor-pointer transition hover:ring-2 hover:ring-[#ff2b42]/70 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42]"
                    >
                      <Avatar url={message.sender.avatarUrl} name={message.sender.displayName} />
                    </button>
                  ) : (
                    <Avatar url={message.sender.avatarUrl} name={message.sender.displayName} />
                  )}
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
        {active && chat.joined && !chat.canSend && (
          <div className="mb-2 space-y-1.5 rounded-lg border border-amber-500/25 bg-amber-500/10 p-2.5 text-xs text-amber-200">
            <p>
              {chat.chatBanned
                ? 'You are banned from sending messages in this channel.'
                : 'You can read chat, but cannot send messages in this channel.'}
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                disabled={chat.isCheckingAccess}
                onClick={() => chat.checkAccess()}
                className="inline-flex items-center gap-1.5 rounded-md border border-amber-400/35 bg-[#101014] px-2.5 py-1 text-[11px] font-semibold text-amber-100 transition hover:bg-[#18181f] disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-[#ff2b42] cursor-pointer"
              >
                {chat.isCheckingAccess && (
                  <Loader2 className="h-3 w-3 animate-spin motion-reduce:animate-none" />
                )}
                <span>{chat.isCheckingAccess ? 'Checking access…' : 'Check chat access'}</span>
              </button>
            </div>
            {chat.accessCheckFeedback && (
              <p role="status" className="text-[11px] text-amber-100/90">
                {chat.accessCheckFeedback}
              </p>
            )}
          </div>
        )}
        {sendError && <div role="alert" className="mb-2 text-xs text-amber-300">{sendError}<div className="mt-1 flex gap-3"><button type="button" disabled={sending || !chat.canSend} onClick={() => void submit(true)} className="underline">Retry message</button><button type="button" onClick={() => { setPending(null); setSendError(null) }} className="underline">Dismiss</button></div></div>}
        <div className="flex items-center gap-2"><input ref={inputRef} aria-label="Send a message" placeholder="Send a message" value={draft} onChange={event => setDraft(event.target.value)} disabled={inputDisabled} readOnly={sendBusy} className="min-w-0 flex-1 rounded-lg border border-[#1f1f26] bg-[#101014] px-3 py-2.5 text-xs text-gray-200 placeholder-gray-500 disabled:opacity-50 read-only:opacity-50" />
          <button aria-label="Send chat message" type="submit" disabled={disabled || !characters || characters > 500} className="rounded-lg bg-[#ff2b42] p-2.5 text-white disabled:opacity-40"><Send className="h-4 w-4" /></button></div>
        <p className={`mt-1 text-right text-[10px] ${characters > 500 ? 'text-red-400' : 'text-gray-500'}`}>{characters}/500</p>
      </form>
      {activeMenu && channelId && (
        <ChannelUserBanMenu
          key={`${channelId}:${activeMenu.target.userId}:${activeMenu.nonce}`}
          viewerId={viewerId}
          channelId={channelId}
          target={activeMenu.target}
          getAnchorElement={getAnchorElement}
          getScrollContainer={getScrollContainer}
          onClose={handleCloseMenu}
        />
      )}
    </>
  )
  if (embedded) return <div className="flex min-h-0 flex-1 flex-col bg-[#08080a]">{content}</div>
  return <aside aria-label="Stream chat" className="flex h-[440px] min-h-0 w-full shrink-0 flex-col border-t border-[#1f1f26] bg-[#08080a] xl:h-full xl:w-[340px] xl:border-t-0 xl:border-l 2xl:w-[360px]">{content}</aside>
}
