import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'

const baseURL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8888'

export const apiClient = axios.create({
  baseURL,
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
  },
  paramsSerializer: {
    indexes: null,
  },
})

// Track ongoing refresh promise to prevent duplicate refresh calls
let refreshTokenPromise: Promise<void> | null = null

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const originalRequest = error.config as InternalAxiosRequestConfig & {
      _retry?: boolean
    }

    const requestUrl = originalRequest?.url || ''
    const isAuthRoute = requestUrl.includes('/user/api/auth/')

    // If unauthorized and not an auth route (login/register/refresh/logout), attempt token refresh
    if (
      error.response?.status === 401 &&
      originalRequest &&
      !originalRequest._retry &&
      !isAuthRoute
    ) {
      originalRequest._retry = true

      try {
        if (!refreshTokenPromise) {
          refreshTokenPromise = (async () => {
            try {
              await axios.post(
                `${baseURL}/user/api/auth/refresh`,
                {},
                { withCredentials: true },
              )
            } finally {
              refreshTokenPromise = null
            }
          })()
        }

        await refreshTokenPromise
        return apiClient(originalRequest)
      } catch (refreshError) {
        return Promise.reject(refreshError)
      }
    }

    return Promise.reject(error)
  },
)

export default apiClient
