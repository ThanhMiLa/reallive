import apiClient from './client'
import type { ApiResponse } from '@/types/auth.types'
import type {
  CategoryItem,
  ChannelListItem,
  ChannelListParams,
  PageResponse,
} from '@/types/channel.types'

export const channelApi = {
  /**
   * Fetch paginated list of channels (supports following=true/false filter).
   */
  getChannels: async (
    params?: ChannelListParams,
  ): Promise<PageResponse<ChannelListItem>> => {
    const response = await apiClient.get<
      ApiResponse<PageResponse<ChannelListItem>>
    >('/channel/api/channels', {
      params,
    })
    return response.data.result
  },

  /**
   * Fetch all active categories.
   */
  getCategories: async (): Promise<CategoryItem[]> => {
    const response = await apiClient.get<ApiResponse<CategoryItem[]>>(
      '/channel/api/categories',
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

