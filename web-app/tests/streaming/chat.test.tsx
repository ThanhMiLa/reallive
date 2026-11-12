import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StreamChatPanel } from '@/components/chat/StreamChatPanel'
import { mergeChatMessage, type ChatWindow } from '@/components/chat/chat-cache'
import type { ChatMessage, ChatPage } from '@/types/chat.types'

const SESSION = '462c912c-ee66-4477-9852-bed13d377297'
const USER = '10000000-0000-4000-8000-000000000001'
const mocks = vi.hoisted(() => ({ history: vi.fn(), refresh: vi.fn(), scrollToIndex: vi.fn(), sockets: [] as FakeSocket[], canSend: true, failSend: false }))
class FakeSocket {
  connected = false
  handlers = new Map<string, ((...args: unknown[]) => void)[]>()
  sent: { clientMessageId: string; content: string }[] = []
  nextSequence = 1
  on(event: string, handler: (...args: never[]) => void) {
    this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler as (...args: unknown[]) => void]); return this
  }
  trigger(event: string, value?: unknown) { this.handlers.get(event)?.forEach(fn => fn(value)) }
  connect() { this.connected = true; queueMicrotask(() => this.trigger('connect')); return this }
  disconnect() { this.connected = false; this.trigger('disconnect'); return this }
  removeAllListeners() { this.handlers.clear(); return this }
  timeout() { return this }
  emit(event: string, input: { clientMessageId: string; content: string }, callback?: (...args: unknown[]) => void) {
    if (event === 'chat:join') queueMicrotask(() => callback?.(null, { ok: true, data: { sessionId: SESSION, canRead: true, canSend: mocks.canSend, watermark: 'head' } }))
    if (event === 'chat:send') {
      this.sent.push(input)
      queueMicrotask(() => {
        if (mocks.failSend) callback?.(new Error('timeout'))
        else callback?.(null, { ok: true, data: { ...message(this.nextSequence++), clientMessageId: input.clientMessageId, content: input.content } })
      })
    }
    return this
  }
}
vi.mock('socket.io-client', () => ({ io: () => { const socket = new FakeSocket(); mocks.sockets.push(socket); return socket } }))
vi.mock('@/api/chat.api', () => ({ chatApi: { history: mocks.history } }))
vi.mock('@/api/client', () => ({ refreshAccessToken: mocks.refresh }))
vi.mock('@/stores/auth.store', () => ({ useAuthStore: (selector: (state: unknown) => unknown) => selector({ isAuthenticated: true, user: { userId: USER } }) }))
vi.mock('@tanstack/react-virtual', () => ({ useVirtualizer: (options: { count: number }) => ({
  getTotalSize: () => options.count * 64,
  getVirtualItems: () => Array.from({ length: options.count }, (_, index) => ({ index, start: index * 64, end: (index + 1) * 64, size: 64 })),
  measureElement: () => undefined, scrollToIndex: mocks.scrollToIndex, getOffsetForIndex: (index: number) => [index * 64, 'start'], scrollToOffset: () => undefined,
}) }))
function message(sequence: number): ChatMessage {
  return { id: `00000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`, cursor: `cursor-${sequence}`, streamId: SESSION,
    channelId: USER, userId: USER, clientMessageId: USER, sequence, content: `Message ${sequence}`,
    sender: { displayName: 'Old name', avatarUrl: null }, createdAt: '2026-11-11T10:00:00Z' }
}
function page(items: ChatMessage[], hasOlder = false): ChatPage {
  return { items, hasOlder, hasNewer: false, olderCursor: items[0]?.cursor ?? null,
    newerCursor: items.at(-1)?.cursor ?? 'zero', watermark: items.at(-1)?.cursor ?? 'zero' }
}
function mount(status = 'LIVE', streamerUserId?: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const view = render(<QueryClientProvider client={client}><StreamChatPanel sessionId={SESSION} status={status} streamerUserId={streamerUserId} /></QueryClientProvider>)
  return { ...view, client }
}
beforeEach(() => { mocks.history.mockReset(); mocks.refresh.mockReset(); mocks.scrollToIndex.mockReset(); mocks.sockets = []; mocks.canSend = true; mocks.failSend = false; mocks.history.mockResolvedValue(page([])) })
afterEach(cleanup)

