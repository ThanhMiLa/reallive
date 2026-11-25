import apiClient from './client'
import type { ApiResponse } from '@/types/auth.types'
import type {
  CreateVnpayDepositRequest,
  CreateVnpayDepositResponse,
  DepositPackage,
  DepositStatusResponse,
  StreamerWallet,
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
}
