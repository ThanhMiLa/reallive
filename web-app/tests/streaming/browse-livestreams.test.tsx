import { type ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { BrowseLivestreamsTab } from '@/pages/browse/tabs/BrowseLivestreamsTab'
import { BrowseCategoriesTab } from '@/pages/browse/tabs/BrowseCategoriesTab'
import { CategoryDetailPage } from '@/pages/browse/CategoryDetailPage'
import { useAuthStore } from '@/stores/auth.store'
import type { CategoryItem } from '@/types/channel.types'
import type {
  LiveStreamItem,
  StreamDiscoveryResponse,
} from '@/types/stream.types'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  getCategories: vi.fn(),
}))

vi.mock('@/api/stream.api', () => ({
  streamApi: {
    list: mocks.list,
  },
}))

vi.mock('@/api/channel.api', () => ({
  channelApi: {
    getCategories: mocks.getCategories,
  },
}))

const CAT_GAMING = '00000000-0000-4000-8000-000000000001'
const CAT_MUSIC = '00000000-0000-4000-8000-000000000002'

const mockCategories: CategoryItem[] = [
  {
    id: CAT_GAMING,
    name: 'Counter-Strike 2',
    slug: 'counter-strike-2',
    thumbnailUrl: 'https://cdn.example.com/cat-cs2.jpg',
  },
  {
    id: CAT_MUSIC,
    name: 'Music & Audio',
    slug: 'music-audio',
    thumbnailUrl: null,
  },
]

function makeStream(overrides: Partial<LiveStreamItem> = {}): LiveStreamItem {
  const id = overrides.id ?? '10000000-0000-4000-8000-000000000001'
  return {
    id,
    title: 'Rob Run fun lil game',
    status: 'LIVE',
    startedAt: '2026-11-07T05:00:00Z',
    thumbnailUrl: 'https://cdn.example.com/thumb-1.jpg',
    thumbnailUpdatedAt: '2026-11-07T06:00:00Z',
    currentViewerCount: 42,
    channelId: '20000000-0000-4000-8000-000000000001',
    categoryId: CAT_GAMING,
    channel: {
      id: '20000000-0000-4000-8000-000000000001',
      name: 'Ltgrunt',
      slug: 'ltgrunt',
      avatarUrl: 'https://cdn.example.com/avatar-1.jpg',
    },
    category: {
      id: CAT_GAMING,
      name: 'Rob Run',
      slug: 'rob-run',
    },
    ...overrides,
  }
}

function makePage(
  content: LiveStreamItem[],
  overrides: Partial<StreamDiscoveryResponse> = {},
): StreamDiscoveryResponse {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length > 0 ? 1 : 0,
    ...overrides,
  }
}

let client: QueryClient

function mount(
  initialEntries: string[] = ['/browse/livestreams'],
  node: ReactNode = <BrowseLivestreamsTab />,
) {
  client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={initialEntries}>{node}</MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  useAuthStore.setState({
    isCheckingAuth: false,
    isAuthenticated: true,
    isAuthModalOpen: false,
    user: {
      userId: 'viewer-1',
      displayName: 'Viewer One',
      email: 'viewer@example.com',
      emailVerified: true,
      roles: ['USER'],
      avatarUrl: null,
      dob: null,
    },
  })
  mocks.getCategories.mockResolvedValue(mockCategories)
  mocks.list.mockResolvedValue(makePage([makeStream()]))
})

afterEach(() => {
  cleanup()
  client?.clear()
})

