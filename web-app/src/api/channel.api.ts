import apiClient from './client'
import type { ApiResponse } from '@/types/auth.types'
import type {
  CategoryItem,
  ChannelListItem,
  ChannelResponse,
  FollowingChannelListParams,
  OwnChannel,
  PageResponse,
  UnfollowedChannelListParams,
  UpdateChannelPayload,
} from '@/types/channel.types'

export const channelApi = {
  /**
   * Fetch paginated list of followed channels.
   * Endpoint: GET /channel/api/channels/following
   */
  getFollowingChannels: async (
    params?: FollowingChannelListParams,
    signal?: AbortSignal,
  ): Promise<PageResponse<ChannelListItem>> => {
    const cleanParams: Record<string, unknown> = {
      sortBy: params?.sortBy ?? 'FOLLOWER_COUNT',
      sortDirection: params?.sortDirection ?? 'DESC',
      page: params?.page ?? 0,
      size: params?.size ?? 20,
    }

    const response = await apiClient.get<
      ApiResponse<PageResponse<ChannelListItem>>
    >('/channel/api/channels/following', {
      params: cleanParams,
      signal,
    })
    return response.data.result
  },

  /**
   * Fetch paginated discover channels, including for guests.
   * Authenticated sessions exclude followed channels and can use user preferences.
   * Endpoint: GET /channel/api/channels
   */
  getUnfollowedChannels: async (
    params?: UnfollowedChannelListParams,
    signal?: AbortSignal,
  ): Promise<PageResponse<ChannelListItem>> => {
    const cleanParams: Record<string, unknown> = {
      sortDirection: 'DESC',
      page: params?.page ?? 0,
      size: params?.size ?? 20,
    }

    if (params?.keyword && params.keyword.trim().length > 0) {
      cleanParams.keyword = params.keyword.trim()
    }

    if (params?.categoryIds && params.categoryIds.length > 0) {
      const uniqueCategoryIds = Array.from(
        new Set(params.categoryIds.filter(Boolean)),
      )
      if (uniqueCategoryIds.length > 0) {
        cleanParams.categoryIds = uniqueCategoryIds
      }
    }

    const response = await apiClient.get<
      ApiResponse<PageResponse<ChannelListItem>>
    >('/channel/api/channels', {
      params: cleanParams,
      signal,
    })
    return response.data.result
  },

  /**
   * Fetch all active categories.
   */
  getCategories: async (signal?: AbortSignal): Promise<CategoryItem[]> => {
    const response = await apiClient.get<ApiResponse<CategoryItem[]>>(
      '/channel/api/categories',
      { signal },
    )
    return response.data.result
  },

  /**
   * Fetch current streamer's own channel details.
   * Endpoint: GET /channel/api/channels/me
   */
  getOwnChannel: async (signal?: AbortSignal): Promise<OwnChannel> => {
    const response = await apiClient.get<ApiResponse<OwnChannel>>(
      '/channel/api/channels/me',
      { signal },
    )
    return response.data.result
  },

  /**
   * Update current streamer's channel (description, banner, categories).
   * Endpoint: PATCH /channel/api/channels/me
   */
  updateChannel: async (
    payload: UpdateChannelPayload,
  ): Promise<ChannelResponse> => {
    const formData = new FormData()

    if (payload.description !== undefined && payload.description !== null) {
      formData.append('description', payload.description)
    }

    if (payload.banner) {
      formData.append('bannerUrl', payload.banner)
    }

    if (payload.categoryIds && payload.categoryIds.length > 0) {
      payload.categoryIds.forEach((id) => {
        formData.append('categoryIds', id)
      })
    }

    const response = await apiClient.patch<ApiResponse<ChannelResponse>>(
      '/channel/api/channels/me',
      formData,
      {
        headers: {
          'Content-Type': undefined,
        },
      },
    )
    return response.data.result
  },

  /**
   * Follow a channel by ID.
   */
  followChannel: async (channelId: string): Promise<boolean> => {
    const response = await apiClient.put<ApiResponse<boolean>>(
      `/channel/api/channels/${channelId}/follow`,
    )
    return response.data.result
  },

  /**
   * Unfollow a channel by ID.
   */
  unfollowChannel: async (channelId: string): Promise<boolean> => {
    const response = await apiClient.delete<ApiResponse<boolean>>(
      `/channel/api/channels/${channelId}/follow`,
    )
    return response.data.result
  },
}

