import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AlertCircle, RefreshCw, LogIn, Users } from 'lucide-react'
import { channelApi } from '@/api/channel.api'
import { useAuthStore } from '@/stores/auth.store'
import type { ChannelListItem } from '@/types/channel.types'

const BANNER_GRADIENTS = [
  'from-cyan-950/60 via-[#1b2032] to-[#141824]',
  'from-purple-950/60 via-[#1b2032] to-[#141824]',
  'from-emerald-950/60 via-[#1b2032] to-[#141824]',
  'from-rose-950/60 via-[#1b2032] to-[#141824]',
  'from-amber-950/60 via-[#1b2032] to-[#141824]',
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

function ChannelCard({ channel }: { channel: ChannelListItem }) {
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
      <div className="relative h-32 sm:h-36 w-full rounded-2xl overflow-hidden bg-[#151822] shadow-md group-hover:shadow-[0_0_24px_rgba(0,240,255,0.2)] transition-all duration-300">
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
            <div className="w-16 h-16 rounded-full p-0.5 bg-[#08090d] ring-4 ring-[#08090d] shadow-lg overflow-hidden group-hover:ring-[#00f0ff]/40 transition-all">
              {hasAvatar ? (
                <img
                  src={channel.avatarUrl!}
                  alt={channel.name}
                  className="w-full h-full rounded-full object-cover"
                  onError={() => setAvatarError(true)}
                />
              ) : (
                <div className="w-full h-full rounded-full bg-gradient-to-tr from-cyan-600 to-indigo-600 flex items-center justify-center text-lg font-bold text-white">
                  {channel.name ? channel.name.charAt(0).toUpperCase() : 'C'}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Channel Name */}
        <div className="flex items-center gap-1.5 mb-1">
          <h3
            title={channel.name}
            className="text-white text-base sm:text-lg font-bold truncate group-hover:text-[#00f0ff] transition-colors"
          >
            {channel.name}
          </h3>
        </div>

        {/* Followers Count below Name */}
        <p className="text-sm font-medium text-gray-400">
          {formatFollowers(channel.numberOfFollower)}
        </p>
      </div>
    </div>
  )
}

export function BrowseChannelsTab() {
  const { isAuthenticated, isCheckingAuth, openAuthModal } = useAuthStore()

  const {
    data: channelsPage,
    isLoading,
    isError,
    refetch,
    isFetching,
  } = useQuery({
    queryKey: ['channels', 'following', 'browse'],
    queryFn: () => channelApi.getChannels({ following: true }),
    enabled: isAuthenticated,
  })

  const channels = channelsPage?.content || []
  const showLoading = isCheckingAuth || (isAuthenticated && isLoading)

  return (
    <div className="pb-12">
      {/* Section Title: Following */}
      <div className="mb-6">
        <h2 className="text-xl sm:text-2xl font-bold text-white tracking-wide">
          Following
        </h2>
      </div>

      {/* Loading Skeleton */}
      {showLoading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
          {Array.from({ length: 8 }).map((_, idx) => (
            <div key={idx} className="flex flex-col animate-pulse">
              <div className="h-32 sm:h-36 w-full rounded-2xl bg-[#151822]" />
              <div className="px-2 pt-0 pb-2 flex flex-col flex-1">
                <div className="-mt-8 mb-2 w-16 h-16 rounded-full bg-[#1b2030] ring-4 ring-[#08090d]" />
                <div className="h-4 w-32 bg-[#151822] rounded mb-2" />
                <div className="h-3.5 w-20 bg-[#151822] rounded" />
              </div>
            </div>
          ))}
        </div>
      ) : !isAuthenticated ? (
        /* Not Logged In State */
        <div className="flex flex-col items-center justify-center py-16 text-center border border-[#1f2639] rounded-xl bg-[#0e111b] px-4">
          <div className="w-12 h-12 rounded-full bg-cyan-500/10 flex items-center justify-center text-[#00f0ff] mb-4">
            <LogIn className="w-6 h-6" />
          </div>
          <h3 className="text-lg font-bold text-white mb-1.5">
            Sign in to view followed channels
          </h3>
          <p className="text-xs text-gray-400 mb-5 max-w-sm">
            Log in to view all the channels you are following, track their status, and support your favorite creators.
          </p>
          <button
            type="button"
            onClick={() => openAuthModal('login')}
            className="bg-[#00f0ff] hover:bg-[#00d0dd] text-black font-semibold text-xs px-5 py-2.5 rounded-lg transition-colors cursor-pointer shadow-md shadow-cyan-500/20"
          >
            Log In
          </button>
        </div>
      ) : isError ? (
        /* Error State */
        <div className="flex flex-col items-center justify-center py-16 text-center border border-[#1f2639] rounded-xl bg-[#0e111b] px-4">
          <AlertCircle className="w-10 h-10 text-rose-500 mb-3" />
          <h3 className="text-base font-semibold text-gray-200 mb-1">
            Failed to load channels
          </h3>
          <p className="text-xs text-gray-400 mb-4 max-w-sm">
            Could not fetch followed channels from server. Please check your connection and try again.
          </p>
          <button
            type="button"
            onClick={() => refetch()}
            disabled={isFetching}
            className="flex items-center gap-2 bg-[#1b2032] hover:bg-[#232a42] text-[#00f0ff] border border-[#1f2639] text-xs font-semibold px-4 py-2 rounded-lg transition-colors cursor-pointer"
          >
            <RefreshCw
              className={`w-3.5 h-3.5 ${isFetching ? 'animate-spin' : ''}`}
            />
            Retry
          </button>
        </div>
      ) : channels.length === 0 ? (
        /* Empty State */
        <div className="flex flex-col items-center justify-center py-16 text-center border border-[#1f2639] rounded-xl bg-[#0e111b] px-4">
          <Users className="w-10 h-10 text-gray-600 mb-3" />
          <h3 className="text-base font-semibold text-gray-200 mb-1">
            No followed channels yet
          </h3>
          <p className="text-xs text-gray-400">
            You haven't followed any channels yet. Explore categories and streams to find streamers you like!
          </p>
        </div>
      ) : (
        /* Channels Grid */
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5">
          {channels.map((channel) => (
            <ChannelCard key={channel.id} channel={channel} />
          ))}
        </div>
      )}
    </div>
  )
}
