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
import {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { apiClient } from '@/api/client'
import { StreamChatPanel } from '@/components/chat/StreamChatPanel'
import { StreamerDashboardLayout } from '@/components/layout/StreamerDashboardLayout'
import { BannedUsersPage } from '@/pages/dashboard/BannedUsersPage'
import { StreamerDashboardPage } from '@/pages/dashboard/StreamerDashboardPage'
import { queryClient as appQueryClient } from '@/lib/query-client'
import { useAuthStore } from '@/stores/auth.store'
import type { ChatAck, ChatJoin, ChatMessage, ChatPage } from '@/types/chat.types'
import type {
  BanType,
  BannedUsersPage as BannedUsersPageData,
  ChannelModerationContext,
  TargetChannelBanState,
} from '@/types/channel-moderation.types'
import type { StreamSession, StreamSetup } from '@/types/stream.types'

const SESSION_ID = '462c912c-ee66-4477-9852-bed13d377297'
const SESSION_ID_2 = '562c912c-ee66-4477-9852-bed13d377298'
const CHANNEL_ID = '20000000-0000-4000-8000-000000000010'
const CHANNEL_ID_2 = '20000000-0000-4000-8000-000000000020'
const OWNER_ID = '10000000-0000-4000-8000-000000000001'
const VIEWER_ID = '10000000-0000-4000-8000-000000000002'
const MODERATOR_ID = '10000000-0000-4000-8000-000000000009'
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
  getTargetBanState: vi.fn(),
  banUser: vi.fn(),
  unbanUser: vi.fn(),
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
vi.mock('@/api/channel-moderation.api', async (importOriginal) => {
  const actual =
    await importOriginal<typeof import('@/api/channel-moderation.api')>()
  return {
    ...actual,
    channelModerationApi: {
      getModerationContext: mocks.getModerationContext,
      getTargetBanState: mocks.getTargetBanState,
      banUser: mocks.banUser,
      unbanUser: mocks.unbanUser,
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
      displayName: userId === OWNER_ID ? 'ChannelOwner' : 'ModeratorOrViewer',
      email: `${userId}@example.com`,
      emailVerified: true,
      roles,
      avatarUrl: null,
      dob: null,
    },
  })
}

const ALL_BAN_TYPES: BanType[] = ['CHAT_BAN', 'LIVE_VIEW_BAN', 'FULL_BAN']

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
  mocks.getTargetBanState.mockReset()
  mocks.banUser.mockReset()
  mocks.unbanUser.mockReset()
  mocks.getBannedUsers.mockReset()
  mocks.onJoin.mockReset()
  mocks.onSend.mockReset()
  mocks.sockets.length = 0
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
    allowedBanTypes: ALL_BAN_TYPES,
    allowedUnbanTypes: ALL_BAN_TYPES,
    canViewBans: true,
  } satisfies ChannelModerationContext)
  mocks.getTargetBanState.mockResolvedValue({
    channelId: CHANNEL_ID,
    userId: TARGET_ID,
    banType: null,
    allowedBanTypes: ALL_BAN_TYPES,
    canUnban: false,
  } satisfies TargetChannelBanState)
  mocks.banUser.mockImplementation(
    async (channelId: string, userId: string, banType: BanType) => ({
      channelId,
      userId,
      banType,
      allowedBanTypes: ALL_BAN_TYPES.filter((type) => type !== banType),
      canUnban: true,
    }),
  )
  mocks.unbanUser.mockImplementation(
    async (channelId: string, userId: string) => ({
      channelId,
      userId,
      banType: null,
      allowedBanTypes: ALL_BAN_TYPES,
      canUnban: false,
    }),
  )
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

