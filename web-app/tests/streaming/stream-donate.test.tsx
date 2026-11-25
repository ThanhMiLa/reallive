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
import { StreamDonateControl } from '@/components/stream/StreamDonateControl'
import { useAuthStore } from '@/stores/auth.store'
import type {
  DonateGiftResponse,
  VirtualGift,
  WalletBalance,
} from '@/types/payment.types'

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

function renderDonateControl(
  props: Partial<Parameters<typeof StreamDonateControl>[0]> = {},
) {
  client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  const result = render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <StreamDonateControl
          sessionId={STREAM_ID}
          status="LIVE"
          ownerUserId={STREAMER_ID}
          {...props}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )

  return {
    ...result,
    rerenderControl: (
      nextProps: Partial<Parameters<typeof StreamDonateControl>[0]>,
    ) =>
      result.rerender(
        <QueryClientProvider client={client}>
          <MemoryRouter>
            <StreamDonateControl
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

describe('StreamDonateControl', () => {
  it('opens popup on Donate click showing gift icons, names, prices, and user balance without a Buy button until a gift is selected', async () => {
    renderDonateControl()

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(mocks.getVirtualGifts).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Donate' }))

    const dialog = await screen.findByRole('dialog')
    const roseButton = await within(dialog).findByRole('button', {
      name: /Rose, 1 Coin/i,
    })

    expect(within(roseButton).getByText('Rose')).toBeTruthy()
    expect(within(roseButton).getByText('1')).toBeTruthy()
    expect(within(dialog).getByAltText('Rose').getAttribute('src')).toBe(
      '/gifts/rose.svg',
    )
    expect(await within(dialog).findByText('250')).toBeTruthy()
    expect(within(dialog).getByRole('link', { name: 'Recharge' }).getAttribute('href')).toBe(
      '/wallet',
    )
    expect(within(dialog).getByText('Select a gift')).toBeTruthy()
    expect(within(dialog).queryByRole('button', { name: 'Buy' })).toBeNull()

    fireEvent.error(within(dialog).getByAltText('Rose'))
    await waitFor(() => {
      expect(within(roseButton).queryByAltText('Rose')).toBeNull()
      expect(roseButton.querySelector('[data-gift-fallback="true"]')).not.toBeNull()
    })
  })

  it('focuses gift without donating on click, then donates on Buy click and updates wallet cache', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let resolveDonation!: (value: DonateGiftResponse) => void
    mocks.donateGift.mockImplementation(
      () =>
        new Promise<DonateGiftResponse>((resolve) => {
          resolveDonation = resolve
        }),
    )

    renderDonateControl()
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')

    fireEvent.click(screen.getByRole('button', { name: 'Donate' }))
    const dialog = await screen.findByRole('dialog')
    const roseButton = await within(dialog).findByRole('button', {
      name: /Rose, 1 Coin/i,
    })

    // Selecting gift focuses it and reveals Buy without calling donateGift
    fireEvent.click(roseButton)
    expect(roseButton.getAttribute('aria-pressed')).toBe('true')
    expect(mocks.donateGift).not.toHaveBeenCalled()

    const buyButton = within(dialog).getByRole('button', { name: 'Buy' })
    let resolveWalletRefetch!: (value: WalletBalance) => void
    mocks.getWalletBalance.mockImplementationOnce(
      () =>
        new Promise<WalletBalance>((resolve) => {
          resolveWalletRefetch = resolve
        }),
    )

    fireEvent.click(buyButton)
    expect(mocks.donateGift).toHaveBeenCalledTimes(1)
    expect(mocks.donateGift).toHaveBeenCalledWith({
      streamId: STREAM_ID,
      giftId: 'gift-rose',
      idempotencyKey: expect.any(String),
    })

    // Duplicate click while in flight is ignored without flashing a loading spinner
    fireEvent.click(buyButton)
    expect(mocks.donateGift).toHaveBeenCalledTimes(1)
    expect(dialog.querySelector('svg.animate-spin')).toBeNull()

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

    expect(client.getQueryData<WalletBalance>(['wallet', VIEWER_ID])?.balance).toBe(249)
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: ['wallet', VIEWER_ID],
      exact: true,
    })
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

    expect(within(dialog).getByText('Sent Rose - 1 Coin')).toBeTruthy()
    expect(within(dialog).getByText('x1')).toBeTruthy()
    expect(within(dialog).getByText('249')).toBeTruthy()

    await act(async () => {
      vi.advanceTimersByTime(3000)
    })

    expect(within(dialog).queryByText('Sent Rose - 1 Coin')).toBeNull()
  })

  it('increments combo count (x1 -> x2 -> x3) on repeated Buy clicks for the same gift, resets on gift switch or timeout', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })

    mocks.donateGift
      .mockResolvedValueOnce({
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
      .mockResolvedValueOnce({
        donationId: 'don-2',
        streamId: STREAM_ID,
        channelId: 'chan-1',
        streamerId: STREAMER_ID,
        giftId: 'gift-rose',
        giftName: 'Rose',
        giftImageUrl: '/gifts/rose.svg',
        coinPrice: 1,
        remainingBalance: 248,
        createdAt: '2026-11-25T10:00:01Z',
      })
      .mockResolvedValueOnce({
        donationId: 'don-3',
        streamId: STREAM_ID,
        channelId: 'chan-1',
        streamerId: STREAMER_ID,
        giftId: 'gift-rose',
        giftName: 'Rose',
        giftImageUrl: '/gifts/rose.svg',
        coinPrice: 1,
        remainingBalance: 247,
        createdAt: '2026-11-25T10:00:02Z',
      })
      .mockResolvedValueOnce({
        donationId: 'don-4',
        streamId: STREAM_ID,
        channelId: 'chan-1',
        streamerId: STREAMER_ID,
        giftId: 'gift-heart',
        giftName: 'Heart',
        giftImageUrl: '/gifts/heart.svg',
        coinPrice: 5,
        remainingBalance: 242,
        createdAt: '2026-11-25T10:00:03Z',
      })
      .mockResolvedValueOnce({
        donationId: 'don-5',
        streamId: STREAM_ID,
        channelId: 'chan-1',
        streamerId: STREAMER_ID,
        giftId: 'gift-heart',
        giftName: 'Heart',
        giftImageUrl: '/gifts/heart.svg',
        coinPrice: 5,
        remainingBalance: 237,
        createdAt: '2026-11-25T10:00:10Z',
      })

    renderDonateControl()
    fireEvent.click(screen.getByRole('button', { name: 'Donate' }))
    const dialog = await screen.findByRole('dialog')

    fireEvent.click(await within(dialog).findByRole('button', { name: /Rose, 1 Coin/i }))
    const buyButton = within(dialog).getByRole('button', { name: 'Buy' })

    // 1st Rose -> x1
    mocks.getWalletBalance.mockResolvedValueOnce({ walletId: 'wallet-1', userId: VIEWER_ID, balance: 249 })
    fireEvent.click(buyButton)
    expect(await within(dialog).findByText('x1')).toBeTruthy()

    // 2nd Rose after 1.5s -> x2
    await act(async () => {
      vi.advanceTimersByTime(1500)
    })
    mocks.getWalletBalance.mockResolvedValueOnce({ walletId: 'wallet-1', userId: VIEWER_ID, balance: 248 })
    fireEvent.click(buyButton)
    expect(await within(dialog).findByText('x2')).toBeTruthy()

    // 3rd Rose after another 1.5s -> x3 (timer restarted so combo stays alive past initial 3s)
    await act(async () => {
      vi.advanceTimersByTime(1500)
    })
    mocks.getWalletBalance.mockResolvedValueOnce({ walletId: 'wallet-1', userId: VIEWER_ID, balance: 247 })
    fireEvent.click(buyButton)
    expect(await within(dialog).findByText('x3')).toBeTruthy()

    // Switching to Heart immediately clears Rose's combo badge before even buying Heart
    fireEvent.click(within(dialog).getByRole('button', { name: /Heart, 5 Coins/i }))
    expect(within(dialog).queryByText('x3')).toBeNull()
    expect(within(dialog).queryByText(/Rose x/i)).toBeNull()

    mocks.getWalletBalance.mockResolvedValueOnce({ walletId: 'wallet-1', userId: VIEWER_ID, balance: 242 })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Buy' }))
    expect(await within(dialog).findByText('Sent Heart - 5 Coins')).toBeTruthy()
    expect(within(dialog).getByText('x1')).toBeTruthy()

    // Stopping for 3s clears combo, next send starts at x1 again
    await act(async () => {
      vi.advanceTimersByTime(3000)
    })
    expect(within(dialog).queryByText('Sent Heart - 5 Coins')).toBeNull()

    mocks.getWalletBalance.mockResolvedValueOnce({ walletId: 'wallet-1', userId: VIEWER_ID, balance: 237 })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Buy' }))
    expect(await within(dialog).findByText('x1')).toBeTruthy()
    expect(mocks.getVirtualGifts).toHaveBeenCalledTimes(1)
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

    renderDonateControl()
    fireEvent.click(screen.getByRole('button', { name: 'Donate' }))

    const dialog = await screen.findByRole('dialog')
    const heartButton = await within(dialog).findByRole('button', {
      name: /Heart, 5 Coins/i,
    })
    fireEvent.click(heartButton)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Buy' }))

    const firstAlert = await within(dialog).findByRole('alert')
    expect(within(firstAlert).getByText('Stream service unavailable')).toBeTruthy()
    const firstKey = mocks.donateGift.mock.calls[0][0].idempotencyKey

    fireEvent.click(within(firstAlert).getByRole('button', { name: 'Retry' }))
    const secondAlert = await within(dialog).findByRole('alert')
    expect(
      within(secondAlert).getByText('Network error while sending gift. Please try again.'),
    ).toBeTruthy()
    expect(mocks.donateGift).toHaveBeenCalledTimes(2)
    expect(mocks.donateGift.mock.calls[1][0].idempotencyKey).toBe(firstKey)

    mocks.getWalletBalance.mockResolvedValueOnce({
      walletId: 'wallet-1',
      userId: VIEWER_ID,
      balance: 245,
    })
    fireEvent.click(within(secondAlert).getByRole('button', { name: 'Retry' }))
    expect(await within(dialog).findByText('Sent Heart - 5 Coins')).toBeTruthy()
    expect(mocks.donateGift).toHaveBeenCalledTimes(3)
    expect(mocks.donateGift.mock.calls[2][0].idempotencyKey).toBe(firstKey)

    // Fail, Dismiss, then Buy again -> fresh idempotencyKey
    mocks.donateGift.mockRejectedValueOnce(makeHttpError(503, 1017, 'Try later'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Buy' }))
    const dismissAlert = await within(dialog).findByRole('alert')
    const failedKey = mocks.donateGift.mock.calls[3][0].idempotencyKey
    expect(failedKey).not.toBe(firstKey)

    fireEvent.click(within(dismissAlert).getByRole('button', { name: 'Dismiss' }))
    expect(within(dialog).queryByRole('alert')).toBeNull()

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
    fireEvent.click(within(dialog).getByRole('button', { name: 'Buy' }))
    await within(dialog).findByText('240')
    expect(mocks.donateGift.mock.calls[4][0].idempotencyKey).not.toBe(failedKey)
  })

  it('clears pendingAttempt on definitive 4xx and handles 1015 STREAM_NOT_LIVE and 1020 VIRTUAL_GIFT_INACTIVE', async () => {
    const onStreamNotLive = vi.fn()
    mocks.donateGift
      .mockRejectedValueOnce(makeHttpError(409, 1015, 'Stream is not live'))
      .mockRejectedValueOnce(makeHttpError(409, 1020, 'Virtual gift is no longer available'))

    renderDonateControl({ onStreamNotLive })
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')

    fireEvent.click(screen.getByRole('button', { name: 'Donate' }))
    const dialog = await screen.findByRole('dialog')
    const roseButton = await within(dialog).findByRole('button', {
      name: /Rose, 1 Coin/i,
    })
    fireEvent.click(roseButton)

    fireEvent.click(within(dialog).getByRole('button', { name: 'Buy' }))
    const liveAlert = await within(dialog).findByRole('alert')
    expect(within(liveAlert).getByText('This stream is no longer live.')).toBeTruthy()
    expect(within(liveAlert).queryByRole('button', { name: 'Retry' })).toBeNull()
    expect(onStreamNotLive).toHaveBeenCalledTimes(1)

    fireEvent.click(within(dialog).getByRole('button', { name: 'Buy' }))
    const giftAlert = await within(dialog).findByRole('alert')
    expect(within(giftAlert).getByText('Virtual gift is no longer available.')).toBeTruthy()
    expect(within(giftAlert).queryByRole('button', { name: 'Retry' })).toBeNull()
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['virtual-gifts'] })
  })

  it('treats 404 WALLET_NOT_FOUND as 0 Coins without an error alert and prompts Recharge on Buy', async () => {
    mocks.getWalletBalance.mockRejectedValue(makeHttpError(404, 1006, 'Wallet not found'))

    renderDonateControl()
    fireEvent.click(screen.getByRole('button', { name: 'Donate' }))

    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText('0')
    expect(within(dialog).queryByRole('alert')).toBeNull()

    fireEvent.click(await within(dialog).findByRole('button', { name: /Rose, 1 Coin/i }))
    expect(within(dialog).queryByRole('alert')).toBeNull()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Buy' }))
    expect(mocks.donateGift).not.toHaveBeenCalled()
    const alert = await within(dialog).findByRole('alert')
    expect(within(alert).getByText(/Insufficient coins to send Rose/i)).toBeTruthy()
    expect(within(alert).getByRole('link', { name: /Recharge/i }).getAttribute('href')).toBe(
      '/wallet',
    )
  })

  it('shows Recharge prompt on client-side insufficient balance and handles backend 1021 cleanly', async () => {
    renderDonateControl()
    fireEvent.click(screen.getByRole('button', { name: 'Donate' }))

    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText('250')
    fireEvent.click(
      await within(dialog).findByRole('button', { name: /Galaxy, 1,000 Coins/i }),
    )
    fireEvent.click(within(dialog).getByRole('button', { name: 'Buy' }))

    expect(mocks.donateGift).not.toHaveBeenCalled()
    const clientAlert = await within(dialog).findByRole('alert')
    expect(within(clientAlert).getByText(/Insufficient coins to send Galaxy/i)).toBeTruthy()
    expect(within(clientAlert).getByRole('link', { name: /Recharge/i })).toBeTruthy()

    // Fallback when balance isn't loaded yet and backend rejects with 1021
    mocks.getWalletBalance.mockRejectedValueOnce(makeHttpError(500, undefined, 'Temp error'))
    mocks.donateGift.mockRejectedValueOnce(makeHttpError(400, 1021, 'Insufficient coin balance'))
    cleanup()
    client.clear()

    renderDonateControl()
    fireEvent.click(screen.getByRole('button', { name: 'Donate' }))
    const retryDialog = await screen.findByRole('dialog')
    fireEvent.click(
      await within(retryDialog).findByRole('button', { name: /Rose, 1 Coin/i }),
    )
    fireEvent.click(within(retryDialog).getByRole('button', { name: 'Buy' }))

    const backendAlert = await within(retryDialog).findByRole('alert')
    expect(within(backendAlert).getByText('Insufficient coin balance.')).toBeTruthy()
    expect(within(backendAlert).getByRole('link', { name: /Recharge/i })).toBeTruthy()
  })

  it('disables Donate button when stream is not LIVE or viewer owns the stream, and closes popover cleanly on Escape, outside click, or streamId change', async () => {
    const { rerenderControl } = renderDonateControl({ status: 'ENDED' })
    expect((screen.getByRole('button', { name: 'Donate' }) as HTMLButtonElement).disabled).toBe(true)

    rerenderControl({ status: 'LIVE', ownerUserId: VIEWER_ID })
    expect((screen.getByRole('button', { name: 'Donate' }) as HTMLButtonElement).disabled).toBe(true)

    rerenderControl({ status: 'LIVE', ownerUserId: STREAMER_ID })
    const donateBtn = screen.getByRole('button', { name: 'Donate' }) as HTMLButtonElement
    expect(donateBtn.disabled).toBe(false)

    fireEvent.click(donateBtn)
    const dialog = await screen.findByRole('dialog')
    expect(dialog.className).not.toContain('fixed inset-0')
    expect(dialog.className).toContain('sm:right-full')
    expect(dialog.className).toContain('sm:top-1/2')
    expect(dialog.className).toContain('sm:-translate-y-1/2')

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()

    fireEvent.click(donateBtn)
    expect(await screen.findByRole('dialog')).toBeTruthy()

    fireEvent.mouseDown(document.body)
    expect(screen.queryByRole('dialog')).toBeNull()

    fireEvent.click(donateBtn)
    expect(await screen.findByRole('dialog')).toBeTruthy()

    rerenderControl({ sessionId: SECOND_STREAM_ID })
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
