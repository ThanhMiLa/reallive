import { StrictMode, type ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { StreamerDashboardPage } from '@/pages/dashboard/StreamerDashboardPage'
import { useAuthStore } from '@/stores/auth.store'

type HlsStub = {
  source: string
  config: { fetchSetup: (context: { url: string }, init: RequestInit) => Promise<Request> }
  destroy: ReturnType<typeof vi.fn>
}

const mocks = vi.hoisted(() => ({
  current: vi.fn(),
  metadata: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  credentials: vi.fn(),
  access: vi.fn(),
  end: vi.fn(),
  categories: vi.fn(),
  toast: vi.fn(),
  hls: [] as HlsStub[],
}))
vi.mock('@/api/stream.api', () => ({ streamApi: mocks }))
vi.mock('@/api/channel.api', () => ({ channelApi: { getCategories: mocks.categories } }))
vi.mock('sonner', () => ({ toast: { success: mocks.toast, error: mocks.toast } }))
vi.mock('hls.js', () => {
  class FakeHls {
    static isSupported() {
      return true
    }
    static Events = { MANIFEST_PARSED: 'parsed', ERROR: 'error' }
    static ErrorTypes = { MEDIA_ERROR: 'media' }
    handlers: Record<string, (...args: unknown[]) => void> = {}
    config: HlsStub['config']
    constructor(config: HlsStub['config']) {
      this.config = config
      mocks.hls.push(this)
    }
    on(event: string, callback: (...args: unknown[]) => void) {
      this.handlers[event] = callback
    }
    source = ''
    loadSource(url: string) {
      this.source = url
    }
    attachMedia() {}
    destroy = vi.fn()
    recoverMediaError = vi.fn()
  }
  return { default: FakeHls, FetchLoader: class {} }
})

const categoryId = '00000000-0000-4000-8000-000000000001'
const sessionId = '462c912c-ee66-4477-9852-bed13d377297'
const ready = {
  id: sessionId,
  channelId: 'channel',
  title: 'Morning',
  categoryId,
  visibility: 'PUBLIC',
  status: 'READY',
  startedAt: null,
  endedAt: null,
  reconnectDeadlineAt: null,
  serverTime: '2026-10-30T00:00:00Z',
  publishReady: true,
}
const credentials = {
  sessionId,
  serverUrl: 'rtmp://localhost:1935/live',
  streamKey: 'secret-key',
  publishExpiresAt: '2026-10-30T08:00:00Z',
}
let client: QueryClient
function mount(node: ReactNode = <StreamerDashboardPage />) {
  client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>{node}</MemoryRouter>
    </QueryClientProvider>,
  )
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.hls.length = 0
  useAuthStore.setState({
    isCheckingAuth: false,
    isAuthenticated: true,
    user: {
      userId: 'owner',
      displayName: 'Owner',
      email: 'owner@example.com',
      emailVerified: true,
      roles: ['STREAMER'],
      avatarUrl: null,
      dob: null,
    },
  })
  mocks.current.mockResolvedValue(null)
  mocks.categories.mockResolvedValue([
    { id: categoryId, name: 'Gaming', slug: 'gaming', thumbnailUrl: null },
  ])
  mocks.metadata.mockResolvedValue(ready)
  mocks.access.mockResolvedValue({
    sessionId,
    playbackUrl: `http://localhost:8898/live/${sessionId}/index.m3u8`,
    readToken: 'read-token',
    readExpiresAt: '2026-10-30T00:05:00Z',
  })
  Object.defineProperty(HTMLMediaElement.prototype, 'load', { configurable: true, value: vi.fn() })
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.open = true
    },
  })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.open = false
    },
  })
})
afterEach(() => {
  cleanup()
  client?.clear()
})

