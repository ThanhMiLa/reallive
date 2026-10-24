import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'
import type { UserProfile } from '@/types/auth.types'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * Checks if a user has the streamer role across any representation (roles array, role property, authorities).
 */
export function hasStreamerRole(
  user: (Partial<UserProfile> & { role?: unknown; authorities?: unknown }) | null | undefined,
): boolean {
  if (!user) return false

  const isStreamerStr = (val: unknown): boolean => {
    if (typeof val === 'string') {
      const v = val.trim().toUpperCase()
      return v === 'STREAMER' || v === 'ROLE_STREAMER'
    }
    return false
  }

  const checkValue = (val: unknown): boolean => {
    if (!val) return false
    if (isStreamerStr(val)) return true
    if (Array.isArray(val)) {
      return val.some((item) => {
        if (isStreamerStr(item)) return true
        if (item && typeof item === 'object' && 'name' in item) {
          return isStreamerStr((item as { name: unknown }).name)
        }
        return false
      })
    }
    return false
  }

  return checkValue(user.roles) || checkValue(user.role) || checkValue(user.authorities)
}
