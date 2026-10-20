import apiClient from './client'
import type { ApiResponse } from '@/types/auth.types'
import type {
  CategoryItem,
  ChannelListItem,
  FollowingChannelListParams,
  PageResponse,
  UnfollowedChannelListParams,
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