describe('BrowseLivestreamsTab & CategoryDetailPage', () => {
  it.each([
    { count: 1234, label: '1,234' },
    { count: 0, label: '0' },
    { count: null, label: '—' },
    { count: undefined, label: '—' },
  ])('renders viewer count $count as $label on stream cards', async ({ count, label }) => {
    mocks.list.mockResolvedValue(makePage([makeStream({ currentViewerCount: count })]))

    mount()

    const badge = await screen.findByRole('group', { name: 'Current viewers' })
    expect(badge.textContent).toBe(`${label} viewers`)
    expect(badge.closest('a')?.getAttribute('href')).toBe(
      '/watch/10000000-0000-4000-8000-000000000001',
    )
  })

  it('keeps counts independent and replaces stale counts when the list refreshes', async () => {
    mocks.list.mockResolvedValueOnce(makePage([
      makeStream({ id: 's-active', currentViewerCount: 25 }),
      makeStream({ id: 's-empty', currentViewerCount: 0 }),
    ]))
    mount()

    const badges = await screen.findAllByRole('group', { name: 'Current viewers' })
    expect(badges[0].textContent).toBe('25 viewers')
    expect(badges[1].textContent).toBe('0 viewers')

    mocks.list.mockResolvedValueOnce(makePage([
      makeStream({ id: 's-active', currentViewerCount: null }),
      makeStream({ id: 's-empty', currentViewerCount: 3 }),
    ]))
    await client.invalidateQueries({ queryKey: ['streams', 'live'] })

    await waitFor(() => {
      const refreshed = screen.getAllByRole('group', { name: 'Current viewers' })
      expect(refreshed[0].textContent).toBe('— viewers')
      expect(refreshed[1].textContent).toBe('3 viewers')
      expect(screen.queryByText('25 viewers')).toBeNull()
    })
  })

  it('renders livestream card with thumbnail, avatar, name, title, and category', async () => {
    const stream1 = makeStream({
      id: 's-1',
      title: 'Rob Run fun lil game',
      channel: {
        id: 'c-1',
        name: 'Ltgrunt',
        slug: 'ltgrunt',
        avatarUrl: 'https://cdn.example.com/avatar-1.jpg',
      },
      category: {
        id: CAT_GAMING,
        name: 'Rob Run',
        slug: 'rob-run',
      },
    })
    const stream2 = makeStream({
      id: 's-2',
      title: 'Lil Leg Fridays | Working',
      channel: {
        id: 'c-2',
        name: 'JETisMusic',
        slug: 'jetismusic',
        avatarUrl: 'https://cdn.example.com/avatar-2.jpg',
      },
      category: {
        id: CAT_MUSIC,
        name: 'Music & Audio',
        slug: 'music-audio',
      },
    })

    mocks.list.mockResolvedValue(makePage([stream1, stream2]))

    mount()

    // Wait for stream cards to render
    expect(await screen.findByText('Rob Run fun lil game')).toBeTruthy()

    // 1. Thumbnail image
    expect(screen.getByAltText('Rob Run fun lil game')).toBeTruthy()
    expect(screen.getByAltText('Lil Leg Fridays | Working')).toBeTruthy()

    // 2. Avatar streamer
    expect(screen.getByAltText('Ltgrunt')).toBeTruthy()
    expect(screen.getByAltText('JETisMusic')).toBeTruthy()

    // 3. Name streamer
    expect(screen.getByText('Ltgrunt')).toBeTruthy()
    expect(screen.getByText('JETisMusic')).toBeTruthy()

    // 4. Title stream session
    expect(screen.getByText('Rob Run fun lil game')).toBeTruthy()
    expect(screen.getByText('Lil Leg Fridays | Working')).toBeTruthy()

    // 5. Name category
    expect(screen.getByText('Rob Run')).toBeTruthy()
    expect(screen.getByText('Music & Audio')).toBeTruthy()

    // Order preserved
    const headings = screen
      .getAllByRole('heading', { level: 3 })
      .map((el) => el.textContent)
    expect(headings).toEqual(['Ltgrunt', 'JETisMusic'])
  })

  it('navigates to CategoryDetailPage when clicking a category card and fetches streams for that category', async () => {
    const cs2Stream = makeStream({
      id: 's-cs2',
      title: 'LIVE: MOUZ vs. BB Team',
      categoryId: CAT_GAMING,
      channel: {
        id: 'c-esl',
        name: 'ESLCS',
        slug: 'eslcs',
        avatarUrl: 'https://cdn.example.com/avatar-esl.jpg',
      },
      category: {
        id: CAT_GAMING,
        name: 'Counter-Strike 2',
        slug: 'counter-strike-2',
      },
    })
    mocks.list.mockResolvedValue(makePage([cs2Stream]))

    mount(
      ['/browse/categories'],
      <Routes>
        <Route path="/browse/categories" element={<BrowseCategoriesTab />} />
        <Route
          path="/browse/categories/:categoryId"
          element={<CategoryDetailPage />}
        />
      </Routes>,
    )

    // Wait for categories to render
    const categoryCard = await screen.findByRole('button', {
      name: /Counter-Strike 2/i,
    })
    fireEvent.click(categoryCard)

    // Verify CategoryDetailPage header: Category Name and Logo
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Counter-Strike 2',
      }),
    ).toBeTruthy()
    expect(screen.getByAltText('Counter-Strike 2')).toBeTruthy()

    // Verify streamApi.list was called with categoryId = CAT_GAMING
    await waitFor(() => {
      expect(mocks.list).toHaveBeenCalledWith(
        { page: 0, size: 20, categoryId: CAT_GAMING },
        expect.any(AbortSignal),
      )
    })

    // Verify livestream of that category is rendered below
    expect(await screen.findByText('ESLCS')).toBeTruthy()
    expect(screen.getByText('LIVE: MOUZ vs. BB Team')).toBeTruthy()
    expect(screen.getByRole('group', { name: 'Current viewers' }).textContent).toBe('42 viewers')
  })

  it('paginates across stream pages', async () => {
    mocks.list.mockImplementation(async (params) => {
      return makePage(
        [
          makeStream({
            id: `s-page-${params?.page ?? 0}`,
            title: `Stream on page ${params?.page ?? 0}`,
          }),
        ],
        {
          page: params?.page ?? 0,
          size: 20,
          totalElements: 40,
          totalPages: 2,
        },
      )
    })

    mount()

    await screen.findByText('Stream on page 0')
    expect(mocks.list).toHaveBeenLastCalledWith(
      { page: 0, size: 20 },
      expect.any(AbortSignal),
    )

    // Click Next page
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('Stream on page 1')
    expect(mocks.list).toHaveBeenLastCalledWith(
      { page: 1, size: 20 },
      expect.any(AbortSignal),
    )

    // Click Previous page
    fireEvent.click(screen.getByRole('button', { name: 'Previous' }))
    await screen.findByText('Stream on page 0')
    expect(mocks.list).toHaveBeenLastCalledWith(
      { page: 0, size: 20 },
      expect.any(AbortSignal),
    )
  })

  it('handles image loading errors and missing data with fallbacks', async () => {
    const fallbackStream = makeStream({
      id: 's-fallback',
      title: 'Minimal Stream',
      thumbnailUrl: 'https://cdn.example.com/broken.jpg',
      channel: {
        id: 'c-fb',
        name: 'FallbackCreator',
        slug: 'fb',
        avatarUrl: 'https://cdn.example.com/broken-avatar.jpg',
      },
      category: null,
    })

    mocks.list.mockResolvedValue(makePage([fallbackStream]))

    mount()

    await screen.findByText('FallbackCreator')
    expect(screen.getByText('Minimal Stream')).toBeTruthy()

    const thumbImg = screen.getByAltText('Minimal Stream')
    const avatarImg = screen.getByAltText('FallbackCreator')

    fireEvent.error(thumbImg)
    fireEvent.error(avatarImg)

    await waitFor(() => {
      expect(screen.getByText('Live Stream')).toBeTruthy()
      expect(screen.getByText('F')).toBeTruthy()
    })
  })

  it('distinguishes error states from empty states and supports retry', async () => {
    const errorResponse = new AxiosError(
      'Service Unavailable',
      'ERR_BAD_RESPONSE',
      {} as InternalAxiosRequestConfig,
      null,
      {
        status: 503,
        statusText: 'Service Unavailable',
        data: { message: 'Stream service down.' },
        headers: {},
        config: {} as InternalAxiosRequestConfig,
      },
    )

    mocks.list.mockRejectedValueOnce(errorResponse)

    mount()

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText('Stream service down.')).toBeTruthy()

    // Retry succeeds with empty data
    mocks.list.mockResolvedValueOnce(makePage([]))
    fireEvent.click(within(alert).getByRole('button', { name: /Retry/i }))

    await screen.findByText('No live streams right now')
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
