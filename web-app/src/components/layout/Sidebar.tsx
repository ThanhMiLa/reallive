import { useState } from 'react'
import { NavLink } from 'react-router-dom'
import { Home, Compass, Heart, Settings } from 'lucide-react'
import { useUiStore } from '@/stores/ui.store'
import { useAuthStore } from '@/stores/auth.store'
import { useQuery } from '@tanstack/react-query'
import { channelApi } from '@/api/channel.api'

function ChannelAvatar({
  name,
  avatarUrl,
}: {
  name: string
  avatarUrl: string | null
}) {
  const [imgError, setImgError] = useState(false)
  const hasAvatar = Boolean(avatarUrl && avatarUrl.trim() && !imgError)

  if (hasAvatar && avatarUrl) {
    return (
      <img
        alt={name}
        className="w-8 h-8 rounded-full object-cover border border-[#1f1f26] flex-shrink-0"
        src={avatarUrl}
        onError={() => setImgError(true)}
      />
    )
  }

  return (
    <div className="w-8 h-8 rounded-full bg-gradient-to-tr from-rose-600 to-amber-600 flex items-center justify-center text-xs font-bold text-white border border-[#1f1f26] flex-shrink-0">
      {name ? name.charAt(0).toUpperCase() : 'C'}
    </div>
  )
}

