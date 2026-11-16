import { type ReactNode } from 'react'
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
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { apiClient } from '@/api/client'
import { StreamChatPanel } from '@/components/chat/StreamChatPanel'
import { StreamerDashboardLayout } from '@/components/layout/StreamerDashboardLayout'
import { BannedUsersPage } from '@/pages/dashboard/BannedUsersPage'
import { StreamerDashboardPage } from '@/pages/dashboard/StreamerDashboardPage'
import { queryClient as appQueryClient } from '@/lib/query-client'
import { useAuthStore } from '@/stores/auth.store'
import type { ChatAck, ChatJoin, ChatMessage, ChatPage } from '@/types/chat.types'
import type {
  BannedUsersPage as BannedUsersPageData,
  ChatModerationContext,
  TargetChatBanStatus,
} from '@/types/chat-moderation.types'
import type { StreamSession, StreamSetup } from '@/types/stream.types'

const SESSION_ID = '462c912c-ee66-4477-9852-bed13d377297'
const SESSION_ID_2 = '562c912c-ee66-4477-9852-bed13d377298'
const CHANNEL_ID = '20000000-0000-4000-8000-000000000010'
const CHANNEL_ID_2 = '20000000-0000-4000-8000-000000000020'
const OWNER_ID = '10000000-0000-4000-8000-000000000001'
const VIEWER_ID = '10000000-0000-4000-8000-000000000002'
const TARGET_ID = '10000000-0000-4000-8000-000000000003'
const TARGET_ID_2 = '10000000-0000-4000-8000-000000000004'
const CATEGORY_ID = '00000000-0000-4000-8000-000000000001'

type JoinAckHandler = (
  error: Error | null,
  ack?: ChatAck<ChatJoin>,
) => void

type SendAckHandler = (
  error: Error | null,
  ack?: ChatAck<ChatMessage>,
) => void

class FakeSocket {
  connected = false
  handlers = new Map<string, ((...args: unknown[]) => void)[]>()
  joinCalls = 0
  sent: { clientMessageId: string; content: string }[] = []

