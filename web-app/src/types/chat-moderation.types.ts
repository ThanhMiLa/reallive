export type BanType = 'CHAT_BAN' | 'LIVE_VIEW_BAN' | 'FULL_BAN'

export interface ChatModerationContext {
  channelId: string
  ownerUserId: string
  canBan: boolean
  canUnban: boolean
  canViewBans: boolean
}

export interface TargetChatBanStatus {
  channelId: string
  userId: string
  banType: BanType | null
}

export interface BanChatUserResponse {
  channelId: string
  userId: string
  banType: 'CHAT_BAN'
}

export interface UnbanChatUserResponse {
  channelId: string
  userId: string
  banType: null
}

export interface BannedUserItem {
  userId: string
  displayName: string | null
  avatarUrl: string | null
  banType: BanType
  createdAt: string
}

export interface BannedUsersPage {
  content: BannedUserItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface BannedUsersParams {
  page?: number
  size?: number
}
