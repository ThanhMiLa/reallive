import { StrictMode, type ReactNode } from 'react'
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
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { BrowseLivestreamsTab } from '@/pages/browse/tabs/BrowseLivestreamsTab'
import { CategoryDetailPage } from '@/pages/browse/CategoryDetailPage'
import { WatchStreamPage } from '@/pages/watch/WatchStreamPage'
import { useAuthStore } from '@/stores/auth.store'
import type {
  StreamMetadata,
  StreamViewerDetails,
} from '@/types/stream.types'

type HlsStub = {
  source: string
  config: {
    fetchSetup: (
      context: { url: string },
      init: RequestInit,
    ) => Promise<Request>
  }
  handlers: Record<string, (...args: unknown[]) => void>
  destroy: ReturnType<typeof vi.fn>
  recoverMediaError: ReturnType<typeof vi.fn>
}

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  metadata: vi.fn(),
  details: vi.fn(),
  access: vi.fn(),
  getCategories: vi.fn(),
  hls: [] as HlsStub[],
}))

vi.mock('@/api/stream.api', () => ({
  streamApi: {
    list: mocks.list,
    metadata: mocks.metadata,
    details: mocks.details,
    access: mocks.access,
  },
}))

vi.mock('@/api/channel.api', () => ({
  channelApi: {
    getCategories: mocks.getCategories,
  },
}))

vi.mock('hls.js', () => {
  class FakeHls {
    static isSupported() {
      return true
    }
    static Events = { MANIFEST_PARSED: 'parsed', ERROR: 'error' }
    static ErrorTypes = { MEDIA_ERROR: 'media', NETWORK_ERROR: 'network' }
    handlers: Record<string, (...args: unknown[]) => void> = {}
    config: HlsStub['config']
    source = ''
    destroy = vi.fn()
    recoverMediaError = vi.fn()

    constructor(config: HlsStub['config']) {
      this.config = config
      mocks.hls.push(this)
    }
    on(event: string, callback: (...args: unknown[]) => void) {
      this.handlers[event] = callback
    }
    loadSource(url: string) {
      this.source = url
    }
    attachMedia() {}
  }
  return { default: FakeHls, FetchLoader: class {} }
})

const SESSION_ID = '462c912c-ee66-4477-9852-bed13d377297'
const CHANNEL_ID = '20000000-0000-4000-8000-000000000001'
const CATEGORY_ID = '00000000-0000-4000-8000-000000000001'
const NEXT_CATEGORY_ID = '00000000-0000-4000-8000-000000000002'

const liveMetadata: StreamMetadata = {
  id: SESSION_ID,
  channelId: CHANNEL_ID,
  title: 'IRL STREAM!! ★ENG/粤/國/ESP★',
  categoryId: CATEGORY_ID,
  visibility: 'PUBLIC',
  status: 'LIVE',
  startedAt: '2026-11-07T10:00:00Z',
  endedAt: null,
  reconnectDeadlineAt: null,
  serverTime: '2026-11-07T11:00:00Z',
  currentViewerCount: 42,
}

const viewerDetails: StreamViewerDetails = {
  id: SESSION_ID,
  channelId: CHANNEL_ID,
  categoryId: CATEGORY_ID,
  channel: {
    id: CHANNEL_ID,
    name: 'Queenofdarknesstv',
    avatarUrl: 'https://cdn.example.com/queen-avatar.jpg',
  },
  category: {
    id: CATEGORY_ID,
    name: 'IRL',
  },
}

function makeHttpError(status: number, message = 'Error') {
  return new AxiosError(
    message,
    'ERR_BAD_RESPONSE',
    {} as InternalAxiosRequestConfig,
    null,
    {
      status,
      statusText: message,
      data: { message },
      headers: {},
      config: {} as InternalAxiosRequestConfig,
    },
  )
}

let client: QueryClient

