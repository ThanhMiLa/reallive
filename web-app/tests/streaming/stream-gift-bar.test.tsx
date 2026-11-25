import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { StreamGiftBar } from '@/components/stream/StreamGiftBar'
import { useAuthStore } from '@/stores/auth.store'
import type { DonateGiftResponse, VirtualGift, WalletBalance } from '@/types/payment.types'

const mocks = vi.hoisted(() => ({
  getVirtualGifts: vi.fn(),
  getWalletBalance: vi.fn(),
  donateGift: vi.fn(),
}))

vi.mock('@/api/payment.api', () => ({
  paymentApi: {
    getVirtualGifts: mocks.getVirtualGifts,
    getWalletBalance: mocks.getWalletBalance,
    donateGift: mocks.donateGift,
  },
}))

const STREAM_ID = '462c912c-ee66-4477-9852-bed13d377297'
const SECOND_STREAM_ID = '55555555-ee66-4477-9852-bed13d377297'
const VIEWER_ID = 'viewer-1'
const STREAMER_ID = 'streamer-1'

const sampleGifts: VirtualGift[] = [
  { id: 'gift-rose', name: 'Rose', imageUrl: '/gifts/rose.svg', coinPrice: 1 },
  { id: 'gift-heart', name: 'Heart', imageUrl: '/gifts/heart.svg', coinPrice: 5 },
  { id: 'gift-galaxy', name: 'Galaxy', imageUrl: '/gifts/galaxy.svg', coinPrice: 1000 },
]

function makeHttpError(status: number, code?: number, message = 'Request failed') {
  return new AxiosError(
    message,
    'ERR_BAD_RESPONSE',
    {} as InternalAxiosRequestConfig,
    null,
    {
      status,
      statusText: message,
      data: code !== undefined ? { status, code, message } : { status, message },
      headers: {},
      config: {} as InternalAxiosRequestConfig,
    },
  )
}

function makeNetworkError(message = 'Network Error') {
  return new AxiosError(message, 'ERR_NETWORK', {} as InternalAxiosRequestConfig)
}

let client: QueryClient

function renderGiftBar(props: Partial<Parameters<typeof StreamGiftBar>[0]> = {}) {
  client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  const ui = (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <StreamGiftBar
          sessionId={STREAM_ID}
          status="LIVE"
          ownerUserId={STREAMER_ID}
          {...props}
        />
      </MemoryRouter>
    </QueryClientProvider>
  )

  const result = render(ui)
  return {
    ...result,
    rerenderBar: (nextProps: Partial<Parameters<typeof StreamGiftBar>[0]>) =>
      result.rerender(
        <QueryClientProvider client={client}>
          <MemoryRouter>
            <StreamGiftBar
              sessionId={STREAM_ID}
              status="LIVE"
              ownerUserId={STREAMER_ID}
              {...nextProps}
            />
          </MemoryRouter>
        </QueryClientProvider>,
      ),
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  useAuthStore.setState({
    isCheckingAuth: false,
    isAuthenticated: true,
    isAuthModalOpen: false,
    user: {
      userId: VIEWER_ID,
      displayName: 'Viewer One',
      email: 'viewer@example.com',
      emailVerified: true,
      roles: ['USER'],
      avatarUrl: null,
      dob: null,
    },
  })
  mocks.getVirtualGifts.mockResolvedValue(sampleGifts)
  mocks.getWalletBalance.mockResolvedValue({
    walletId: 'wallet-1',
    userId: VIEWER_ID,
    balance: 250,
  } satisfies WalletBalance)
})

afterEach(() => {
  vi.useRealTimers()
  cleanup()
  client?.clear()
})

