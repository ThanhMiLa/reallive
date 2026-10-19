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
