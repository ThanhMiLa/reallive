import { useEffect } from 'react'
import {
  BrowserRouter,
  Routes,
  Route,
  Navigate,
  Outlet,
  useLocation,
  useNavigate,
} from 'react-router-dom'
import { QueryClientProvider } from '@tanstack/react-query'
import { Toaster } from 'sonner'
import { Header } from '@/components/layout/Header'
import { AccountLayout } from '@/components/layout/AccountLayout'
import { StreamerDashboardLayout } from '@/components/layout/StreamerDashboardLayout'
import { Sidebar } from '@/components/layout/Sidebar'
import { CategoryPreferencesPage } from '@/pages/category-preferences/CategoryPreferencesPage'
import { BecomeStreamerPage } from '@/pages/become-streamer/BecomeStreamerPage'
import { ProfilePage } from '@/pages/profile/ProfilePage'
import { StreamerDashboardPage } from '@/pages/dashboard/StreamerDashboardPage'
import { HomePage } from '@/pages/HomePage'
import { BrowsePage } from '@/pages/browse/BrowsePage'
import { BrowseLivestreamsTab } from '@/pages/browse/tabs/BrowseLivestreamsTab'
import { BrowseCategoriesTab } from '@/pages/browse/tabs/BrowseCategoriesTab'
import { BrowseChannelsTab } from '@/pages/browse/tabs/BrowseChannelsTab'
import { AuthModal } from '@/components/auth/AuthModal'
import { useAuthStore } from '@/stores/auth.store'
import { queryClient } from '@/lib/query-client'

function SiteLayout() {
  return (
    <>
      <Header />
      <div className="flex-1 flex overflow-hidden">
        <Sidebar />
        <Outlet />
      </div>
    </>
  )
}

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<AccountLayout />}>
        <Route path="/profile" element={<ProfilePage />} />
        <Route path="/become-streamer" element={<BecomeStreamerPage />} />
        <Route path="/category-preferences" element={<CategoryPreferencesPage />} />
      </Route>
      <Route element={<StreamerDashboardLayout />}>
        <Route path="/dashboard" element={<StreamerDashboardPage />} />
        <Route path="/streamer/dashboard" element={<Navigate to="/dashboard" replace />} />
      </Route>
      <Route element={<SiteLayout />}>
        <Route path="/" element={<HomePage />} />
        <Route path="/login" element={<HomePage />} />
        <Route path="/register" element={<HomePage />} />
        <Route path="/browse" element={<BrowsePage />}>
          <Route index element={<Navigate to="livestreams" replace />} />
          <Route path="livestreams" element={<BrowseLivestreamsTab />} />
          <Route path="categories" element={<BrowseCategoriesTab />} />
          <Route path="channels" element={<BrowseChannelsTab />} />
        </Route>
        <Route path="*" element={<HomePage />} />
      </Route>
    </Routes>
  )
}

function AppContent() {
  const location = useLocation()
  const navigate = useNavigate()
  const { checkAuth, openAuthModal, isAuthModalOpen } = useAuthStore()

  // Initialize auth session on mount
  useEffect(() => {
    checkAuth()
  }, [checkAuth])

  // Sync URL route (/login, /register) with modal state
  useEffect(() => {
    if (location.pathname === '/login') {
      openAuthModal('login')
    } else if (location.pathname === '/register') {
      openAuthModal('register')
    }
  }, [location.pathname, openAuthModal])

  // When modal is closed by user while URL is /login or /register, revert URL back to /
  useEffect(() => {
    if (
      !isAuthModalOpen &&
      (location.pathname === '/login' || location.pathname === '/register')
    ) {
      navigate('/', { replace: true })
    }
  }, [isAuthModalOpen, location.pathname, navigate])

  return (
    <div className="bg-[#000000] text-gray-100 font-sans min-h-screen flex flex-col antialiased selection:bg-[#ff2b42] selection:text-white overflow-x-hidden">
      <AppRoutes />

      {/* Global Auth Modal Popup (Dimming and blurring the background) */}
      <AuthModal />

      {/* Toast notifications */}
      <Toaster
        theme="dark"
        position="top-right"
        richColors
        closeButton
        offset={{ top: '76px', right: '16px' }}
        toastOptions={{
          style: {
            background: '#101014',
            border: '1px solid #1f1f26',
            color: '#f3f4f6',
          },
          classNames: {
            closeButton:
              '!bg-[#18181f] !text-gray-300 hover:!text-white !border-[#1f1f26]',
          },
        }}
      />
    </div>
  )
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AppContent />
      </BrowserRouter>
    </QueryClientProvider>
  )
}
