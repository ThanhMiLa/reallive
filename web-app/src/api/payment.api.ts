import apiClient from './client'
import type { ApiResponse } from '@/types/auth.types'
import type {
  CreateVnpayDepositRequest,
  CreateVnpayDepositResponse,
  DepositPackage,
  DepositStatusResponse,
  DonateGiftRequest,
  DonateGiftResponse,
  StreamerWallet,
  VirtualGift,
  WalletBalance,
} from '@/types/payment.types'

export const paymentApi = {
  createWallet: async (): Promise<WalletBalance> => {
    const response = await apiClient.post<ApiResponse<WalletBalance>>(
      '/payment/api/wallets/me',
    )
    return response.data.result
  },

  getWalletBalance: async (): Promise<WalletBalance> => {
    const response = await apiClient.get<ApiResponse<WalletBalance>>(
      '/payment/api/wallets/me',
    )
    return response.data.result
  },

  createStreamerWallet: async (): Promise<StreamerWallet> => {
    const response = await apiClient.post<ApiResponse<StreamerWallet>>(
      '/payment/api/streamer-wallets/me',
    )
    return response.data.result
  },

  getStreamerWallet: async (): Promise<StreamerWallet> => {
    const response = await apiClient.get<ApiResponse<StreamerWallet>>(
      '/payment/api/streamer-wallets/me',
    )
    return response.data.result
  },

  getDepositPackages: async (): Promise<DepositPackage[]> => {
    const response = await apiClient.get<ApiResponse<DepositPackage[]>>(
      '/payment/api/deposits/packages',
    )
    return response.data.result
  },

  createVnpayDeposit: async (
    packageCode: string,
  ): Promise<CreateVnpayDepositResponse> => {
    const payload: CreateVnpayDepositRequest = { packageCode }
    const response = await apiClient.post<ApiResponse<CreateVnpayDepositResponse>>(
      '/payment/api/deposits/vnpay',
      payload,
    )
    return response.data.result
  },

  getDepositStatus: async (
    orderCode: string | number,
  ): Promise<DepositStatusResponse> => {
    const response = await apiClient.get<ApiResponse<DepositStatusResponse>>(
      `/payment/api/deposits/${encodeURIComponent(String(orderCode))}`,
    )
    return response.data.result
  },

  getVirtualGifts: async (signal?: AbortSignal): Promise<VirtualGift[]> => {
    const response = await apiClient.get<ApiResponse<VirtualGift[]>>(
      '/payment/api/gifts',
      { signal },
    )
    return response.data.result
  },

  donateGift: async (
    payload: DonateGiftRequest,
  ): Promise<DonateGiftResponse> => {
    const response = await apiClient.post<ApiResponse<DonateGiftResponse>>(
      '/payment/api/donations',
      payload,
    )
    return response.data.result
  },
}
