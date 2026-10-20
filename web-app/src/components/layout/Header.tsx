import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Search, Menu, Bell, LayoutDashboard, LogOut, User as UserIcon } from 'lucide-react'
import { toast } from 'sonner'
import { useAuthStore } from '@/stores/auth.store'
import { useUiStore } from '@/stores/ui.store'

export function Header() {
  const { user, isAuthenticated, isLoading, openAuthModal, logout } = useAuthStore()
  const { toggleSidebar } = useUiStore()
  const [isUserMenuOpen, setUserMenuOpen] = useState(false)
  const userMenuRef = useRef<HTMLDivElement>(null)
  const avatarRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!isUserMenuOpen) return

    const handlePointerDown = (event: PointerEvent) => {
      if (!userMenuRef.current?.contains(event.target as Node)) {
        setUserMenuOpen(false)
      }
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setUserMenuOpen(false)
        avatarRef.current?.focus()
      }
    }

    document.addEventListener('pointerdown', handlePointerDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [isUserMenuOpen])

  const handleNotificationClick = () => {
    toast.info('You have no new notifications.')
  }

  const handleAvatarClick = () => {
    if (!isAuthenticated) {
      openAuthModal('login')
    } else {
      setUserMenuOpen((open) => !open)
    }
  }

  const handleLogout = async () => {
    setUserMenuOpen(false)
    try {
      await logout()
      toast.success('Logged out successfully!')
    } catch {
      toast.error('Unable to log out. Please try again.')
    }
  }

  return (
    <header className="h-[62px] bg-[#08080a] border-b border-[#1f1f26] flex items-center justify-between px-4 sticky top-0 z-40 select-none">
      {/* Left: 3-bar Hamburger Menu & Brand Name (Text Only) */}
      <div className="flex items-center gap-3">
        {/* Toggle the sidebar */}
        <button
          type="button"
          onClick={toggleSidebar}
          aria-label="Toggle navigation"
          className="p-1.5 text-gray-400 hover:text-white rounded-lg hover:bg-[#18181f] transition cursor-pointer"
        >
          <Menu className="w-5 h-5" />
        </button>

        {/* RealLive wordmark */}
        <a href="/" className="flex items-center group">
          <span className="text-[34px] sm:text-[43px] font-black tracking-tight leading-none bg-gradient-to-r from-white via-slate-100 to-[#ff2b42] bg-clip-text text-transparent font-outfit">
            Real<span className="text-[#ff2b42]">Live</span>
          </span>
        </a>
      </div>

      {/* Center: Search Bar */}
      <div className="flex-1 max-w-xl mx-6 hidden md:block">
        <div className="relative w-full">
          <span className="absolute inset-y-0 left-0 flex items-center pl-3.5 pointer-events-none text-gray-400">
            <Search className="w-4 h-4" />
          </span>
          <input
            type="text"
            placeholder="Search streams, categories, or streamers..."
            className="w-full bg-[#101014] text-sm text-gray-200 placeholder-gray-400 pl-10 pr-4 py-2 rounded-lg border border-[#1f1f26] focus:outline-none focus:border-[#ff2b42] focus:ring-1 focus:ring-[#ff2b42] transition"
          />
        </div>
      </div>

      {/* Right: Notification Bell & User Avatar */}
      <div className="flex items-center gap-3">
        {/* Notifications */}
        <button
          type="button"
          onClick={handleNotificationClick}
          aria-label="Notifications"
          className="p-2 text-gray-300 hover:text-white rounded-lg hover:bg-[#18181f] relative transition cursor-pointer"
        >
          <Bell className="w-5 h-5" />
          <span className="absolute top-1.5 right-1.5 w-2 h-2 bg-[#ff2b42] rounded-full ring-2 ring-[#08080a]" />
        </button>

        {/* User avatar */}
        <div
          className="relative"
          ref={userMenuRef}
          onBlur={(event) => {
            if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
              setUserMenuOpen(false)
            }
          }}
        >
          <button
            ref={avatarRef}
            type="button"
            onClick={handleAvatarClick}
            aria-label={isAuthenticated ? 'Account options' : 'Log in'}
            aria-expanded={isAuthenticated ? isUserMenuOpen : undefined}
            aria-controls={isAuthenticated && isUserMenuOpen ? 'account-options' : undefined}
            className="relative flex items-center rounded-full p-0.5 focus-visible:ring-2 focus-visible:ring-[#ff2b42] hover:ring-2 hover:ring-[#ff2b42]/50 transition cursor-pointer"
          >
            {user?.avatarUrl ? (
              <img
                src={user.avatarUrl}
                alt={user.displayName || 'Avatar'}
                className="w-9 h-9 rounded-full object-cover border border-[#ff2b42]/70"
              />
            ) : (
              <div className="w-9 h-9 rounded-full bg-gradient-to-tr from-rose-600 to-indigo-600 flex items-center justify-center text-sm font-bold text-white border border-[#ff2b42]/70 shadow-sm">
                {user?.displayName ? (
                  user.displayName.charAt(0).toUpperCase()
                ) : (
                  <UserIcon className="w-4.5 h-4.5 text-white" />
                )}
              </div>
            )}
            {/* Online Green Indicator Dot */}
            <span className="absolute bottom-0 right-0 w-2.5 h-2.5 bg-emerald-500 border-2 border-[#08080a] rounded-full" />
          </button>
          {isAuthenticated && isUserMenuOpen && (
            <div
              id="account-options"
              aria-label="Account options"
              className="absolute right-0 z-50 mt-3 w-48 overflow-hidden rounded-xl border border-[#1f1f26] bg-[#101014] p-1.5 shadow-2xl"
            >
              <Link
                to="/profile"
                onClick={() => setUserMenuOpen(false)}
                className="flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm text-gray-200 hover:bg-[#18181f] hover:text-white focus-visible:outline-2 focus-visible:outline-[#ff2b42]"
              >
                <LayoutDashboard className="h-4 w-4" />
                Dashboard
              </Link>
              <button
                type="button"
                onClick={handleLogout}
                disabled={isLoading}
                className="flex w-full cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-left text-sm text-red-400 hover:bg-[#18181f] hover:text-red-300 focus-visible:outline-2 focus-visible:outline-[#ff2b42] disabled:opacity-50"
              >
                <LogOut className="h-4 w-4" />
                Logout
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  )
}
