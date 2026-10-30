export type StreamStatus = 'READY' | 'LIVE' | 'RECONNECTING' | 'ENDED'

export interface StreamMetadata {
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

export interface StreamSession extends StreamMetadata {
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
