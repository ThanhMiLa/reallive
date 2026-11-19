import { isAxiosError } from 'axios'
import apiClient from './client'
import type { PageResponse } from '@/types/channel.types'
import type { ApiResponse } from '@/types/auth.types'
import type {
  BanType,
  BannedUsersPage,
  BannedUsersParams,
  ChannelModerationContext,
  ModeratorAssignment,
  ModeratorCandidate,
  SetChannelBanRequest,
  TargetChannelBanState,
} from '@/types/channel-moderation.types'

const channelBase = (channelId: string) =>
  `/channel/api/channels/${encodeURIComponent(channelId)}`

export class ChannelModerationApiError extends Error {
  status?: number
  code?: number

  constructor(message: string, status?: number, code?: number) {
    super(message)
    this.name = 'ChannelModerationApiError'
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
    throw new ChannelModerationApiError(
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
  1023: 'This user already has the selected ban level.',
  1024: 'This user does not have an active ban.',
  1025: 'The channel owner cannot be banned.',
}

export function parseChannelModerationError(
  error: unknown,
  fallback = 'Unable to complete this moderation request.',
): ParsedModerationError {
  if (error instanceof ChannelModerationApiError) {
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

    if (status === 400) {
      return {
        status,
        code,
        message: serverMessage || 'Invalid moderation request.',
        isConflict: false,
        isForbidden: false,
        isUnauthorized: false,
      }
    }

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

async function getCsrfHeaders(): Promise<Record<string, string>> {
  try {
    const { data } = await apiClient.get<ApiResponse<{ headerName: string; token: string }>>(
      '/api/auth/csrf',
    )
    const { headerName, token } = unwrapEnvelope(data)
    return { [headerName]: token }
  } catch (error) {
    // The gateway omits this endpoint when CSRF is disabled in development.
    if (isAxiosError(error) && error.response?.status === 404) return {}
    throw error
  }
}

export const channelModerationApi = {
  async getModerators(channelId: string, page: number, size: number, signal?: AbortSignal) {
    const { data } = await apiClient.get<ApiResponse<PageResponse<ModeratorAssignment>>>(
      `${channelBase(channelId)}/moderators`,
      { params: { page, size }, signal },
    )
    return unwrapEnvelope(data)
  },

  async getModeratorRoster(channelId: string, page: number, size: number, signal?: AbortSignal) {
    const { data } = await apiClient.get<ApiResponse<PageResponse<ModeratorAssignment>>>(
      `${channelBase(channelId)}/moderators/roster`,
      { params: { page, size }, signal },
    )
    return unwrapEnvelope(data)
  },

  async searchModeratorCandidates(channelId: string, displayName: string, page: number, size: number, signal: AbortSignal) {
    const { data } = await apiClient.get<ApiResponse<PageResponse<ModeratorCandidate>>>(
      `${channelBase(channelId)}/moderators/candidate`,
      { params: { displayName, page, size }, signal },
    )
    return unwrapEnvelope(data)
  },

  async addModerator(channelId: string, userId: string) {
    const headers = await getCsrfHeaders()
    const { data } = await apiClient.post<ApiResponse<{ channelId: string; userId: string }>>(
      `${channelBase(channelId)}/moderators/${encodeURIComponent(userId)}`,
      undefined,
      { headers },
    )
    return unwrapEnvelope(data)
  },

  async removeModerator(channelId: string, userId: string) {
    const headers = await getCsrfHeaders()
    await apiClient.delete(
      `${channelBase(channelId)}/moderators/${encodeURIComponent(userId)}`,
      { headers },
    )
  },

  getModerationContext: async (
    channelId: string,
    signal?: AbortSignal,
  ): Promise<ChannelModerationContext> => {
    const response = await apiClient.get<ApiResponse<ChannelModerationContext>>(
      `${channelBase(channelId)}/moderation-context`,
      { signal },
    )
    return unwrapEnvelope(response.data)
  },

  getTargetBanState: async (
    channelId: string,
    userId: string,
    signal?: AbortSignal,
  ): Promise<TargetChannelBanState> => {
    const response = await apiClient.get<ApiResponse<TargetChannelBanState>>(
      `${channelBase(channelId)}/users/${encodeURIComponent(userId)}/ban`,
      { signal },
    )
    return unwrapEnvelope(response.data)
  },

  banUser: async (
    channelId: string,
    userId: string,
    banType: BanType,
  ): Promise<TargetChannelBanState> => {
    const body: SetChannelBanRequest = { banType }
    const response = await apiClient.post<ApiResponse<TargetChannelBanState>>(
      `${channelBase(channelId)}/users/${encodeURIComponent(userId)}/ban`,
      body,
    )
    return unwrapEnvelope(response.data)
  },

  unbanUser: async (
    channelId: string,
    userId: string,
  ): Promise<TargetChannelBanState> => {
    const response = await apiClient.post<ApiResponse<TargetChannelBanState>>(
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
    const requestedSize = params?.size ?? 20
    const cleanParams = {
      page: Math.max(0, params?.page ?? 0),
      size: Math.min(50, Math.max(1, requestedSize)),
    }
    const response = await apiClient.get<ApiResponse<BannedUsersPage>>(
      `${channelBase(channelId)}/bans`,
      { params: cleanParams, signal },
    )
    return unwrapEnvelope(response.data)
  },
}
