export type StreamStatus = 'LIVE' | 'RECONNECTING' | 'ENDED'

export type StreamConfigStatus = 'DRAFT' | 'ACTIVE' | 'BLOCKED'

interface StreamMetadataFields {
  id: string
  channelId: string
  title: string
  categoryId: string
  visibility: 'PUBLIC'
  status: StreamStatus
  startedAt: string | null
  endedAt: string | null
  reconnectDeadlineAt: string | null
  serverTime: string
}

export interface StreamMetadata extends StreamMetadataFields {
  ownerUserId?: string
  currentViewerCount: number | null
}

export interface StreamSession extends StreamMetadataFields {
  publishReady: boolean
}

export interface PlaybackAccess {
  sessionId: string
  playbackUrl: string
  readToken: string
  readExpiresAt: string
}

export interface StreamDetails {
  title: string
  categoryId: string
}

export type SaveStreamSetupRequest = StreamDetails

export interface StreamSetup {
  channelId: string
  title: string | null
  categoryId: string | null
  visibility: 'PUBLIC'
  status: StreamConfigStatus
  hasStreamKey: boolean
  blockedReason: string | null
}

export interface StreamKeyCredentials {
  channelId: string
  serverUrl: string
  streamKey: string
}

export interface StreamChannelSummary {
  id: string
  name: string
  slug: string
  avatarUrl: string | null
}

export interface StreamCategorySummary {
  id: string
  name: string
  slug: string
}

export interface LiveStreamItem {
  id: string
  title: string
  status: StreamStatus | string
  startedAt: string | null
  thumbnailUrl: string | null
  thumbnailUpdatedAt: string | null
  channelId: string
  categoryId: string
  channel: StreamChannelSummary | null
  category: StreamCategorySummary | null
  currentViewerCount: number | null
}

export interface StreamDiscoveryResponse {
  content: LiveStreamItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface StreamListParams {
  page?: number
  size?: number
  categoryId?: string | null
  categoryIds?: string[] | null
}

export interface StreamDetailsChannelSummary {
  id: string
  name: string
  avatarUrl: string | null
}

export interface StreamDetailsCategorySummary {
  id: string
  name: string
}

export interface StreamViewerDetails {
  id: string
  channelId: string
  categoryId: string
  channel: StreamDetailsChannelSummary | null
  category: StreamDetailsCategorySummary | null
}

