export interface ModeratorProfile {
  userId: string
  displayName: string | null
  avatarUrl: string | null
}

export interface ModeratorAssignment extends ModeratorProfile {
  assignedAt: string
}

export type ModeratorEligibility =
  | 'ELIGIBLE'
  | 'CHANNEL_OWNER'
  | 'ALREADY_MODERATOR'
  | 'CHANNEL_BANNED'
  | 'ACCOUNT_INACTIVE'

export interface ModeratorCandidate extends ModeratorProfile {
  displayName: string
  eligibility: ModeratorEligibility
}

export type BanType = 'CHAT_BAN' | 'LIVE_VIEW_BAN' | 'FULL_BAN'

export interface ChannelModerationContext {
  channelId: string
  ownerUserId: string
  allowedBanTypes: BanType[]
  allowedUnbanTypes: BanType[]
  canViewBans: boolean
}

export interface TargetChannelBanState {
  channelId: string
  userId: string
  banType: BanType | null
  allowedBanTypes: BanType[]
  canUnban: boolean
}

export interface SetChannelBanRequest {
  banType: BanType
}

export interface BannedUserItem {
  userId: string
  displayName: string | null
  avatarUrl: string | null
  banType: BanType
  createdAt: string
  allowedBanTypes: BanType[]
  canUnban: boolean
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

export const BAN_TYPE_OPTIONS: ReadonlyArray<{
  value: BanType
  label: string
  badgeLabel: string
}> = [
  { value: 'CHAT_BAN', label: 'Chat ban', badgeLabel: 'Chat ban' },
  {
    value: 'LIVE_VIEW_BAN',
    label: 'Live ban',
    badgeLabel: 'Live ban',
  },
  { value: 'FULL_BAN', label: 'Channel ban', badgeLabel: 'Channel ban' },
]

export function getBanTypeLabel(banType: BanType): string {
  return (
    BAN_TYPE_OPTIONS.find((option) => option.value === banType)?.badgeLabel ??
    banType
  )
}