describe('channel moderation API contract', () => {
  it('calls new channel-scoped routes, sends { banType } for ban, omits body for unban, and enforces pagination bounds', async () => {
    const actualMod = await vi.importActual<
      typeof import('@/api/channel-moderation.api')
    >('@/api/channel-moderation.api')
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
      await actualMod.channelModerationApi.getModerationContext(CHANNEL_ID)
      await actualMod.channelModerationApi.getTargetBanState(
        CHANNEL_ID,
        TARGET_ID,
      )
      await actualMod.channelModerationApi.banUser(
        CHANNEL_ID,
        TARGET_ID,
        'LIVE_VIEW_BAN',
      )
      await actualMod.channelModerationApi.unbanUser(CHANNEL_ID, TARGET_ID)
      await actualMod.channelModerationApi.getBannedUsers(CHANNEL_ID, {
        page: 1,
        size: 100,
      })

      expect(requests.map((r) => [r.method, r.url])).toEqual([
        ['get', `/channel/api/channels/${CHANNEL_ID}/moderation-context`],
        ['get', `/channel/api/channels/${CHANNEL_ID}/users/${TARGET_ID}/ban`],
        ['post', `/channel/api/channels/${CHANNEL_ID}/users/${TARGET_ID}/ban`],
        [
          'post',
          `/channel/api/channels/${CHANNEL_ID}/users/${TARGET_ID}/unban`,
        ],
        ['get', `/channel/api/channels/${CHANNEL_ID}/bans`],
      ])
      expect(requests.every((r) => r.withCredentials)).toBe(true)
      expect(JSON.parse(requests[2].data)).toEqual({
        banType: 'LIVE_VIEW_BAN',
      })
      expect(requests[3].data).toBeUndefined()
      expect(requests[4].params).toEqual({ page: 1, size: 50 })
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
    expect(mocks.getTargetBanState).not.toHaveBeenCalled()
  })
})

