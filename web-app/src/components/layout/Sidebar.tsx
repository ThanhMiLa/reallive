import { useState } from 'react'
import { NavLink } from 'react-router-dom'
import { Home, Compass, Heart, Settings } from 'lucide-react'
import { useUiStore } from '@/stores/ui.store'
import { useAuthStore } from '@/stores/auth.store'
import { useQuery } from '@tanstack/react-query'
import { channelApi } from '@/api/channel.api'

interface StreamerItem {
  id: string
  name: string
  category: string
  avatar: string
  isLive: boolean
}

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
        className="w-8 h-8 rounded-full object-cover border border-[#232838]"
        src={avatarUrl}
        onError={() => setImgError(true)}
      />
    )
  }

  return (
    <div className="w-8 h-8 rounded-full bg-gradient-to-tr from-cyan-600 to-indigo-600 flex items-center justify-center text-xs font-bold text-white border border-[#232838] flex-shrink-0">
      {name ? name.charAt(0).toUpperCase() : 'C'}
    </div>
  )
}

const recommendedStreamers: StreamerItem[] = [
  {
    id: '4',
    name: 'bladeito',
    category: 'Counter-Strike 2',
    avatar:
      'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=100&h=100&fit=crop&crop=faces',
    isLive: true,
  },
  {
    id: '5',
    name: 'Astaturo',
    category: 'GTA V',
    avatar:
      'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=100&h=100&fit=crop&crop=faces',
    isLive: true,
  },
  {
    id: '6',
    name: 'FrankDimes',
    category: 'Dota 2',
    avatar:
      'https://images.unsplash.com/photo-1522075469751-3a6694fb2f61?w=100&h=100&fit=crop&crop=faces',
    isLive: true,
  },
  {
    id: '7',
    name: 'Miken',
    category: 'Minecraft',
    avatar:
      'https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?w=100&h=100&fit=crop&crop=faces',
    isLive: true,
  },
]

