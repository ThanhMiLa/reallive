import { create } from 'zustand'
import { isAxiosError } from 'axios'
import { authApi } from '@/api/auth.api'
import { queryClient } from '@/lib/query-client'
import type {
  AuthRequest,
  AuthTab,
  RegisterRequest,
  UserProfile,
} from '@/types/auth.types'

let authCheckPromise: Promise<void> | null = null

interface AuthState {
  user: UserProfile | null
  isAuthenticated: boolean
  isLoading: boolean
  isCheckingAuth: boolean
  isAuthModalOpen: boolean
  authModalTab: AuthTab

  // Modal actions
  openAuthModal: (tab?: AuthTab) => void
  closeAuthModal: () => void
  setAuthModalTab: (tab: AuthTab) => void

  // Auth actions
  login: (payload: AuthRequest) => Promise<void>
  register: (payload: RegisterRequest) => Promise<void>
  logout: () => Promise<void>
  checkAuth: () => Promise<void>
  setUser: (user: UserProfile | null) => void
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isAuthenticated: false,
  isLoading: false,
  isCheckingAuth: true,
  isAuthModalOpen: false,
  authModalTab: 'login',

  openAuthModal: (tab = 'login') =>
    set({
      isAuthModalOpen: true,
      authModalTab: tab,
    }),

  closeAuthModal: () =>
    set({
      isAuthModalOpen: false,
    }),

  setAuthModalTab: (tab) =>
    set({
      authModalTab: tab,
    }),

  login: async (payload: AuthRequest) => {
    set({ isLoading: true })
    try {
      await authApi.login(payload)
      const profile = await authApi.getProfile()
      queryClient.setQueryData(['profile', profile.userId], profile)
      set({
        user: profile,
        isAuthenticated: true,
        isAuthModalOpen: false,
      })
    } finally {
      set({ isLoading: false })
    }
  },

  register: async (payload: RegisterRequest) => {
    set({ isLoading: true })
    try {
      await authApi.register(payload)
      const profile = await authApi.getProfile()
      queryClient.setQueryData(['profile', profile.userId], profile)
      set({
        user: profile,
        isAuthenticated: true,
        isAuthModalOpen: false,
      })
    } finally {
      set({ isLoading: false })
    }
  },

  logout: async () => {
    set({ isLoading: true })
    try {
      await authApi.logout()
    } catch {
      // Even if network fails, reset client auth state
    } finally {
      queryClient.removeQueries({ queryKey: ['profile'] })
      set({
        user: null,
        isAuthenticated: false,
        isLoading: false,
      })
    }
  },

  checkAuth: () => {
    if (authCheckPromise) return authCheckPromise

    authCheckPromise = (async () => {
      set({ isCheckingAuth: true })
      try {
        const profile = await authApi.getProfile()
        queryClient.setQueryData(['profile', profile.userId], profile)
        set({ user: profile, isAuthenticated: true })
      } catch (error) {
        if (isAxiosError(error) && error.response?.status === 503) return
        queryClient.removeQueries({ queryKey: ['profile'] })
        set({ user: null, isAuthenticated: false })
      } finally {
        set({ isCheckingAuth: false })
      }
    })().finally(() => {
      authCheckPromise = null
    })
    return authCheckPromise
  },

  setUser: (user) =>
    set({
      user,
      isAuthenticated: !!user,
    }),
}))
