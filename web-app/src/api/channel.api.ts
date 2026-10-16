import apiClient from './client'
import type { ApiResponse } from '@/types/auth.types'
import type {
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
}