function mount(
  initialEntries: string[] = [`/watch/${SESSION_ID}`],
  node: ReactNode = (
    <Routes>
      <Route path="/watch/:sessionId" element={<WatchStreamPage />} />
      <Route path="/browse/livestreams" element={<BrowseLivestreamsTab />} />
      <Route
        path="/browse/categories/:categoryId"
        element={<CategoryDetailPage />}
      />
    </Routes>
  ),
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
  mocks.hls.length = 0
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
  mocks.metadata.mockResolvedValue(liveMetadata)
  mocks.details.mockResolvedValue(viewerDetails)
  mocks.access.mockResolvedValue({
    sessionId: SESSION_ID,
    playbackUrl: `http://localhost:8898/live/${SESSION_ID}/index.m3u8`,
    readToken: 'viewer-read-jwt',
    readExpiresAt: '2026-11-07T11:05:00Z',
  })
  mocks.getCategories.mockResolvedValue([
    { id: CATEGORY_ID, name: 'IRL', slug: 'irl', thumbnailUrl: null },
  ])
  Object.defineProperty(HTMLMediaElement.prototype, 'load', {
    configurable: true,
    value: vi.fn(),
  })
  Object.defineProperty(HTMLMediaElement.prototype, 'play', {
    configurable: true,
    value: vi.fn().mockResolvedValue(undefined),
  })
})

afterEach(() => {
  cleanup()
  client?.clear()
})

