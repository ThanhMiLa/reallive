import { z } from 'zod'

export const followingSortByEnum = z.enum(['FOLLOWER_COUNT', 'FOLLOWED_AT'])
export const sortDirectionEnum = z.enum(['ASC', 'DESC'])
export const unfollowedSortDirectionEnum = z.literal('DESC')

export const followingParamsSchema = z
  .object({
    sortBy: followingSortByEnum.default('FOLLOWER_COUNT'),
    sortDirection: sortDirectionEnum.default('DESC'),
    page: z.number().int().min(0).default(0),
    size: z.number().int().min(1).max(100).default(20),
  })
  .refine((data) => (data.page ?? 0) * (data.size ?? 20) <= 2147483647, {
    message: 'Page offset exceeds the supported range',
  })

export type ValidatedFollowingParams = z.infer<typeof followingParamsSchema>

export const rawKeywordSchema = z
  .string()
  .max(255, 'Keyword must not exceed 255 characters')

export const unfollowedParamsSchema = z
  .object({
    keyword: rawKeywordSchema.optional(),
    categoryIds: z.array(z.string().uuid()).optional(),
    sortDirection: unfollowedSortDirectionEnum.default('DESC'),
    page: z.number().int().min(0).default(0),
    size: z.number().int().min(1).max(100).default(20),
  })
  .refine((data) => (data.page ?? 0) * (data.size ?? 20) <= 2147483647, {
    message: 'Page offset exceeds the supported range',
  })

export type ValidatedUnfollowedParams = z.infer<typeof unfollowedParamsSchema>

export const MAX_BANNER_BYTES = 5 * 1024 * 1024
export const ALLOWED_BANNER_TYPES = ['image/jpeg', 'image/png', 'image/webp']

export function validateBannerFile(file: File): string | null {
  if (!ALLOWED_BANNER_TYPES.includes(file.type)) {
    return 'Banner must be a JPEG, PNG, or WebP image.'
  }
  if (file.size > MAX_BANNER_BYTES) {
    return 'Banner image must be 5 MB or smaller.'
  }
  return null
}

export const updateChannelSchema = z.object({
  description: z
    .string()
    .trim()
    .max(1000, 'Description must not exceed 1000 characters')
    .refine((val) => val.length === 0 || val.trim().length > 0, {
      message: 'Description cannot be only whitespace',
    })
    .optional(),
  categoryIds: z
    .array(z.string().uuid('Invalid category ID'))
    .min(1, 'Please select at least one category')
    .optional(),
})

export type ValidatedUpdateChannel = z.infer<typeof updateChannelSchema>
