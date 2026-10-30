import { useState } from 'react'
import { ArrowLeft, ChevronLeft, LayoutDashboard, Tv, UserRound } from 'lucide-react'
import { Link, NavLink, Outlet } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth.store'

export function StreamerDashboardLayout() {
  const { user, isAuthenticated } = useAuthStore()
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
          isCollapsed ? 'md:w-[68px]' : 'md:w-64'
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
            className="inline-flex items-baseline rounded text-[26px] leading-none font-black tracking-tight text-white font-outfit focus-visible:outline-2 focus-visible:outline-[#ff2b42]"
          >
            <span>R</span>
            <span
              className={`inline-block overflow-hidden transition-[max-width,opacity] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
                isCollapsed ? 'max-w-0 opacity-0' : 'max-w-[3rem] opacity-100'
              }`}
            >
              eal
            </span>
            <span className="text-[#ff2b42]">L</span>
            <span
              className={`inline-block overflow-hidden text-[#ff2b42] transition-[max-width,opacity] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
                isCollapsed ? 'max-w-0 opacity-0' : 'max-w-[3rem] opacity-100'
              }`}
            >
              ive
            </span>
          </Link>
        </div>

        <nav aria-label="Streamer navigation" className="space-y-1.5 px-3 pb-4 md:flex-1">
          <NavLink
            to="/dashboard"
            end
            title={isCollapsed ? 'Stream Manager' : undefined}
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
                <LayoutDashboard className="h-5 w-5 shrink-0" />
                <span
                  className={`overflow-hidden whitespace-nowrap transition-[max-width,opacity,margin,transform] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
                    isCollapsed
                      ? 'ml-0 max-w-0 -translate-x-1 opacity-0'
                      : 'ml-3 max-w-[160px] translate-x-0 opacity-100'
                  }`}
                >
                  Stream Manager
                </span>
              </>
            )}
          </NavLink>
          <NavLink
            to="/dashboard/channel"
            title={isCollapsed ? 'My Channel' : undefined}
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
                <Tv className="h-5 w-5 shrink-0" />
                <span
                  className={`overflow-hidden whitespace-nowrap transition-[max-width,opacity,margin,transform] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
                    isCollapsed
                      ? 'ml-0 max-w-0 -translate-x-1 opacity-0'
                      : 'ml-3 max-w-[160px] translate-x-0 opacity-100'
                  }`}
                >
                  My Channel
                </span>
              </>
            )}
          </NavLink>
        </nav>

        <div className="space-y-3 border-t border-[#1f1f26] px-4 py-4 md:py-5">
          {isAuthenticated && user && (
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
          )}
          <Link
            to="/"
            title={isCollapsed ? 'Back to home' : undefined}
            className="flex h-9 items-center rounded px-2 text-sm text-gray-400 hover:text-white overflow-hidden whitespace-nowrap transition-colors duration-200 focus-visible:outline-2 focus-visible:outline-[#ff2b42]"
          >
            <ArrowLeft className="h-5 w-5 shrink-0" />
            <span
              className={`overflow-hidden whitespace-nowrap transition-[max-width,opacity,margin,transform] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
                isCollapsed
                  ? 'ml-0 max-w-0 -translate-x-1 opacity-0'
                  : 'ml-2.5 max-w-[140px] translate-x-0 opacity-100'
              }`}
            >
              Back to home
            </span>
          </Link>
        </div>
      </aside>
      <div className="min-h-0 min-w-0 flex-1 overflow-y-auto overscroll-contain">
        <Outlet />
      </div>
    </div>
  )
}
