import { useState, useEffect, useRef } from 'react'
import { isAxiosError } from 'axios'
import {
  useQuery,
  useMutation,
  useQueryClient,
  keepPreviousData,
} from '@tanstack/react-query'
import {
  AlertCircle,
  RefreshCw,
  LogIn,
  Users,
  UserPlus,
  UserMinus,
  Search,
  X,
  SlidersHorizontal,
  RotateCcw,
  ChevronLeft,
  ChevronRight,
  Check,
} from 'lucide-react'
import { toast } from 'sonner'
import { channelApi } from '@/api/channel.api'
import { useAuthStore } from '@/stores/auth.store'
import { rawKeywordSchema } from '@/schemas/channel.schema'
import type { ApiResponse } from '@/types/auth.types'
import type {
  CategoryItem,
  ChannelListItem,
  FollowingSortBy,
  SortDirection,
} from '@/types/channel.types'



function formatFollowers(count: number): string {
  if (count >= 1_000_000) {
    return `${(count / 1_000_000).toFixed(1).replace(/\.0$/, '')}M Followers`
  }
  if (count >= 1_000) {
    return `${(count / 1_000).toFixed(1).replace(/\.0$/, '')}K Followers`
  }
  return `${count} ${count === 1 ? 'Follower' : 'Followers'}`
}

type FollowingSortOption =
  | 'FOLLOWER_DESC'
  | 'FOLLOWER_ASC'
  | 'FOLLOWED_DESC'
  | 'FOLLOWED_ASC'

const FOLLOWING_SORT_OPTIONS: {
  value: FollowingSortOption
  label: string
  sortBy: FollowingSortBy
  sortDirection: SortDirection
}[] = [
  {
    value: 'FOLLOWER_DESC',
    label: 'Most Followers',
    sortBy: 'FOLLOWER_COUNT',
    sortDirection: 'DESC',
  },
  {
    value: 'FOLLOWER_ASC',
    label: 'Fewest Followers',
    sortBy: 'FOLLOWER_COUNT',
    sortDirection: 'ASC',
  },
  {
    value: 'FOLLOWED_DESC',
    label: 'Recently Followed',
    sortBy: 'FOLLOWED_AT',
    sortDirection: 'DESC',
  },
  {
    value: 'FOLLOWED_ASC',
    label: 'Followed Longest',
    sortBy: 'FOLLOWED_AT',
    sortDirection: 'ASC',
  },
]

interface ChannelCardProps {
  channel: ChannelListItem
  actionType?: 'follow' | 'unfollow'
  onAction?: (channel: ChannelListItem) => void
  isActionLoading?: boolean
}

function ChannelCard({
  channel,
  actionType,
  onAction,
  isActionLoading = false,
}: ChannelCardProps) {
  const [avatarError, setAvatarError] = useState(false)

  const hasAvatar = Boolean(
    channel.avatarUrl && channel.avatarUrl.trim() !== '' && !avatarError,
  )

  return (
    <div className="group flex items-center justify-between gap-3 p-3.5 sm:p-4 rounded-xl bg-[#101014] border border-[#1f1f26] hover:border-[#ff2b42]/40 hover:bg-[#14141a] transition-all duration-200 cursor-pointer shadow-sm">
      <div className="flex items-center gap-3.5 min-w-0 flex-1">
        {/* Avatar */}
        <div className="relative flex-shrink-0">
          <div className="w-12 h-12 rounded-full p-0.5 bg-[#000000] ring-2 ring-[#1f1f26] shadow overflow-hidden group-hover:ring-[#ff2b42]/50 transition-all">
            {hasAvatar ? (
              <img
                src={channel.avatarUrl!}
                alt={channel.name}
                className="w-full h-full rounded-full object-cover"
                onError={() => setAvatarError(true)}
              />
            ) : (
              <div className="w-full h-full rounded-full bg-gradient-to-tr from-rose-600 to-indigo-600 flex items-center justify-center text-base font-bold text-white">
                {channel.name ? channel.name.charAt(0).toUpperCase() : 'C'}
              </div>
            )}
          </div>
        </div>

        {/* Channel Info beside Avatar */}
        <div className="min-w-0 flex-1">
          <h3
            title={channel.name}
            className="text-white text-sm sm:text-base font-bold truncate group-hover:text-[#ff2b42] transition-colors"
          >
            {channel.name}
          </h3>
          <p className="text-xs font-medium text-gray-400">
            {formatFollowers(channel.numberOfFollower)}
          </p>
        </div>
      </div>

      {/* Channel Follow / Unfollow Action */}
      {actionType === 'follow' && (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation()
            onAction?.(channel)
          }}
          disabled={isActionLoading}
          className="flex-shrink-0 flex items-center gap-1.5 bg-[#ff2b42] hover:bg-[#e02237] active:scale-95 text-white font-semibold text-xs px-3.5 py-1.5 rounded-lg transition-all shadow-md shadow-red-500/25 disabled:opacity-60 disabled:cursor-not-allowed cursor-pointer"
        >
          {isActionLoading ? (
            <RefreshCw className="w-3.5 h-3.5 animate-spin" />
          ) : (
            <UserPlus className="w-3.5 h-3.5" />
          )}
          <span>Follow</span>
        </button>
      )}

      {actionType === 'unfollow' && (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation()
            onAction?.(channel)
          }}
          disabled={isActionLoading}
          className="flex-shrink-0 flex items-center gap-1.5 bg-[#101014] hover:bg-rose-500/15 hover:text-rose-400 hover:border-rose-500/40 text-gray-300 border border-[#1f1f26] font-semibold text-xs px-3.5 py-1.5 rounded-lg transition-all shadow-sm active:scale-95 disabled:opacity-60 disabled:cursor-not-allowed cursor-pointer"
        >
          {isActionLoading ? (
            <RefreshCw className="w-3.5 h-3.5 animate-spin" />
          ) : (
            <UserMinus className="w-3.5 h-3.5" />
          )}
          <span>Unfollow</span>
        </button>
      )}
    </div>
  )
}

