import apiClient from './client'
import { categoryPreferencesSchema } from '@/schemas/category-preferences.schema'
import type { ApiResponse } from '@/types/auth.types'
import type { CategoryPreferences } from '@/types/category-preferences.types'

const endpoint = '/user/api/users/me/category-preferences'

export const categoryPreferencesApi = {
  get: async (signal?: AbortSignal): Promise<CategoryPreferences> => {
    const response = await apiClient.get<ApiResponse<CategoryPreferences>>(endpoint, { signal })
    return response.data.result
  },
  update: async (categoryIds: string[]): Promise<CategoryPreferences> => {
    const payload = categoryPreferencesSchema.parse({ categoryIds: [...new Set(categoryIds)] })
    const response = await apiClient.put<ApiResponse<CategoryPreferences>>(endpoint, payload)
    return response.data.result
  },
}
