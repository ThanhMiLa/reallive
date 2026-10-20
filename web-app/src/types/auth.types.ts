export interface ApiResponse<T = unknown> {
  status: number
  code: number
  message: string
  result: T
  timestamp?: string
}

export interface AuthRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  email: string
  password: string
  confirmPassword: string
}

export interface UserProfile {
  userId: string
  displayName: string
  email: string
  avatarUrl: string | null
  dob: string | null
  create_at?: string
}

export type AuthTab = 'login' | 'register'

export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}
