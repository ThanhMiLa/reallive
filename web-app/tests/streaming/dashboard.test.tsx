import { StrictMode, type ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { StreamerDashboardPage } from '@/pages/dashboard/StreamerDashboardPage'
import { useAuthStore } from '@/stores/auth.store'
import type { ChatMessage, ChatPage } from '@/types/chat.types'
import type {
  StreamMetadata,
  StreamSession,
  StreamSetup,
} from '@/types/stream.types'

type HlsStub = {
  source: string
  config: { fetchSetup: (context: { url: string }, init: RequestInit) => Promise<Request> }
  destroy: ReturnType<typeof vi.fn>
}
type SocketStub = {
  connected: boolean
  sent: { clientMessageId: string; content: string }[]
  trigger: (event: string, value?: unknown) => void
}

const mocks = vi.hoisted(() => ({
  setup: vi.fn(),
  saveSetup: vi.fn(),
  issueKey: vi.fn(),
  current: vi.fn(),
  metadata: vi.fn(),
  access: vi.fn(),
  categories: vi.fn(),
  chatHistory: vi.fn(),
  toast: vi.fn(),
  hls: [] as HlsStub[],
  sockets: [] as SocketStub[],
}))

class FakeSocket {
  connected = false
  handlers = new Map<string, ((...args: unknown[]) => void)[]>()
  sent: { clientMessageId: string; content: string }[] = []
  nextSequence = 2
  on(event: string, handler: (...args: never[]) => void) {
    this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler as (...args: unknown[]) => void])
    return this
  }
  trigger(event: string, value?: unknown) {
    this.handlers.get(event)?.forEach((fn) => fn(value))
  }
  connect() {
    this.connected = true
    queueMicrotask(() => this.trigger('connect'))
    return this
  }
  disconnect() {
    this.connected = false
    this.trigger('disconnect')
    return this
  }
  removeAllListeners() {
    this.handlers.clear()
    return this
  }
  timeout() {
    return this
  }
  emit(event: string, input: { clientMessageId: string; content: string }, callback?: (...args: unknown[]) => void) {
    if (event === 'chat:join') {
      queueMicrotask(() =>
        callback?.(null, { ok: true, data: { sessionId, canRead: true, canSend: true, watermark: 'head' } }),
      )
    }
    if (event === 'chat:send') {
      this.sent.push(input)
      const seq = this.nextSequence++
      queueMicrotask(() =>
        callback?.(null, {
          ok: true,
          data: {
            id: `00000000-0000-4000-8000-${String(seq).padStart(12, '0')}`,
            cursor: `cursor-${seq}`,
            streamId: sessionId,
            channelId: 'channel',
            userId: 'owner',
            clientMessageId: input.clientMessageId,
            sequence: seq,
            content: input.content,
            sender: { displayName: 'Owner', avatarUrl: null },
            createdAt: '2026-10-30T00:01:00Z',
          } satisfies ChatMessage,
        }),
      )
    }
    return this
  }
}

vi.mock('@/api/stream.api', () => ({ streamApi: mocks }))
vi.mock('@/api/channel.api', () => ({ channelApi: { getCategories: mocks.categories } }))
vi.mock('@/api/chat.api', () => ({ chatApi: { history: mocks.chatHistory } }))
vi.mock('socket.io-client', () => ({
  io: () => {
    const socket = new FakeSocket()
    mocks.sockets.push(socket)
    return socket
  },
}))
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: (options: { count: number }) => ({
    getTotalSize: () => options.count * 64,
    getVirtualItems: () =>
      Array.from({ length: options.count }, (_, index) => ({
        index,
        start: index * 64,
        end: (index + 1) * 64,
        size: 64,
      })),
    measureElement: () => undefined,
    scrollToIndex: () => undefined,
    getOffsetForIndex: (index: number) => [index * 64, 'start'],
    scrollToOffset: () => undefined,
  }),
}))
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