  on(event: string, handler: (...args: never[]) => void) {
    this.handlers.set(event, [
      ...(this.handlers.get(event) ?? []),
      handler as (...args: unknown[]) => void,
    ])
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
  emit(
    event: string,
    input: { clientMessageId?: string; content?: string },
    callback?: (...args: unknown[]) => void,
  ) {
    if (event === 'chat:join') {
      this.joinCalls += 1
      queueMicrotask(() => {
        mocks.onJoin(this.joinCalls, callback as JoinAckHandler)
      })
    }
    if (event === 'chat:send') {
      this.sent.push({
        clientMessageId: input.clientMessageId ?? '',
        content: input.content ?? '',
      })
      queueMicrotask(() => {
        mocks.onSend(input, callback as SendAckHandler)
      })
    }
    return this
  }
}

const mocks = vi.hoisted(() => ({
  history: vi.fn(),
  getOwnChannel: vi.fn(),
  getCategories: vi.fn(),
  setup: vi.fn(),
  current: vi.fn(),
  metadata: vi.fn(),
  access: vi.fn(),
  getModerationContext: vi.fn(),
  getTargetBanStatus: vi.fn(),
  banChatUser: vi.fn(),
  unbanChatUser: vi.fn(),
  getBannedUsers: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
  sockets: [] as FakeSocket[],
  onJoin: vi.fn((_attempt: number, cb: JoinAckHandler) => {
    cb(null, {
      ok: true,
      data: {
        sessionId: SESSION_ID,
        canRead: true,
        canSend: true,
        watermark: 'head',
      },
    })
  }),
  onSend: vi.fn(
    (
      input: { clientMessageId?: string; content?: string },
      cb: SendAckHandler,
    ) => {
      cb(null, {
        ok: true,
        data: chatMessage(99, VIEWER_ID, 'Viewer', input.content ?? '', {
          clientMessageId: input.clientMessageId,
        }),
      })
    },
  ),
  virtualItemsOverride: null as
    | ((count: number) => Array<{ index: number; start: number; end: number; size: number }>)
    | null,
}))

vi.mock('socket.io-client', () => ({
  io: () => {
    const socket = new FakeSocket()
    mocks.sockets.push(socket)
    return socket
  },
}))
vi.mock('@/api/chat.api', () => ({ chatApi: { history: mocks.history } }))
vi.mock('@/api/channel.api', () => ({
  channelApi: {
    getOwnChannel: mocks.getOwnChannel,
    getCategories: mocks.getCategories,
  },
}))
vi.mock('@/api/stream.api', () => ({
  streamApi: {
    setup: mocks.setup,
    current: mocks.current,
    metadata: mocks.metadata,
    access: mocks.access,
  },
}))
vi.mock('@/api/chat-moderation.api', async (importOriginal) => {
  const actual =
    await importOriginal<typeof import('@/api/chat-moderation.api')>()
  return {
    ...actual,
    chatModerationApi: {
      getModerationContext: mocks.getModerationContext,
      getTargetBanStatus: mocks.getTargetBanStatus,
      banChatUser: mocks.banChatUser,
      unbanChatUser: mocks.unbanChatUser,
      getBannedUsers: mocks.getBannedUsers,
    },
  }
})
vi.mock('sonner', () => ({
  toast: {
    success: mocks.toastSuccess,
    error: mocks.toastError,
  },
}))
vi.mock('hls.js', () => {
  class FakeHls {
    static isSupported() {
      return false
    }
    static Events = { MANIFEST_PARSED: 'parsed', ERROR: 'error' }
    static ErrorTypes = { MEDIA_ERROR: 'media', NETWORK_ERROR: 'network' }
  }
  return { default: FakeHls, FetchLoader: class {} }
})
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: (options: { count: number }) => ({
    getTotalSize: () => options.count * 64,
    getVirtualItems: () =>
      mocks.virtualItemsOverride
        ? mocks.virtualItemsOverride(options.count)
        : Array.from({ length: options.count }, (_, index) => ({
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

function chatMessage(
  sequence: number,
  userId: string,
  displayName: string,
  content: string,
  options?: { avatarUrl?: string | null; clientMessageId?: string },
): ChatMessage {
  return {
    id: `00000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`,
    cursor: `cursor-${sequence}`,
    streamId: SESSION_ID,
    channelId: CHANNEL_ID,
    userId,
    clientMessageId: options?.clientMessageId ?? `client-${sequence}`,
    sequence,
    content,
    sender: {
      displayName,
      avatarUrl: options?.avatarUrl ?? null,
    },
    createdAt: '2026-11-16T10:00:00Z',
  }
}

function chatPage(items: ChatMessage[]): ChatPage {
  return {
    items,
    olderCursor: items[0]?.cursor ?? null,
    newerCursor: items.at(-1)?.cursor ?? 'head',
    hasOlder: false,
    hasNewer: false,
    watermark: items.at(-1)?.cursor ?? 'head',
  }
}

function makeAxiosError(
  status: number,
  code?: number,
  message = 'Request failed',
) {
  return new AxiosError(
    message,
    'ERR_BAD_RESPONSE',
    {} as InternalAxiosRequestConfig,
    null,
    {
      status,
      statusText: message,
      data: { status, code, message },
      headers: {},
      config: {} as InternalAxiosRequestConfig,
    },
  )
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function setAuthUser(userId: string, roles: string[] = ['USER']) {
  useAuthStore.setState({
    isCheckingAuth: false,
    isAuthenticated: true,
    isAuthModalOpen: false,
    user: {
      userId,
      displayName: userId === OWNER_ID ? 'ChannelOwner' : 'ViewerUser',
      email: `${userId}@example.com`,
      emailVerified: true,
      roles,
      avatarUrl: null,
      dob: null,
    },
  })
}

let client: QueryClient

function mountWithRouter(
  node: ReactNode,
  initialEntries: string[] = ['/'],
) {
  client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={initialEntries}>{node}</MemoryRouter>
      </QueryClientProvider>,
    ),
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.history.mockReset()
  mocks.getOwnChannel.mockReset()
  mocks.getCategories.mockReset()
  mocks.setup.mockReset()
  mocks.current.mockReset()
  mocks.metadata.mockReset()
  mocks.access.mockReset()
  mocks.getModerationContext.mockReset()
  mocks.getTargetBanStatus.mockReset()
  mocks.banChatUser.mockReset()
  mocks.unbanChatUser.mockReset()
  mocks.getBannedUsers.mockReset()
  mocks.onJoin.mockReset()
  mocks.onSend.mockReset()
  mocks.sockets.length = 0
  mocks.virtualItemsOverride = null
  setAuthUser(OWNER_ID, ['USER', 'STREAMER'])

  mocks.onJoin.mockImplementation((_attempt, cb) => {
    cb(null, {
      ok: true,
      data: {
        sessionId: SESSION_ID,
        canRead: true,
        canSend: true,
        watermark: 'head',
      },
    })
  })
  mocks.onSend.mockImplementation((input, cb) => {
    cb(null, {
      ok: true,
      data: chatMessage(99, VIEWER_ID, 'ViewerUser', input.content ?? '', {
        clientMessageId: input.clientMessageId,
      }),
    })
  })

  mocks.history.mockResolvedValue(chatPage([]))
  mocks.getOwnChannel.mockResolvedValue({
    id: CHANNEL_ID,
    streamerId: OWNER_ID,
    name: 'Owner Channel',
    slug: 'owner-channel',
    description: null,
    avatarUrl: null,
    bannerUrl: null,
    status: 'ACTIVE',
    categories: [],
    numberOfFollower: 10,
  })
  mocks.getCategories.mockResolvedValue([
    { id: CATEGORY_ID, name: 'Gaming', slug: 'gaming', thumbnailUrl: null },
  ])
  mocks.setup.mockResolvedValue({
    channelId: CHANNEL_ID,
    title: 'Live Stream',
    categoryId: CATEGORY_ID,
    visibility: 'PUBLIC',
    status: 'ACTIVE',
    hasStreamKey: true,
    blockedReason: null,
  } satisfies StreamSetup)
  mocks.current.mockResolvedValue({
    id: SESSION_ID,
    channelId: CHANNEL_ID,
    title: 'Live Stream',
    categoryId: CATEGORY_ID,
    visibility: 'PUBLIC',
    status: 'LIVE',
    startedAt: '2026-11-16T09:00:00Z',
    endedAt: null,
    reconnectDeadlineAt: null,
    serverTime: '2026-11-16T10:00:00Z',
    publishReady: true,
  } satisfies StreamSession)
  mocks.metadata.mockResolvedValue({
    id: SESSION_ID,
    channelId: CHANNEL_ID,
    ownerUserId: OWNER_ID,
    title: 'Live Stream',
    categoryId: CATEGORY_ID,
    visibility: 'PUBLIC',
    status: 'LIVE',
    startedAt: '2026-11-16T09:00:00Z',
    endedAt: null,
    reconnectDeadlineAt: null,
    serverTime: '2026-11-16T10:00:00Z',
    currentViewerCount: 5,
  })
  mocks.access.mockResolvedValue({
    sessionId: SESSION_ID,
    playbackUrl: `http://localhost:8898/live/${SESSION_ID}/index.m3u8`,
    readToken: 'tok',
    readExpiresAt: '2026-11-16T12:00:00Z',
  })

  mocks.getModerationContext.mockResolvedValue({
    channelId: CHANNEL_ID,
    ownerUserId: OWNER_ID,
    canBan: true,
    canUnban: true,
    canViewBans: true,
  } satisfies ChatModerationContext)
  mocks.getTargetBanStatus.mockResolvedValue({
    channelId: CHANNEL_ID,
    userId: TARGET_ID,
    banType: null,
  } satisfies TargetChatBanStatus)
  mocks.banChatUser.mockResolvedValue({
    channelId: CHANNEL_ID,
    userId: TARGET_ID,
    banType: 'CHAT_BAN',
  })
  mocks.unbanChatUser.mockResolvedValue({
    channelId: CHANNEL_ID,
    userId: TARGET_ID,
    banType: null,
  })
  mocks.getBannedUsers.mockResolvedValue({
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
  } satisfies BannedUsersPageData)
})

afterEach(() => {
  cleanup()
  client?.clear()
  appQueryClient.clear()
})

describe('chat moderation API contract', () => {
  it('calls exact backend endpoints, methods, params, and omits POST bodies', async () => {
    const actualMod = await vi.importActual<
      typeof import('@/api/chat-moderation.api')
    >('@/api/chat-moderation.api')
    const originalAdapter = apiClient.defaults.adapter
    const requests: InternalAxiosRequestConfig[] = []
    apiClient.defaults.adapter = async (config): Promise<AxiosResponse> => {
      requests.push(config)
      return {
        data: { status: 200, code: 1000, message: 'OK', result: {} },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }

    try {
      await actualMod.chatModerationApi.getModerationContext(CHANNEL_ID)
      await actualMod.chatModerationApi.getTargetBanStatus(CHANNEL_ID, TARGET_ID)
      await actualMod.chatModerationApi.banChatUser(CHANNEL_ID, TARGET_ID)
      await actualMod.chatModerationApi.unbanChatUser(CHANNEL_ID, TARGET_ID)
      await actualMod.chatModerationApi.getBannedUsers(CHANNEL_ID, {
        page: 1,
        size: 20,
      })

      expect(requests.map((r) => [r.method, r.url])).toEqual([
        ['get', `/channel/api/channels/${CHANNEL_ID}/chat/moderation-context`],
        [
          'get',
          `/channel/api/channels/${CHANNEL_ID}/chat/users/${TARGET_ID}/ban`,
        ],
        [
          'post',
          `/channel/api/channels/${CHANNEL_ID}/chat/users/${TARGET_ID}/ban`,
        ],
        [
          'post',
          `/channel/api/channels/${CHANNEL_ID}/chat/users/${TARGET_ID}/unban`,
        ],
        ['get', `/channel/api/channels/${CHANNEL_ID}/chat/bans`],
      ])
      expect(requests.every((r) => r.withCredentials)).toBe(true)
      expect(requests[2].data).toBeUndefined()
      expect(requests[3].data).toBeUndefined()
      expect(requests[4].params).toEqual({ page: 1, size: 20 })
    } finally {
      apiClient.defaults.adapter = originalAdapter
    }
  })

  it('does not call moderation APIs when channelId is missing', async () => {
    mocks.history.mockResolvedValue(
      chatPage([chatMessage(1, TARGET_ID, 'ViewerA', 'Hello')]),
    )
    mountWithRouter(
      <StreamChatPanel
        sessionId={SESSION_ID}
        status="LIVE"
        streamerUserId={OWNER_ID}
      />,
    )
    await screen.findByText('Hello')
    expect(mocks.getModerationContext).not.toHaveBeenCalled()
    expect(mocks.getTargetBanStatus).not.toHaveBeenCalled()
  })
})

describe('avatar ban menu in chat', () => {
  it('lets authorized owner open avatar menu, checks status on open only, and bans user immediately', async () => {
    const targetMsg = chatMessage(1, TARGET_ID, 'TargetViewer', 'Spam msg', {
      avatarUrl: 'https://cdn.example.com/target.jpg',
    })
    const ownerMsg = chatMessage(2, OWNER_ID, 'ChannelOwner', 'Welcome!')
    mocks.history.mockResolvedValue(chatPage([targetMsg, ownerMsg]))

    mountWithRouter(
      <StreamChatPanel
        sessionId={SESSION_ID}
        channelId={CHANNEL_ID}
        status="LIVE"
        streamerUserId={OWNER_ID}
      />,
    )

    await screen.findByText('Spam msg')
    // No per-message status API calls during list render
    expect(mocks.getTargetBanStatus).not.toHaveBeenCalled()

    // Owner avatar is not interactive
    expect(
      screen.queryByRole('button', { name: /Moderation options for ChannelOwner/i }),
    ).toBeNull()

    // Click target avatar button
    const trigger = screen.getByRole('button', {
      name: 'Moderation options for TargetViewer',
    })
    fireEvent.click(trigger)

    expect(mocks.getTargetBanStatus).toHaveBeenCalledTimes(1)
    expect(mocks.getTargetBanStatus).toHaveBeenCalledWith(
      CHANNEL_ID,
      TARGET_ID,
      expect.any(AbortSignal),
    )

    const menu = await screen.findByRole('menu', {
      name: 'Moderation options for TargetViewer',
    })
    const banButton = await within(menu).findByRole('menuitem', {
      name: 'Ban from chat',
    })
    expect(within(menu).queryByRole('menuitem', { name: /Unban/i })).toBeNull()

    fireEvent.click(banButton)

    await waitFor(() =>
      expect(mocks.banChatUser).toHaveBeenCalledWith(CHANNEL_ID, TARGET_ID),
    )
    await waitFor(() =>
      expect(
        screen.queryByRole('menu', {
          name: 'Moderation options for TargetViewer',
        }),
      ).toBeNull(),
    )
    expect(mocks.toastSuccess).toHaveBeenCalledWith('User banned from chat')
  })

  it('keeps avatar non-interactive for regular users or when canBan is false even if user has STREAMER role', async () => {
    setAuthUser(OWNER_ID, ['USER', 'STREAMER'])
    mocks.getModerationContext.mockResolvedValue({
      channelId: CHANNEL_ID,
      ownerUserId: OWNER_ID,
      canBan: false,
      canUnban: false,
      canViewBans: false,
    })
    mocks.history.mockResolvedValue(
      chatPage([chatMessage(1, TARGET_ID, 'ViewerOne', 'Hi')]),
    )

    mountWithRouter(
      <StreamChatPanel
        sessionId={SESSION_ID}
        channelId={CHANNEL_ID}
        status="LIVE"
        streamerUserId={OWNER_ID}
      />,
    )

    await screen.findByText('Hi')
    await waitFor(() => expect(mocks.getModerationContext).toHaveBeenCalled())
    expect(
      screen.queryByRole('button', { name: /Moderation options/i }),
    ).toBeNull()
    expect(
      screen.queryByRole('link', { name: 'Manage banned users' }),
    ).toBeNull()
  })

  it('supports fallback avatar interaction when avatarUrl is null or fails to load', async () => {
    mocks.history.mockResolvedValue(
      chatPage([
        chatMessage(1, TARGET_ID, 'FallbackUser', 'No avatar', {
          avatarUrl: 'https://cdn.example.com/broken.jpg',
        }),
      ]),
    )

    mountWithRouter(
      <StreamChatPanel
        sessionId={SESSION_ID}
        channelId={CHANNEL_ID}
        status="LIVE"
        streamerUserId={OWNER_ID}
      />,
    )

    await screen.findByText('No avatar')
    const trigger = screen.getByRole('button', {
      name: 'Moderation options for FallbackUser',
    })
    const img = trigger.querySelector('img')!
    fireEvent.error(img)
    expect(within(trigger).getByText('F')).toBeTruthy()

    fireEvent.click(trigger)
    expect(
      await screen.findByRole('menu', {
        name: 'Moderation options for FallbackUser',
      }),
    ).toBeTruthy()
  })

  it('handles loading, status fetch error + retry, already CHAT_BAN, and LIVE_VIEW_BAN / FULL_BAN', async () => {
    const pendingStatus = deferred<TargetChatBanStatus>()
    mocks.getTargetBanStatus.mockReturnValueOnce(pendingStatus.promise)
    mocks.history.mockResolvedValue(
      chatPage([
        chatMessage(1, TARGET_ID, 'UserA', 'Msg A'),
        chatMessage(2, TARGET_ID_2, 'UserB', 'Msg B'),
      ]),
    )

    mountWithRouter(
      <StreamChatPanel
        sessionId={SESSION_ID}
        channelId={CHANNEL_ID}
        status="LIVE"
        streamerUserId={OWNER_ID}
      />,
    )

    await screen.findByText('Msg A')
    fireEvent.click(
      screen.getByRole('button', { name: 'Moderation options for UserA' }),
    )
    expect(await screen.findByText('Checking…')).toBeTruthy()

    await act(async () => {
      pendingStatus.reject(makeAxiosError(503, undefined, 'Unavailable'))
    })
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.getByText(/temporarily unavailable/i)).toBeTruthy()

    mocks.getTargetBanStatus.mockResolvedValueOnce({
      channelId: CHANNEL_ID,
      userId: TARGET_ID,
      banType: 'CHAT_BAN',
    })
    fireEvent.click(screen.getByRole('menuitem', { name: 'Retry' }))
    const alreadyBanned = await screen.findByRole('menuitem', {
      name: 'Already banned from chat',
    })
    expect(alreadyBanned.hasAttribute('disabled')).toBe(true)

    // Higher-level ban (LIVE_VIEW_BAN)
    mocks.getTargetBanStatus.mockResolvedValueOnce({
      channelId: CHANNEL_ID,
      userId: TARGET_ID_2,
      banType: 'LIVE_VIEW_BAN',
    })
    fireEvent.click(
      screen.getByRole('button', { name: 'Moderation options for UserB' }),
    )
    expect(await screen.findByText('Livestream ban')).toBeTruthy()
    expect(
      screen.getByText(/cannot be managed with chat ban/i),
    ).toBeTruthy()
    expect(
      screen.queryByRole('menuitem', { name: 'Ban from chat' }),
    ).toBeNull()

    // FULL_BAN
    mocks.getTargetBanStatus.mockResolvedValueOnce({
      channelId: CHANNEL_ID,
      userId: TARGET_ID_2,
      banType: 'FULL_BAN',
    })
    fireEvent.click(
      screen.getByRole('button', { name: 'Moderation options for UserB' }),
    )
    fireEvent.click(
      screen.getByRole('button', { name: 'Moderation options for UserB' }),
    )
    expect(await screen.findByText('Full ban')).toBeTruthy()
  })

  it('prevents double-click duplicate ban mutations', async () => {
    const pendingBan = deferred<{ channelId: string; userId: string; banType: 'CHAT_BAN' }>()
    mocks.banChatUser.mockReturnValueOnce(pendingBan.promise)
    mocks.history.mockResolvedValue(
      chatPage([chatMessage(1, TARGET_ID, 'FastClicker', 'Hello')]),
    )

    mountWithRouter(
      <StreamChatPanel
        sessionId={SESSION_ID}
        channelId={CHANNEL_ID}
        status="LIVE"
        streamerUserId={OWNER_ID}
      />,
    )

    await screen.findByText('Hello')
    fireEvent.click(
      screen.getByRole('button', { name: 'Moderation options for FastClicker' }),
    )
    const banBtn = await screen.findByRole('menuitem', { name: 'Ban from chat' })

    fireEvent.click(banBtn)
    fireEvent.click(banBtn)
    await waitFor(() => expect(mocks.banChatUser).toHaveBeenCalledTimes(1))

    await act(async () => {
      pendingBan.resolve({
        channelId: CHANNEL_ID,
        userId: TARGET_ID,
        banType: 'CHAT_BAN',
      })
    })
    await waitFor(() =>
      expect(screen.queryByRole('menu')).toBeNull(),
    )
  })

  it('keeps selected target userId safe when virtualized rows change and closes menu on Escape or anchor scroll loss', async () => {
    const firstMsg = chatMessage(1, TARGET_ID, 'SelectedUser', 'First')
    const secondMsg = chatMessage(2, TARGET_ID_2, 'OtherUser', 'Second')
    mocks.history.mockResolvedValue(chatPage([firstMsg, secondMsg]))

    mountWithRouter(
      <StreamChatPanel
        sessionId={SESSION_ID}
        channelId={CHANNEL_ID}
        status="LIVE"
        streamerUserId={OWNER_ID}
      />,
    )

    await screen.findByText('First')
    const trigger = screen.getByRole('button', {
      name: 'Moderation options for SelectedUser',
    })
    fireEvent.click(trigger)
    const menu = await screen.findByRole('menu', {
      name: 'Moderation options for SelectedUser',
    })
    await within(menu).findByRole('menuitem', { name: 'Ban from chat' })

    // Simulate a new message arriving from another user at top/bottom shifting row order
    await act(async () => {
      mocks.sockets[0].trigger(
        'chat:message',
        chatMessage(3, TARGET_ID_2, 'OtherUser', 'Third'),
      )
    })

    // Clicking Ban still bans TARGET_ID (not TARGET_ID_2)
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Ban from chat' }))
    await waitFor(() =>
      expect(mocks.banChatUser).toHaveBeenCalledWith(CHANNEL_ID, TARGET_ID),
    )

    // Reopen and test Escape + focus return
    mocks.getTargetBanStatus.mockResolvedValue({
      channelId: CHANNEL_ID,
      userId: TARGET_ID,
      banType: null,
    })
    fireEvent.click(trigger)
    await screen.findByRole('menu', {
      name: 'Moderation options for SelectedUser',
    })
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(trigger))

    // Reopen and test closing when scrolling loses anchor
    fireEvent.click(trigger)
    await screen.findByRole('menu', {
      name: 'Moderation options for SelectedUser',
    })
    const scroller = screen.getByLabelText('Chat messages')
    vi.spyOn(scroller, 'getBoundingClientRect').mockReturnValue({
      top: 100,
      bottom: 500,
      left: 0,
      right: 300,
      width: 300,
      height: 400,
      x: 0,
      y: 100,
      toJSON: () => ({}),
    })
    vi.spyOn(trigger, 'getBoundingClientRect').mockReturnValue({
      top: 20,
      bottom: 50,
      left: 10,
      right: 38,
      width: 28,
      height: 28,
      x: 10,
      y: 20,
      toJSON: () => ({}),
    })
    fireEvent.scroll(scroller)
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
  })

  it('handles HTTP 409 on ban without faking success and refreshes target ban state', async () => {
    mocks.history.mockResolvedValue(
      chatPage([chatMessage(1, TARGET_ID, 'ConflictUser', 'Hi')]),
    )
    mocks.getTargetBanStatus
      .mockResolvedValueOnce({
        channelId: CHANNEL_ID,
        userId: TARGET_ID,
        banType: null,
      })
      .mockResolvedValueOnce({
        channelId: CHANNEL_ID,
        userId: TARGET_ID,
        banType: 'CHAT_BAN',
      })
    mocks.banChatUser.mockRejectedValueOnce(
      makeAxiosError(409, 1023, 'Already chat banned'),
    )

    mountWithRouter(
      <StreamChatPanel
        sessionId={SESSION_ID}
        channelId={CHANNEL_ID}
        status="LIVE"
        streamerUserId={OWNER_ID}
      />,
    )

    await screen.findByText('Hi')
    fireEvent.click(
      screen.getByRole('button', { name: 'Moderation options for ConflictUser' }),
    )
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Ban from chat' }))

    await waitFor(() =>
      expect(mocks.toastError).toHaveBeenCalledWith(
        'This user is already banned from chat.',
      ),
    )
    expect(mocks.toastSuccess).not.toHaveBeenCalled()
    expect(
      await screen.findByRole('menuitem', { name: 'Already banned from chat' }),
    ).toBeTruthy()
    expect(mocks.getTargetBanStatus).toHaveBeenCalledTimes(2)
  })

  it('revokes canBan and closes menu when backend returns 403', async () => {
    mocks.history.mockResolvedValue(
      chatPage([chatMessage(1, TARGET_ID, 'UserX', 'Test')]),
    )
    mocks.getModerationContext
      .mockResolvedValueOnce({
        channelId: CHANNEL_ID,
        ownerUserId: OWNER_ID,
        canBan: true,
        canUnban: true,
        canViewBans: true,
      })
      .mockResolvedValue({
        channelId: CHANNEL_ID,
        ownerUserId: OWNER_ID,
        canBan: false,
        canUnban: false,
        canViewBans: false,
      })
    mocks.banChatUser.mockRejectedValueOnce(makeAxiosError(403, undefined, 'Forbidden'))

    mountWithRouter(
      <StreamChatPanel
        sessionId={SESSION_ID}
        channelId={CHANNEL_ID}
        status="LIVE"
        streamerUserId={OWNER_ID}
      />,
    )

    await screen.findByText('Test')
    fireEvent.click(
      screen.getByRole('button', { name: 'Moderation options for UserX' }),
    )
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Ban from chat' }))

    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: 'Moderation options for UserX' }),
      ).toBeNull(),
    )
  })

  it('shows Manage banned users link in Watch chat header and embedded Stream Manager chat header', async () => {
    mountWithRouter(
      <Routes>
        <Route path="/dashboard" element={<StreamerDashboardPage />} />
      </Routes>,
      ['/dashboard'],
    )

    const aside = await screen.findByLabelText('Stream chat')
    const dashboardLink = await within(aside).findByRole('link', {
      name: 'Manage banned users',
    })
    expect(dashboardLink.getAttribute('href')).toBe('/dashboard/chat-bans')
    cleanup()

    mountWithRouter(
      <StreamChatPanel
        sessionId={SESSION_ID}
        channelId={CHANNEL_ID}
        status="LIVE"
        streamerUserId={OWNER_ID}
      />,
    )
    const watchLink = await screen.findByRole('link', {
      name: 'Manage banned users',
    })
    expect(watchLink.getAttribute('href')).toBe('/dashboard/chat-bans')
  })
})

