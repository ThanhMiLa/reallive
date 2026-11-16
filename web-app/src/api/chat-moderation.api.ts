import { isAxiosError } from 'axios'
import apiClient from './client'
import type { ApiResponse } from '@/types/auth.types'
import type {
  BanChatUserResponse,
  BannedUsersPage,
  BannedUsersParams,
  ChatModerationContext,
  TargetChatBanStatus,
  UnbanChatUserResponse,
} from '@/types/chat-moderation.types'

const channelBase = (channelId: string) =>
  `/channel/api/channels/${encodeURIComponent(channelId)}/chat`

export class ChatModerationApiError extends Error {
  status?: number
  code?: number

  constructor(message: string, status?: number, code?: number) {
    super(message)
    this.name = 'ChatModerationApiError'
    this.status = status
    this.code = code
  }
}

function unwrapEnvelope<T>(payload: ApiResponse<T>): T {
  if (
    payload &&
    typeof payload.code === 'number' &&
    payload.code !== 1000
  ) {
    throw new ChatModerationApiError(
      payload.message || 'Moderation request failed.',
      payload.status,
      payload.code,
    )
  }
  return payload.result
}

export interface ParsedModerationError {
  status: number | null
  code: number | null
  message: string
  isConflict: boolean
  isForbidden: boolean
  isUnauthorized: boolean
}

const CONFLICT_MESSAGES: Record<number, string> = {
  1023: 'This user is already banned from chat.',
  1024: 'This user does not have an active chat ban.',
  1025: 'The channel owner cannot be banned.',
  1026: 'This user has a livestream or full ban and cannot be chat banned.',
  1027: 'Livestream or full bans cannot be removed with chat unban.',
}

export function parseChatModerationError(
  error: unknown,
  fallback = 'Unable to complete this moderation request.',
): ParsedModerationError {
  if (error instanceof ChatModerationApiError) {
    const status = error.status ?? null
    const code = error.code ?? null
    const conflictMessage = code !== null ? CONFLICT_MESSAGES[code] : undefined
    return {
      status,
      code,
      message: conflictMessage || error.message || fallback,
      isConflict: status === 409 || (code !== null && code in CONFLICT_MESSAGES),
      isForbidden: status === 403,
      isUnauthorized: status === 401,
    }
  }

  if (isAxiosError<ApiResponse>(error)) {
    const status = error.response?.status ?? null
    const code =
      typeof error.response?.data?.code === 'number'
        ? error.response.data.code
        : null
    const serverMessage =
      typeof error.response?.data?.message === 'string' &&
      error.response.data.message.trim().length > 0
        ? error.response.data.message.trim()
        : null

    if (status === 401) {
      return {
        status,
        code,
        message: 'Your session has expired. Please log in again.',
        isConflict: false,
        isForbidden: false,
        isUnauthorized: true,
      }
    }

    if (status === 403) {
      return {
        status,
        code,
        message:
          serverMessage || 'You do not have permission for this moderation action.',
        isConflict: false,
        isForbidden: true,
        isUnauthorized: false,
      }
    }

    if (status === 404) {
      return {
        status,
        code,
        message: serverMessage || 'Channel or user could not be found.',
        isConflict: false,
        isForbidden: false,
        isUnauthorized: false,
      }
    }

    if (status === 409) {
      const conflictMessage = code !== null ? CONFLICT_MESSAGES[code] : undefined
      return {
        status,
        code,
        message:
          conflictMessage ||
          serverMessage ||
          'This user’s ban status changed. Refreshing current status.',
        isConflict: true,
        isForbidden: false,
        isUnauthorized: false,
      }
    }

    if (status === 503) {
      return {
        status,
        code,
        message: 'Moderation service is temporarily unavailable. Please try again.',
        isConflict: false,
        isForbidden: false,
        isUnauthorized: false,
      }
    }

    if (!error.response) {
      return {
        status: null,
        code: null,
        message: 'Unable to connect to the server. Please try again.',
        isConflict: false,
        isForbidden: false,
        isUnauthorized: false,
      }
    }

    return {
      status,
      code,
      message: serverMessage || fallback,
      isConflict: false,
      isForbidden: false,
      isUnauthorized: false,
    }
  }

  return {
    status: null,
    code: null,
    message: error instanceof Error && error.message ? error.message : fallback,
    isConflict: false,
    isForbidden: false,
    isUnauthorized: false,
  }
}

export const chatModerationApi = {
  getModerationContext: async (
    channelId: string,
    signal?: AbortSignal,
  ): Promise<ChatModerationContext> => {
    const response = await apiClient.get<ApiResponse<ChatModerationContext>>(
      `${channelBase(channelId)}/moderation-context`,
      { signal },
    )
    return unwrapEnvelope(response.data)
  },

  getTargetBanStatus: async (
    channelId: string,
    userId: string,
    signal?: AbortSignal,
  ): Promise<TargetChatBanStatus> => {
    const response = await apiClient.get<ApiResponse<TargetChatBanStatus>>(
      `${channelBase(channelId)}/users/${encodeURIComponent(userId)}/ban`,
      { signal },
    )
    return unwrapEnvelope(response.data)
  },

  banChatUser: async (
    channelId: string,
    userId: string,
  ): Promise<BanChatUserResponse> => {
    const response = await apiClient.post<ApiResponse<BanChatUserResponse>>(
      `${channelBase(channelId)}/users/${encodeURIComponent(userId)}/ban`,
      undefined,
    )
    return unwrapEnvelope(response.data)
  },

  unbanChatUser: async (
    channelId: string,
    userId: string,
  ): Promise<UnbanChatUserResponse> => {
    const response = await apiClient.post<ApiResponse<UnbanChatUserResponse>>(
      `${channelBase(channelId)}/users/${encodeURIComponent(userId)}/unban`,
      undefined,
    )
    return unwrapEnvelope(response.data)
  },

  getBannedUsers: async (
    channelId: string,
    params?: BannedUsersParams,
    signal?: AbortSignal,
  ): Promise<BannedUsersPage> => {
    const cleanParams = {
      page: params?.page ?? 0,
      size: params?.size ?? 20,
    }
    const response = await apiClient.get<ApiResponse<BannedUsersPage>>(
      `${channelBase(channelId)}/bans`,
      { params: cleanParams, signal },
    )
    return unwrapEnvelope(response.data)
  },
}