describe('StreamGiftBar', () => {
  it('renders gift tiles, coin balance, Recharge link, and falls back when gift image fails', async () => {
    renderGiftBar()

    const roseButton = await screen.findByRole('button', { name: /Send Rose for 1 Coin/i })
    expect(within(roseButton).getByText('Rose')).toBeTruthy()
    expect(within(roseButton).getByText('1')).toBeTruthy()
    expect(screen.getByAltText('Rose').getAttribute('src')).toBe('/gifts/rose.svg')

    expect(await screen.findByText('250')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Recharge' }).getAttribute('href')).toBe('/wallet')

    fireEvent.error(screen.getByAltText('Rose'))
    await waitFor(() => {
      expect(within(roseButton).queryByAltText('Rose')).toBeNull()
      expect(roseButton.querySelector('[data-gift-fallback="true"]')).not.toBeNull()
    })
  })

  it('sends 1-click gift, ignores duplicate clicks in flight, updates wallet cache, and clears celebration after 3s', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let resolveDonation!: (value: DonateGiftResponse) => void
    mocks.donateGift.mockImplementation(
      () =>
        new Promise<DonateGiftResponse>((resolve) => {
          resolveDonation = resolve
        }),
    )

    renderGiftBar()
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')

    const roseButton = await screen.findByRole('button', { name: /Send Rose for 1 Coin/i })
    const heartButton = screen.getByRole('button', { name: /Send Heart for 5 Coins/i })
    await screen.findByText('250')

    fireEvent.click(roseButton)
    fireEvent.click(heartButton)

    expect(mocks.donateGift).toHaveBeenCalledTimes(1)
    expect(mocks.donateGift).toHaveBeenCalledWith({
      streamId: STREAM_ID,
      giftId: 'gift-rose',
      idempotencyKey: expect.any(String),
    })
    expect((roseButton as HTMLButtonElement).disabled).toBe(true)

    let resolveWalletRefetch!: (value: WalletBalance) => void
    mocks.getWalletBalance.mockImplementationOnce(
      () =>
        new Promise<WalletBalance>((resolve) => {
          resolveWalletRefetch = resolve
        }),
    )

    await act(async () => {
      resolveDonation({
        donationId: 'don-1',
        streamId: STREAM_ID,
        channelId: 'chan-1',
        streamerId: STREAMER_ID,
        giftId: 'gift-rose',
        giftName: 'Rose',
        giftImageUrl: '/gifts/rose.svg',
        coinPrice: 1,
        remainingBalance: 249,
        createdAt: '2026-11-25T10:00:00Z',
      })
      await Promise.resolve()
    })

    // Cache is updated immediately via setQueryData before refetch resolves
    expect(client.getQueryData<WalletBalance>(['wallet', VIEWER_ID])?.balance).toBe(249)
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['wallet', VIEWER_ID], exact: true })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: ['wallet', VIEWER_ID, 'create'],
      exact: true,
    })

    await act(async () => {
      resolveWalletRefetch({
        walletId: 'wallet-1',
        userId: VIEWER_ID,
        balance: 249,
      })
      await Promise.resolve()
    })

    expect(screen.getByText('Sent Rose - 1 Coin')).toBeTruthy()
    expect(screen.getByText('249')).toBeTruthy()

    await act(async () => {
      vi.advanceTimersByTime(3000)
    })

    expect(screen.queryByText('Sent Rose - 1 Coin')).toBeNull()
  })

  it('preserves idempotencyKey across retryable 5xx/network errors and sends a fresh key after Dismiss', async () => {
    mocks.donateGift
      .mockRejectedValueOnce(makeHttpError(503, 1017, 'Stream service unavailable'))
      .mockRejectedValueOnce(makeNetworkError())
      .mockResolvedValueOnce({
        donationId: 'don-retry-ok',
        streamId: STREAM_ID,
        channelId: 'chan-1',
        streamerId: STREAMER_ID,
        giftId: 'gift-heart',
        giftName: 'Heart',
        giftImageUrl: '/gifts/heart.svg',
        coinPrice: 5,
        remainingBalance: 245,
        createdAt: '2026-11-25T10:01:00Z',
      })

    renderGiftBar()
    const heartButton = await screen.findByRole('button', { name: /Send Heart for 5 Coins/i })
    await screen.findByText('250')

    fireEvent.click(heartButton)
    const firstAlert = await screen.findByRole('alert')
    expect(within(firstAlert).getByText('Stream service unavailable')).toBeTruthy()
    const firstKey = mocks.donateGift.mock.calls[0][0].idempotencyKey

    fireEvent.click(within(firstAlert).getByRole('button', { name: 'Retry' }))
    const secondAlert = await screen.findByRole('alert')
    expect(within(secondAlert).getByText('Network error while sending gift. Please try again.')).toBeTruthy()
    expect(mocks.donateGift).toHaveBeenCalledTimes(2)
    expect(mocks.donateGift.mock.calls[1][0].idempotencyKey).toBe(firstKey)

    mocks.getWalletBalance.mockResolvedValueOnce({
      walletId: 'wallet-1',
      userId: VIEWER_ID,
      balance: 245,
    })
    fireEvent.click(within(secondAlert).getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('Sent Heart - 5 Coins')).toBeTruthy()
    expect(mocks.donateGift).toHaveBeenCalledTimes(3)
    expect(mocks.donateGift.mock.calls[2][0].idempotencyKey).toBe(firstKey)

    // Next failed request + Dismiss generates a new idempotencyKey on a subsequent send
    mocks.donateGift.mockRejectedValueOnce(makeHttpError(503, 1017, 'Try later'))
    fireEvent.click(heartButton)
    const dismissAlert = await screen.findByRole('alert')
    const failedKey = mocks.donateGift.mock.calls[3][0].idempotencyKey
    expect(failedKey).not.toBe(firstKey)

    fireEvent.click(within(dismissAlert).getByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByRole('alert')).toBeNull()

    mocks.getWalletBalance.mockResolvedValueOnce({
      walletId: 'wallet-1',
      userId: VIEWER_ID,
      balance: 240,
    })
    mocks.donateGift.mockResolvedValueOnce({
      donationId: 'don-after-dismiss',
      streamId: STREAM_ID,
      channelId: 'chan-1',
      streamerId: STREAMER_ID,
      giftId: 'gift-heart',
      giftName: 'Heart',
      giftImageUrl: '/gifts/heart.svg',
      coinPrice: 5,
      remainingBalance: 240,
      createdAt: '2026-11-25T10:02:00Z',
    })
    fireEvent.click(heartButton)
    await screen.findByText('240')
    expect(mocks.donateGift.mock.calls[4][0].idempotencyKey).not.toBe(failedKey)
  })

  it('clears pendingAttempt on definitive 4xx and handles 1015 STREAM_NOT_LIVE and 1020 VIRTUAL_GIFT_INACTIVE', async () => {
    const onStreamNotLive = vi.fn()
    mocks.donateGift
      .mockRejectedValueOnce(makeHttpError(409, 1015, 'Stream is not live'))
      .mockRejectedValueOnce(makeHttpError(409, 1020, 'Virtual gift is no longer available'))

    renderGiftBar({ onStreamNotLive })
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')

    const roseButton = await screen.findByRole('button', { name: /Send Rose for 1 Coin/i })
    await screen.findByText('250')

    fireEvent.click(roseButton)
    const liveAlert = await screen.findByRole('alert')
    expect(within(liveAlert).getByText('This stream is no longer live.')).toBeTruthy()
    expect(within(liveAlert).queryByRole('button', { name: 'Retry' })).toBeNull()
    expect(onStreamNotLive).toHaveBeenCalledTimes(1)

    fireEvent.click(roseButton)
    const giftAlert = await screen.findByRole('alert')
    expect(within(giftAlert).getByText('Virtual gift is no longer available.')).toBeTruthy()
    expect(within(giftAlert).queryByRole('button', { name: 'Retry' })).toBeNull()
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['virtual-gifts'] })
  })

  it('handles 404 WALLET_NOT_FOUND as 0 Coins without an error alert and shows Recharge prompt on click', async () => {
    mocks.getWalletBalance.mockRejectedValue(makeHttpError(404, 1006, 'Wallet not found'))

    renderGiftBar()

    await screen.findByText('0')
    expect(screen.queryByRole('alert')).toBeNull()

    const roseButton = await screen.findByRole('button', { name: /Send Rose for 1 Coin/i })
    fireEvent.click(roseButton)

    expect(mocks.donateGift).not.toHaveBeenCalled()
    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText(/Insufficient coins to send Rose/i)).toBeTruthy()
    expect(within(alert).getByRole('link', { name: /Recharge/i }).getAttribute('href')).toBe('/wallet')
  })

  it('shows Recharge prompt on client-side insufficient balance and handles backend 1021 cleanly', async () => {
    renderGiftBar()

    const galaxyButton = await screen.findByRole('button', { name: /Send Galaxy for 1,000 Coins/i })
    await screen.findByText('250')

    fireEvent.click(galaxyButton)
    expect(mocks.donateGift).not.toHaveBeenCalled()
    const clientAlert = await screen.findByRole('alert')
    expect(within(clientAlert).getByText(/Insufficient coins to send Galaxy/i)).toBeTruthy()
    expect(within(clientAlert).getByRole('link', { name: /Recharge/i })).toBeTruthy()

    // Simulate balance wasn't loaded yet, backend returns 1021
    mocks.getWalletBalance.mockRejectedValueOnce(makeHttpError(500, undefined, 'Temp error'))
    mocks.donateGift.mockRejectedValueOnce(makeHttpError(400, 1021, 'Insufficient coin balance'))
    cleanup()
    client.clear()

    renderGiftBar()
    const roseButton = await screen.findByRole('button', { name: /Send Rose for 1 Coin/i })
    fireEvent.click(roseButton)
    const backendAlert = await screen.findByRole('alert')
    expect(within(backendAlert).getByText('Insufficient coin balance.')).toBeTruthy()
    expect(within(backendAlert).getByRole('link', { name: /Recharge/i })).toBeTruthy()
  })

  it('disables gift tiles when stream is not LIVE or viewer owns the stream', async () => {
    const { rerenderBar } = renderGiftBar({ status: 'ENDED' })

    const roseEnded = await screen.findByRole('button', { name: /Send Rose for 1 Coin/i })
    expect((roseEnded as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByText('Gifts are available while the stream is live.')).toBeTruthy()
    fireEvent.click(roseEnded)
    expect(mocks.donateGift).not.toHaveBeenCalled()

    rerenderBar({ status: 'LIVE', ownerUserId: VIEWER_ID })
    const roseSelf = await screen.findByRole('button', { name: /Send Rose for 1 Coin/i })
    expect((roseSelf as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByText('You cannot send gifts to your own stream.')).toBeTruthy()
    fireEvent.click(roseSelf)
    expect(mocks.donateGift).not.toHaveBeenCalled()
  })

  it('shows non-blocking Retry state when gift list fails to load and resets pending state when streamId changes', async () => {
    mocks.getVirtualGifts
      .mockRejectedValueOnce(makeHttpError(503, undefined, 'Unavailable'))
      .mockResolvedValue(sampleGifts)

    const { rerenderBar } = renderGiftBar()

    expect(await screen.findByText('Unable to load gifts.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    const roseButton = await screen.findByRole('button', { name: /Send Rose for 1 Coin/i })
    await screen.findByText('250')

    mocks.donateGift.mockRejectedValueOnce(makeNetworkError())
    fireEvent.click(roseButton)
    expect(await screen.findByRole('alert')).toBeTruthy()

    rerenderBar({ sessionId: SECOND_STREAM_ID })
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
