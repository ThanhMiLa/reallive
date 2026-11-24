import { isAxiosError } from 'axios'
import type { ApiResponse } from '@/types/auth.types'

export function formatCoins(amount: number): string {
  return new Intl.NumberFormat('en-US').format(amount)
}

export function formatVnd(amount: number): string {
  return `${new Intl.NumberFormat('vi-VN').format(amount)} VND`
}

export function getPaymentErrorMessage(error: unknown, fallback: string): string {
  if (isAxiosError<ApiResponse>(error)) {
    if (error.response?.status === 401) {
      return 'Your session has expired. Please log in again.'
    }
    if (error.response?.data?.message) {
      return error.response.data.message
    }
  }
  return fallback
}

export function isSafeRedirectUrl(url: string): boolean {
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'https:' || parsed.protocol === 'http:'
  } catch {
    return false
  }
}
