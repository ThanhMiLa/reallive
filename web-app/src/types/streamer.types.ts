export interface BecomeStreamerResponse {
  streamerId: string
  userId: string
  status: 'ACTIVE' | 'SUSPENDED' | 'BANNED'
  roles: string[]
}