function ChannelSkeleton() {
  return (
    <div className="flex items-center justify-between gap-3 p-3.5 sm:p-4 rounded-xl bg-[#101014] border border-[#1f1f26] animate-pulse">
      <div className="flex items-center gap-3.5 min-w-0 flex-1">
        <div className="w-12 h-12 rounded-full bg-[#18181f] flex-shrink-0" />
        <div className="space-y-2 flex-1 min-w-0">
          <div className="h-4 w-28 sm:w-36 bg-[#18181f] rounded" />
          <div className="h-3 w-20 bg-[#18181f] rounded" />
        </div>
      </div>
      <div className="w-16 h-7 bg-[#18181f] rounded-lg flex-shrink-0" />
    </div>
  )
}

interface PaginationProps {
  currentPage: number // 0-based
  totalPages: number
  totalElements: number
  pageSize: number
  onPageChange: (newPage: number) => void
  disabled?: boolean
}

function Pagination({
  currentPage,
  totalPages,
  totalElements,
  pageSize,
  onPageChange,
  disabled = false,
}: PaginationProps) {
  if (totalPages <= 1) return null

  const startItem = currentPage * pageSize + 1
  const endItem = Math.min((currentPage + 1) * pageSize, totalElements)

  return (
    <div className="flex flex-col sm:flex-row items-center justify-between gap-3 pt-6 border-t border-[#1f1f26]/60 text-xs text-gray-400">
      <div>
        Showing <span className="font-semibold text-gray-200">{startItem}</span> to{' '}
        <span className="font-semibold text-gray-200">{endItem}</span> of{' '}
        <span className="font-semibold text-gray-200">{totalElements}</span> channels
      </div>

      <div className="flex items-center gap-1.5">
        <button
          type="button"
          onClick={() => onPageChange(currentPage - 1)}
          disabled={currentPage <= 0 || disabled}
          className="flex items-center gap-1 px-3 py-1.5 rounded-lg bg-[#101014] border border-[#1f1f26] text-gray-300 hover:text-white hover:bg-[#18181f] disabled:opacity-40 disabled:cursor-not-allowed transition cursor-pointer"
        >
          <ChevronLeft className="w-3.5 h-3.5" />
          <span>Previous</span>
        </button>

        <span className="px-3 py-1.5 font-medium text-gray-300">
          Page {currentPage + 1} of {totalPages}
        </span>

        <button
          type="button"
          onClick={() => onPageChange(currentPage + 1)}
          disabled={currentPage >= totalPages - 1 || disabled}
          className="flex items-center gap-1 px-3 py-1.5 rounded-lg bg-[#101014] border border-[#1f1f26] text-gray-300 hover:text-white hover:bg-[#18181f] disabled:opacity-40 disabled:cursor-not-allowed transition cursor-pointer"
        >
          <span>Next</span>
          <ChevronRight className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  )
}