export function Sidebar() {
  const { isSidebarCollapsed } = useUiStore()
  const { user, isAuthenticated, isCheckingAuth, openAuthModal } = useAuthStore()
  const userId = user?.userId

  const {
    data: channelsPage,
    isLoading: isChannelsLoading,
    isError,
  } = useQuery({
    queryKey: [
      'channels',
      'following',
      userId,
      { sortBy: 'FOLLOWER_COUNT', sortDirection: 'DESC', page: 0, size: 20 },
    ],
    queryFn: ({ signal }) =>
      channelApi.getFollowingChannels(
        { sortBy: 'FOLLOWER_COUNT', sortDirection: 'DESC', page: 0, size: 20 },
        signal,
      ),
    enabled: isAuthenticated && !isCheckingAuth && Boolean(userId),
  })

  const followedChannels = channelsPage?.content || []
  const showLoading = isCheckingAuth || (isAuthenticated && isChannelsLoading)

  return (
    <aside
      className={`bg-[#08080a] border-r border-[#1f1f26] flex flex-col justify-between select-none flex-shrink-0 z-30 overflow-hidden transition-[width] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] will-change-[width] ${
        isSidebarCollapsed ? 'w-16 hidden sm:flex' : 'w-64 hidden lg:flex'
      }`}
    >
      <div className="overflow-y-auto overflow-x-hidden px-2.5 py-3 space-y-6">
        {/* Primary Navigation: Home, Browse, Following */}
        <nav className="space-y-1">
          {/* Active Tab: Home */}
          <NavLink
            to="/"
            end
            title="Home"
            className={({ isActive }) =>
              `relative flex h-10 items-center px-3.5 rounded-lg text-sm overflow-hidden whitespace-nowrap transition-colors duration-200 ${
                isActive
                  ? 'font-semibold bg-[#121216] text-[#ff2b42] shadow-sm'
                  : 'font-medium text-gray-400 hover:text-white hover:bg-[#121216]'
              }`
            }
          >
            {({ isActive }) => (
              <>
                <span
                  className={`absolute left-0 top-1.5 bottom-1.5 w-1 rounded-r-full bg-[#ff2b42] transition-opacity duration-200 ${
                    isActive ? 'opacity-100' : 'opacity-0'
                  }`}
                />
                <Home className="w-4 h-4 flex-shrink-0" />
                <span
                  className={`overflow-hidden whitespace-nowrap transition-[max-width,opacity,margin,transform] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
                    isSidebarCollapsed
                      ? 'ml-0 max-w-0 -translate-x-1 opacity-0'
                      : 'ml-3.5 max-w-[160px] translate-x-0 opacity-100'
                  }`}
                >
                  Home
                </span>
              </>
            )}
          </NavLink>

          {/* Browse */}
          <NavLink
            to="/browse"
            title="Browse"
            className={({ isActive }) =>
              `relative flex h-10 items-center px-3.5 rounded-lg text-sm overflow-hidden whitespace-nowrap transition-colors duration-200 ${
                isActive
                  ? 'font-semibold bg-[#121216] text-[#ff2b42] shadow-sm'
                  : 'font-medium text-gray-400 hover:text-white hover:bg-[#121216]'
              }`
            }
          >
            {({ isActive }) => (
              <>
                <span
                  className={`absolute left-0 top-1.5 bottom-1.5 w-1 rounded-r-full bg-[#ff2b42] transition-opacity duration-200 ${
                    isActive ? 'opacity-100' : 'opacity-0'
                  }`}
                />
                <Compass className="w-4 h-4 flex-shrink-0" />
                <span
                  className={`overflow-hidden whitespace-nowrap transition-[max-width,opacity,margin,transform] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
                    isSidebarCollapsed
                      ? 'ml-0 max-w-0 -translate-x-1 opacity-0'
                      : 'ml-3.5 max-w-[160px] translate-x-0 opacity-100'
                  }`}
                >
                  Browse
                </span>
              </>
            )}
          </NavLink>

          {/* Following */}
          <a
            href="#following"
            title="Following"
            className="relative flex h-10 items-center px-3.5 rounded-lg text-sm font-medium text-gray-400 hover:text-white hover:bg-[#121216] overflow-hidden whitespace-nowrap transition-colors duration-200"
          >
            <Heart className="w-4 h-4 flex-shrink-0" />
            <span
              className={`overflow-hidden whitespace-nowrap transition-[max-width,opacity,margin,transform] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
                isSidebarCollapsed
                  ? 'ml-0 max-w-0 -translate-x-1 opacity-0'
                  : 'ml-3.5 max-w-[160px] translate-x-0 opacity-100'
              }`}
            >
              Following
            </span>
          </a>
        </nav>

        {/* Channels Section 1: FOLLOWING */}
        <div>
          <div className="relative h-5 mb-2 flex items-center overflow-hidden">
            <span
              className={`absolute left-[9.5px] w-6 h-px bg-[#1f1f26] transition-opacity duration-300 ${
                isSidebarCollapsed ? 'opacity-100' : 'opacity-0'
              }`}
            />
            <span
              className={`px-3 text-[11px] font-bold text-gray-400 tracking-wider whitespace-nowrap transition-[opacity,transform] duration-200 ease-out ${
                isSidebarCollapsed
                  ? 'opacity-0 -translate-x-1 pointer-events-none'
                  : 'opacity-100 translate-x-0'
              }`}
            >
              FOLLOWING
            </span>
          </div>

          {showLoading ? (
            <div className="space-y-2 py-1">
              {[1, 2, 3].map((idx) => (
                <div
                  key={idx}
                  className="flex items-center px-[5.5px] py-1 overflow-hidden whitespace-nowrap"
                >
                  <div className="w-8 h-8 rounded-full bg-[#121216] animate-pulse flex-shrink-0" />
                  <div
                    className={`h-3.5 bg-[#121216] rounded animate-pulse transition-[width,opacity,margin] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
                      isSidebarCollapsed
                        ? 'ml-0 w-0 opacity-0'
                        : 'ml-3 w-24 opacity-100'
                    }`}
                  />
                </div>
              ))}
            </div>
          ) : !isAuthenticated ? (
            <div
              className={`px-3 text-xs text-gray-400 overflow-hidden whitespace-nowrap transition-[max-height,opacity,padding] duration-200 ease-out ${
                isSidebarCollapsed
                  ? 'max-h-0 py-0 opacity-0 pointer-events-none'
                  : 'max-h-24 py-2 opacity-100'
              }`}
            >
              <p className="text-[11px] text-gray-500 mb-1 leading-relaxed">
                Sign in to view followed channels
              </p>
              <button
                type="button"
                onClick={() => openAuthModal('login')}
                className="text-xs text-[#ff2b42] hover:underline font-medium cursor-pointer"
              >
                Log in
              </button>
            </div>
          ) : isError ? (
            <p
              className={`text-xs text-gray-500 px-3 overflow-hidden whitespace-nowrap transition-[max-height,opacity,padding] duration-200 ease-out ${
                isSidebarCollapsed
                  ? 'max-h-0 py-0 opacity-0 pointer-events-none'
                  : 'max-h-12 py-1.5 opacity-100'
              }`}
            >
              Failed to load channels
            </p>
          ) : followedChannels.length === 0 ? (
            <p
              className={`text-xs text-gray-500 px-3 overflow-hidden whitespace-nowrap transition-[max-height,opacity,padding] duration-200 ease-out ${
                isSidebarCollapsed
                  ? 'max-h-0 py-0 opacity-0 pointer-events-none'
                  : 'max-h-12 py-1.5 opacity-100'
              }`}
            >
              No followed channels yet
            </p>
          ) : (
            <div className="space-y-1">
              {followedChannels.map((channel) => (
                <div
                  key={channel.id}
                  title={channel.name}
                  className="flex items-center px-[5.5px] py-1.5 rounded-lg hover:bg-[#121216] transition-colors duration-200 group cursor-pointer overflow-hidden whitespace-nowrap"
                >
                  <div className="relative flex-shrink-0">
                    <ChannelAvatar
                      key={channel.avatarUrl || channel.id}
                      name={channel.name}
                      avatarUrl={channel.avatarUrl}
                    />
                  </div>
                  <div
                    className={`flex flex-col min-w-0 overflow-hidden whitespace-nowrap transition-[max-width,opacity,margin,transform] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
                      isSidebarCollapsed
                        ? 'ml-0 max-w-0 -translate-x-1 opacity-0'
                        : 'ml-3 max-w-[160px] translate-x-0 opacity-100'
                    }`}
                  >
                    <span className="text-xs font-semibold text-gray-200 truncate group-hover:text-white">
                      {channel.name}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Channels Section 2: RECOMMENDED */}
        <div>
          <div className="relative h-5 mb-2 flex items-center overflow-hidden">
            <span
              className={`absolute left-[9.5px] w-6 h-px bg-[#1f1f26] transition-opacity duration-300 ${
                isSidebarCollapsed ? 'opacity-100' : 'opacity-0'
              }`}
            />
            <span
              className={`px-3 text-[11px] font-bold text-gray-400 tracking-wider whitespace-nowrap transition-[opacity,transform] duration-200 ease-out ${
                isSidebarCollapsed
                  ? 'opacity-0 -translate-x-1 pointer-events-none'
                  : 'opacity-100 translate-x-0'
              }`}
            >
              RECOMMENDED
            </span>
          </div>
        </div>
      </div>

      {/* Left Sidebar Footer Status */}
      <div className="px-2.5 py-3 border-t border-[#1f1f26] bg-[#050507] flex items-center justify-between overflow-hidden whitespace-nowrap">
        <div className="flex items-center px-3.5 min-w-0">
          <span className="w-4 h-4 flex items-center justify-center flex-shrink-0">
            <span
              title="Live Network Online"
              className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-pulse"
            />
          </span>
          <span
            className={`text-xs font-medium text-gray-300 overflow-hidden whitespace-nowrap transition-[max-width,opacity,margin,transform] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
              isSidebarCollapsed
                ? 'ml-0 max-w-0 -translate-x-1 opacity-0'
                : 'ml-2.5 max-w-[150px] translate-x-0 opacity-100'
            }`}
          >
            Live Network Online
          </span>
        </div>
        <div
          className={`overflow-hidden transition-[max-width,opacity,margin] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
            isSidebarCollapsed
              ? 'max-w-0 opacity-0 mr-0 pointer-events-none'
              : 'max-w-6 opacity-100 mr-1'
          }`}
        >
          <Settings className="w-3.5 h-3.5 text-gray-400 hover:text-white cursor-pointer transition flex-shrink-0" />
        </div>
      </div>
    </aside>
  )
}
