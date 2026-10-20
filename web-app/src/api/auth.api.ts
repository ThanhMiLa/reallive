import apiClient from './client'
import type {
  ApiResponse,
  AuthRequest,
  ChangePasswordRequest,
  RegisterRequest,
  UserProfile,
} from '@/types/auth.types'

export const authApi = {
  changePassword: async (payload: ChangePasswordRequest): Promise<void> => {
    await apiClient.post('/user/api/auth/change-password', payload)
  },

  updateProfile: async (payload: FormData): Promise<UserProfile> => {
    const response = await apiClient.patch<ApiResponse<UserProfile>>(
      '/user/api/users/me', payload,
      { headers: { 'Content-Type': undefined } },
    )
    return response.data.result
  },

  /**
   * Log in user with username and password.
   * Gateway sets access_token and refresh_token HttpOnly cookies.
   */
  login: async (payload: AuthRequest): Promise<ApiResponse<unknown>> => {
    const response = await apiClient.post<ApiResponse<unknown>>(
      '/user/api/auth/login',
      payload,
    )
    return response.data
  },

  /**
   * Register new user.
   * Backend automatically authenticates upon registration and sets HttpOnly cookies.
   */
  register: async (payload: RegisterRequest): Promise<ApiResponse<unknown>> => {
    const response = await apiClient.post<ApiResponse<unknown>>(
      '/user/api/auth/register',
      payload,
    )
    return response.data
  },

  /**
   * Log out user and clear HttpOnly cookies.
   */
  logout: async (): Promise<ApiResponse<void>> => {
    const response = await apiClient.post<ApiResponse<void>>(
      '/user/api/auth/logout',
      {},
    )
    return response.data
  },

  /**
   * Refresh access token via refresh token cookie.
   */
  refreshToken: async (): Promise<ApiResponse<unknown>> => {
    const response = await apiClient.post<ApiResponse<unknown>>(
      '/user/api/auth/refresh',
      {},
    )
    return response.data
  },

  /**
   * Fetch current authenticated user's profile.
   */
  getProfile: async (): Promise<UserProfile> => {
    const response = await apiClient.get<ApiResponse<UserProfile>>(
      '/user/api/users/me',
    )
    return response.data.result
  },
}
