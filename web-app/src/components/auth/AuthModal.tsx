import { useEffect, useRef } from 'react'
import { X } from 'lucide-react'
import { toast } from 'sonner'
import { LoginForm } from './LoginForm'
import { RegisterForm } from './RegisterForm'
import { GoogleIcon } from '@/components/ui/GoogleIcon'
import { useAuthStore } from '@/stores/auth.store'

export function AuthModal() {
  const { isAuthModalOpen, authModalTab, closeAuthModal, setAuthModalTab } =
    useAuthStore()
  const modalRef = useRef<HTMLDivElement>(null)

  // Close on Escape key
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        closeAuthModal()
      }
    }

    if (isAuthModalOpen) {
      document.addEventListener('keydown', handleKeyDown)
      document.body.style.overflow = 'hidden'
    }

    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      document.body.style.overflow = 'unset'
    }
  }, [isAuthModalOpen, closeAuthModal])

  if (!isAuthModalOpen) return null

  const handleGoogleAuth = () => {
    toast.info('Google sign-in is coming soon.')
  }

  return (
    <div
      id="auth-modal-overlay"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4 overflow-y-auto animate-in fade-in duration-200"
      onClick={(e) => {
        if (e.target === e.currentTarget) {
          closeAuthModal()
        }
      }}
      role="dialog"
      aria-modal="true"
      aria-labelledby="auth-modal-title"
    >
      <div
        ref={modalRef}
        className="relative w-full max-w-md rounded-2xl bg-[#0a0a0d] border border-[#1f1f26] p-6 sm:p-8 shadow-2xl animate-in zoom-in-95 duration-150"
      >
        {/* Close Button */}
        <button
          onClick={closeAuthModal}
          type="button"
          aria-label="Close modal"
          className="absolute top-5 right-5 text-gray-400 hover:text-white transition p-1.5 rounded-lg hover:bg-white/5 cursor-pointer"
        >
          <X className="w-5 h-5" />
        </button>

        {/* Brand Logo Header */}
        <div className="flex flex-col items-center mb-6">
          <div className="flex items-center gap-2 group mb-2">
            <span
              id="auth-modal-title"
              className="text-2xl font-extrabold tracking-tight bg-gradient-to-r from-white via-slate-100 to-[#ff2b42] bg-clip-text text-transparent font-outfit"
            >
              Real<span className="text-[#ff2b42]">Live</span>
            </span>
          </div>
          <p className="text-xs text-gray-400">
            {authModalTab === 'login'
              ? 'Log in to your account to continue'
              : 'Create an account to experience RealLive'}
          </p>
        </div>

        {/* Tabs: Log In / Sign Up */}
        <div className="flex border-b border-[#1f1f26] mb-6">
          <button
            type="button"
            onClick={() => setAuthModalTab('login')}
            className={`pb-3 px-4 text-sm transition cursor-pointer ${
              authModalTab === 'login'
                ? 'font-bold text-[#ff2b42] border-b-2 border-[#ff2b42]'
                : 'font-medium text-gray-400 hover:text-white'
            }`}
          >
            Log In
          </button>
          <button
            type="button"
            onClick={() => setAuthModalTab('register')}
            className={`pb-3 px-4 text-sm transition cursor-pointer ${
              authModalTab === 'register'
                ? 'font-bold text-[#ff2b42] border-b-2 border-[#ff2b42]'
                : 'font-medium text-gray-400 hover:text-white'
            }`}
          >
            Sign Up
          </button>
        </div>

        {/* Form Body */}
        {authModalTab === 'login' ? <LoginForm /> : <RegisterForm />}

        {/* Divider */}
        <div className="relative flex items-center justify-center my-6">
          <div className="border-t border-[#1f1f26] w-full" />
          <span className="bg-[#0a0a0d] px-3 text-xs text-gray-500 font-medium absolute">
            or continue with
          </span>
        </div>

        {/* Social Authentication */}
        <div>
          <button
            type="button"
            onClick={handleGoogleAuth}
            className="w-full flex items-center justify-center gap-2.5 py-2.5 px-4 bg-[#101014] hover:bg-[#18181f] border border-[#1f1f26] rounded-lg text-sm text-gray-200 transition font-medium cursor-pointer"
          >
            <GoogleIcon className="w-4 h-4" />
            <span>Continue with Google</span>
          </button>
        </div>

        {/* Footer switch prompt */}
        <div className="mt-6 text-center">
          {authModalTab === 'login' ? (
            <p className="text-xs text-gray-400">
              Don't have an account?{' '}
              <button
                type="button"
                onClick={() => setAuthModalTab('register')}
                className="text-[#ff2b42] font-semibold hover:underline cursor-pointer ml-1"
              >
                Sign Up
              </button>
            </p>
          ) : (
            <p className="text-xs text-gray-400">
              Already have an account?{' '}
              <button
                type="button"
                onClick={() => setAuthModalTab('login')}
                className="text-[#ff2b42] font-semibold hover:underline cursor-pointer ml-1"
              >
                Log In
              </button>
            </p>
          )}
        </div>
      </div>
    </div>
  )
}
