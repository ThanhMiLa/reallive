import { z } from 'zod'
import apiClient from './client'
import type { ChatPage, ChatPageParam } from '@/types/chat.types'

const message = z.object({
  id: z.uuid(), cursor: z.string(), streamId: z.uuid(), channelId: z.uuid(), userId: z.uuid(), clientMessageId: z.uuid(),
  sequence: z.number().int().positive(), content: z.string(),
  sender: z.object({ displayName: z.string(), avatarUrl: z.string().nullable() }), createdAt: z.string(),
})
const page = z.object({ items: z.array(message).max(50), olderCursor: z.string().nullable(), newerCursor: z.string().nullable(),
  hasOlder: z.boolean(), hasNewer: z.boolean(), watermark: z.string() })
export const chatApi = {
  async history(sessionId: string, params: ChatPageParam, signal?: AbortSignal): Promise<ChatPage> {
    const response = await apiClient.get<{ result: unknown }>(`/chat/api/streams/${sessionId}/messages`, { params, signal })
    return page.parse(response.data.result)
  },
}
