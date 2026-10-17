import { useState, useRef, useEffect } from 'react'
import { Search, Menu, Bell, LogOut, User as UserIcon } from 'lucide-react'
import { toast } from 'sonner'
import { useAuthStore } from '@/stores/auth.store'
import { useUiStore } from '@/stores/ui.store'

export function Header() {
  const { user, isAuthenticated, openAuthModal, logout } = useAuthStore()
  const { toggleSidebar } = useUiStore()
  const [showUserMenu, setShowUserMenu] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

  // Close user dropdown on outside click
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setShowUserMenu(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const handleNotificationClick = () => {
    toast.info('Bạn không có thông báo mới.')
  }

  const handleAvatarClick = () => {
    if (!isAuthenticated) {
      openAuthModal('login')
    } else {
      setShowUserMenu(!showUserMenu)
    }
  }

  const handleLogout = async () => {
    setShowUserMenu(false)
    try {
      await logout()
      toast.success('Đã đăng xuất thành công!')
    } catch {
      toast.error('Có lỗi xảy ra khi đăng xuất')
    }
  }

  return (
    <header className="h-[62px] bg-[#08080a] border-b border-[#1f1f26] flex items-center justify-between px-4 sticky top-0 z-40 select-none">
      {/* Left: 3-bar Hamburger Menu & Brand Name (Text Only) */}
      <div className="flex items-center gap-3">
        {/* Dấu 3 gạch để bật tắt navbar */}
        <button
          type="button"
          onClick={toggleSidebar}
          aria-label="Toggle navigation"
          className="p-1.5 text-gray-400 hover:text-white rounded-lg hover:bg-[#18181f] transition cursor-pointer"
        >
          <Menu className="w-5 h-5" />
        </button>

        {/* Chữ RealLive - Không dùng logo icon như template */}
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
        {/* Hình chuông thông báo */}
        <button
          type="button"
          onClick={handleNotificationClick}
          aria-label="Notifications"
          className="p-2 text-gray-300 hover:text-white rounded-lg hover:bg-[#18181f] relative transition cursor-pointer"
        >
          <Bell className="w-5 h-5" />
          <span className="absolute top-1.5 right-1.5 w-2 h-2 bg-[#ff2b42] rounded-full ring-2 ring-[#08080a]" />
        </button>

        {/* Logo avatar của user */}
        <div className="relative" ref={menuRef}>
          <button
            type="button"
            onClick={handleAvatarClick}
            aria-label="User profile"
            className="relative flex items-center rounded-full p-0.5 focus:outline-none hover:ring-2 hover:ring-[#ff2b42]/50 transition cursor-pointer"
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

          {/* User Dropdown Menu (If authenticated) */}
          {showUserMenu && isAuthenticated && (
            <div className="absolute right-0 mt-2 w-48 rounded-xl bg-[#0e0e12] border border-[#1f1f26] py-1.5 shadow-2xl z-50 animate-in fade-in zoom-in-95 duration-150">
              <div className="px-3 py-2 border-b border-[#1f1f26]">
                <p className="text-xs font-bold text-white truncate">
                  {user?.displayName || 'Streamer'}
                </p>
                <p className="text-[11px] text-gray-400 truncate">
                  {user?.email}
                </p>
              </div>
              <button
                type="button"
                onClick={handleLogout}
                className="w-full text-left px-3 py-2 text-xs text-red-400 hover:bg-[#18181f] hover:text-red-300 flex items-center gap-2 transition cursor-pointer"
              >
                <LogOut className="w-3.5 h-3.5" />
                <span>Đăng xuất</span>
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  )
}