describe('WatchStreamPage', () => {
  it('retries muted when the browser blocks audible autoplay', async () => {
    const play = vi.fn()
      .mockRejectedValueOnce(new DOMException('Autoplay blocked', 'NotAllowedError'))
      .mockResolvedValueOnce(undefined)
    Object.defineProperty(HTMLMediaElement.prototype, 'play', { configurable: true, value: play })
    mount()
    await waitFor(() => expect(mocks.hls).toHaveLength(1))

    await act(async () => { mocks.hls[0].handlers.parsed() })

    const video = screen.getByLabelText('Live stream player') as HTMLVideoElement
    expect(play).toHaveBeenCalledTimes(2)
    expect(video.muted).toBe(true)
    expect(video.volume).toBe(1)
    expect(screen.getByText('Playback started muted. Turn on sound when ready.')).toBeTruthy()
  })

  it('asks the viewer to press play when muted autoplay is also blocked', async () => {
    const play = vi.fn().mockRejectedValue(new DOMException('Autoplay blocked', 'NotAllowedError'))
    Object.defineProperty(HTMLMediaElement.prototype, 'play', { configurable: true, value: play })
    mount()
    await waitFor(() => expect(mocks.hls).toHaveLength(1))

    await act(async () => { mocks.hls[0].handlers.parsed() })

    expect(play).toHaveBeenCalledTimes(2)
    expect(screen.queryByText('Playback started muted. Turn on sound when ready.')).toBeNull()
    expect(screen.getByText('Press play to watch the live stream.')).toBeTruthy()
  })

  it.each([
    [0, '0'],
    [1234, '1,234'],
    [null, '—'],
    [undefined, '—'],
  ])('shows the metadata viewer count %s without inventing a count', async (count, label) => {
    mocks.metadata.mockResolvedValue({ ...liveMetadata, currentViewerCount: count })
    mount()

    const viewers = await screen.findByRole('group', { name: 'Current viewers' })
    expect(within(viewers).getByText(label)).toBeTruthy()
    expect(within(viewers).queryByText('Viewers')).toBeNull()
    expect(viewers.querySelector('svg.lucide-user-round[aria-hidden="true"]')).not.toBeNull()
  })

  it('updates viewers from refreshed metadata without reopening HLS or refetching details', async () => {
    mount()
    await screen.findByLabelText('Live stream player')
    const viewers = await screen.findByRole('group', { name: 'Current viewers' })
    expect(within(viewers).getByText('42')).toBeTruthy()
    await waitFor(() => expect(mocks.access).toHaveBeenCalledTimes(1))

    mocks.metadata.mockResolvedValue({ ...liveMetadata, currentViewerCount: 87 })
    await act(async () => {
      await client.refetchQueries({
        queryKey: ['stream', 'metadata', 'viewer-1', SESSION_ID],
        exact: true,
      })
    })

    await waitFor(() => expect(within(viewers).getByText('87')).toBeTruthy())
    expect(mocks.access).toHaveBeenCalledTimes(1)
    expect(mocks.details).toHaveBeenCalledTimes(1)
    expect(mocks.hls).toHaveLength(1)
    expect(mocks.hls[0].destroy).not.toHaveBeenCalled()
  })

  it('does not show the previous account viewer count while metadata for a new account loads', async () => {
    mount()
    const viewers = await screen.findByRole('group', { name: 'Current viewers' })
    expect(within(viewers).getByText('42')).toBeTruthy()
    let resolveMetadata!: (value: StreamMetadata) => void
    mocks.metadata.mockImplementation(
      () => new Promise<StreamMetadata>((resolve) => { resolveMetadata = resolve }),
    )

    act(() => {
      const user = useAuthStore.getState().user!
      useAuthStore.setState({ user: { ...user, userId: 'viewer-2' } })
    })

    await screen.findByLabelText('Loading stream')
    expect(screen.queryByRole('group', { name: 'Current viewers' })).toBeNull()
    await act(async () => {
      resolveMetadata({ ...liveMetadata, currentViewerCount: 5 })
      await Promise.resolve()
    })

    const nextViewers = await screen.findByRole('group', { name: 'Current viewers' })
    expect(within(nextViewers).getByText('5')).toBeTruthy()
    expect(within(nextViewers).queryByText('42')).toBeNull()
  })

  it('loads metadata first, then details and playback access on direct URL navigation', async () => {
    const callOrder: string[] = []
    mocks.metadata.mockImplementation(async () => {
      callOrder.push('metadata')
      return liveMetadata
    })
    mocks.details.mockImplementation(async () => {
      callOrder.push('details')
      return viewerDetails
    })
    mocks.access.mockImplementation(async () => {
      callOrder.push('access')
      return {
        sessionId: SESSION_ID,
        playbackUrl: `http://localhost:8898/live/${SESSION_ID}/index.m3u8`,
        readToken: 'viewer-read-jwt',
        readExpiresAt: '2026-11-07T11:05:00Z',
      }
    })

    mount()

    expect(
      await screen.findByRole('heading', {
        level: 2,
        name: 'Queenofdarknesstv',
      }),
    ).toBeTruthy()
    expect(
      screen.getByRole('heading', {
        level: 1,
        name: 'IRL STREAM!! ★ENG/粤/國/ESP★',
      }),
    ).toBeTruthy()
    expect(screen.getByRole('link', { name: 'IRL' })).toBeTruthy()
    expect(screen.getByAltText('Queenofdarknesstv')).toBeTruthy()
    expect(await screen.findByLabelText('Live stream player')).toBeTruthy()
    expect(screen.getByLabelText('Stream chat')).toBeTruthy()
    expect(screen.queryByText(/preview/i)).toBeNull()

    await waitFor(() => {
      expect(callOrder[0]).toBe('metadata')
      expect(callOrder).toContain('details')
      expect(callOrder).toContain('access')
    })
  })

  it('opens WatchStreamPage from BrowseLivestreamsTab and keeps category link independent', async () => {
    mocks.list.mockResolvedValue({
      content: [
        {
          id: SESSION_ID,
          title: liveMetadata.title,
          status: 'LIVE',
          startedAt: liveMetadata.startedAt,
          thumbnailUrl: 'https://cdn.example.com/thumb.jpg',
          thumbnailUpdatedAt: null,
          channelId: CHANNEL_ID,
          categoryId: CATEGORY_ID,
          channel: {
            id: CHANNEL_ID,
            name: 'Queenofdarknesstv',
            slug: 'queen',
            avatarUrl: 'https://cdn.example.com/queen-avatar.jpg',
          },
          category: {
            id: CATEGORY_ID,
            name: 'IRL',
            slug: 'irl',
          },
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    })

    mount(['/browse/livestreams'])

    const categoryBadge = await screen.findByRole('link', { name: 'IRL' })
    expect(categoryBadge.getAttribute('href')).toBe(
      `/browse/categories/${CATEGORY_ID}`,
    )

    fireEvent.click(screen.getByText('IRL STREAM!! ★ENG/粤/國/ESP★'))

    expect(await screen.findByLabelText('Live stream player')).toBeTruthy()
    expect(
      await screen.findByRole('heading', {
        level: 2,
        name: 'Queenofdarknesstv',
      }),
    ).toBeTruthy()
  })

  it('rejects invalid UUID without calling backend APIs', () => {
    mount(['/watch/not-a-valid-uuid'])

    expect(
      screen.getByRole('heading', { name: 'Stream session not found' }),
    ).toBeTruthy()
    expect(mocks.metadata).not.toHaveBeenCalled()
    expect(mocks.details).not.toHaveBeenCalled()
    expect(mocks.access).not.toHaveBeenCalled()
  })

  it('handles READY -> LIVE -> RECONNECTING -> LIVE -> ENDED lifecycle transitions and does not re-issue token on repeated LIVE polls', async () => {
    mocks.metadata.mockResolvedValue({
      ...liveMetadata,
      status: 'READY',
      startedAt: null,
    })

    mount()

    // 1. READY: shows waiting message, does not call playback-access
    expect(
      await screen.findByText('Stream has not started yet'),
    ).toBeTruthy()
    expect(mocks.access).not.toHaveBeenCalled()
    expect(mocks.hls).toHaveLength(0)
    expect(within(screen.getByRole('group', { name: 'Current viewers' })).getByText('0')).toBeTruthy()

    // 2. READY -> LIVE: opens player and calls playback-access once
    act(() => {
      client.setQueryData(
        ['stream', 'metadata', 'viewer-1', SESSION_ID],
        liveMetadata,
      )
    })
    expect(await screen.findByLabelText('Live stream player')).toBeTruthy()
    await waitFor(() => expect(mocks.access).toHaveBeenCalledTimes(1))
    expect(mocks.hls).toHaveLength(1)

    // 3. Repeated LIVE poll: does NOT recreate player or call playback-access again
    act(() => {
      client.setQueryData(['stream', 'metadata', 'viewer-1', SESSION_ID], {
        ...liveMetadata,
        serverTime: '2026-11-07T11:00:03Z',
      })
    })
    expect(mocks.access).toHaveBeenCalledTimes(1)
    expect(mocks.hls).toHaveLength(1)

    // 4. LIVE -> RECONNECTING: stops player and shows reconnecting message
    const firstHls = mocks.hls[0]
    act(() => {
      client.setQueryData(['stream', 'metadata', 'viewer-1', SESSION_ID], {
        ...liveMetadata,
        status: 'RECONNECTING',
        reconnectDeadlineAt: '2026-11-07T11:01:00Z',
      })
    })
    expect(
      await screen.findByText('Waiting for the stream to reconnect'),
    ).toBeTruthy()
    expect(firstHls.destroy).toHaveBeenCalledTimes(1)

    // 5. RECONNECTING -> LIVE: reopens player and requests fresh playback-access
    act(() => {
      client.setQueryData(
        ['stream', 'metadata', 'viewer-1', SESSION_ID],
        liveMetadata,
      )
    })
    expect(await screen.findByLabelText('Live stream player')).toBeTruthy()
    await waitFor(() => expect(mocks.access).toHaveBeenCalledTimes(2))
    expect(mocks.hls).toHaveLength(2)

    // 6. LIVE -> ENDED: stops player and shows ended state
    const secondHls = mocks.hls[1]
    act(() => {
      client.setQueryData(['stream', 'metadata', 'viewer-1', SESSION_ID], {
        ...liveMetadata,
        status: 'ENDED',
        endedAt: '2026-11-07T11:30:00Z',
      })
    })
    expect(await screen.findByText('This stream has ended')).toBeTruthy()
    expect(secondHls.destroy).toHaveBeenCalledTimes(1)
    expect(within(screen.getByRole('group', { name: 'Current viewers' })).getByText('0')).toBeTruthy()
  })

  it('refetches details when metadata categoryId changes and avoids showing stale category name', async () => {
    mount()

    expect(await screen.findByRole('link', { name: 'IRL' })).toBeTruthy()
    expect(mocks.details).toHaveBeenCalledTimes(1)

    let resolveNextDetails!: (value: StreamViewerDetails) => void
    mocks.details.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveNextDetails = resolve
        }),
    )

    // Metadata poll reports new categoryId
    act(() => {
      client.setQueryData(['stream', 'metadata', 'viewer-1', SESSION_ID], {
        ...liveMetadata,
        categoryId: NEXT_CATEGORY_ID,
      })
    })

    // Old category name "IRL" is immediately hidden while new details are loading, but channel name remains
    await waitFor(() => {
      expect(screen.queryByRole('link', { name: 'IRL' })).toBeNull()
    })
    expect(
      screen.getByRole('heading', { level: 2, name: 'Queenofdarknesstv' }),
    ).toBeTruthy()
    expect(mocks.details).toHaveBeenCalledTimes(2)

    // Resolve new details
    await act(async () => {
      resolveNextDetails({
        ...viewerDetails,
        categoryId: NEXT_CATEGORY_ID,
        category: {
          id: NEXT_CATEGORY_ID,
          name: 'Counter-Strike 2',
        },
      })
    })

    expect(
      await screen.findByRole('link', { name: 'Counter-Strike 2' }),
    ).toBeTruthy()
  })

  it('keeps video playing when details API fails and supports retrying details', async () => {
    mocks.details.mockRejectedValueOnce(
      makeHttpError(503, 'Stream data unavailable'),
    )

    mount()

    expect(await screen.findByLabelText('Live stream player')).toBeTruthy()
    const errorAlert = await screen.findByRole('alert')
    expect(
      within(errorAlert).getByText(
        'Could not load channel and category details.',
      ),
    ).toBeTruthy()
    expect(mocks.hls).toHaveLength(1)

    // Retry details succeeds
    mocks.details.mockResolvedValueOnce(viewerDetails)
    fireEvent.click(within(errorAlert).getByRole('button', { name: /Retry/i }))

    expect(
      await screen.findByRole('heading', {
        level: 2,
        name: 'Queenofdarknesstv',
      }),
    ).toBeTruthy()
    expect(screen.getByRole('link', { name: 'IRL' })).toBeTruthy()
  })

  it('handles race where metadata is LIVE but POST playback-access returns 409 without infinite retry', async () => {
    mocks.metadata
      .mockResolvedValueOnce(liveMetadata)
      .mockResolvedValue({ ...liveMetadata, status: 'ENDED' })
    mocks.access.mockRejectedValue(makeHttpError(409, 'State invalid'))

    mount()

    expect(await screen.findByText('This stream has ended')).toBeTruthy()
    expect(mocks.access).toHaveBeenCalledTimes(1)
    expect(mocks.metadata.mock.calls.length).toBeGreaterThanOrEqual(2)
  })

  it('keeps video playing when a background metadata poll fails with 503', async () => {
    mocks.metadata
      .mockResolvedValueOnce(liveMetadata)
      .mockRejectedValueOnce(makeHttpError(503, 'Service unavailable'))

    mount()

    expect(await screen.findByLabelText('Live stream player')).toBeTruthy()
    expect(mocks.hls).toHaveLength(1)
    expect(within(screen.getByRole('group', { name: 'Current viewers' })).getByText('42')).toBeTruthy()

    await act(async () => {
      await client.refetchQueries({
        queryKey: ['stream', 'metadata', 'viewer-1', SESSION_ID],
      })
    })

    expect(screen.getByLabelText('Live stream player')).toBeTruthy()
    expect(mocks.hls[0].destroy).not.toHaveBeenCalled()
    expect(
      await screen.findByText('Unable to refresh stream status.'),
    ).toBeTruthy()
    expect(within(screen.getByRole('group', { name: 'Current viewers' })).getByText('—')).toBeTruthy()
  })

  it('prompts login at the same URL for guests or 401 and resumes after authentication', async () => {
    useAuthStore.setState({
      isCheckingAuth: false,
      isAuthenticated: false,
      isAuthModalOpen: false,
      user: null,
    })

    mount()

    expect(
      screen.getByRole('heading', { name: 'Sign in to watch this stream' }),
    ).toBeTruthy()
    expect(useAuthStore.getState().isAuthModalOpen).toBe(true)
    expect(mocks.metadata).not.toHaveBeenCalled()

    // User logs in at the same URL
    act(() => {
      useAuthStore.setState({
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
    })

    expect(await screen.findByLabelText('Live stream player')).toBeTruthy()
    expect(
      await screen.findByRole('heading', {
        level: 2,
        name: 'Queenofdarknesstv',
      }),
    ).toBeTruthy()
  })

  it('falls back to initial avatar when channel avatarUrl fails to load and cleans up under StrictMode', async () => {
    const view = mount(
      [`/watch/${SESSION_ID}`],
      <StrictMode>
        <Routes>
          <Route path="/watch/:sessionId" element={<WatchStreamPage />} />
        </Routes>
      </StrictMode>,
    )

    const avatarImg = await screen.findByAltText('Queenofdarknesstv')
    fireEvent.error(avatarImg)

    await waitFor(() => {
      expect(screen.getByText('Q')).toBeTruthy()
    })

    const lastHls = mocks.hls.at(-1)!
    view.unmount()
    expect(lastHls.destroy).toHaveBeenCalled()
  })

  it('normalizes uppercase UUID links for metadata, details and playback access', async () => {
    mount([`/watch/${SESSION_ID.toUpperCase()}`])

    expect(await screen.findByLabelText('Live stream player')).toBeTruthy()
    expect(await screen.findByRole('heading', { level: 2, name: 'Queenofdarknesstv' })).toBeTruthy()
    expect(mocks.metadata).toHaveBeenCalledWith(SESSION_ID, expect.any(AbortSignal))
    expect(mocks.details).toHaveBeenCalledWith(SESSION_ID, expect.any(AbortSignal))
    expect(mocks.access).toHaveBeenCalledWith(SESSION_ID, expect.any(AbortSignal))
    expect(mocks.hls[0].source).toContain(`/live/${SESSION_ID}/index.m3u8`)
  })

  it.each([403, 404])('stops playback on details HTTP %s and resumes after a successful retry', async (status) => {
    let rejectDetails!: (error: unknown) => void
    mocks.details.mockImplementationOnce(() => new Promise((_resolve, reject) => {
      rejectDetails = reject
    }))
    mount()
    await screen.findByLabelText('Live stream player')
    await waitFor(() => expect(mocks.hls).toHaveLength(1))
    const playingHls = mocks.hls[0]

    await act(async () => rejectDetails(makeHttpError(status)))
    expect(await screen.findByRole('heading', {
      name: status === 403 ? 'Stream unavailable' : 'Stream session not found',
    })).toBeTruthy()
    expect(screen.queryByLabelText('Live stream player')).toBeNull()
    expect(playingHls.destroy).toHaveBeenCalled()

    const metadataCalls = mocks.metadata.mock.calls.length
    await act(async () => document.dispatchEvent(new Event('visibilitychange')))
    expect(mocks.metadata).toHaveBeenCalledTimes(metadataCalls)

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByLabelText('Live stream player')).toBeTruthy()
    await waitFor(() => expect(mocks.access).toHaveBeenCalledTimes(2))
  })

  it('refetches mismatched cached details when the category changes A -> B -> A', async () => {
    const nextDetails = {
      ...viewerDetails,
      categoryId: NEXT_CATEGORY_ID,
      category: { id: NEXT_CATEGORY_ID, name: 'Gaming' },
    }
    // The first details snapshot is newer than the first metadata snapshot.
    mocks.details.mockResolvedValueOnce(nextDetails).mockResolvedValueOnce(nextDetails).mockResolvedValue(viewerDetails)
    mount()
    await screen.findByRole('heading', { level: 2, name: 'Queenofdarknesstv' })

    await act(async () => {
      client.setQueryData(['stream', 'metadata', 'viewer-1', SESSION_ID], {
        ...liveMetadata, categoryId: NEXT_CATEGORY_ID,
      })
    })
    expect(await screen.findByRole('link', { name: 'Gaming' })).toBeTruthy()

    await act(async () => {
      client.setQueryData(['stream', 'metadata', 'viewer-1', SESSION_ID], liveMetadata)
    })
    expect(await screen.findByRole('link', { name: 'IRL' })).toBeTruthy()
    expect(mocks.details).toHaveBeenCalledTimes(3)
    expect(mocks.access).toHaveBeenCalledTimes(1)
  })

})
