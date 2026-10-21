import apiClient from './client'
import type { ApiResponse } from '@/types/auth.types'
import type { BecomeStreamerResponse } from '@/types/streamer.types'

export const streamerApi = {
  becomeStreamer: async (): Promise<BecomeStreamerResponse> => {
    const response = await apiClient.post<ApiResponse<BecomeStreamerResponse>>(
      '/user/api/users/me/become-streamer',
    )
    return response.data.result
  },
}