describe('BannedUsersPage and dashboard navigation', () => {
  it('shows Banned users nav item only when canViewBans is true and renders paginated list with profile fallback', async () => {
    mocks.getBannedUsers
      .mockResolvedValueOnce({
        content: [
          {
            userId: TARGET_ID,
            displayName: '<b>BadActor</b>',
            avatarUrl: null,
            banType: 'CHAT_BAN',
            createdAt: '2026-11-16T08:30:00Z',
          },
          {
            userId: TARGET_ID_2,
            displayName: null,
            avatarUrl: null,
            banType: 'LIVE_VIEW_BAN',
            createdAt: '2026-11-16T07:30:00Z',
          },
        ],
        page: 0,
        size: 20,
        totalElements: 21,
        totalPages: 2,
      })
      .mockResolvedValueOnce({
        content: [
          {
            userId: '10000000-0000-4000-8000-000000000005',
            displayName: 'FullBannedUser',
            avatarUrl: null,
            banType: 'FULL_BAN',
            createdAt: '2026-11-16T06:30:00Z',
          },
        ],
        page: 1,
        size: 20,
        totalElements: 21,
        totalPages: 2,
      })

    mountWithRouter(
      <Routes>
        <Route element={<StreamerDashboardLayout />}>
          <Route path="/dashboard/chat-bans" element={<BannedUsersPage />} />
        </Route>
      </Routes>,
      ['/dashboard/chat-bans'],
    )

    const nav = await screen.findByRole('navigation', {
      name: 'Streamer navigation',
    })
    expect(
      await within(nav).findByRole('link', { name: 'Banned users' }),
    ).toBeTruthy()

    expect(await screen.findByText('Owner Channel')).toBeTruthy()
    // Literal text rendering (no HTML element injected)
    expect(screen.getByText('<b>BadActor</b>')).toBeTruthy()
    expect(document.querySelector('b')).toBeNull()
    // Missing profile fallback
    expect(screen.getByText('Unavailable user')).toBeTruthy()
    expect(screen.getByText(TARGET_ID_2)).toBeTruthy()
    // Badges and action restrictions
    expect(screen.getByText('Chat ban')).toBeTruthy()
    expect(screen.getByText('Livestream ban')).toBeTruthy()
    expect(screen.getByText('Cannot unban here')).toBeTruthy()
    expect(screen.getAllByRole('button', { name: 'Unban' })).toHaveLength(1)

    // Go to page 2
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('FullBannedUser')).toBeTruthy()
    expect(screen.getByText('Full ban')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Unban' })).toBeNull()
    expect(mocks.getBannedUsers).toHaveBeenLastCalledWith(
      CHANNEL_ID,
      { page: 1, size: 20 },
      expect.any(AbortSignal),
    )
  })

  it('unbans CHAT_BAN, prevents double click, and steps back when removing the last item of a page', async () => {
    const pendingUnban = deferred<{
      channelId: string
      userId: string
      banType: null
    }>()
    mocks.unbanChatUser.mockReturnValueOnce(pendingUnban.promise)

    mocks.getBannedUsers
      .mockResolvedValueOnce({
        content: [
          {
            userId: TARGET_ID,
            displayName: 'PageZeroUser',
            avatarUrl: null,
            banType: 'CHAT_BAN',
            createdAt: '2026-11-16T08:30:00Z',
          },
        ],
        page: 0,
        size: 20,
        totalElements: 21,
        totalPages: 2,
      })
      .mockResolvedValueOnce({
        content: [
          {
            userId: TARGET_ID_2,
            displayName: 'LastOnPageTwo',
            avatarUrl: null,
            banType: 'CHAT_BAN',
            createdAt: '2026-11-16T07:30:00Z',
          },
        ],
        page: 1,
        size: 20,
        totalElements: 21,
        totalPages: 2,
      })
      .mockResolvedValueOnce({
        content: [],
        page: 1,
        size: 20,
        totalElements: 20,
        totalPages: 1,
      })
      .mockResolvedValueOnce({
        content: [
          {
            userId: TARGET_ID,
            displayName: 'PageZeroUser',
            avatarUrl: null,
            banType: 'CHAT_BAN',
            createdAt: '2026-11-16T08:30:00Z',
          },
        ],
        page: 0,
        size: 20,
        totalElements: 20,
        totalPages: 1,
      })

    mountWithRouter(
      <Routes>
        <Route path="/dashboard/chat-bans" element={<BannedUsersPage />} />
      </Routes>,
      ['/dashboard/chat-bans'],
    )

    await screen.findByText('PageZeroUser')
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('LastOnPageTwo')

    const unbanButton = screen.getByRole('button', { name: 'Unban' })
    fireEvent.click(unbanButton)
    fireEvent.click(unbanButton)
    await waitFor(() => expect(mocks.unbanChatUser).toHaveBeenCalledTimes(1))
    expect(mocks.unbanChatUser).toHaveBeenCalledWith(CHANNEL_ID, TARGET_ID_2)

    await act(async () => {
      pendingUnban.resolve({
        channelId: CHANNEL_ID,
        userId: TARGET_ID_2,
        banType: null,
      })
    })

    expect(mocks.toastSuccess).toHaveBeenCalledWith('User unbanned from chat')
    expect(await screen.findByText('PageZeroUser')).toBeTruthy()
    expect(screen.getByText('Page 1 of 1')).toBeTruthy()
  })

  it('handles 409 on unban without faking success and refreshes list, and supports empty/error states', async () => {
    mocks.getBannedUsers
      .mockRejectedValueOnce(makeAxiosError(503, undefined, 'Service down'))
      .mockResolvedValueOnce({
        content: [
          {
            userId: TARGET_ID,
            displayName: 'AlreadyUnbanned',
            avatarUrl: null,
            banType: 'CHAT_BAN',
            createdAt: '2026-11-16T08:30:00Z',
          },
        ],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      })
      .mockResolvedValueOnce({
        content: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      })
    mocks.unbanChatUser.mockRejectedValueOnce(
      makeAxiosError(409, 1024, 'No active chat ban'),
    )

    mountWithRouter(
      <Routes>
        <Route path="/dashboard/chat-bans" element={<BannedUsersPage />} />
      </Routes>,
      ['/dashboard/chat-bans'],
    )

    expect(await screen.findByText('Unable to load banned users')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    await screen.findByText('AlreadyUnbanned')
    fireEvent.click(screen.getByRole('button', { name: 'Unban' }))

    await waitFor(() =>
      expect(mocks.toastError).toHaveBeenCalledWith(
        'This user does not have an active chat ban.',
      ),
    )
    expect(mocks.toastSuccess).not.toHaveBeenCalled()
    expect(await screen.findByText('No banned users')).toBeTruthy()
  })

  it('hides Banned users link and blocks page when user lacks canViewBans', async () => {
    mocks.getModerationContext.mockResolvedValue({
      channelId: CHANNEL_ID,
      ownerUserId: OWNER_ID,
      canBan: false,
      canUnban: false,
      canViewBans: false,
    })

    mountWithRouter(
      <Routes>
        <Route element={<StreamerDashboardLayout />}>
          <Route path="/dashboard/chat-bans" element={<BannedUsersPage />} />
        </Route>
      </Routes>,
      ['/dashboard/chat-bans'],
    )

    expect(await screen.findByText('Permission required')).toBeTruthy()
    const nav = screen.getByRole('navigation', { name: 'Streamer navigation' })
    expect(within(nav).queryByRole('link', { name: 'Banned users' })).toBeNull()
    expect(mocks.getBannedUsers).not.toHaveBeenCalled()
  })
})

describe('CHAT_BANNED socket handling and Check chat access', () => {
  it('locks sending on CHAT_BANNED without blocking watch page or wiping history, keeps draft, and restores on Check chat access', async () => {
    setAuthUser(VIEWER_ID, ['USER'])
    const onAccessError = vi.fn()
    mocks.getModerationContext.mockResolvedValue({
      channelId: CHANNEL_ID,
      ownerUserId: OWNER_ID,
      canBan: false,
      canUnban: false,
      canViewBans: false,
    })
    mocks.history.mockResolvedValue(
      chatPage([chatMessage(1, OWNER_ID, 'Streamer', 'Existing message')]),
    )
    mocks.onSend.mockImplementationOnce((_input, cb) => {
      cb(null, {
        ok: false,
        error: {
          code: 'CHAT_BANNED',
          status: 403,
          message: 'You are banned from chat.',
          retryable: false,
        },
      })
    })

    mountWithRouter(
      <StreamChatPanel
        sessionId={SESSION_ID}
        channelId={CHANNEL_ID}
        status="LIVE"
        streamerUserId={OWNER_ID}
        onAccessError={onAccessError}
      />,
    )

    await screen.findByText('Existing message')
    const input = screen.getByLabelText('Send a message') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'My unsent draft' } })
    fireEvent.click(screen.getByLabelText('Send chat message'))

    expect(
      await screen.findByText(
        'You are banned from sending messages in this channel.',
      ),
    ).toBeTruthy()
    expect(onAccessError).not.toHaveBeenCalled()
    expect(input.disabled).toBe(true)
    expect(input.value).toBe('My unsent draft')
    expect(screen.getByText('Existing message')).toBeTruthy()

    // Check chat access while still banned
    const pendingJoin = deferred<ChatAck<ChatJoin>>()
    mocks.onJoin.mockImplementationOnce((_attempt, cb) => {
      void pendingJoin.promise.then((ack) => cb(null, ack))
    })

    const checkBtn = screen.getByRole('button', { name: 'Check chat access' })
    fireEvent.click(checkBtn)
    fireEvent.click(screen.getByRole('button', { name: 'Checking access…' }))
    // Only 1 additional join (initial join = 1, plus this single click = 2)
    expect(mocks.sockets[0].joinCalls).toBe(2)

    await act(async () => {
      pendingJoin.resolve({
        ok: true,
        data: {
          sessionId: SESSION_ID,
          canRead: true,
          canSend: false,
          watermark: 'cursor-1',
        },
      })
    })
    expect(
      await screen.findByText(/still cannot send messages/i),
    ).toBeTruthy()
    expect(input.disabled).toBe(true)

    // Network/timeout on check keeps chat readable and allows retry
    mocks.onJoin.mockImplementationOnce((_attempt, cb) => {
      cb(new Error('timeout'))
    })
    fireEvent.click(screen.getByRole('button', { name: 'Check chat access' }))
    expect(
      await screen.findByText('Unable to check chat access. Please try again.'),
    ).toBeTruthy()
    expect(screen.getByText('Existing message')).toBeTruthy()

    // Now unbanned -> Check chat access restores sending without auto-sending draft
    mocks.onJoin.mockImplementationOnce((_attempt, cb) => {
      cb(null, {
        ok: true,
        data: {
          sessionId: SESSION_ID,
          canRead: true,
          canSend: true,
          watermark: 'cursor-1',
        },
      })
    })
    fireEvent.click(screen.getByRole('button', { name: 'Check chat access' }))
    await waitFor(() => expect(input.disabled).toBe(false))
    expect(
      screen.queryByText(
        'You are banned from sending messages in this channel.',
      ),
    ).toBeNull()
    expect(input.value).toBe('My unsent draft')
    expect(mocks.sockets[0].sent).toHaveLength(1)
  })
})