describe('stream chat', () => {
  it('loads older pages additively and keeps existing messages', async () => {
    mocks.history.mockImplementation((_id, params) => Promise.resolve(params.before ? page([message(1)]) : page([message(51)], true)))
    mount()
    await screen.findByText('Message 51')
    fireEvent.click(screen.getByText('Load older messages'))
    await screen.findByText('Message 1')
    expect(screen.getByText('Message 51')).toBeTruthy()
    expect(mocks.history.mock.calls[1][1]).toEqual({ before: 'cursor-51' })
  })
  it('shows text literally and counts Unicode code points before sending', async () => {
    mount()
    const input = screen.getByLabelText('Send a message')
    await waitFor(() => expect(input.hasAttribute('disabled')).toBe(false))
    fireEvent.change(input, { target: { value: '😀'.repeat(501) } })
    expect(screen.getByLabelText('Send chat message').hasAttribute('disabled')).toBe(true)
    fireEvent.change(input, { target: { value: '  <b>Hello</b>  ' } })
    fireEvent.click(screen.getByLabelText('Send chat message'))
    await screen.findByText('<b>Hello</b>')
    expect(document.querySelector('b')).toBeNull()
    expect(mocks.sockets[0].sent[0].content).toBe('<b>Hello</b>')
  })
  it('reuses the client message ID after an unconfirmed send', async () => {
    mocks.failSend = true
    mount()
    const input = screen.getByLabelText('Send a message')
    await waitFor(() => expect(input.hasAttribute('disabled')).toBe(false))
    fireEvent.change(input, { target: { value: 'Retry me' } }); fireEvent.click(screen.getByLabelText('Send chat message'))
    await screen.findByText('Retry message')
    mocks.failSend = false; fireEvent.click(screen.getByText('Retry message'))
    await screen.findByText('Retry me')
    expect(mocks.sockets[0].sent).toHaveLength(2)
    expect(mocks.sockets[0].sent[0].clientMessageId).toBe(mocks.sockets[0].sent[1].clientMessageId)
  })
  it('allows a chat-banned viewer to read and disables sending', async () => {
    mocks.canSend = false; mocks.history.mockResolvedValue(page([message(1)]))
    mount()
    await screen.findByText('Message 1')
    expect(screen.getByLabelText('Send a message').hasAttribute('disabled')).toBe(true)
    expect(screen.getByText(/You can read chat/)).toBeTruthy()
  })
  it('removes history when the room closes and never connects for ended sessions', async () => {
    mocks.history.mockResolvedValue(page([message(1)]))
    const view = mount(); await screen.findByText('Message 1')
    await act(async () => mocks.sockets[0].trigger('chat:closed', { code: 'CHAT_CLOSED', status: 409, message: 'Chat is not open', retryable: false }))
    await waitFor(() => expect(screen.queryByText('Message 1')).toBeNull())
    expect(screen.getByText('This stream has ended. Chat is closed.')).toBeTruthy()
    view.unmount(); mocks.sockets = []; mocks.history.mockClear()
    mount('ENDED')
    expect(screen.getByText('This stream has ended. Chat is closed.')).toBeTruthy()
    expect(mocks.sockets).toHaveLength(0); expect(mocks.history).not.toHaveBeenCalled()
  })
  it('fills a realtime gap from persisted history using the last cursor', async () => {
    mocks.history.mockImplementation((_id, params) => Promise.resolve(params.after ? page([message(2), message(3)]) : page([message(1)])))
    mount(); await screen.findByText('Message 1')
    await act(async () => mocks.sockets[0].trigger('chat:message', message(3)))
    await screen.findByText('Message 2'); await screen.findByText('Message 3')
    expect(mocks.history.mock.calls.some(call => call[1].after === 'cursor-1')).toBe(true)
    await act(async () => mocks.sockets[0].trigger('chat:message', message(3)))
    expect(screen.getAllByText('Message 3')).toHaveLength(1)
  })
  it('adds a Messenger blue border for the current viewer while keeping names white', async () => {
    const mine: ChatMessage = { ...message(1), sender: { displayName: 'Current User', avatarUrl: null } }
    const other: ChatMessage = {
      ...message(2),
      userId: '10000000-0000-4000-8000-000000000002',
      sender: { displayName: 'Other Viewer', avatarUrl: null },
    }
    mocks.history.mockResolvedValue(page([mine, other]))
    mount()
    const ownName = await screen.findByText('Current User')
    const otherName = screen.getByText('Other Viewer')
    expect(ownName.className).toContain('text-white')
    expect(ownName.closest('[data-index]')?.firstElementChild?.className).toContain('border-[#0084ff]')
    expect(otherName.className).toContain('text-white')
    expect(otherName.closest('[data-index]')?.firstElementChild?.className).not.toContain('border-[#0084ff]')
  })
  it('adds a red border and green name when the stream owner comments', async () => {
    const streamerId = '10000000-0000-4000-8000-000000000003'
    const ownComment: ChatMessage = { ...message(1), sender: { displayName: 'Current User', avatarUrl: null } }
    const otherViewer: ChatMessage = {
      ...message(2),
      userId: '10000000-0000-4000-8000-000000000002',
      sender: { displayName: 'Other Viewer', avatarUrl: null },
    }
    const streamerComment: ChatMessage = {
      ...message(3),
      userId: streamerId,
      sender: { displayName: 'Channel Owner', avatarUrl: null },
    }
    mocks.history.mockResolvedValue(page([ownComment, otherViewer, streamerComment]))
    mount('LIVE', streamerId)
    const streamerName = await screen.findByText('Channel Owner')
    const ownName = screen.getByText('Current User')
    const otherName = screen.getByText('Other Viewer')
    expect(streamerName.className).toContain('text-green-400')
    expect(streamerName.closest('[data-index]')?.firstElementChild?.className).toContain('border-[#ff2b42]')
    expect(streamerName.closest('[data-index]')?.firstElementChild?.className).not.toContain('bg-[#ff2b42]')
    expect(ownName.className).toContain('text-white')
    expect(ownName.closest('[data-index]')?.firstElementChild?.className).toContain('border-[#0084ff]')
    expect(ownName.closest('[data-index]')?.firstElementChild?.className).not.toContain('border-[#ff2b42]')
    expect(otherName.className).toContain('text-white')
    expect(otherName.closest('[data-index]')?.firstElementChild?.className).not.toContain('border-[#ff2b42]')
    expect(otherName.closest('[data-index]')?.firstElementChild?.className).not.toContain('border-[#0084ff]')
  })
  it('keeps focus in the chat input after sending a message', async () => {
    mount()
    const input = screen.getByLabelText('Send a message') as HTMLInputElement
    const button = screen.getByLabelText('Send chat message')
    await waitFor(() => expect(input.hasAttribute('disabled')).toBe(false))
    input.focus()
    expect(document.activeElement).toBe(input)
    fireEvent.change(input, { target: { value: 'First message' } })
    fireEvent.submit(input.closest('form')!)
    await screen.findByText('First message')
    expect(input.value).toBe('')
    expect(document.activeElement).toBe(input)
    fireEvent.change(input, { target: { value: 'Second message' } })
    button.focus()
    fireEvent.click(button)
    await screen.findByText('Second message')
    expect(input.value).toBe('')
    expect(document.activeElement).toBe(input)
  })
  it('stops following live on a gentle upward scroll without snapping back to the bottom', async () => {
    mocks.history.mockResolvedValue(page([message(1), message(2), message(3)]))
    mount()
    await screen.findByText('Message 3')
    const scroller = screen.getByLabelText('Chat messages')
    Object.defineProperty(scroller, 'scrollHeight', { value: 500, configurable: true })
    Object.defineProperty(scroller, 'clientHeight', { value: 300, configurable: true })
    Object.defineProperty(scroller, 'scrollTop', { value: 200, writable: true, configurable: true })
    fireEvent.scroll(scroller)
    mocks.scrollToIndex.mockClear()

    // Gentle 20px upward scroll (still within 80px of bottom)
    scroller.scrollTop = 180
    fireEvent.scroll(scroller)
    const backToLatest = await screen.findByText('Back to latest')
    expect(backToLatest.className).toContain('text-green-300')
    expect(backToLatest.className).not.toContain('bg-green-')
    expect(mocks.scrollToIndex).not.toHaveBeenCalled()
  })
})