function parseHttpError(error: unknown): {
  title: string
  description: string
  canRetry: boolean
} {
  if (isAxiosError<ApiResponse<unknown>>(error)) {
    const status = error.response?.status
    const serverMessage = error.response?.data?.message

    if (status === 400) {
      return {
        title: 'Invalid Request',
        description:
          serverMessage ||
          'The request parameters or filter values are invalid. Please check your filters.',
        canRetry: false,
      }
    }
    if (status === 401) {
      return {
        title: 'Authentication Required',
        description:
          serverMessage || 'Your session has expired. Please log in again.',
        canRetry: false,
      }
    }
    if (status === 403) {
      return {
        title: 'Access Denied',
        description:
          serverMessage || 'You do not have permission to view this channel list.',
        canRetry: false,
      }
    }
    if (status === 404) {
      return {
        title: 'Channels Not Found',
        description:
          serverMessage || 'The requested channel data was not found on the server.',
        canRetry: true,
      }
    }
    if (status === 503) {
      return {
        title: 'Service Temporarily Unavailable',
        description:
          serverMessage ||
          'The channel service is temporarily down. Please try again shortly.',
        canRetry: true,
      }
    }
    if (serverMessage) {
      return {
        title: 'Failed to load channels',
        description: serverMessage,
        canRetry: true,
      }
    }
  }

  return {
    title: 'Failed to load channels',
    description:
      'Could not fetch channels from server. Please check your connection and try again.',
    canRetry: true,
  }
}

