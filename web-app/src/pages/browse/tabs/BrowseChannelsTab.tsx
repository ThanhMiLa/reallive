import { useState } from 'react'
import { isAxiosError } from 'axios'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertCircle, RefreshCw, LogIn, Users, UserPlus, UserMinus } from 'lucide-react'
import { toast } from 'sonner'
import { channelApi } from '@/api/channel.api'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse } from '@/types/auth.types'
import type { ChannelListItem } from '@/types/channel.types'

const BANNER_GRADIENTS = [
  'from-rose-950/60 via-[#141419] to-[#0a0a0c]',
  'from-purple-950/60 via-[#141419] to-[#0a0a0c]',
  'from-emerald-950/60 via-[#141419] to-[#0a0a0c]',
  'from-red-950/60 via-[#141419] to-[#0a0a0c]',
  'from-amber-950/60 via-[#141419] to-[#0a0a0c]',
]

function formatFollowers(count: number): string {
  if (count >= 1_000_000) {
    return `${(count / 1_000_000).toFixed(1).replace(/\.0$/, '')}M Followers`
  }
  if (count >= 1_000) {
    return `${(count / 1_000).toFixed(1).replace(/\.0$/, '')}K Followers`
  }
  return `${count} ${count === 1 ? 'Follower' : 'Followers'}`
}

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
  const [bannerError, setBannerError] = useState(false)

  const hasAvatar = Boolean(
    channel.avatarUrl && channel.avatarUrl.trim() !== '' && !avatarError,
  )
  const hasBanner = Boolean(
    channel.bannerUrl && channel.bannerUrl.trim() !== '' && !bannerError,
  )

  const gradientIndex =
    Math.abs(
      channel.name
        .split('')
        .reduce((acc, char) => acc + char.charCodeAt(0), 0),
    ) % BANNER_GRADIENTS.length

  return (
    <div className="group flex flex-col cursor-pointer transition-transform duration-200 hover:-translate-y-1">
      {/* Banner at top with rounded corners */}
      <div className="relative h-32 sm:h-36 w-full rounded-2xl overflow-hidden bg-[#101014] shadow-md group-hover:shadow-[0_0_24px_rgba(255,43,66,0.25)] transition-all duration-300">
        {hasBanner ? (
          <>
            <img
              src={channel.bannerUrl!}
              alt={`${channel.name} banner`}
              className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
              onError={() => setBannerError(true)}
            />
            <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-transparent to-transparent pointer-events-none" />
          </>
        ) : (
          <div
            className={`w-full h-full bg-gradient-to-r ${BANNER_GRADIENTS[gradientIndex]} relative`}
          >
            <div className="absolute inset-0 bg-[radial-gradient(#ffffff0a_1px,transparent_1px)] [background-size:12px_12px]" />
            <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-transparent to-transparent pointer-events-none" />
          </div>
        )}
      </div>

      {/* Card Content - transparent background matching page */}
      <div className="relative px-2 pt-0 pb-2 flex flex-col flex-1">
        {/* Avatar overlapping bottom of banner */}
        <div className="flex items-end -mt-8 mb-2">
          <div className="relative flex-shrink-0">
            <div className="w-16 h-16 rounded-full p-0.5 bg-[#000000] ring-4 ring-[#000000] shadow-lg overflow-hidden group-hover:ring-[#ff2b42]/40 transition-all">
              {hasAvatar ? (
                <img
                  src={channel.avatarUrl!}
                  alt={channel.name}
                  className="w-full h-full rounded-full object-cover"
                  onError={() => setAvatarError(true)}
                />
              ) : (
                <div className="w-full h-full rounded-full bg-gradient-to-tr from-rose-600 to-indigo-600 flex items-center justify-center text-lg font-bold text-white">
                  {channel.name ? channel.name.charAt(0).toUpperCase() : 'C'}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Channel Info & Action */}
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0 flex-1">
            <h3
              title={channel.name}
              className="text-white text-base sm:text-lg font-bold truncate group-hover:text-[#ff2b42] transition-colors"
            >
              {channel.name}
            </h3>
            <p className="text-sm font-medium text-gray-400">
              {formatFollowers(channel.numberOfFollower)}
            </p>
          </div>

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
      </div>
    </div>
  )
}

function ChannelSkeleton() {
  return (
    <div className="flex flex-col animate-pulse">
      <div className="h-32 sm:h-36 w-full rounded-2xl bg-[#101014]" />
      <div className="px-2 pt-0 pb-2 flex flex-col flex-1">
        <div className="-mt-8 mb-2 w-16 h-16 rounded-full bg-[#18181f] ring-4 ring-[#000000]" />
        <div className="h-4 w-32 bg-[#101014] rounded mb-2" />
        <div className="h-3.5 w-20 bg-[#101014] rounded" />
      </div>
    </div>
  )
}

