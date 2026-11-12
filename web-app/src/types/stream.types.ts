export type StreamStatus = 'READY' | 'LIVE' | 'RECONNECTING' | 'ENDED'

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
  currentViewerCount: number | null
}

export interface StreamSession extends StreamMetadataFields {
  publishReady: boolean
}

export interface PublishCredentials {
  sessionId: string
  serverUrl: string
  streamKey: string
  publishExpiresAt: string
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

export interface CreateStreamRequest extends StreamDetails {
  requestId: string
}

export interface CreateStreamResponse {
  session: StreamSession
  publishCredentials: PublishCredentials
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

