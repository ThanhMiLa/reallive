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

function extractRolesFromAuthResponse(response: unknown): { role?: string; roles: string[] } {
  if (!response || typeof response !== 'object') return { roles: [] }
  const resp = response as Record<string, unknown>
  const result = (resp.result && typeof resp.result === 'object' ? resp.result : resp) as Record<string, unknown>

  const role = typeof result.role === 'string'
    ? result.role
    : typeof resp.role === 'string'
      ? resp.role
      : undefined

  const rawRoles = Array.isArray(result.roles)
    ? result.roles
    : Array.isArray(resp.roles)
      ? resp.roles
      : role
        ? [role]
        : []

  const roles = rawRoles.filter((r): r is string => typeof r === 'string')
  return { role, roles }
}

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
      const loginResponse = await authApi.login(payload)
      const profile = await authApi.getProfile()

      const { role: extraRole, roles: extraRoles } = extractRolesFromAuthResponse(loginResponse)
      const mergedRoles = extraRoles.length > 0
        ? Array.from(new Set([...(Array.isArray(profile.roles) ? profile.roles : []), ...extraRoles]))
        : profile.roles

      const mergedUser: UserProfile = {
        ...profile,
        roles: mergedRoles,
        ...(extraRole ? { role: extraRole } : {}),
      }

      queryClient.setQueryData(['profile', profile.userId], mergedUser)
      set({
        user: mergedUser,
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
      const registerResponse = await authApi.register(payload)
      const profile = await authApi.getProfile()

      const { role: extraRole, roles: extraRoles } = extractRolesFromAuthResponse(registerResponse)
      const mergedRoles = extraRoles.length > 0
        ? Array.from(new Set([...(Array.isArray(profile.roles) ? profile.roles : []), ...extraRoles]))
        : profile.roles

      const mergedUser: UserProfile = {
        ...profile,
        roles: mergedRoles,
        ...(extraRole ? { role: extraRole } : {}),
      }

      queryClient.setQueryData(['profile', profile.userId], mergedUser)
      set({
        user: mergedUser,
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
