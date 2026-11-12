export interface ChatMessage {
  id: string
  cursor: string
  streamId: string
  channelId: string
  userId: string
  clientMessageId: string
  sequence: number
  content: string
  sender: { displayName: string; avatarUrl: string | null }
  createdAt: string
}
export interface ChatPage {
  items: ChatMessage[]
  olderCursor: string | null
  newerCursor: string | null
  hasOlder: boolean
  hasNewer: boolean
  watermark: string
}
export interface ChatPageParam { before?: string; after?: string; through?: string }
export interface ChatProblem { code: string; status: number; message: string; retryable: boolean }
export type ChatAck<T> = { ok: true; data: T } | { ok: false; error: ChatProblem }
export interface ChatJoin { sessionId: string; canRead: boolean; canSend: boolean; watermark: string }