describe('bounded chat window', () => {
  it('evicts a far page while preserving the ability to fetch it again', () => {
    const data: ChatWindow = { pages: Array.from({ length: 20 }, (_, p) => page(Array.from({ length: 50 }, (_, i) => message(p * 50 + i + 1)), p > 0)),
      pageParams: Array.from({ length: 20 }, () => ({})) }
    const next = mergeChatMessage(data, message(1001), true)!
    expect(next.pages).toHaveLength(20); expect(next.pageParams).toHaveLength(20)
    expect(next.pages[0].items[0].sequence).toBe(51)
    expect(next.pages[0].hasOlder).toBe(true)
    expect(next.pages[0].olderCursor).toBe('cursor-51')
    expect(next.pages.at(-1)!.items[0].sequence).toBe(1001)
    expect(data.pages[0].items[0].sequence).toBe(1)
  })
  it('does not grow memory with new messages while a viewer is reading history', () => {
    let data: ChatWindow = { pages: [page([message(1)])], pageParams: [{}] }
    for (let i = 2; i < 10000; i++) data = mergeChatMessage(data, message(i), false)!
    expect(data.pages[0].items).toHaveLength(1)
    expect(data.pages[0].hasNewer).toBe(true)
    expect(data.pages[0].newerCursor).toBe('cursor-1')
  })
})
