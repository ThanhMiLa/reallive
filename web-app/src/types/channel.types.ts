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

export interface ChannelListParams {
  categoryIds?: string[]
  keyword?: string
  following?: boolean
  sortDirection?: 'ASC' | 'DESC'
  page?: number
  size?: number
}

export interface CategoryItem {
  id: string
  name: string
  slug: string
  thumbnailUrl: string | null
}

