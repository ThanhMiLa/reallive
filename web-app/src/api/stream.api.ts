import apiClient from './client'
import type { ApiResponse } from '@/types/auth.types'
import type {
  CreateStreamRequest,
  CreateStreamResponse,
  PlaybackAccess,
  PublishCredentials,
  StreamDetails,
  StreamDiscoveryResponse,
  StreamListParams,
  StreamMetadata,
  StreamSession,
} from '@/types/stream.types'

const base = '/stream/api/streams'
const path = (id: string) => `${base}/${encodeURIComponent(id)}`

export const streamApi = {
  list: async (params?: StreamListParams, signal?: AbortSignal) => {
    const queryParams: Record<string, unknown> = {
      page: params?.page ?? 0,
      size: params?.size ?? 20,
    }
    if (params?.categoryIds && params.categoryIds.length > 0) {
      const uniqueCategoryIds = Array.from(
        new Set(params.categoryIds.filter(Boolean)),
      )
      if (uniqueCategoryIds.length > 0) {
        queryParams.categoryIds = uniqueCategoryIds
      }
    } else if (params?.categoryId?.trim()) {
      queryParams.categoryId = params.categoryId.trim()
    }
    return (
      await apiClient.get<ApiResponse<StreamDiscoveryResponse>>(base, {
        params: queryParams,
        signal,
      })
    ).data.result
  },
  current: async (signal?: AbortSignal) =>
    (await apiClient.get<ApiResponse<StreamSession | null>>(`${base}/me/current`, { signal })).data
      .result,
  metadata: async (id: string, signal?: AbortSignal) =>
    (await apiClient.get<ApiResponse<StreamMetadata>>(path(id), { signal })).data.result,
  create: async (payload: CreateStreamRequest, signal?: AbortSignal) =>
    (await apiClient.post<ApiResponse<CreateStreamResponse>>(base, payload, { signal })).data
      .result,
  update: async (id: string, payload: Partial<StreamDetails>, signal?: AbortSignal) =>
    (await apiClient.patch<ApiResponse<StreamSession>>(path(id), payload, { signal })).data.result,
  credentials: async (id: string, signal?: AbortSignal) =>
    (
      await apiClient.post<ApiResponse<PublishCredentials>>(
        `${path(id)}/publish-credentials`,
        undefined,
        { signal },
      )
    ).data.result,
  access: async (id: string, signal?: AbortSignal) =>
    (
      await apiClient.post<ApiResponse<PlaybackAccess>>(`${path(id)}/playback-access`, undefined, {
        signal,
      })
    ).data.result,
  end: async (id: string, signal?: AbortSignal) =>
    (await apiClient.post<ApiResponse<StreamSession>>(`${path(id)}/end`, undefined, { signal }))
      .data.result,
}