export function Sidebar() {
  const { isSidebarCollapsed } = useUiStore()
  const { isAuthenticated, isCheckingAuth, openAuthModal } = useAuthStore()

  const {
    data: channelsPage,
    isLoading: isChannelsLoading,
    isError,
  } = useQuery({
    queryKey: ['channels', 'following'],
    queryFn: () => channelApi.getChannels({ following: true }),
    enabled: isAuthenticated,
  })

  const followedChannels = channelsPage?.content || []
  const showLoading = isCheckingAuth || (isAuthenticated && isChannelsLoading)

  return (
    <aside
      className={`bg-[#0f1118] border-r border-[#232838] flex flex-col justify-between transition-all duration-300 select-none flex-shrink-0 z-30 ${
        isSidebarCollapsed ? 'w-16 hidden sm:flex' : 'w-64 hidden lg:flex'
      }`}
    >
      <div className="overflow-y-auto overflow-x-hidden px-2.5 py-3 space-y-6">
        {/* Primary Navigation: Home, Browse, Following (No Drops & Rewards) */}
        <nav className="space-y-1">
          {/* Active Tab: Home */}
          <NavLink
            to="/"
            end
            title="Home"
            className={({ isActive }) =>
              `flex items-center gap-3.5 px-3 py-2.5 rounded-lg text-sm transition ${
                isActive
                  ? 'font-semibold bg-[#151822] text-[#00f0ff] border-l-4 border-[#00f0ff] shadow-sm'
                  : 'font-medium text-gray-400 hover:text-white hover:bg-[#151822]'
              } ${isSidebarCollapsed ? 'justify-center px-0' : ''}`
            }
          >
            <Home className="w-4 h-4 flex-shrink-0" />
            {!isSidebarCollapsed && <span>Home</span>}
          </NavLink>

          {/* Browse */}
          <NavLink
            to="/browse"
            title="Browse"
            className={({ isActive }) =>
              `flex items-center gap-3.5 px-3 py-2.5 rounded-lg text-sm transition ${
                isActive
                  ? 'font-semibold bg-[#151822] text-[#00f0ff] border-l-4 border-[#00f0ff] shadow-sm'
                  : 'font-medium text-gray-400 hover:text-white hover:bg-[#151822]'
              } ${isSidebarCollapsed ? 'justify-center px-0' : ''}`
            }
          >
            <Compass className="w-4 h-4 flex-shrink-0" />
            {!isSidebarCollapsed && <span>Browse</span>}
          </NavLink>

          {/* Following */}
          <a
            href="#following"
            title="Following"
            className={`flex items-center gap-3.5 px-3 py-2.5 rounded-lg text-sm font-medium text-gray-400 hover:text-white hover:bg-[#151822] transition ${
              isSidebarCollapsed ? 'justify-center px-0' : ''
            }`}
          >
            <Heart className="w-4 h-4 flex-shrink-0" />
            {!isSidebarCollapsed && <span>Following</span>}
          </a>
        </nav>

        {/* Channels Section 1: FOLLOWING */}
        <div>
          {!isSidebarCollapsed ? (
            <div className="flex items-center justify-between px-3 mb-2">
              <span className="text-[11px] font-bold text-gray-400 tracking-wider">
                FOLLOWING
              </span>
            </div>
          ) : (
            <div className="w-full flex justify-center mb-2">
              <span className="w-6 h-px bg-[#232838]" />
            </div>
          )}

          {showLoading ? (
            <div className="space-y-2 px-2 py-1">
              {[1, 2, 3].map((idx) => (
                <div
                  key={idx}
                  className={`flex items-center gap-3 px-1 py-1 ${
                    isSidebarCollapsed ? 'justify-center px-0' : ''
                  }`}
                >
                  <div className="w-8 h-8 rounded-full bg-[#1b2030] animate-pulse flex-shrink-0" />
                  {!isSidebarCollapsed && (
                    <div className="h-3.5 w-24 bg-[#1b2030] rounded animate-pulse" />
                  )}
                </div>
              ))}
            </div>
          ) : !isAuthenticated ? (
            !isSidebarCollapsed ? (
              <div className="px-3 py-2 text-xs text-gray-400">
                <p className="text-[11px] text-gray-500 mb-1 leading-relaxed">
                  Sign in to view followed channels
                </p>
                <button
                  type="button"
                  onClick={() => openAuthModal('login')}
                  className="text-xs text-[#00f0ff] hover:underline font-medium cursor-pointer"
                >
                  Log in
                </button>
              </div>
            ) : null
          ) : isError ? (
            !isSidebarCollapsed ? (
              <p className="text-xs text-gray-500 px-3 py-1.5">
                Failed to load channels
              </p>
            ) : null
          ) : followedChannels.length === 0 ? (
            !isSidebarCollapsed ? (
              <p className="text-xs text-gray-500 px-3 py-1.5">
                No followed channels yet
              </p>
            ) : null
          ) : (
            <div className="space-y-1">
              {followedChannels.map((channel) => (
                <div
                  key={channel.id}
                  title={channel.name}
                  className={`flex items-center gap-3 px-2 py-1.5 rounded-lg hover:bg-[#151822] transition group cursor-pointer ${
                    isSidebarCollapsed ? 'justify-center px-0' : ''
                  }`}
                >
                  <div className="relative flex-shrink-0">
                    <ChannelAvatar
                      key={channel.avatarUrl || channel.id}
                      name={channel.name}
                      avatarUrl={channel.avatarUrl}
                    />
                  </div>
                  {!isSidebarCollapsed && (
                    <div className="flex flex-col min-w-0">
                      <span className="text-xs font-semibold text-gray-200 truncate group-hover:text-white">
                        {channel.name}
                      </span>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Channels Section 2: RECOMMENDED */}
        <div>
          {!isSidebarCollapsed ? (
            <div className="flex items-center justify-between px-3 mb-2">
              <span className="text-[11px] font-bold text-gray-400 tracking-wider">
                RECOMMENDED
              </span>
            </div>
          ) : (
            <div className="w-full flex justify-center mb-2">
              <span className="w-6 h-px bg-[#232838]" />
            </div>
          )}

          <div className="space-y-1">
            {recommendedStreamers.map((streamer) => (
              <div
                key={streamer.id}
                title={`${streamer.name} • ${streamer.category}`}
                className={`flex items-center gap-3 px-2 py-1.5 rounded-lg hover:bg-[#151822] transition group cursor-pointer ${
                  isSidebarCollapsed ? 'justify-center px-0' : ''
                }`}
              >
                <div className="relative flex-shrink-0">
                  <img
                    alt={streamer.name}
                    className="w-8 h-8 rounded-full object-cover"
                    src={streamer.avatar}
                  />
                  {streamer.isLive && (
                    <span className="absolute bottom-0 right-0 w-2 h-2 bg-emerald-400 rounded-full border border-[#0f1118]" />
                  )}
                </div>
                {!isSidebarCollapsed && (
                  <div className="flex flex-col min-w-0">
                    <span className="text-xs font-semibold text-gray-200 truncate group-hover:text-white">
                      {streamer.name}
                    </span>
                    <span className="text-[11px] text-gray-400 truncate">
                      {streamer.category}
                    </span>
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Left Sidebar Footer Status */}
      <div className="p-3 border-t border-[#232838] bg-[#0c0d14] flex items-center justify-between">
        {!isSidebarCollapsed ? (
          <>
            <div className="flex items-center gap-2">
              <span className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-pulse" />
              <span className="text-xs font-medium text-gray-300">
                Live Network Online
              </span>
            </div>
            <Settings className="w-3.5 h-3.5 text-gray-400 hover:text-white cursor-pointer transition" />
          </>
        ) : (
          <div className="w-full flex justify-center">
            <span
              title="Live Network Online"
              className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-pulse"
            />
          </div>
        )}
      </div>
    </aside>
  )
}