export function BrowseChannelsTab() {
  const { isAuthenticated, isCheckingAuth, openAuthModal } = useAuthStore()
  const queryClient = useQueryClient()

  // Following channels query (following: true)
  const {
    data: followingPage,
    isLoading: isFollowingLoading,
    isError: isFollowingError,
    refetch: refetchFollowing,
    isFetching: isFollowingFetching,
  } = useQuery({
    queryKey: ['channels', 'following', 'browse'],
    queryFn: () => channelApi.getChannels({ following: true }),
    enabled: isAuthenticated,
  })

  // Recommended / Unfollowed channels query (following: false)
  const {
    data: recommendedPage,
    isLoading: isRecommendedLoading,
    isError: isRecommendedError,
    refetch: refetchRecommended,
    isFetching: isRecommendedFetching,
  } = useQuery({
    queryKey: ['channels', 'recommended', 'browse'],
    queryFn: () => channelApi.getChannels({ following: false }),
    enabled: isAuthenticated,
  })

  // Follow channel mutation
  const followMutation = useMutation({
    mutationFn: (channel: ChannelListItem) =>
      channelApi.followChannel(channel.id),
    onSuccess: (_, channel) => {
      toast.success(`Followed ${channel.name} successfully!`)
      queryClient.invalidateQueries({ queryKey: ['channels'] })
    },
    onError: (error: unknown) => {
      let message = 'Failed to follow channel'
      if (
        isAxiosError<ApiResponse<unknown>>(error) &&
        error.response?.data?.message
      ) {
        message = error.response.data.message
      }
      toast.error(message)
    },
  })

  // Unfollow channel mutation
  const unfollowMutation = useMutation({
    mutationFn: (channel: ChannelListItem) =>
      channelApi.unfollowChannel(channel.id),
    onSuccess: (_, channel) => {
      toast.success(`Unfollowed ${channel.name} successfully!`)
      queryClient.invalidateQueries({ queryKey: ['channels'] })
    },
    onError: (error: unknown) => {
      let message = 'Failed to unfollow channel'
      if (
        isAxiosError<ApiResponse<unknown>>(error) &&
        error.response?.data?.message
      ) {
        message = error.response.data.message
      }
      toast.error(message)
    },
  })

  const followingChannels = followingPage?.content || []
  const recommendedChannels = recommendedPage?.content || []

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

  return (
    <div className="pb-12 space-y-12">
      {/* Section 1: Following Channels */}
      <section>
        <div className="mb-6">
          <h2 className="text-xl sm:text-2xl font-bold text-white tracking-wide">
            Following
          </h2>
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
              Failed to load followed channels
            </h3>
            <p className="text-xs text-gray-400 mb-4 max-w-sm">
              Could not fetch followed channels from server. Please check your connection and try again.
            </p>
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
        )}
      </section>

      {/* Section 2: Recommended / Unfollowed Channels */}
      <section>
        <div className="mb-6">
          <h2 className="text-xl sm:text-2xl font-bold text-white tracking-wide">
            Recommended
          </h2>
        </div>

        {isRecommendedLoading ? (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
            {Array.from({ length: 8 }).map((_, idx) => (
              <ChannelSkeleton key={idx} />
            ))}
          </div>
        ) : isRecommendedError ? (
          <div className="flex flex-col items-center justify-center py-12 text-center border border-[#1f1f26] rounded-xl bg-[#08080a] px-4">
            <AlertCircle className="w-9 h-9 text-rose-500 mb-2.5" />
            <h3 className="text-sm font-semibold text-gray-200 mb-1">
              Failed to load recommended channels
            </h3>
            <p className="text-xs text-gray-400 mb-4 max-w-sm">
              Could not fetch recommended channels from server. Please check your connection and try again.
            </p>
            <button
              type="button"
              onClick={() => refetchRecommended()}
              disabled={isRecommendedFetching}
              className="flex items-center gap-2 bg-[#101014] hover:bg-[#18181f] text-[#ff2b42] border border-[#1f1f26] text-xs font-semibold px-4 py-2 rounded-lg transition-colors cursor-pointer"
            >
              <RefreshCw
                className={`w-3.5 h-3.5 ${isRecommendedFetching ? 'animate-spin' : ''}`}
              />
              Retry
            </button>
          </div>
        ) : recommendedChannels.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-center border border-[#1f1f26] rounded-xl bg-[#08080a] px-4">
            <Users className="w-9 h-9 text-gray-600 mb-2.5" />
            <h3 className="text-sm font-semibold text-gray-200 mb-1">
              No recommended channels
            </h3>
            <p className="text-xs text-gray-400">
              There are no additional channels available right now.
            </p>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5">
            {recommendedChannels.map((channel) => (
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
        )}
      </section>
    </div>
  )
}