const channelId = '00000000-0000-4000-8000-000000000010'
const categoryId = '00000000-0000-4000-8000-000000000001'
const sessionId = '462c912c-ee66-4477-9852-bed13d377297'

const draftSetup: StreamSetup = {
  channelId, title: '', categoryId: null, visibility: 'PUBLIC', status: 'DRAFT',
  hasStreamKey: false, blockedReason: null,
}
const activeSetup: StreamSetup = {
  ...draftSetup, title: 'Morning', categoryId, status: 'ACTIVE', hasStreamKey: true,
}
const issuedKey = {
  channelId, serverUrl: 'rtmp://localhost:1935/ingest', streamKey: 'ingest-id-1?token=secret-jwt-1',
}

const liveSession: StreamSession = {
  id: sessionId,
  channelId,
  title: 'Morning',
  categoryId,
  visibility: 'PUBLIC',
  status: 'LIVE',
  startedAt: '2026-10-30T00:00:00Z',
  endedAt: null,
  reconnectDeadlineAt: null,
  serverTime: '2026-10-30T00:00:00Z',
  publishReady: true,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
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
  mocks.sockets.length = 0
  mocks.chatHistory.mockResolvedValue({
    items: [],
    hasOlder: false,
    hasNewer: false,
    olderCursor: null,
    newerCursor: null,
    watermark: 'head',
  } satisfies ChatPage)
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
  mocks.issueKey.mockResolvedValue(issuedKey)
  mocks.setup.mockResolvedValue(activeSetup)
  mocks.current.mockResolvedValue(null)
  mocks.categories.mockResolvedValue([
    { id: categoryId, name: 'Gaming', slug: 'gaming', thumbnailUrl: null },
  ])
  mocks.metadata.mockResolvedValue({ ...liveSession, currentViewerCount: 0 })
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

describe('Stream Manager (persistent stream key)', () => {
  it('seeds owner metadata with an unknown count then updates viewers without restarting the preview', async () => {
    const pendingMetadata = deferred<StreamMetadata>()
    mocks.current.mockResolvedValue(liveSession)
    mocks.metadata.mockReturnValue(pendingMetadata.promise)
    mount()

    const viewers = await screen.findByRole('group', { name: 'Current viewers' })
    expect(within(viewers).getByText('—')).toBeTruthy()
    expect(client.getQueryData(['stream', 'metadata', 'owner', sessionId])).toHaveProperty('currentViewerCount', null)
    expect(client.getQueryData(['stream', 'current', 'owner'])).not.toHaveProperty('currentViewerCount')
    await waitFor(() => expect(mocks.access).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(mocks.hls).toHaveLength(1))

    await act(async () => {
      pendingMetadata.resolve({ ...liveSession, currentViewerCount: 7 })
      await Promise.resolve()
    })

    await waitFor(() => expect(within(viewers).getByText('7')).toBeTruthy())
    expect(mocks.access).toHaveBeenCalledTimes(1)
    expect(mocks.hls).toHaveLength(1)
    expect(mocks.hls[0].destroy).not.toHaveBeenCalled()

    mocks.metadata.mockResolvedValue({ ...liveSession, currentViewerCount: 8 })
    await act(async () => {
      await client.refetchQueries({ queryKey: ['stream', 'metadata', 'owner', sessionId], exact: true })
    })
    await waitFor(() => expect(within(viewers).getByText('8')).toBeTruthy())
    expect(mocks.access).toHaveBeenCalledTimes(1)
    expect(mocks.hls[0].destroy).not.toHaveBeenCalled()

    mocks.metadata.mockRejectedValue(new Error('Metadata unavailable'))
    await act(async () => {
      await client.refetchQueries({ queryKey: ['stream', 'metadata', 'owner', sessionId], exact: true })
    })
    await waitFor(() => expect(within(viewers).getByText('—')).toBeTruthy())
    expect(mocks.hls[0].destroy).not.toHaveBeenCalled()
  })

  it('waits for login and does not query private APIs for guests', () => {
    useAuthStore.setState({ user: null, isAuthenticated: false })
    mount()
    expect(screen.getByText('Log in to manage your stream.')).toBeTruthy()
    expect(mocks.setup).not.toHaveBeenCalled()
    expect(mocks.current).not.toHaveBeenCalled()
    expect(mocks.access).not.toHaveBeenCalled()
  })

  it('auto-detects OBS going live from offline /me/current refetch and displays LIVE preview and chat', async () => {
    mocks.setup.mockResolvedValue(activeSetup)
    mocks.current.mockResolvedValueOnce(null).mockResolvedValue(liveSession)
    mocks.metadata.mockResolvedValue({ ...liveSession, currentViewerCount: 2 })

    mount()
    expect(await screen.findByText('Waiting for OBS')).toBeTruthy()
    expect(screen.getByText('Chat opens automatically when you start streaming in OBS.')).toBeTruthy()

    await act(async () => {
      await client.refetchQueries({ queryKey: ['stream', 'current', 'owner'], exact: true })
    })

    expect(await screen.findByLabelText('Stream preview')).toBeTruthy()
    await waitFor(() => expect(mocks.access).toHaveBeenCalledTimes(1))
    const header = screen.getByRole('heading', { name: 'Stream Manager' }).closest('header')!
    expect(within(header).getByText('LIVE')).toBeTruthy()
  })

  it('uses authenticated media requests only for the current stream path', async () => {
    mocks.current.mockResolvedValue(liveSession)
    mocks.metadata.mockResolvedValue(liveSession)
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

  it('discards delayed playback access after the session ends', async () => {
    mocks.current.mockResolvedValue(liveSession)
    mocks.metadata.mockResolvedValue(liveSession)
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
        ...liveSession,
        status: 'ENDED',
        endedAt: '2026-10-30T00:30:00Z',
      }),
    )
    await screen.findByText(/Previous broadcast ended/i)
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

  it('preserves dirty StreamInfoForm input across polled setup and metadata updates', async () => {
    mocks.current.mockResolvedValue(liveSession)
    mocks.metadata.mockResolvedValue(liveSession)
    mount()
    const input = (await screen.findByLabelText('Stream title')) as HTMLInputElement
    fireEvent.change(input, { target: { value: 'Unsaved Draft Title' } })
    act(() => {
      client.setQueryData(['stream', 'setup', 'owner'], {
        ...activeSetup,
        title: 'Changed elsewhere',
      })
      client.setQueryData(['stream', 'metadata', 'owner', sessionId], {
        ...liveSession,
        title: 'Changed elsewhere',
      })
    })
    expect(input.value).toBe('Unsaved Draft Title')
  })

  it('refetches metadata when playback access returns 409', async () => {
    const reconnecting = {
      ...liveSession,
      status: 'RECONNECTING',
      reconnectDeadlineAt: '2026-10-30T00:01:00Z',
    }
    mocks.current.mockResolvedValue(liveSession)
    mocks.metadata.mockResolvedValueOnce(liveSession).mockResolvedValue(reconnecting)
    mocks.access.mockRejectedValue({ isAxiosError: true, response: { status: 409 } })
    mount()
    await screen.findByText('Waiting for the stream to reconnect')
    expect(mocks.access).toHaveBeenCalledTimes(1)
    expect(mocks.metadata.mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(mocks.hls).toHaveLength(0)
  })

  it('stops the preview when metadata says the session no longer exists', async () => {
    mocks.current.mockResolvedValueOnce(liveSession).mockRejectedValue(new Error('service unavailable'))
    mocks.metadata.mockRejectedValue({ isAxiosError: true, response: { status: 404 } })
    mount()
    await screen.findByText('Stream session is unavailable.')
    expect(screen.queryByLabelText('Stream preview')).toBeNull()
  })

  it('shows offline chat guidance without a session and opens live chat for the streamer', async () => {
    mocks.current.mockResolvedValue(null)
    mount()
    await screen.findByText('Chat opens automatically when you start streaming in OBS.')
    cleanup()

    const live: StreamMetadata = {
      ...liveSession,
      currentViewerCount: 3,
      ownerUserId: 'owner',
    }
    mocks.current.mockResolvedValue(live)
    mocks.metadata.mockResolvedValue(live)
    mocks.access.mockResolvedValue({
      sessionId,
      playbackUrl: 'https://cdn.example/live/playlist.m3u8',
      readToken: 'tok',
      readExpiresAt: '2026-10-30T08:00:00Z',
    })
    mocks.chatHistory.mockResolvedValue({
      items: [
        {
          id: '00000000-0000-4000-8000-000000000001',
          cursor: 'cursor-1',
          streamId: sessionId,
          channelId,
          userId: 'viewer-1',
          clientMessageId: 'viewer-1-msg',
          sequence: 1,
          content: 'Hi streamer!',
          sender: { displayName: 'Viewer One', avatarUrl: null },
          createdAt: '2026-10-30T00:00:10Z',
        },
      ],
      hasOlder: false,
      hasNewer: false,
      olderCursor: 'cursor-1',
      newerCursor: 'cursor-1',
      watermark: 'head',
    } satisfies ChatPage)

    mount()
    const chatAside = await screen.findByLabelText('Stream chat')
    expect(within(chatAside).getByText('Stream Chat')).toBeTruthy()
    expect(await within(chatAside).findByText('Hi streamer!')).toBeTruthy()
    expect(within(chatAside).getByText('Viewer One').className).toContain('text-white')

    const input = within(chatAside).getByLabelText('Send a message') as HTMLInputElement
    input.focus()
    fireEvent.change(input, { target: { value: 'Welcome everyone!' } })
    fireEvent.click(within(chatAside).getByLabelText('Send chat message'))

    await within(chatAside).findByText('Welcome everyone!')
    const ownerName = within(chatAside).getByText('Owner')
    expect(ownerName.className).toContain('text-green-400')
    expect(ownerName.closest('.border-\\[\\#ff2b42\\]')).toBeTruthy()
    expect(document.activeElement).toBe(input)
  })
})
it('reopens playback after a fatal temporary HLS error with unchanged LIVE metadata', async () => {
  mocks.current.mockResolvedValue(liveSession)
  mount()
  await waitFor(() => expect(mocks.hls).toHaveLength(1))
  await act(async () => {
    (mocks.hls[0] as unknown as { handlers: Record<string, (...args: unknown[]) => void> })
      .handlers.error('error', { fatal: true, type: 'network', response: { code: 404 } })
  })
  expect(mocks.hls[0].destroy).toHaveBeenCalled()
  await act(async () => {
    await client.refetchQueries({ queryKey: ['stream', 'metadata', 'owner', sessionId], exact: true })
    await new Promise((resolve) => setTimeout(resolve, 4000))
  })
  expect(mocks.access).toHaveBeenCalledTimes(2)
  expect(mocks.hls).toHaveLength(2)
}, 10000)
it('clears permanent credentials when the authenticated owner changes', async () => {
  mount()
  const { queryClient: applicationClient } = await import('@/lib/query-client')
  applicationClient.setQueryData(['stream', 'setup', 'owner'], activeSetup)
  await act(async () => {
    const owner = useAuthStore.getState().user!
    useAuthStore.getState().setUser({ ...owner, userId: 'another-owner' })
  })
  expect(applicationClient.getQueryData(['stream', 'setup', 'owner'])).toBeUndefined()
  applicationClient.clear()
})

it('cancels a scheduled HLS reopen when the preview unmounts', async () => {
  mocks.current.mockResolvedValue(liveSession)
  const view = mount()
  await waitFor(() => expect(mocks.hls).toHaveLength(1))
  await act(async () => {
    (mocks.hls[0] as unknown as { handlers: Record<string, (...args: unknown[]) => void> })
      .handlers.error('error', { fatal: true, type: 'network', response: { code: 503 } })
  })
  view.unmount()
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 1200)) })
  expect(mocks.access).toHaveBeenCalledTimes(1)
})