describe('session, channel, and account isolation', () => {
  it('clears moderation cache on logout/user switch and closes open menu when switching sessions or channels', async () => {
    mocks.history.mockResolvedValue(
      chatPage([chatMessage(1, TARGET_ID, 'ViewerOne', 'Msg 1')]),
    )

    const { rerender } = mountWithRouter(
      <StreamChatPanel
        sessionId={SESSION_ID}
        channelId={CHANNEL_ID}
        status="LIVE"
        streamerUserId={OWNER_ID}
      />,
    )

    await screen.findByText('Msg 1')
    fireEvent.click(
      screen.getByRole('button', { name: 'Moderation options for ViewerOne' }),
    )
    await screen.findByRole('menu', {
      name: 'Moderation options for ViewerOne',
    })

    // Switch sessionId -> closes menu and scopes new context
    rerender(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <StreamChatPanel
            sessionId={SESSION_ID_2}
            channelId={CHANNEL_ID_2}
            status="LIVE"
            streamerUserId={OWNER_ID}
          />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    await waitFor(() =>
      expect(mocks.getModerationContext).toHaveBeenCalledWith(
        CHANNEL_ID_2,
        expect.any(AbortSignal),
      ),
    )

    // Auth store logout/switch clears chat-moderation cache in appQueryClient
    appQueryClient.setQueryData(
      ['chat-moderation', 'context', OWNER_ID, CHANNEL_ID],
      {
        channelId: CHANNEL_ID,
        ownerUserId: OWNER_ID,
        canBan: true,
        canUnban: true,
        canViewBans: true,
      },
    )
    await act(async () => {
      useAuthStore.getState().setUser({
        ...useAuthStore.getState().user!,
        userId: VIEWER_ID,
      })
    })
    expect(
      appQueryClient.getQueryData([
        'chat-moderation',
        'context',
        OWNER_ID,
        CHANNEL_ID,
      ]),
    ).toBeUndefined()
  })
})
