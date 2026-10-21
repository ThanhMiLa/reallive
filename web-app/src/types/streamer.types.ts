export interface BecomeStreamerResponse {
  streamerId: string
  userId: string
  status: 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'BANNED'
  roles: string[]
}
