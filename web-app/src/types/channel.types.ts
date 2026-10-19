export interface ChannelListItem {
  id: string
  name: string
  bannerUrl: string | null
  avatarUrl: string | null
  numberOfFollower: number
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type FollowingSortBy = 'FOLLOWER_COUNT' | 'FOLLOWED_AT'
export type SortDirection = 'ASC' | 'DESC'

export interface FollowingChannelListParams {
  sortBy?: FollowingSortBy
  sortDirection?: SortDirection
  page?: number
  size?: number
}

export type UnfollowedSortDirection = 'DESC'

export interface UnfollowedChannelListParams {
  keyword?: string
  categoryIds?: string[]
  sortDirection?: UnfollowedSortDirection
  page?: number
  size?: number
}

export interface CategoryItem {
  id: string
  name: string
  slug: string
  thumbnailUrl: string | null
}

