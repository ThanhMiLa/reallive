import { ArrowLeft, LogOut, UserRound } from 'lucide-react'
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { useAuthStore } from '@/stores/auth.store'

export function AccountLayout() {
  const { user, isAuthenticated, isLoading, logout } = useAuthStore()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/', { replace: true })
    toast.success('Logged out successfully!')
  }

  return (
    <div className="flex min-h-screen flex-1 flex-col md:flex-row">
      <aside className="flex shrink-0 flex-col border-b border-[#1f1f26] bg-[#08080a] md:sticky md:top-0 md:h-screen md:w-64 md:border-r md:border-b-0">
        <div className="px-5 pt-6 pb-5 md:px-6 md:pt-8">
          <Link
            to="/"
            aria-label="RealLive - Home"
            className="inline-block rounded text-3xl font-black tracking-tight text-white font-outfit focus-visible:outline-2 focus-visible:outline-[#ff2b42]"
          >
            Real<span className="text-[#ff2b42]">Live</span>
          </Link>
          <p className="mt-2 text-sm text-gray-400">Your account</p>
        </div>

        <nav aria-label="Account navigation" className="px-3 pb-4 md:flex-1">
          <NavLink
            to="/profile"
            end
            className={({ isActive }) =>
              `flex items-center gap-3 rounded-lg border-l-4 px-4 py-3 text-sm focus-visible:outline-2 focus-visible:outline-[#ff2b42] ${
                isActive
                  ? 'border-[#ff2b42] bg-[#121216] font-semibold text-[#ff2b42]'
                  : 'border-transparent text-gray-400 hover:bg-[#121216] hover:text-white'
              }`
            }
          >
            <UserRound className="h-4 w-4 shrink-0" />
            Profile
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
                <p className="truncate text-sm font-semibold text-white">{user.displayName}</p>
                <p className="truncate text-xs text-gray-400">{user.email}</p>
              </div>
              <button
                type="button"
                aria-label="Log out"
                title="Log out"
                onClick={handleLogout}
                disabled={isLoading}
                className="shrink-0 cursor-pointer rounded p-1.5 text-gray-400 hover:text-[#ff2b42] focus-visible:outline-2 focus-visible:outline-[#ff2b42] disabled:opacity-50"
              >
                <LogOut className="h-4 w-4" />
              </button>
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
      <Outlet />
    </div>
  )
}
