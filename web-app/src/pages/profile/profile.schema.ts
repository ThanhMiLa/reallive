import { z } from 'zod'

export function todayDate() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

export const profileSchema = z.object({
  displayName: z.string().trim().min(1, 'Please enter a display name').max(100, 'Display name must not exceed 100 characters'),
  dob: z.string().refine((value) => {
    if (!value) return true
    const date = new Date(`${value}T00:00:00Z`)
    return /^\d{4}-\d{2}-\d{2}$/.test(value) && !value.startsWith('0000-') &&
      !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value && value <= todayDate()
  }, 'Enter a valid date of birth that is not in the future'),
})

export const passwordSchema = z.object({
  currentPassword: z.string().min(5, 'Current password must contain at least 5 characters').refine((value) => value.trim().length > 0, 'Please enter your current password'),
  newPassword: z.string().min(5, 'New password must contain at least 5 characters').refine((value) => value.trim().length > 0, 'Please enter a new password'),
  confirmPassword: z.string().min(1, 'Please confirm your new password'),
}).refine((data) => data.newPassword === data.confirmPassword, {
  message: 'Passwords do not match', path: ['confirmPassword'],
}).refine((data) => data.newPassword !== data.currentPassword, {
  message: 'New password must differ from your current password', path: ['newPassword'],
})

export function avatarError(file: File) {
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) return 'Please choose a JPG, PNG or WebP image.'
  if (!file.size || file.size > 5 * 1024 * 1024) return 'Please choose a non-empty image no larger than 5 MB.'
  return null
}
