export interface ChannelListItem {
  id: string
  name: string
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

export interface CategorySummary {
  id: string
  name: string
}

export interface OwnChannel {
  id: string
  streamerId: string
  name: string
  slug: string
  description: string | null
  avatarUrl: string | null
  bannerUrl: string | null
  status: 'ACTIVE' | 'SUSPENDED' | 'DELETED' | string
  categories: CategorySummary[]
  numberOfFollower: number
}

export interface ChannelResponse {
  id: string
  streamerId: string
  name: string
  slug: string
  description: string | null
  avatarUrl: string | null
  bannerUrl: string | null
  status: string
  categoryIds: string[]
}

export interface UpdateChannelPayload {
  description?: string
  banner?: File | null
  categoryIds?: string[]
}