describe('avatar ban menu in chat', () => {
  it('lets an unban-only manager remove the current ban from the avatar menu', async () => {
    mocks.getModerationContext.mockResolvedValue({
      channelId: CHANNEL_ID, ownerUserId: OWNER_ID, allowedBanTypes: [],
      allowedUnbanTypes: ['LIVE_VIEW_BAN'], canViewBans: false,
    } satisfies ChannelModerationContext)
    mocks.getTargetBanState.mockResolvedValue({
      channelId: CHANNEL_ID, userId: TARGET_ID, banType: 'LIVE_VIEW_BAN',
      allowedBanTypes: [], canUnban: true,
    } satisfies TargetChannelBanState)
    mocks.history.mockResolvedValue(chatPage([chatMessage(1, TARGET_ID, 'TargetViewer', 'Hello')]))
    mountWithRouter(<StreamChatPanel sessionId={SESSION_ID} channelId={CHANNEL_ID} status="LIVE" streamerUserId={OWNER_ID} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Moderation options for TargetViewer' }))
    const menu = await screen.findByRole('menu', { name: 'Moderation options for TargetViewer' })
    const unban = await within(menu).findByRole('menuitem', { name: 'Unban' })
    fireEvent.click(unban)
    fireEvent.click(unban)
    await waitFor(() => expect(mocks.unbanUser).toHaveBeenCalledTimes(1))
    expect(mocks.unbanUser).toHaveBeenCalledWith(CHANNEL_ID, TARGET_ID)
    await waitFor(() => expect(screen.queryByRole('menu', { name: 'Moderation options for TargetViewer' })).toBeNull())
  })

  it('keeps the ban menu open and shows an error when unban fails', async () => {
    mocks.getTargetBanState.mockResolvedValue({
      channelId: CHANNEL_ID, userId: TARGET_ID, banType: 'CHAT_BAN',
      allowedBanTypes: ['LIVE_VIEW_BAN', 'FULL_BAN'], canUnban: true,
    } satisfies TargetChannelBanState)
    mocks.unbanUser.mockRejectedValueOnce(new Error('Unban failed'))
    mocks.history.mockResolvedValue(chatPage([chatMessage(1, TARGET_ID, 'TargetViewer', 'Hello')]))
    mountWithRouter(<StreamChatPanel sessionId={SESSION_ID} channelId={CHANNEL_ID} status="LIVE" streamerUserId={OWNER_ID} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Moderation options for TargetViewer' }))
    const menu = await screen.findByRole('menu', { name: 'Moderation options for TargetViewer' })
    fireEvent.click(await within(menu).findByRole('menuitem', { name: 'Unban' }))
    expect((await within(menu).findByRole('alert')).textContent).toContain('Unban failed')
    expect(screen.getByRole('menu', { name: 'Moderation options for TargetViewer' })).toBeTruthy()
  })

  it('disables unban when the target cannot be unbanned', async () => {
    mocks.getTargetBanState.mockResolvedValue({
      channelId: CHANNEL_ID, userId: TARGET_ID, banType: 'FULL_BAN',
      allowedBanTypes: [], canUnban: false,
    } satisfies TargetChannelBanState)
    mocks.history.mockResolvedValue(chatPage([chatMessage(1, TARGET_ID, 'TargetViewer', 'Hello')]))
    mountWithRouter(<StreamChatPanel sessionId={SESSION_ID} channelId={CHANNEL_ID} status="LIVE" streamerUserId={OWNER_ID} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Moderation options for TargetViewer' }))
    const menu = await screen.findByRole('menu', { name: 'Moderation options for TargetViewer' })
    const unban = await within(menu).findByRole('menuitem', { name: 'Unban' }) as HTMLButtonElement
    expect(unban.disabled).toBe(true)
    fireEvent.click(unban)
    expect(mocks.unbanUser).not.toHaveBeenCalled()
  })

  it.each([
    ['CHAT_BAN', 'Chat ban', 'User banned from chat'],
    ['LIVE_VIEW_BAN', 'Live ban', 'User banned from livestream'],
    ['FULL_BAN', 'Channel ban', 'User given a full channel ban'],
  ] as const)(
    'creates %s via avatar menu without confirmation and updates cache from server result',
    async (banType, buttonLabel, expectedToast) => {
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
      expect(mocks.getTargetBanState).not.toHaveBeenCalled()
      expect(
        screen.queryByRole('button', {
          name: /Moderation options for ChannelOwner/i,
        }),
      ).toBeNull()

      fireEvent.click(
        screen.getByRole('button', {
          name: 'Moderation options for TargetViewer',
        }),
      )

      const menu = await screen.findByRole('menu', {
        name: 'Moderation options for TargetViewer',
      })
      expect(within(menu).queryByRole('menuitem', { name: /Unban/i })).toBeNull()

      const optionButton = await within(menu).findByRole('menuitem', {
        name: buttonLabel,
      })
      fireEvent.click(optionButton)

      await waitFor(() =>
        expect(mocks.banUser).toHaveBeenCalledWith(
          CHANNEL_ID,
          TARGET_ID,
          banType,
        ),
      )
      await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
      expect(mocks.toastSuccess).toHaveBeenCalledWith(expectedToast)
    },
  )

  it('uses backend capabilities rather than role names so moderator without STREAMER role can ban', async () => {
    setAuthUser(MODERATOR_ID, ['USER'])
    mocks.getModerationContext.mockResolvedValue({
      channelId: CHANNEL_ID,
      ownerUserId: OWNER_ID,
      allowedBanTypes: ['CHAT_BAN'],
      allowedUnbanTypes: [],
      canViewBans: false,
    })
    mocks.getTargetBanState.mockResolvedValue({
      channelId: CHANNEL_ID,
      userId: TARGET_ID,
      banType: 'FULL_BAN',
      allowedBanTypes: [],
      canUnban: false,
    })
    mocks.history.mockResolvedValue(
      chatPage([chatMessage(1, TARGET_ID, 'TargetViewer', 'Msg')]),
    )

    mountWithRouter(
      <StreamChatPanel
        sessionId={SESSION_ID}
        channelId={CHANNEL_ID}
        status="LIVE"
        streamerUserId={OWNER_ID}
      />,
    )

    await screen.findByText('Msg')
    const trigger = await screen.findByRole('button', {
      name: 'Moderation options for TargetViewer',
    })
    fireEvent.click(trigger)

    const menu = await screen.findByRole('menu', {
      name: 'Moderation options for TargetViewer',
    })
    const banChatBtn = (await within(menu).findByRole('menuitem', {
      name: /Chat ban/i,
    })) as HTMLButtonElement
    const banLiveBtn = within(menu).getByRole('menuitem', {
      name: /Live ban/i,
    }) as HTMLButtonElement
    const fullBanBtn = within(menu).getByRole('menuitem', {
      name: /Channel ban/i,
    }) as HTMLButtonElement

    // Current FULL_BAN is disabled and marked, and downgrading to CHAT_BAN is blocked by target.allowedBanTypes = []
    expect(fullBanBtn.disabled).toBe(true)
    expect(within(fullBanBtn).getByText('Current')).toBeTruthy()
    expect(banChatBtn.disabled).toBe(true)
    expect(banLiveBtn.disabled).toBe(true)
    expect(
      within(menu).getAllByText(/do not have permission to switch/i),
    ).toHaveLength(2)
  })

  it('keeps avatar non-interactive when context.allowedBanTypes is empty even if user has STREAMER role', async () => {
    setAuthUser(OWNER_ID, ['USER', 'STREAMER'])
    mocks.getModerationContext.mockResolvedValue({
      channelId: CHANNEL_ID,
      ownerUserId: OWNER_ID,
      allowedBanTypes: [],
      allowedUnbanTypes: [],
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

  it('supports fallback avatar, loading Checking..., error + Retry, and prevents double-click duplicate ban mutations', async () => {
    const pendingStatus = deferred<TargetChannelBanState>()
    const pendingBan = deferred<TargetChannelBanState>()
    mocks.getTargetBanState.mockReturnValueOnce(pendingStatus.promise)
    mocks.banUser.mockReturnValueOnce(pendingBan.promise)
    mocks.history.mockResolvedValue(
      chatPage([
        chatMessage(1, TARGET_ID, 'FallbackUser', 'Hello', {
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

    await screen.findByText('Hello')
    const trigger = screen.getByRole('button', {
      name: 'Moderation options for FallbackUser',
    })
    fireEvent.error(trigger.querySelector('img')!)
    expect(within(trigger).getByText('F')).toBeTruthy()

    fireEvent.click(trigger)
    expect(await screen.findByText('Checking…')).toBeTruthy()

    await act(async () => {
      pendingStatus.reject(makeAxiosError(503, undefined, 'Unavailable'))
    })
    expect(await screen.findByRole('alert')).toBeTruthy()

    mocks.getTargetBanState.mockResolvedValueOnce({
      channelId: CHANNEL_ID,
      userId: TARGET_ID,
      banType: null,
      allowedBanTypes: ALL_BAN_TYPES,
      canUnban: false,
    })
    fireEvent.click(screen.getByRole('menuitem', { name: 'Retry' }))
    const banLiveBtn = await screen.findByRole('menuitem', {
      name: 'Live ban',
    })
    fireEvent.click(banLiveBtn)
    fireEvent.click(banLiveBtn)
    await waitFor(() => expect(mocks.banUser).toHaveBeenCalledTimes(1))

    await act(async () => {
      pendingBan.resolve({
        channelId: CHANNEL_ID,
        userId: TARGET_ID,
        banType: 'LIVE_VIEW_BAN',
        allowedBanTypes: ['CHAT_BAN', 'FULL_BAN'],
        canUnban: true,
      })
    })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
  })

  it('keeps selected targetUserId safe across virtualized updates and closes menu on Escape or anchor scroll loss', async () => {
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
    await within(menu).findByRole('menuitem', { name: 'Chat ban' })

    await act(async () => {
      mocks.sockets[0].trigger(
        'chat:message',
        chatMessage(3, TARGET_ID_2, 'OtherUser', 'Third'),
      )
    })

    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Channel ban' }))
    await waitFor(() =>
      expect(mocks.banUser).toHaveBeenCalledWith(
        CHANNEL_ID,
        TARGET_ID,
        'FULL_BAN',
      ),
    )

    // Escape closes and returns focus
    fireEvent.click(trigger)
    await screen.findByRole('menu', {
      name: 'Moderation options for SelectedUser',
    })
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(trigger))

    // Scroll loses anchor -> closes menu
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

  it('reloads context and target state on 403 without permanently wiping remaining capabilities, and refreshes on 409', async () => {
    mocks.history.mockResolvedValue(
      chatPage([chatMessage(1, TARGET_ID, 'TargetUser', 'Hi')]),
    )
    mocks.getModerationContext
      .mockResolvedValueOnce({
        channelId: CHANNEL_ID,
        ownerUserId: OWNER_ID,
        allowedBanTypes: ['CHAT_BAN', 'LIVE_VIEW_BAN'],
        allowedUnbanTypes: ['CHAT_BAN'],
        canViewBans: true,
      })
      .mockResolvedValue({
        channelId: CHANNEL_ID,
        ownerUserId: OWNER_ID,
        allowedBanTypes: ['LIVE_VIEW_BAN'],
        allowedUnbanTypes: [],
        canViewBans: true,
      })
    mocks.getTargetBanState
      .mockResolvedValueOnce({
        channelId: CHANNEL_ID,
        userId: TARGET_ID,
        banType: 'CHAT_BAN',
        allowedBanTypes: ['LIVE_VIEW_BAN'],
        canUnban: true,
      })
      .mockResolvedValueOnce({
        channelId: CHANNEL_ID,
        userId: TARGET_ID,
        banType: 'CHAT_BAN',
        allowedBanTypes: [],
        canUnban: false,
      })
      .mockResolvedValueOnce({
        channelId: CHANNEL_ID,
        userId: TARGET_ID,
        banType: 'LIVE_VIEW_BAN',
        allowedBanTypes: ['FULL_BAN'],
        canUnban: true,
      })

    mocks.banUser
      .mockRejectedValueOnce(makeAxiosError(403, undefined, 'Missing permission'))
      .mockRejectedValueOnce(makeAxiosError(409, 1023, 'Already banned at level'))

    mountWithRouter(
      <StreamChatPanel
        sessionId={SESSION_ID}
        channelId={CHANNEL_ID}
        status="LIVE"
        streamerUserId={OWNER_ID}
      />,
    )

    await screen.findByText('Hi')
    const trigger = screen.getByRole('button', {
      name: 'Moderation options for TargetUser',
    })
    fireEvent.click(trigger)
    fireEvent.click(
      await screen.findByRole('menuitem', { name: 'Live ban' }),
    )

    await waitFor(() =>
      expect(mocks.toastError).toHaveBeenCalledWith('Missing permission'),
    )
    // Context & target were reloaded, and avatar menu trigger remains available for remaining capabilities
    await waitFor(() =>
      expect(mocks.getModerationContext).toHaveBeenCalledTimes(2),
    )
    await waitFor(() =>
      expect(mocks.getTargetBanState).toHaveBeenCalledTimes(2),
    )
    expect(
      screen.getByRole('button', { name: 'Moderation options for TargetUser' }),
    ).toBeTruthy()
  })

  it('includes channelId query param on Manage banned users in Watch chat and omits it from embedded Stream Manager chat', async () => {
    mountWithRouter(
      <Routes>
        <Route path="/dashboard" element={<StreamerDashboardPage />} />
      </Routes>,
      ['/dashboard'],
    )

    const aside = await screen.findByLabelText('Stream chat')
    await within(aside).findByText('Stream Chat')
    expect(
      within(aside).queryByRole('link', { name: 'Manage banned users' }),
    ).toBeNull()
    expect(
      within(aside).queryByRole('group', { name: 'Current viewers' }),
    ).toBeNull()
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
    expect(watchLink.getAttribute('href')).toBe(
      `/dashboard/chat-bans?channelId=${CHANNEL_ID}`,
    )
  })
})

describe('BannedUsersPage dropdown actions and moderator channel support', () => {
  it('renders unified card, hides userId/channel badge, supports level upgrades/downgrades, and respects row capabilities', async () => {
    mocks.getBannedUsers
      .mockResolvedValueOnce({
        content: [
          {
            userId: TARGET_ID,
            displayName: '<b>BadActor</b>',
            avatarUrl: null,
            banType: 'CHAT_BAN',
            createdAt: '2026-11-16T08:30:00Z',
            allowedBanTypes: ['LIVE_VIEW_BAN', 'FULL_BAN'],
            canUnban: true,
          },
          {
            userId: TARGET_ID_2,
            displayName: null,
            avatarUrl: null,
            banType: 'FULL_BAN',
            createdAt: '2026-11-16T07:30:00Z',
            allowedBanTypes: ['CHAT_BAN'],
            canUnban: false,
          },
        ],
        page: 0,
        size: 20,
        totalElements: 2,
        totalPages: 1,
      })
      .mockResolvedValueOnce({
        content: [
          {
            userId: TARGET_ID,
            displayName: '<b>BadActor</b>',
            avatarUrl: null,
            banType: 'LIVE_VIEW_BAN',
            createdAt: '2026-11-16T08:30:00Z',
            allowedBanTypes: ['CHAT_BAN', 'FULL_BAN'],
            canUnban: true,
          },
          {
            userId: TARGET_ID_2,
            displayName: null,
            avatarUrl: null,
            banType: 'FULL_BAN',
            createdAt: '2026-11-16T07:30:00Z',
            allowedBanTypes: ['CHAT_BAN'],
            canUnban: false,
          },
        ],
        page: 0,
        size: 20,
        totalElements: 2,
        totalPages: 1,
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

    expect(await screen.findByText('<b>BadActor</b>')).toBeTruthy()
    expect(document.querySelector('b')).toBeNull()
    expect(screen.queryByText('Owner Channel')).toBeNull()
    expect(screen.getByText('Unavailable user')).toBeTruthy()
    expect(screen.queryByText(TARGET_ID)).toBeNull()
    expect(screen.queryByText(TARGET_ID_2)).toBeNull()
    expect(screen.queryByText('Cannot unban here')).toBeNull()

    const list = screen.getByRole('list', { name: 'Banned users list' })
    const pagination = screen.getByRole('navigation', {
      name: 'Banned users pagination',
    })
    expect(list.parentElement).toBe(pagination.parentElement)

    // Open dropdown for Unavailable user (FULL_BAN, allowedBanTypes: ['CHAT_BAN'], canUnban: false)
    const unavailableRow = screen.getByRole('listitem', {
      name: 'Banned user Unavailable user',
    })
    fireEvent.click(
      within(unavailableRow).getByRole('button', { name: /Actions/i }),
    )
    const menu2 = await screen.findByRole('menu', {
      name: 'Moderation actions for Unavailable user',
    })
    expect(
      (
        within(menu2).getByRole('menuitem', {
          name: /Channel ban/i,
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true)
    expect(
      (
        within(menu2).getByRole('menuitem', {
          name: /Live ban/i,
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true)
    expect(
      (
        within(menu2).getByRole('menuitem', {
          name: /Chat ban/i,
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(false)
    expect(
      (
        within(menu2).getByRole('menuitem', {
          name: /Unban/i,
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true)

    // Switch BadActor from CHAT_BAN -> LIVE_VIEW_BAN
    const actorRow = screen.getByRole('listitem', {
      name: 'Banned user <b>BadActor</b>',
    })
    fireEvent.click(
      within(actorRow).getByRole('button', { name: /Actions/i }),
    )
    const menu1 = await screen.findByRole('menu', {
      name: 'Moderation actions for <b>BadActor</b>',
    })
    fireEvent.click(
      within(menu1).getByRole('menuitem', { name: 'Live ban' }),
    )

    await waitFor(() =>
      expect(mocks.banUser).toHaveBeenCalledWith(
        CHANNEL_ID,
        TARGET_ID,
        'LIVE_VIEW_BAN',
      ),
    )
    expect(mocks.toastSuccess).toHaveBeenCalledWith(
      'Updated ban to Live ban',
    )
    expect(
      await within(actorRow).findByText('Live ban'),
    ).toBeTruthy()
  })

  it('allows moderator without own channel to manage bans via ?channelId and preserves channelId on pagination and switch', async () => {
    setAuthUser(MODERATOR_ID, ['USER'])
    mocks.getOwnChannel.mockRejectedValue(
      makeAxiosError(404, undefined, 'No own channel'),
    )
    mocks.getModerationContext
      .mockResolvedValueOnce({
        channelId: CHANNEL_ID,
        ownerUserId: OWNER_ID,
        allowedBanTypes: ['CHAT_BAN', 'LIVE_VIEW_BAN'],
        allowedUnbanTypes: ['CHAT_BAN', 'LIVE_VIEW_BAN', 'FULL_BAN'],
        canViewBans: true,
      })
      .mockResolvedValueOnce({
        channelId: CHANNEL_ID_2,
        ownerUserId: OWNER_ID,
        allowedBanTypes: ['CHAT_BAN'],
        allowedUnbanTypes: [],
        canViewBans: true,
      })
    mocks.getBannedUsers
      .mockResolvedValueOnce({
        content: [
          {
            userId: TARGET_ID,
            displayName: 'ChannelOneBanned',
            avatarUrl: null,
            banType: 'FULL_BAN',
            createdAt: '2026-11-16T08:30:00Z',
            allowedBanTypes: ['CHAT_BAN'],
            canUnban: true,
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
            displayName: 'ChannelOnePageTwo',
            avatarUrl: null,
            banType: 'LIVE_VIEW_BAN',
            createdAt: '2026-11-16T07:30:00Z',
            allowedBanTypes: ['CHAT_BAN'],
            canUnban: true,
          },
        ],
        page: 1,
        size: 20,
        totalElements: 21,
        totalPages: 2,
      })
      .mockResolvedValueOnce({
        content: [
          {
            userId: TARGET_ID,
            displayName: 'ChannelTwoBanned',
            avatarUrl: null,
            banType: 'CHAT_BAN',
            createdAt: '2026-11-16T06:30:00Z',
            allowedBanTypes: [],
            canUnban: false,
          },
        ],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      })

    function ChannelSwitcher() {
      const navigate = useNavigate()
      return (
        <button
          type="button"
          onClick={() => navigate(`/dashboard/chat-bans?channelId=${CHANNEL_ID_2}`)}
        >
          Switch channel
        </button>
      )
    }

    mountWithRouter(
      <Routes>
        <Route element={<StreamerDashboardLayout />}>
          <Route
            path="/dashboard/chat-bans"
            element={
              <>
                <ChannelSwitcher />
                <BannedUsersPage />
              </>
            }
          />
        </Route>
      </Routes>,
      [`/dashboard/chat-bans?channelId=${CHANNEL_ID}`],
    )

    expect(await screen.findByText('ChannelOneBanned')).toBeTruthy()
    expect(mocks.getOwnChannel).not.toHaveBeenCalled()
    expect(mocks.getModerationContext).toHaveBeenCalledWith(
      CHANNEL_ID,
      expect.any(AbortSignal),
    )

    const nav = screen.getByRole('navigation', { name: 'Streamer navigation' })
    expect(
      within(nav).getByRole('link', { name: 'Banned users' }).getAttribute('href'),
    ).toBe(`/dashboard/chat-bans?channelId=${CHANNEL_ID}`)

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('ChannelOnePageTwo')).toBeTruthy()
    expect(mocks.getBannedUsers).toHaveBeenLastCalledWith(
      CHANNEL_ID,
      { page: 1, size: 20 },
      expect.any(AbortSignal),
    )

    // Switch channelId in-place -> resets page back to 0
    fireEvent.click(screen.getByRole('button', { name: 'Switch channel' }))
    expect(await screen.findByText('ChannelTwoBanned')).toBeTruthy()
    expect(mocks.getBannedUsers).toHaveBeenLastCalledWith(
      CHANNEL_ID_2,
      { page: 0, size: 20 },
      expect.any(AbortSignal),
    )
  })

  it('unbans FULL_BAN when canUnban=true, prevents double click, and steps back when removing last item of page', async () => {
    const pendingUnban = deferred<TargetChannelBanState>()
    mocks.unbanUser.mockReturnValueOnce(pendingUnban.promise)

    mocks.getBannedUsers
      .mockResolvedValueOnce({
        content: [
          {
            userId: TARGET_ID,
            displayName: 'PageZeroUser',
            avatarUrl: null,
            banType: 'CHAT_BAN',
            createdAt: '2026-11-16T08:30:00Z',
            allowedBanTypes: ['LIVE_VIEW_BAN', 'FULL_BAN'],
            canUnban: true,
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
            banType: 'FULL_BAN',
            createdAt: '2026-11-16T07:30:00Z',
            allowedBanTypes: ['CHAT_BAN'],
            canUnban: true,
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
            allowedBanTypes: ['LIVE_VIEW_BAN', 'FULL_BAN'],
            canUnban: true,
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

    fireEvent.click(screen.getByRole('button', { name: /Actions/i }))
    const unbanItem = await screen.findByRole('menuitem', { name: 'Unban' })
    fireEvent.click(unbanItem)
    fireEvent.click(unbanItem)

    await waitFor(() => expect(mocks.unbanUser).toHaveBeenCalledTimes(1))
    expect(mocks.unbanUser).toHaveBeenCalledWith(CHANNEL_ID, TARGET_ID_2)

    await act(async () => {
      pendingUnban.resolve({
        channelId: CHANNEL_ID,
        userId: TARGET_ID_2,
        banType: null,
        allowedBanTypes: ALL_BAN_TYPES,
        canUnban: false,
      })
    })

    expect(mocks.toastSuccess).toHaveBeenCalledWith('User unbanned')
    expect(await screen.findByText('PageZeroUser')).toBeTruthy()
    expect(screen.getByText('Page 1 of 1')).toBeTruthy()
  })

  it('does not falsely mutate row on 409/503 errors, refreshes on 409, and handles error + empty states', async () => {
    mocks.getBannedUsers
      .mockRejectedValueOnce(makeAxiosError(503, undefined, 'Service down'))
      .mockResolvedValueOnce({
        content: [
          {
            userId: TARGET_ID,
            displayName: 'ConflictTarget',
            avatarUrl: null,
            banType: 'CHAT_BAN',
            createdAt: '2026-11-16T08:30:00Z',
            allowedBanTypes: ['LIVE_VIEW_BAN', 'FULL_BAN'],
            canUnban: true,
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
    mocks.banUser.mockRejectedValueOnce(
      makeAxiosError(503, undefined, 'Network timeout'),
    )
    mocks.unbanUser.mockRejectedValueOnce(
      makeAxiosError(409, 1024, 'No active ban'),
    )

    mountWithRouter(
      <Routes>
        <Route path="/dashboard/chat-bans" element={<BannedUsersPage />} />
      </Routes>,
      ['/dashboard/chat-bans'],
    )

    expect(await screen.findByText('Unable to load banned users')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    await screen.findByText('ConflictTarget')
    fireEvent.click(screen.getByRole('button', { name: /Actions/i }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Channel ban' }))

    await waitFor(() =>
      expect(mocks.toastError).toHaveBeenCalledWith(
        'Moderation service is temporarily unavailable. Please try again.',
      ),
    )
    // Badge must NOT falsely switch to Channel ban on 503
    expect(screen.getByText('Chat ban')).toBeTruthy()
    expect(screen.queryByText('Channel ban')).toBeNull()

    // Now unban hits 409 -> refreshes list without faking success
    fireEvent.click(screen.getByRole('button', { name: /Actions/i }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Unban' }))

    await waitFor(() =>
      expect(mocks.toastError).toHaveBeenCalledWith(
        'This user does not have an active ban.',
      ),
    )
    expect(mocks.toastSuccess).not.toHaveBeenCalled()
    expect(await screen.findByText('No banned users')).toBeTruthy()
  })

  it('hides Banned users link and blocks page when user lacks canViewBans', async () => {
    mocks.getModerationContext.mockResolvedValue({
      channelId: CHANNEL_ID,
      ownerUserId: OWNER_ID,
      allowedBanTypes: [],
      allowedUnbanTypes: [],
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
      allowedBanTypes: [],
      allowedUnbanTypes: [],
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

    const pendingJoin = deferred<ChatAck<ChatJoin>>()
    mocks.onJoin.mockImplementationOnce((_attempt, cb) => {
      void pendingJoin.promise.then((ack) => cb(null, ack))
    })

    fireEvent.click(screen.getByRole('button', { name: 'Check chat access' }))
    fireEvent.click(screen.getByRole('button', { name: 'Checking access…' }))
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
    expect(input.value).toBe('My unsent draft')
    expect(mocks.sockets[0].sent).toHaveLength(1)
  })
})

describe('session, channel, and account isolation', () => {
  it('clears channel-moderation cache on logout/user switch and closes open menu when switching sessions or channels', async () => {
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

    appQueryClient.setQueryData(
      ['channel-moderation', 'context', OWNER_ID, CHANNEL_ID],
      {
        channelId: CHANNEL_ID,
        ownerUserId: OWNER_ID,
        allowedBanTypes: ALL_BAN_TYPES,
        allowedUnbanTypes: ALL_BAN_TYPES,
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
        'channel-moderation',
        'context',
        OWNER_ID,
        CHANNEL_ID,
      ]),
    ).toBeUndefined()
  })
})
