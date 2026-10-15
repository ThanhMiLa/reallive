import { Search, Globe, Menu, Play, LogOut, User as UserIcon } from 'lucide-react'
import { toast } from 'sonner'
import { useAuthStore } from '@/stores/auth.store'

export function Header() {
  const { user, isAuthenticated, openAuthModal, logout } = useAuthStore()

  const handleLogout = async () => {
    try {
      await logout()
      toast.success('Đã đăng xuất thành công!')
    } catch {
      toast.error('Có lỗi xảy ra khi đăng xuất')
    }
  }

  return (
    <header className="h-14 bg-[#0f1118] border-b border-[#232838] flex items-center justify-between px-4 sticky top-0 z-40 select-none">
      {/* Left: Brand Logo & Hamburger */}
      <div className="flex items-center gap-4">
        <button
          aria-label="Toggle menu"
          className="p-1.5 text-gray-400 hover:text-white rounded-lg hover:bg-[#1f2433] transition cursor-pointer"
        >
          <Menu className="w-5 h-5" />
        </button>

        {/* Brand Logo */}
        <a href="/" className="flex items-center gap-2 group">
          <div className="w-8 h-8 rounded-lg bg-gradient-to-tr from-cyan-500 to-indigo-600 flex items-center justify-center neon-glow-cyan shadow-cyan-500/20">
            <Play className="w-3.5 h-3.5 text-black fill-black ml-0.5" />
          </div>
          <span className="text-xl font-extrabold tracking-tight bg-gradient-to-r from-white via-slate-100 to-[#00f0ff] bg-clip-text text-transparent font-outfit">
            Real<span className="text-[#00f0ff]">Live</span>
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
            className="w-full bg-[#151822] text-sm text-gray-200 placeholder-gray-400 pl-10 pr-4 py-2 rounded-lg border border-[#232838] focus:outline-none focus:border-[#00f0ff] focus:ring-1 focus:ring-[#00f0ff] transition"
          />
        </div>
      </div>

      {/* Right: Quick Actions & Profile */}
      <div className="flex items-center gap-3">
        <button
          aria-label="Language"
          className="p-2 text-gray-400 hover:text-white rounded-lg hover:bg-[#1f2433] transition cursor-pointer"
        >
          <Globe className="w-4 h-4" />
        </button>

        {!isAuthenticated ? (
          <>
            <button
              type="button"
              onClick={() => openAuthModal('login')}
              className="text-sm font-semibold text-gray-200 hover:text-white px-3 py-1.5 rounded-lg hover:bg-[#1f2433] transition cursor-pointer"
            >
              Log In
            </button>
            <button
              type="button"
              onClick={() => openAuthModal('register')}
              className="text-sm font-bold bg-[#53fc18] hover:bg-[#46e012] text-black px-4 py-1.5 rounded-lg shadow transition cursor-pointer"
            >
              Sign Up
            </button>
          </>
        ) : (
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-2 pl-2">
              {user?.avatarUrl ? (
                <img
                  src={user.avatarUrl}
                  alt={user.displayName}
                  className="w-8 h-8 rounded-full object-cover border border-[#00f0ff]"
                />
              ) : (
                <div className="w-8 h-8 rounded-full bg-gradient-to-tr from-cyan-500 to-indigo-600 flex items-center justify-center text-xs font-bold text-black border border-white/20">
                  {user?.displayName ? (
                    user.displayName.charAt(0).toUpperCase()
                  ) : (
                    <UserIcon className="w-4 h-4 text-white" />
                  )}
                </div>
              )}
              <div className="hidden sm:flex flex-col text-left">
                <span className="text-xs font-bold text-white leading-tight">
                  {user?.displayName || 'User'}
                </span>
                <span className="text-[10px] text-gray-400 truncate max-w-[120px]">
                  {user?.email}
                </span>
              </div>
            </div>

            <button
              type="button"
              onClick={handleLogout}
              title="Đăng xuất"
              className="p-2 text-gray-400 hover:text-red-400 rounded-lg hover:bg-[#1f2433] transition cursor-pointer"
            >
              <LogOut className="w-4 h-4" />
            </button>
          </div>
        )}
      </div>
    </header>
  )
}
