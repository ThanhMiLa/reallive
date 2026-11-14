import apiClient from './client'
import type { ApiResponse } from '@/types/auth.types'
import type {
  PlaybackAccess,
  SaveStreamSetupRequest,
  StreamDiscoveryResponse,
  StreamListParams,
  StreamMetadata,
  StreamKeyCredentials,
  StreamSession,
  StreamSetup,
  StreamViewerDetails,
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
  setup: async (signal?: AbortSignal) =>
    (await apiClient.get<ApiResponse<StreamSetup>>(`${base}/me/setup`, { signal })).data.result,
  saveSetup: async (payload: SaveStreamSetupRequest, signal?: AbortSignal) =>
    (await apiClient.put<ApiResponse<StreamSetup>>(`${base}/me/setup`, payload, { signal })).data
      .result,
  issueKey: async (signal?: AbortSignal) =>
    (await apiClient.post<ApiResponse<StreamKeyCredentials>>(`${base}/me/stream-key`, undefined, { signal }))
      .data.result,
  current: async (signal?: AbortSignal) =>
    (await apiClient.get<ApiResponse<StreamSession | null>>(`${base}/me/current`, { signal })).data
      .result,
  metadata: async (id: string, signal?: AbortSignal) =>
    (await apiClient.get<ApiResponse<StreamMetadata>>(path(id), { signal })).data.result,
  details: async (id: string, signal?: AbortSignal) =>
    (await apiClient.get<ApiResponse<StreamViewerDetails>>(`${path(id)}/details`, { signal })).data
      .result,
  access: async (id: string, signal?: AbortSignal) =>
    (
      await apiClient.post<ApiResponse<PlaybackAccess>>(`${path(id)}/playback-access`, undefined, {
        signal,
      })
    ).data.result,
}