export function BrowseChannelsTab() {
  const { user, isAuthenticated, isCheckingAuth, openAuthModal } = useAuthStore()
  const userId = user?.userId
  const queryClient = useQueryClient()

  // --- Following List State ---
  const [followingSortOption, setFollowingSortOption] =
    useState<FollowingSortOption>('FOLLOWER_DESC')
  const [followingPage, setFollowingPage] = useState(0)
  const followingPageSize = 20

  // --- Unfollowed List State ---
  const [keywordInput, setKeywordInput] = useState('')
  const [debouncedKeyword, setDebouncedKeyword] = useState('')
  const [selectedCategoryIds, setSelectedCategoryIds] = useState<string[]>([])
  const [unfollowedPage, setUnfollowedPage] = useState(0)
  const unfollowedPageSize = 20

  // Category dropdown UI state
  const [isCategoryDropdownOpen, setIsCategoryDropdownOpen] = useState(false)
  const [categorySearchQuery, setCategorySearchQuery] = useState('')
  const categoryDropdownRef = useRef<HTMLDivElement>(null)

  // Validate raw keyword length (max 255 chars before trim)
  const keywordValidation = rawKeywordSchema.safeParse(keywordInput)
  const isKeywordValid = keywordValidation.success
  const keywordError = isKeywordValid
    ? null
    : keywordValidation.error.issues[0]?.message

  // Debounce keyword input by ~300ms
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedKeyword(keywordInput)
      setUnfollowedPage(0)
    }, 300)
    return () => clearTimeout(timer)
  }, [keywordInput])

  // Close category dropdown on outside click
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (
        categoryDropdownRef.current &&
        !categoryDropdownRef.current.contains(event.target as Node)
      ) {
        setIsCategoryDropdownOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  // Fetch categories for multi-select
  const { data: categories = [] } = useQuery({
    queryKey: ['categories'],
    queryFn: ({ signal }) => channelApi.getCategories(signal),
  })

  // Selected Following Sort configuration
  const currentFollowingSortConfig =
    FOLLOWING_SORT_OPTIONS.find((opt) => opt.value === followingSortOption) ??
    FOLLOWING_SORT_OPTIONS[0]

  // Query 1: Following Channels (GET /channel/api/channels/following)
  const {
    data: followingPageData,
    isLoading: isFollowingLoading,
    isError: isFollowingError,
    error: followingError,
    refetch: refetchFollowing,
    isFetching: isFollowingFetching,
  } = useQuery({
    queryKey: [
      'channels',
      'following',
      userId,
      {
        sortBy: currentFollowingSortConfig.sortBy,
        sortDirection: currentFollowingSortConfig.sortDirection,
        page: followingPage,
        size: followingPageSize,
      },
    ],
    queryFn: ({ signal }) =>
      channelApi.getFollowingChannels(
        {
          sortBy: currentFollowingSortConfig.sortBy,
          sortDirection: currentFollowingSortConfig.sortDirection,
          page: followingPage,
          size: followingPageSize,
        },
        signal,
      ),
    enabled: isAuthenticated && !isCheckingAuth && Boolean(userId),
    placeholderData: keepPreviousData,
  })

  // Normalize keyword & validate category UUIDs for Unfollowed query
  const normalizedKeyword =
    debouncedKeyword.trim().length > 0 ? debouncedKeyword.trim() : undefined
  const validCategoryIds = Array.from(
    new Set(
      selectedCategoryIds.filter((id) =>
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
          id,
        ),
      ),
    ),
  )

  // Query 2: Unfollowed Channels (GET /channel/api/channels)
  const {
    data: unfollowedPageData,
    isLoading: isUnfollowedLoading,
    isError: isUnfollowedError,
    error: unfollowedError,
    refetch: refetchUnfollowed,
    isFetching: isUnfollowedFetching,
  } = useQuery({
    queryKey: [
      'channels',
      'unfollowed',
      userId,
      {
        keyword: normalizedKeyword ?? '',
        categoryIds: validCategoryIds,
        sortDirection: 'DESC',
        page: unfollowedPage,
        size: unfollowedPageSize,
      },
    ],
    queryFn: ({ signal }) =>
      channelApi.getUnfollowedChannels(
        {
          keyword: normalizedKeyword,
          categoryIds:
            validCategoryIds.length > 0 ? validCategoryIds : undefined,
          sortDirection: 'DESC',
          page: unfollowedPage,
          size: unfollowedPageSize,
        },
        signal,
      ),
    enabled:
      isAuthenticated && !isCheckingAuth && Boolean(userId) && isKeywordValid,
    placeholderData: keepPreviousData,
  })

  // Adjust page if after follow/unfollow the current page exceeds valid totalPages
  if (followingPageData) {
    if (followingPageData.totalPages > 0 && followingPage >= followingPageData.totalPages) {
      setFollowingPage(followingPageData.totalPages - 1)
    } else if (followingPageData.totalPages === 0 && followingPage !== 0) {
      setFollowingPage(0)
    }
  }

  if (unfollowedPageData) {
    if (unfollowedPageData.totalPages > 0 && unfollowedPage >= unfollowedPageData.totalPages) {
      setUnfollowedPage(unfollowedPageData.totalPages - 1)
    } else if (unfollowedPageData.totalPages === 0 && unfollowedPage !== 0) {
      setUnfollowedPage(0)
    }
  }

  // Invalidate queries for current user following / unfollowed / sidebar
  const invalidateUserChannelQueries = () => {
    if (userId) {
      queryClient.invalidateQueries({
        queryKey: ['channels', 'following', userId],
      })
      queryClient.invalidateQueries({
        queryKey: ['channels', 'unfollowed', userId],
      })
    } else {
      queryClient.invalidateQueries({ queryKey: ['channels'] })
    }
  }

  // Follow mutation (PUT /channel/api/channels/{channelId}/follow)
  const followMutation = useMutation({
    mutationFn: (channel: ChannelListItem) =>
      channelApi.followChannel(channel.id),
    onSuccess: (_, channel) => {
      toast.success(`Followed ${channel.name} successfully!`)
      invalidateUserChannelQueries()
    },
    onError: (error: unknown, channel) => {
      if (isAxiosError<ApiResponse<unknown>>(error)) {
        const status = error.response?.status
        const message = error.response?.data?.message
        if (status === 409) {
          toast.error(message || 'Channel follow status has already changed.')
          invalidateUserChannelQueries()
          return
        }
        if (message) {
          toast.error(message)
          return
        }
      }
      toast.error(`Failed to follow ${channel.name}`)
    },
  })

  // Unfollow mutation (DELETE /channel/api/channels/{channelId}/follow)
  const unfollowMutation = useMutation({
    mutationFn: (channel: ChannelListItem) =>
      channelApi.unfollowChannel(channel.id),
    onSuccess: (_, channel) => {
      toast.success(`Unfollowed ${channel.name} successfully!`)
      invalidateUserChannelQueries()
    },
    onError: (error: unknown, channel) => {
      if (isAxiosError<ApiResponse<unknown>>(error)) {
        const status = error.response?.status
        const message = error.response?.data?.message
        if (status === 409) {
          toast.error(message || 'Channel follow status has already changed.')
          invalidateUserChannelQueries()
          return
        }
        if (message) {
          toast.error(message)
          return
        }
      }
      toast.error(`Failed to unfollow ${channel.name}`)
    },
  })

  // Handlers for category filter
  const handleToggleCategory = (categoryId: string) => {
    setSelectedCategoryIds((prev) =>
      prev.includes(categoryId)
        ? prev.filter((id) => id !== categoryId)
        : [...prev, categoryId],
    )
    setUnfollowedPage(0)
  }

  const handleClearCategories = () => {
    setSelectedCategoryIds([])
    setUnfollowedPage(0)
  }

  const handleClearKeyword = () => {
    setKeywordInput('')
    setDebouncedKeyword('')
    setUnfollowedPage(0)
  }

  const handleResetAllUnfollowedFilters = () => {
    setKeywordInput('')
    setDebouncedKeyword('')
    setSelectedCategoryIds([])
    setUnfollowedPage(0)
  }

  const followingChannels = followingPageData?.content || []
  const unfollowedChannels = unfollowedPageData?.content || []
  const hasActiveFilters = Boolean(
    normalizedKeyword || selectedCategoryIds.length > 0,
  )

  // Filtered categories in dropdown search
  const visibleCategories = categories.filter((cat) =>
    cat.name.toLowerCase().includes(categorySearchQuery.trim().toLowerCase()),
  )

  // --- Auth Checks ---
  if (isCheckingAuth) {
    return (
      <div className="pb-12 space-y-12">
        <div>
          <div className="mb-6">
            <h2 className="text-xl sm:text-2xl font-bold text-white tracking-wide">
              Following
            </h2>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
            {Array.from({ length: 4 }).map((_, idx) => (
              <ChannelSkeleton key={idx} />
            ))}
          </div>
        </div>
        <div>
          <div className="mb-6">
            <h2 className="text-xl sm:text-2xl font-bold text-white tracking-wide">
              Recommended
            </h2>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
            {Array.from({ length: 8 }).map((_, idx) => (
              <ChannelSkeleton key={idx} />
            ))}
          </div>
        </div>
      </div>
    )
  }

  if (!isAuthenticated) {
    return (
      <div className="pb-12">
        <div className="flex flex-col items-center justify-center py-16 text-center border border-[#1f1f26] rounded-xl bg-[#08080a] px-4">
          <div className="w-12 h-12 rounded-full bg-rose-500/10 flex items-center justify-center text-[#ff2b42] mb-4">
            <LogIn className="w-6 h-6" />
          </div>
          <h3 className="text-lg font-bold text-white mb-1.5">
            Sign in to view channels
          </h3>
          <p className="text-xs text-gray-400 mb-5 max-w-sm">
            Log in to view channels you are following and discover recommended creators.
          </p>
          <button
            type="button"
            onClick={() => openAuthModal('login')}
            className="bg-[#ff2b42] hover:bg-[#e02237] text-white font-semibold text-xs px-5 py-2.5 rounded-lg transition-colors cursor-pointer shadow-md shadow-rose-950/40"
          >
            Log In
          </button>
        </div>
      </div>
    )
  }

  const followingErrorDetails = followingError
    ? parseHttpError(followingError)
    : null
  const unfollowedErrorDetails = unfollowedError
    ? parseHttpError(unfollowedError)
    : null

  return (
    <div className="pb-12 space-y-12">
      {/* =========================================================================
          SECTION 1: Following Channels
          ========================================================================= */}
      <section>
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
          <div className="flex items-center gap-3">
            <h2 className="text-xl sm:text-2xl font-bold text-white tracking-wide">
              Following
            </h2>
            {isFollowingFetching && !isFollowingLoading && (
              <span title="Updating...">
                <RefreshCw className="w-3.5 h-3.5 animate-spin text-gray-400" />
              </span>
            )}
          </div>

          {/* Business sort dropdown (Mutually exclusive criteria) */}
          <div className="flex items-center gap-2.5">
            <label
              htmlFor="following-sort-select"
              className="text-xs font-medium text-gray-400 select-none"
            >
              Sort by:
            </label>
            <select
              id="following-sort-select"
              value={followingSortOption}
              onChange={(e) => {
                setFollowingSortOption(e.target.value as FollowingSortOption)
                setFollowingPage(0)
              }}
              className="bg-[#101014] text-gray-200 border border-[#1f1f26] hover:border-gray-700 rounded-lg px-3 py-1.5 text-xs font-medium focus:border-[#ff2b42] focus:outline-none transition-colors cursor-pointer"
            >
              {FOLLOWING_SORT_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value} className="bg-[#101014] text-gray-200">
                  {opt.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        {isFollowingLoading ? (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
            {Array.from({ length: 4 }).map((_, idx) => (
              <ChannelSkeleton key={idx} />
            ))}
          </div>
        ) : isFollowingError ? (
          <div className="flex flex-col items-center justify-center py-12 text-center border border-[#1f1f26] rounded-xl bg-[#08080a] px-4">
            <AlertCircle className="w-9 h-9 text-rose-500 mb-2.5" />
            <h3 className="text-sm font-semibold text-gray-200 mb-1">
              {followingErrorDetails?.title || 'Failed to load followed channels'}
            </h3>
            <p className="text-xs text-gray-400 mb-4 max-w-sm">
              {followingErrorDetails?.description ||
                'Could not fetch followed channels from server. Please try again.'}
            </p>
            {followingErrorDetails?.canRetry && (
              <button
                type="button"
                onClick={() => refetchFollowing()}
                disabled={isFollowingFetching}
                className="flex items-center gap-2 bg-[#101014] hover:bg-[#18181f] text-[#ff2b42] border border-[#1f1f26] text-xs font-semibold px-4 py-2 rounded-lg transition-colors cursor-pointer"
              >
                <RefreshCw
                  className={`w-3.5 h-3.5 ${isFollowingFetching ? 'animate-spin' : ''}`}
                />
                Retry
              </button>
            )}
          </div>
        ) : followingChannels.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-center border border-[#1f1f26] rounded-xl bg-[#08080a] px-4">
            <Users className="w-9 h-9 text-gray-600 mb-2.5" />
            <h3 className="text-sm font-semibold text-gray-200 mb-1">
              No followed channels yet
            </h3>
            <p className="text-xs text-gray-400">
              You haven't followed any channels yet. Check out recommended channels below!
            </p>
          </div>
        ) : (
          <>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5">
              {followingChannels.map((channel) => (
                <ChannelCard
                  key={channel.id}
                  channel={channel}
                  actionType="unfollow"
                  onAction={(c) => unfollowMutation.mutate(c)}
                  isActionLoading={
                    unfollowMutation.isPending &&
                    unfollowMutation.variables?.id === channel.id
                  }
                />
              ))}
            </div>

            {followingPageData && (
              <Pagination
                currentPage={followingPage}
                totalPages={followingPageData.totalPages}
                totalElements={followingPageData.totalElements}
                pageSize={followingPageSize}
                onPageChange={(newPage) => setFollowingPage(newPage)}
                disabled={isFollowingFetching}
              />
            )}
          </>
        )}
      </section>

      {/* =========================================================================
          SECTION 2: Unfollowed / Recommended Channels
          ========================================================================= */}
      <section>
        <div className="flex items-center justify-between gap-4 mb-4">
          <div className="flex items-center gap-3">
            <h2 className="text-xl sm:text-2xl font-bold text-white tracking-wide">
              Recommended
            </h2>
            {isUnfollowedFetching && !isUnfollowedLoading && (
              <span title="Updating...">
                <RefreshCw className="w-3.5 h-3.5 animate-spin text-gray-400" />
              </span>
            )}
          </div>
        </div>

        {/* Filter Controls Bar (Search keyword + Multi-select Category) */}
        <div className="space-y-3 mb-6">
          <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-3">
            {/* Search Keyword Input (compact width) */}
            <div className="relative w-full sm:w-72 md:w-80">
              <div className="absolute inset-y-0 left-3 flex items-center pointer-events-none text-gray-400">
                <Search className="w-4 h-4" />
              </div>
              <input
                type="text"
                value={keywordInput}
                onChange={(e) => setKeywordInput(e.target.value)}
                placeholder="Search channels..."
                className={`w-full bg-[#101014] text-gray-200 placeholder-gray-500 text-xs sm:text-sm pl-9 pr-9 py-2.5 rounded-lg border transition-colors focus:outline-none ${
                  keywordError
                    ? 'border-red-500 focus:border-red-500'
                    : 'border-[#1f1f26] focus:border-[#ff2b42]'
                }`}
              />
              {keywordInput.length > 0 && (
                <button
                  type="button"
                  onClick={handleClearKeyword}
                  aria-label="Clear search"
                  className="absolute inset-y-0 right-2.5 flex items-center text-gray-400 hover:text-gray-200 transition cursor-pointer"
                >
                  <X className="w-4 h-4" />
                </button>
              )}
            </div>

            {/* Category Multi-Select Dropdown Trigger */}
            <div className="relative" ref={categoryDropdownRef}>
              <button
                type="button"
                onClick={() => setIsCategoryDropdownOpen((prev) => !prev)}
                className={`flex items-center justify-between sm:justify-start gap-2 bg-[#101014] border text-xs sm:text-sm font-medium px-3.5 py-2.5 rounded-lg transition-colors cursor-pointer w-full sm:w-auto ${
                  selectedCategoryIds.length > 0
                    ? 'border-[#ff2b42]/60 text-white'
                    : 'border-[#1f1f26] text-gray-300 hover:border-gray-700'
                }`}
              >
                <div className="flex items-center gap-2">
                  <SlidersHorizontal className="w-3.5 h-3.5 text-gray-400" />
                  <span>
                    Categories
                    {selectedCategoryIds.length > 0
                      ? ` (${selectedCategoryIds.length})`
                      : ''}
                  </span>
                </div>
              </button>

              {/* Category Popover Menu */}
              {isCategoryDropdownOpen && (
                <div className="absolute left-0 mt-2 w-72 max-w-[calc(100vw-2rem)] bg-[#0c0c10] border border-[#1f1f26] rounded-xl shadow-2xl z-50 overflow-hidden">
                  <div className="p-2.5 border-b border-[#1f1f26] space-y-2">
                    <div className="flex items-center justify-between text-xs">
                      <span className="font-semibold text-gray-200">
                        Filter by Categories
                      </span>
                      {selectedCategoryIds.length > 0 && (
                        <button
                          type="button"
                          onClick={handleClearCategories}
                          className="text-[#ff2b42] hover:underline font-medium text-[11px] cursor-pointer"
                        >
                          Clear
                        </button>
                      )}
                    </div>
                    <input
                      type="text"
                      value={categorySearchQuery}
                      onChange={(e) => setCategorySearchQuery(e.target.value)}
                      placeholder="Filter categories..."
                      className="w-full bg-[#14141a] text-xs text-white placeholder-gray-500 px-2.5 py-1.5 rounded-lg border border-[#1f1f26] focus:border-[#ff2b42] focus:outline-none"
                    />
                  </div>

                  <div className="max-h-60 overflow-y-auto p-1.5 space-y-0.5">
                    {visibleCategories.length === 0 ? (
                      <div className="py-4 text-center text-xs text-gray-500">
                        No categories found
                      </div>
                    ) : (
                      visibleCategories.map((category: CategoryItem) => {
                        const isSelected = selectedCategoryIds.includes(category.id)
                        return (
                          <button
                            type="button"
                            key={category.id}
                            onClick={() => handleToggleCategory(category.id)}
                            className={`w-full flex items-center justify-between px-2.5 py-1.5 rounded-lg text-xs transition cursor-pointer text-left ${
                              isSelected
                                ? 'bg-rose-500/15 text-rose-300 font-semibold'
                                : 'text-gray-300 hover:bg-[#14141a]'
                            }`}
                          >
                            <span className="truncate">{category.name}</span>
                            {isSelected && (
                              <Check className="w-3.5 h-3.5 text-[#ff2b42] flex-shrink-0" />
                            )}
                          </button>
                        )
                      })
                    )}
                  </div>
                </div>
              )}
            </div>

            {/* Reset All Filters Button */}
            {hasActiveFilters && (
              <button
                type="button"
                onClick={handleResetAllUnfollowedFilters}
                className="flex items-center justify-center gap-1.5 bg-[#101014] hover:bg-[#18181f] text-gray-400 hover:text-white border border-[#1f1f26] text-xs font-semibold px-3 py-2.5 rounded-lg transition-colors cursor-pointer"
                title="Reset all filters"
              >
                <RotateCcw className="w-3.5 h-3.5" />
                <span>Reset</span>
              </button>
            )}
          </div>

          {/* Keyword validation error message */}
          {keywordError && (
            <p className="text-xs text-red-400 font-medium">{keywordError}</p>
          )}

          {/* Active Filter Chips Bar */}
          {hasActiveFilters && (
            <div className="flex flex-wrap items-center gap-2 pt-1">
              {normalizedKeyword && (
                <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-rose-500/10 border border-rose-500/30 text-rose-300 text-xs">
                  <span>Keyword: "{normalizedKeyword}"</span>
                  <button
                    type="button"
                    onClick={handleClearKeyword}
                    className="hover:text-white transition cursor-pointer"
                    aria-label="Remove keyword filter"
                  >
                    <X className="w-3 h-3" />
                  </button>
                </span>
              )}

              {selectedCategoryIds.map((catId) => {
                const categoryObj = categories.find((c) => c.id === catId)
                const name = categoryObj ? categoryObj.name : catId
                return (
                  <span
                    key={catId}
                    className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-[#18181f] border border-[#2a2a35] text-gray-200 text-xs"
                  >
                    <span>{name}</span>
                    <button
                      type="button"
                      onClick={() => handleToggleCategory(catId)}
                      className="hover:text-white transition cursor-pointer"
                      aria-label={`Remove ${name} filter`}
                    >
                      <X className="w-3 h-3" />
                    </button>
                  </span>
                )
              })}
            </div>
          )}
        </div>

        {isUnfollowedLoading ? (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
            {Array.from({ length: 8 }).map((_, idx) => (
              <ChannelSkeleton key={idx} />
            ))}
          </div>
        ) : isUnfollowedError ? (
          <div className="flex flex-col items-center justify-center py-12 text-center border border-[#1f1f26] rounded-xl bg-[#08080a] px-4">
            <AlertCircle className="w-9 h-9 text-rose-500 mb-2.5" />
            <h3 className="text-sm font-semibold text-gray-200 mb-1">
              {unfollowedErrorDetails?.title || 'Failed to load recommended channels'}
            </h3>
            <p className="text-xs text-gray-400 mb-4 max-w-sm">
              {unfollowedErrorDetails?.description ||
                'Could not fetch recommended channels from server. Please try again.'}
            </p>
            {unfollowedErrorDetails?.canRetry && (
              <button
                type="button"
                onClick={() => refetchUnfollowed()}
                disabled={isUnfollowedFetching}
                className="flex items-center gap-2 bg-[#101014] hover:bg-[#18181f] text-[#ff2b42] border border-[#1f1f26] text-xs font-semibold px-4 py-2 rounded-lg transition-colors cursor-pointer"
              >
                <RefreshCw
                  className={`w-3.5 h-3.5 ${isUnfollowedFetching ? 'animate-spin' : ''}`}
                />
                Retry
              </button>
            )}
          </div>
        ) : unfollowedChannels.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-center border border-[#1f1f26] rounded-xl bg-[#08080a] px-4">
            {hasActiveFilters ? (
              <>
                <Search className="w-9 h-9 text-gray-600 mb-2.5" />
                <h3 className="text-sm font-semibold text-gray-200 mb-1">
                  No channels match your filters
                </h3>
                <p className="text-xs text-gray-400 mb-4 max-w-sm">
                  We couldn't find any unfollowed channels matching your search keyword or selected categories.
                </p>
                <button
                  type="button"
                  onClick={handleResetAllUnfollowedFilters}
                  className="flex items-center gap-1.5 bg-[#101014] hover:bg-[#18181f] text-gray-200 border border-[#1f1f26] text-xs font-semibold px-4 py-2 rounded-lg transition-colors cursor-pointer"
                >
                  <RotateCcw className="w-3.5 h-3.5" />
                  <span>Reset All Filters</span>
                </button>
              </>
            ) : (
              <>
                <Users className="w-9 h-9 text-gray-600 mb-2.5" />
                <h3 className="text-sm font-semibold text-gray-200 mb-1">
                  No recommended channels
                </h3>
                <p className="text-xs text-gray-400">
                  There are no additional channels available right now.
                </p>
              </>
            )}
          </div>
        ) : (
          <>
            {/* Channel cards rendered in exact server order */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5">
              {unfollowedChannels.map((channel) => (
                <ChannelCard
                  key={channel.id}
                  channel={channel}
                  actionType="follow"
                  onAction={(c) => followMutation.mutate(c)}
                  isActionLoading={
                    followMutation.isPending &&
                    followMutation.variables?.id === channel.id
                  }
                />
              ))}
            </div>

            {unfollowedPageData && (
              <Pagination
                currentPage={unfollowedPage}
                totalPages={unfollowedPageData.totalPages}
                totalElements={unfollowedPageData.totalElements}
                pageSize={unfollowedPageSize}
                onPageChange={(newPage) => setUnfollowedPage(newPage)}
                disabled={isUnfollowedFetching}
              />
            )}
          </>
        )}
      </section>
    </div>
  )
}