it('saves metadata separately and reveals a newly issued key only until copied', async () => {
  mocks.setup.mockResolvedValue({ ...draftSetup, title: 'Morning', categoryId })
  mocks.saveSetup.mockResolvedValue({ ...draftSetup, title: 'Changed', categoryId })
  mount()
  const title = await screen.findByLabelText('Stream title')
  fireEvent.change(title, { target: { value: 'Changed' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))
  await waitFor(() => expect(mocks.saveSetup).toHaveBeenCalledTimes(1))
  expect(mocks.issueKey).not.toHaveBeenCalled()
  expect(screen.queryByLabelText('Stream key')).toBeNull()
  mocks.setup.mockResolvedValue(activeSetup)
  fireEvent.click(screen.getByRole('button', { name: 'Generate stream key' }))
  const key = await screen.findByLabelText('Stream key') as HTMLInputElement
  expect(key.type).toBe('password')
  expect(key.value).toBe(issuedKey.streamKey)
  expect(JSON.stringify(client.getQueryCache().getAll().map((query) => query.state.data)))
    .not.toContain('secret-jwt-1')
  Object.defineProperty(navigator, 'clipboard', { configurable: true,
    value: { writeText: vi.fn().mockResolvedValue(undefined) } })
  fireEvent.click(within(key.parentElement!).getByRole('button', { name: 'Copy' }))
  await waitFor(() => expect(screen.queryByLabelText('Stream key')).toBeNull())
  expect(mocks.issueKey).toHaveBeenCalledTimes(1)
})

it('never reveals an existing key on reload and requires confirmation to generate another', async () => {
  mount()
  await screen.findByRole('button', { name: 'Generate new stream key' })
  expect(screen.queryByLabelText('Stream key')).toBeNull()
  expect(mocks.issueKey).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Generate new stream key' }))
  const dialog = await screen.findByRole('dialog')
  fireEvent.click(within(dialog).getByRole('button', { name: 'Reset stream key' }))
  await screen.findByDisplayValue(issuedKey.streamKey)
  expect(mocks.issueKey).toHaveBeenCalledTimes(1)
  cleanup()
  mount()
  await screen.findByRole('button', { name: 'Generate new stream key' })
  expect(screen.queryByLabelText('Stream key')).toBeNull()
})

it('keeps one-time credentials visible when copying fails', async () => {
  mocks.setup.mockResolvedValue({ ...activeSetup, hasStreamKey: false })
  Object.defineProperty(navigator, 'clipboard', { configurable: true,
    value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
  mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Generate stream key' }))
  const key = await screen.findByLabelText('Stream key')
  fireEvent.click(within(key.parentElement!).getByRole('button', { name: 'Copy' }))
  await waitFor(() => expect(mocks.toast).toHaveBeenCalledWith(expect.stringContaining('Could not copy stream key')))
  expect(screen.getByLabelText('Stream key')).toBeTruthy()
})

it('returns a synchronous issuance error without polling or retrying key generation', async () => {
  mocks.setup.mockResolvedValue({ ...activeSetup, hasStreamKey: false })
  mocks.issueKey.mockRejectedValue({ isAxiosError: true, response: { status: 503 } })
  mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Generate stream key' }))
  await waitFor(() => expect(mocks.toast).toHaveBeenCalledWith(expect.stringContaining('unavailable')))
  expect(mocks.issueKey).toHaveBeenCalledTimes(1)
  expect(screen.queryByLabelText('Stream key')).toBeNull()
})
