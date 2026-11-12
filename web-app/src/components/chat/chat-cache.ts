import type { InfiniteData } from '@tanstack/react-query'
import type { ChatMessage, ChatPage, ChatPageParam } from '@/types/chat.types'

export type ChatWindow = InfiniteData<ChatPage, ChatPageParam>
export const CHAT_MAX_PAGES = 20

/** Appends only contiguous realtime messages; a gap is filled from persisted history. */
export function mergeChatMessage(data: ChatWindow | undefined, message: ChatMessage, followLive: boolean): ChatWindow | undefined {
  if (!data?.pages.length) return data
  const last = data.pages.at(-1)!
  const tail = last.items.at(-1)
  if (tail && message.sequence <= tail.sequence) return data
  const pages = data.pages.map(p => ({ ...p, items: [...p.items] }))
  const params = [...data.pageParams]
  if (!followLive || last.hasNewer || ((tail && message.sequence !== tail.sequence + 1) || (!tail && message.sequence !== 1))) {
    pages[pages.length - 1].hasNewer = true
    return { pages, pageParams: params }
  }
  const updated = pages[pages.length - 1]
  if (updated.items.length < 50) {
    updated.items.push(message)
    updated.newerCursor = message.cursor
    updated.olderCursor ??= message.cursor
    updated.watermark = message.cursor
  } else {
    pages.push({ items: [message], olderCursor: message.cursor, newerCursor: message.cursor,
      hasOlder: true, hasNewer: false, watermark: message.cursor })
    params.push({ after: last.newerCursor ?? last.watermark })
  }
  if (pages.length > CHAT_MAX_PAGES) {
    pages.shift(); params.shift()
    pages[0] = { ...pages[0], hasOlder: true }
  }
  return { pages, pageParams: params }
}

export function markChatGap(data: ChatWindow | undefined): ChatWindow | undefined {
  if (!data?.pages.length) return data
  return { ...data, pages: data.pages.map((p, index) => index === data.pages.length - 1 ? { ...p, hasNewer: true } : p) }
}
