import { useState } from 'react'
import {
  AlertCircle,
  ChevronLeft,
  CircleDollarSign,
  KeyRound,
  LayoutDashboard,
  Loader2,
  RefreshCw,
  Shield,
  ShieldBan,
  Tv,
  UserRound,
} from 'lucide-react'
import { Link, NavLink, Outlet, useMatch, useSearchParams } from 'react-router-dom'
import { Button } from '@/components/ui/Button'
import {
  useChannelModerationContext,
  useEnsureOwnChannel,
} from '@/hooks/useChannelModeration'
import { useAuthStore } from '@/stores/auth.store'
import { hasStreamerRole } from '@/lib/utils'
import realLiveLogo from '@/assets/RealLive-logo.svg'
import realLiveCompactLogo from '@/assets/RealLive-RL-transparent.svg'

const navigationItems = [
  { to: '/dashboard', label: 'Stream Manager', icon: LayoutDashboard, end: true },
  { to: '/dashboard/stream-key', label: 'Stream URL & Key', icon: KeyRound },
  { to: '/dashboard/channel', label: 'My Channel', icon: Tv, end: true },
]

export function StreamerDashboardLayout() {
  const { user, isAuthenticated, isCheckingAuth } = useAuthStore()
  const [searchParams] = useSearchParams()
  const isBannedUsersPage = useMatch('/dashboard/chat-bans')
  const queryChannelId = isBannedUsersPage ? searchParams.get('channelId')?.trim() || '' : ''
  const userId = isAuthenticated && !isCheckingAuth && user?.userId ? user.userId : ''
  const isStreamer = hasStreamerRole(user)
  const shouldBootstrapChannel = Boolean(isAuthenticated && !isCheckingAuth && userId && isStreamer)
  const ownChannelQuery = useEnsureOwnChannel(userId, shouldBootstrapChannel)
  const canManageModerators = Boolean(isStreamer && ownChannelQuery.data?.status === 'ACTIVE')
  const moderationChannelId = queryChannelId || ownChannelQuery.data?.id || ''
  const moderationContextQuery = useChannelModerationContext(
    userId,
    moderationChannelId,
  )
  const canViewBans = Boolean(
    moderationChannelId && moderationContextQuery.data?.canViewBans,
  )
  const bannedUsersTo = queryChannelId
    ? `/dashboard/chat-bans?channelId=${encodeURIComponent(queryChannelId)}`
    : '/dashboard/chat-bans'
  const visibleNavigationItems = [
    ...navigationItems,
    ...(isStreamer ? [{ to: '/dashboard/revenue', label: 'Revenue', icon: CircleDollarSign }] : []),
    ...(canManageModerators
      ? [{ to: '/dashboard/channel/moderators', label: 'Moderators', icon: Shield }]
      : []),
    ...(canViewBans ? [{ to: bannedUsersTo, label: 'Banned users', icon: ShieldBan }] : []),
  ]
  const [isCollapsed, setIsCollapsed] = useState(() => {
    try {
      return localStorage.getItem('reallive_streamer_sidebar_collapsed') === 'true'
    } catch {
      return false
    }
  })

  const toggleCollapsed = () => {
    setIsCollapsed((prev) => {
      const next = !prev
      try {
        localStorage.setItem('reallive_streamer_sidebar_collapsed', String(next))
      } catch {
        // ignore
      }
      return next
    })
  }

  return (
    <div className="flex h-dvh flex-none flex-col overflow-hidden md:flex-row bg-[#000000] text-gray-100">
      <aside
        className={`relative flex shrink-0 flex-col border-b border-[#1f1f26] bg-[#08080a] md:h-full md:border-r md:border-b-0 md:transition-[width] md:duration-300 md:ease-[cubic-bezier(0.22,1,0.36,1)] md:will-change-[width] ${
          isCollapsed ? 'md:w-20' : 'md:w-64'
        }`}
      >
        <button
          type="button"
          onClick={toggleCollapsed}
          aria-label={isCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          className="hidden md:flex absolute -right-3 top-1/2 -translate-y-1/2 z-30 h-6 w-6 items-center justify-center rounded-full border border-[#2a2a35] bg-[#121216] text-gray-400 hover:text-white hover:bg-[#1a1a24] hover:border-[#3a3a48] shadow-md transition-colors duration-200 focus-visible:outline-2 focus-visible:outline-[#ff2b42] cursor-pointer"
        >
          <ChevronLeft
            className={`h-3.5 w-3.5 transition-transform duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
              isCollapsed ? 'rotate-180' : 'rotate-0'
            }`}
          />
        </button>

        <div
          className={`pt-6 pb-5 md:pt-8 transition-[padding] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
            isCollapsed ? 'px-5' : 'px-5 md:px-6'
          }`}
        >
          <Link
            to="/"
            aria-label="RealLive - Home"
            className={`relative inline-flex h-10 w-[160px] items-center overflow-hidden rounded transition-[width] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] motion-reduce:transition-none focus-visible:outline-2 focus-visible:outline-[#ff2b42] ${
              isCollapsed ? 'md:w-10' : ''
            }`}
          >
            <img
              src={realLiveLogo}
              alt=""
              aria-hidden="true"
              width={805.14}
              height={157.8}
              className={`block h-auto w-[160px] max-w-none shrink-0 origin-left transition-[opacity,transform] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] motion-reduce:transition-none ${
                isCollapsed ? 'md:scale-95 md:opacity-0' : 'scale-100 opacity-100'
              }`}
            />
            <img
              src={realLiveCompactLogo}
              alt=""
              aria-hidden="true"
              width={512}
              height={512}
              className={`absolute left-0 top-0 block h-auto w-10 max-w-none shrink-0 opacity-0 transition-opacity duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] motion-reduce:transition-none ${
                isCollapsed ? 'md:opacity-100' : ''
              }`}
            />
          </Link>
        </div>

        <nav aria-label="Streamer navigation" className="space-y-1.5 px-3 pb-4 md:flex-1">
          {visibleNavigationItems.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              aria-label={label}
              title={isCollapsed ? label : undefined}
              className={({ isActive }) =>
                `relative flex h-11 items-center rounded-lg px-3 text-sm overflow-hidden whitespace-nowrap focus-visible:outline-2 focus-visible:outline-[#ff2b42] transition-colors duration-200 ${
                  isActive
                    ? 'bg-[#121216] font-semibold text-[#ff2b42]'
                    : 'text-gray-400 hover:bg-[#121216] hover:text-white'
                }`
              }
            >
              {({ isActive }) => (
                <>
                  <span
                    className={`absolute left-0 top-2 bottom-2 w-1 rounded-r-full bg-[#ff2b42] transition-opacity duration-200 ${
                      isActive ? 'opacity-100' : 'opacity-0'
                    }`}
                  />
                  <Icon className="h-5 w-5 shrink-0" />
                  <span
                    className={`overflow-hidden whitespace-nowrap transition-[max-width,opacity,margin,transform] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] motion-reduce:transition-none ${
                      isCollapsed
                        ? 'ml-3 max-w-[170px] translate-x-0 opacity-100 md:ml-0 md:max-w-0 md:-translate-x-1 md:opacity-0'
                        : 'ml-3 max-w-[170px] translate-x-0 opacity-100'
                    }`}
                  >
                    {label}
                  </span>
                </>
              )}
            </NavLink>
          ))}
        </nav>

        {isAuthenticated && user && (
          <div className="border-t border-[#1f1f26] px-4 py-4 md:py-5">
            <div
              className="flex min-w-0 items-center overflow-hidden"
              title={isCollapsed ? user.displayName || user.email : undefined}
            >
              <div className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-full border border-[#1f1f26] bg-[#18181f]">
                {user.avatarUrl ? (
                  <img
                    src={user.avatarUrl}
                    alt="Profile picture"
                    className="h-full w-full object-cover"
                  />
                ) : (
                  <UserRound className="h-4 w-4 text-gray-400" />
                )}
              </div>
              <div
                className={`min-w-0 flex-1 overflow-hidden whitespace-nowrap transition-[max-width,opacity,margin,transform] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
                  isCollapsed
                    ? 'ml-0 max-w-0 -translate-x-1 opacity-0'
                    : 'ml-3 max-w-[170px] translate-x-0 opacity-100'
                }`}
              >
                <p className="truncate text-sm font-semibold text-white">
                  {user.displayName || 'Streamer'}
                </p>
                <p className="truncate text-xs text-gray-400">{user.email}</p>
              </div>
            </div>
          </div>
        )}
      </aside>
      <div className="min-h-0 min-w-0 flex-1 overflow-y-auto overscroll-contain">
        {isCheckingAuth ? (
          <div className="flex min-h-full items-center justify-center p-6">
            <div
              role="status"
              className="flex items-center gap-3 rounded-xl border border-[#1f1f26] bg-[#08080a] px-5 py-4 text-sm text-gray-300"
            >
              <Loader2 className="h-4 w-4 shrink-0 animate-spin motion-reduce:animate-none text-[#ff2b42]" aria-hidden="true" />
              <span>Checking your session...</span>
            </div>
          </div>
        ) : shouldBootstrapChannel && ownChannelQuery.isPending ? (
          <div className="flex min-h-full items-center justify-center p-6">
            <div
              role="status"
              className="flex items-center gap-3 rounded-xl border border-[#1f1f26] bg-[#08080a] px-5 py-4 text-sm text-gray-300"
            >
              <Loader2 className="h-4 w-4 shrink-0 animate-spin motion-reduce:animate-none text-[#ff2b42]" aria-hidden="true" />
              <span>Setting up your channel...</span>
            </div>
          </div>
        ) : shouldBootstrapChannel && ownChannelQuery.isError ? (
          <div className="flex min-h-full items-center justify-center p-6">
            <div className="max-w-md space-y-4 rounded-xl border border-red-500/30 bg-[#08080a] p-6">
              <div className="flex items-start gap-2.5 text-sm text-red-400">
                <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
                <p role="alert">
                  Unable to set up your channel right now. Please try again.
                </p>
              </div>
              <Button
                type="button"
                size="sm"
                variant="secondary"
                isLoading={ownChannelQuery.isFetching}
                onClick={() => {
                  void ownChannelQuery.refetch()
                }}
                leftIcon={<RefreshCw className="h-3.5 w-3.5" />}
                className="focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42]"
              >
                Try again
              </Button>
            </div>
          </div>
        ) : (
          <Outlet />
        )}
      </div>
    </div>
  )
}