describe('Stream Manager', () => {
  it('waits for login and does not query private APIs for guests', () => {
    useAuthStore.setState({ user: null, isAuthenticated: false })
    mount()
    expect(screen.getByText('Log in to manage your stream.')).toBeTruthy()
    expect(mocks.current).not.toHaveBeenCalled()
    expect(mocks.access).not.toHaveBeenCalled()
  })

  it('creates a session using real form values and shows masked OBS credentials', async () => {
    mocks.create.mockResolvedValue({ session: ready, publishCredentials: credentials })
    mount()
    await screen.findByText('Gaming')
    fireEvent.change(screen.getByLabelText('Stream title'), { target: { value: '  Morning  ' } })
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: categoryId } })
    fireEvent.click(screen.getByRole('button', { name: 'Create stream' }))
    await waitFor(() => expect(mocks.create).toHaveBeenCalledTimes(1))
    expect(mocks.create.mock.calls[0][0]).toEqual({
      title: 'Morning',
      categoryId,
      requestId: expect.any(String),
    })
    expect(await screen.findByLabelText('Stream key')).toHaveProperty('type', 'password')
    expect(screen.getByDisplayValue('rtmp://localhost:1935/live')).toBeTruthy()
    expect(mocks.access).not.toHaveBeenCalled()
    expect(client.getMutationCache().getAll()).toHaveLength(0)
  })

  it('uses authenticated media requests only for the current stream path', async () => {
    const live = { ...ready, status: 'LIVE', startedAt: '2026-10-30T00:00:00Z' }
    mocks.current.mockResolvedValue(live)
    mocks.metadata.mockResolvedValue(live)
    mount(
      <StrictMode>
        <StreamerDashboardPage />
      </StrictMode>,
    )
    await waitFor(() => expect(mocks.hls.length).toBeGreaterThan(0))
    const hls = mocks.hls.at(-1)!
    expect(hls.source).toContain('cookieCheck=1')
    const request = await hls.config.fetchSetup(
      { url: `http://localhost:8898/live/${sessionId}/video.m3u8` },
      { headers: new Headers(), method: 'GET' },
    )
    expect(request.headers.get('Authorization')).toBe('Bearer read-token')
    expect(request.credentials).toBe('omit')
    expect(request.redirect).toBe('error')
    await expect(
      hls.config.fetchSetup(
        { url: 'http://localhost:8898/live/other/segment.mp4' },
        { headers: new Headers() },
      ),
    ).rejects.toThrow()
    await expect(
      hls.config.fetchSetup(
        { url: `http://other.example/live/${sessionId}/video.m3u8` },
        { headers: new Headers() },
      ),
    ).rejects.toThrow()
    cleanup()
    expect(hls.destroy).toHaveBeenCalled()
  })

  it('keeps the session visible when ending fails', async () => {
    mocks.current.mockResolvedValue(ready)
    mocks.metadata.mockResolvedValue(ready)
    mocks.end.mockRejectedValue({ isAxiosError: true, response: { status: 503 } })
    mount()
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel session' }))
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel session' }),
    )
    await waitFor(() => expect(mocks.end).toHaveBeenCalledTimes(1))
    expect(screen.getByText('Waiting for OBS')).toBeTruthy()
    expect(screen.queryByText('This stream has ended')).toBeNull()
  })

  it('retries an uncertain create with the same request ID and payload', async () => {
    mocks.create
      .mockReset()
      .mockRejectedValueOnce(new Error('connection lost'))
      .mockResolvedValueOnce({ session: ready, publishCredentials: credentials })
    mount()
    await screen.findByText('Gaming')
    fireEvent.change(screen.getByLabelText('Stream title'), { target: { value: 'Morning' } })
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: categoryId } })
    fireEvent.click(screen.getByRole('button', { name: 'Create stream' }))
    await waitFor(() => expect(mocks.create).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(mocks.toast).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: 'Create stream' }))
    await waitFor(() => expect(mocks.create).toHaveBeenCalledTimes(2))
    expect(mocks.create.mock.calls[1][0]).toEqual(mocks.create.mock.calls[0][0])
    expect(await screen.findByLabelText('Stream key')).toBeTruthy()
  })

  it('discards delayed playback access after the session ends', async () => {
    const live = { ...ready, status: 'LIVE', startedAt: '2026-10-30T00:00:00Z' }
    mocks.current.mockResolvedValue(live)
    mocks.metadata.mockResolvedValue(live)
    let resolveAccess!: (value: unknown) => void
    mocks.access.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveAccess = resolve
        }),
    )
    mount()
    await waitFor(() => expect(mocks.access).toHaveBeenCalled())
    act(() =>
      client.setQueryData(['stream', 'metadata', 'owner', sessionId], {
        ...live,
        status: 'ENDED',
        endedAt: '2026-10-30T00:30:00Z',
      }),
    )
    await screen.findByText('This stream has ended')
    await act(async () => {
      resolveAccess({
        sessionId,
        playbackUrl: `http://localhost:8898/live/${sessionId}/index.m3u8`,
        readToken: 'late-token',
      })
      await Promise.resolve()
    })
    expect(mocks.hls).toHaveLength(0)
  })

  it('rejects a blank title without calling the backend', async () => {
    mount()
    await screen.findByText('Gaming')
    fireEvent.change(screen.getByLabelText('Stream title'), { target: { value: '   ' } })
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: categoryId } })
    fireEvent.click(screen.getByRole('button', { name: 'Create stream' }))
    await screen.findByText('Enter a stream title.')
    expect(mocks.create).not.toHaveBeenCalled()
  })

  it('preserves an edited title when metadata is refreshed', async () => {
    mocks.current.mockResolvedValue(ready)
    mocks.metadata.mockResolvedValue(ready)
    mount()
    const input = (await screen.findByLabelText('Stream title')) as HTMLInputElement
    fireEvent.change(input, { target: { value: 'Draft title' } })
    act(() =>
      client.setQueryData(['stream', 'metadata', 'owner', sessionId], {
        ...ready,
        title: 'Changed elsewhere',
      }),
    )
    expect(input.value).toBe('Draft title')
  })

  it('clears credentials after confirmed End', async () => {
    mocks.current.mockResolvedValue(ready)
    mocks.metadata.mockResolvedValue(ready)
    mocks.credentials.mockResolvedValue(credentials)
    mocks.end.mockResolvedValue({ ...ready, status: 'ENDED', endedAt: '2026-10-30T00:30:00Z' })
    mount()
    fireEvent.click(await screen.findByRole('button', { name: 'Get OBS credentials' }))
    await screen.findByLabelText('Stream key')
    fireEvent.click(screen.getByRole('button', { name: 'Cancel session' }))
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel session' }),
    )
    await screen.findByText('This stream has ended')
    expect(screen.queryByLabelText('Stream key')).toBeNull()
    expect(screen.getByRole('button', { name: 'Create new stream' })).toBeTruthy()
  })

  it('reports clipboard failure rather than a false success', async () => {
    mocks.current.mockResolvedValue(ready)
    mocks.metadata.mockResolvedValue(ready)
    mocks.credentials.mockResolvedValue(credentials)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
    })
    mount()
    fireEvent.click(await screen.findByRole('button', { name: 'Get OBS credentials' }))
    await screen.findByLabelText('Stream key')
    fireEvent.click(screen.getAllByRole('button', { name: 'Copy' })[1])
    await waitFor(() =>
      expect(mocks.toast).toHaveBeenCalledWith(
        expect.stringContaining('Could not copy stream key'),
      ),
    )
  })

  it('refetches metadata when playback access returns 409', async () => {
    const live = { ...ready, status: 'LIVE', startedAt: '2026-10-30T00:00:00Z' }
    mocks.current.mockResolvedValue(live)
    mocks.metadata.mockResolvedValueOnce(live).mockResolvedValue(ready)
    mocks.access.mockRejectedValue({ isAxiosError: true, response: { status: 409 } })
    mount()
    await screen.findByText('Waiting for OBS')
    expect(mocks.access).toHaveBeenCalledTimes(1)
    expect(mocks.metadata.mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(mocks.hls).toHaveLength(0)
  })

  it('stops the preview when metadata says the session no longer exists', async () => {
    const live = { ...ready, status: 'LIVE', startedAt: '2026-10-30T00:00:00Z' }
    mocks.current.mockResolvedValueOnce(live).mockRejectedValue(new Error('service unavailable'))
    mocks.metadata.mockRejectedValue({ isAxiosError: true, response: { status: 404 } })
    mount()
    await screen.findByText('Stream session is unavailable.')
    expect(screen.queryByLabelText('Stream preview')).toBeNull()
  })
})
