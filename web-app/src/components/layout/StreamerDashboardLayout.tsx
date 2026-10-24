import { ArrowLeft, LayoutDashboard, UserRound } from 'lucide-react'
import { Link, NavLink, Outlet } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth.store'

export function StreamerDashboardLayout() {
  const { user, isAuthenticated } = useAuthStore()

  return (
    <div className="flex h-dvh flex-none flex-col overflow-hidden md:flex-row bg-[#000000] text-gray-100">
      <aside className="flex shrink-0 flex-col border-b border-[#1f1f26] bg-[#08080a] md:h-full md:w-64 md:border-r md:border-b-0">
        <div className="px-5 pt-6 pb-5 md:px-6 md:pt-8">
          <Link
            to="/"
            aria-label="RealLive - Home"
            className="inline-block rounded text-3xl font-black tracking-tight text-white font-outfit focus-visible:outline-2 focus-visible:outline-[#ff2b42]"
          >
            Real<span className="text-[#ff2b42]">Live</span>
          </Link>
        </div>

        <nav aria-label="Streamer navigation" className="space-y-1 px-3 pb-4 md:flex-1">
          <NavLink
            to="/dashboard"
            end
            className={({ isActive }) =>
              `flex items-center gap-3 rounded-lg border-l-4 px-4 py-3 text-sm focus-visible:outline-2 focus-visible:outline-[#ff2b42] ${
                isActive
                  ? 'border-[#ff2b42] bg-[#121216] font-semibold text-[#ff2b42]'
                  : 'border-transparent text-gray-400 hover:bg-[#121216] hover:text-white'
              }`
            }
          >
            <LayoutDashboard className="h-4 w-4 shrink-0" />
            Stream Manager
          </NavLink>
        </nav>

        <div className="space-y-4 border-t border-[#1f1f26] p-5 md:p-6">
          {isAuthenticated && user && (
            <div className="flex min-w-0 items-center gap-3">
              <div className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-full border border-[#1f1f26] bg-[#18181f]">
                {user.avatarUrl ? (
                  <img src={user.avatarUrl} alt="Profile picture" className="h-full w-full object-cover" />
                ) : (
                  <UserRound className="h-4 w-4 text-gray-400" />
                )}
              </div>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-semibold text-white">{user.displayName || 'Streamer'}</p>
                <p className="truncate text-xs text-gray-400">{user.email}</p>
              </div>
            </div>
          )}
          <Link
            to="/"
            className="flex items-center gap-2 rounded text-sm text-gray-400 hover:text-white focus-visible:outline-2 focus-visible:outline-[#ff2b42]"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to home
          </Link>
        </div>
      </aside>
      <div className="min-h-0 min-w-0 flex-1 overflow-y-auto overscroll-contain">
        <Outlet />
      </div>
    </div>
  )
}
