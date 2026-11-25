export type TransactionStatus = 'PENDING' | 'PAID' | 'FAILED' | 'CANCELLED'

export interface WalletBalance {
  walletId: string
  userId: string
  balance: number
}

export interface StreamerWallet {
  walletId: string
  streamerId: string
  availableBalance: number
}

export interface DepositPackage {
  packageCode: string
  displayName: string
  amountVnd: number
  coinAmount: number
}

export interface CreateVnpayDepositRequest {
  packageCode: string
}

export interface CreateVnpayDepositResponse {
  paymentId: string
  orderCode: number
  packageCode: string
  amountVnd: number
  coinAmount: number
  status: TransactionStatus
  expiresAt: string
  paymentUrl: string
}

export interface DepositStatusResponse {
  paymentId: string
  orderCode: number
  packageCode: string
  amountVnd: number
  coinAmount: number
  status: TransactionStatus
  providerTransactionNo: string | null
  providerResponseCode: string | null
  walletBalance: number
  paidAt: string | null
  createdAt: string
  expiresAt: string
}

export interface VirtualGift {
  id: string
  name: string
  imageUrl: string
  coinPrice: number
}

export interface DonateGiftRequest {
  streamId: string
  giftId: string
  idempotencyKey: string
}

export interface DonateGiftResponse {
  donationId: string
  streamId: string
  channelId: string
  streamerId: string
  giftId: string
  giftName: string
  giftImageUrl: string
  coinPrice: number
  remainingBalance: number
  createdAt: string
}
