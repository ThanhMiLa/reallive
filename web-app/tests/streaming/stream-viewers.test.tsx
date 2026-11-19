import type { ComponentProps } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { streamApi } from '@/api/stream.api'
import { StreamViewersPanel } from '@/components/stream/StreamViewersPanel'
import { useAuthStore } from '@/stores/auth.store'
import type { StreamViewerItem, StreamViewerPage } from '@/types/stream.types'

vi.mock('@/api/stream.api', () => ({ streamApi: { viewers: vi.fn() } }))

const viewers = vi.mocked(streamApi.viewers)
const alice: StreamViewerItem = {
  userId: 'alice-id', displayName: 'Alice', avatarUrl: 'https://cdn.example.com/alice.jpg',
}
const bob: StreamViewerItem = {
  userId: 'bob-id', displayName: 'bob', avatarUrl: null,
}
const carol: StreamViewerItem = {
  userId: 'carol-id', displayName: 'Carol', avatarUrl: null,
}
const sessionId = 'stream-session'
const unavailable = (status: number, code: number) => ({ isAxiosError: true, response: { status, data: { code } } })

function page(items: StreamViewerItem[], nextCursor: string | null = null): StreamViewerPage {
  return { items, nextCursor, hasMore: nextCursor !== null }
}

function deferredPage() {
  let resolve!: (page: StreamViewerPage) => void
  const promise = new Promise<StreamViewerPage>((done) => { resolve = done })
  return { promise, resolve }
}

let client: QueryClient
function mount(props: Partial<ComponentProps<typeof StreamViewersPanel>> = {}) {
  const panel = (overrides = props) => (
    <QueryClientProvider client={client}>
      <StreamViewersPanel userId="owner" sessionId={sessionId} status="LIVE" currentViewerCount={null} lastEndedAt={null} {...overrides} />
    </QueryClientProvider>
  )
  const view = render(panel())
  return (overrides: typeof props) => view.rerender(panel(overrides))
}

function expectCursorCall(callNumber: number, cursor: string, id = sessionId) {
  expect(viewers).toHaveBeenNthCalledWith(callNumber, id, { cursor, size: 50 }, expect.any(AbortSignal))
}

beforeEach(() => {
  client = new QueryClient()
  viewers.mockReset().mockResolvedValue(page([]))
  vi.stubGlobal('IntersectionObserver', undefined)
})

afterEach(() => {
  cleanup()
  client.clear()
  useAuthStore.getState().closeAuthModal()
  vi.unstubAllGlobals()
})

it.each([
  { label: 'offline', props: { status: undefined, sessionId: undefined }, message: 'Viewers appear here when your stream is live.' },
  { label: 'ended', props: { status: 'ENDED' as const }, message: 'This stream has ended.' },
  { label: 'ended session cleared', props: { status: undefined, sessionId: undefined, lastEndedAt: '2026-10-30T00:04:00Z' }, message: 'This stream has ended.' },
])('shows the $label status without requesting viewers', ({ props, message }) => {
  mount(props)

  expect(screen.getByRole('status').textContent).toBe(message)
  expect((screen.getByRole('button', { name: 'Refresh viewers' }) as HTMLButtonElement).disabled).toBe(true)
  expect(screen.queryByLabelText('Current viewer count')).toBeNull()
  expect(viewers).not.toHaveBeenCalled()
})

it('loads live viewers and uses initials for missing or broken avatars', async () => {
  const firstPage = deferredPage()
  viewers.mockReturnValue(firstPage.promise)
  mount({ currentViewerCount: 1234 })

  expect(screen.getByRole('status').textContent).toBe('Loading viewers…')
  expect(screen.getByLabelText('Current viewer count').textContent).toBe((1234).toLocaleString())
  firstPage.resolve(page([alice, bob]))
  const aliceRow = (await screen.findByText('Alice')).closest('li')!
  const bobRow = screen.getByText('bob').closest('li')!
  expect(viewers).toHaveBeenLastCalledWith(sessionId, { cursor: '0', size: 50 }, expect.any(AbortSignal))
  expect(within(aliceRow).queryByText(/^@/)).toBeNull()
  expect(within(bobRow).getByText('B').getAttribute('aria-hidden')).toBe('true')
  expect(within(bobRow).queryByText(/^@/)).toBeNull()

  const image = aliceRow.querySelector('img')!
  expect(image.getAttribute('src')).toBe(alice.avatarUrl)
  expect(image.alt).toBe('')
  fireEvent.error(image)
  expect(aliceRow.querySelector('img')).toBeNull()
  expect(within(aliceRow).getByText('A')).toBeTruthy()
})

it('allows an empty result while reconnecting and keeps a zero count visible', async () => {
  mount({ status: 'RECONNECTING', currentViewerCount: 0 })

  expect(await screen.findByText('No viewers right now.')).toBeTruthy()
  expect(screen.getByText('Viewers may temporarily disappear while reconnecting.')).toBeTruthy()
  expect(screen.getByLabelText('Current viewer count').textContent).toBe('0')
})

it('loads intersecting pages through the scroll container, including an empty scan page', async () => {
  const observers: Observer[] = []
  class Observer {
    observe = vi.fn()
    disconnect = vi.fn()
    callback: IntersectionObserverCallback
    options: IntersectionObserverInit
    constructor(callback: IntersectionObserverCallback, options: IntersectionObserverInit) {
      this.callback = callback
      this.options = options
      observers.push(this)
    }
    notify(isIntersecting: boolean) {
      this.callback([{ isIntersecting } as IntersectionObserverEntry], this as unknown as IntersectionObserver)
    }
  }
  vi.stubGlobal('IntersectionObserver', Observer)
  const nextPage = deferredPage()
  viewers
    .mockResolvedValueOnce(page([alice], '48'))
    .mockReturnValueOnce(nextPage.promise)
    .mockResolvedValueOnce(page([alice, bob]))
  mount()

  await waitFor(() => expect(observers).toHaveLength(1))
  const [observer] = observers
  expect(observer.options).toEqual({ root: screen.getByRole('group', { name: 'Viewer list' }), rootMargin: '0px 0px 200px 0px' })
  expect(observer.observe.mock.calls[0][0].getAttribute('aria-hidden')).toBe('true')
  act(() => observer.notify(false))
  expect(viewers).toHaveBeenCalledTimes(1)

  act(() => observer.notify(true))
  await screen.findByText('Loading more…')
  expectCursorCall(2, '48')
  nextPage.resolve(page([], '144'))
  await waitFor(() => expect(observers).toHaveLength(2))
  expect(observer.disconnect).toHaveBeenCalled()
  act(() => observers[1].notify(true))

  await screen.findByText('bob')
  expectCursorCall(3, '144')
  expect(screen.getAllByRole('listitem')).toHaveLength(2)
  expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull()
  expect(observers[1].disconnect).toHaveBeenCalled()
  act(() => observers[1].notify(true))
  expect(viewers).toHaveBeenCalledTimes(3)
})

it('uses Load more without an observer and retains the first occurrence in cursor order', async () => {
  const extraViewers = Array.from({ length: 50 }, (_, index) => ({
    ...bob, userId: `extra-${index}`, displayName: `Viewer ${index}`,
  }))
  viewers
    .mockResolvedValueOnce(page([alice, ...extraViewers], '48'))
    .mockResolvedValueOnce(page([{ ...alice, displayName: 'Duplicate Alice' }, carol]))
  mount()

  const loadMore = await screen.findByRole('button', { name: 'Load more' })
  expect(screen.getAllByRole('listitem')).toHaveLength(51)
  fireEvent.click(loadMore)

  await screen.findByText('Carol')
  expectCursorCall(2, '48')
  const rows = screen.getAllByRole('listitem')
  expect(rows).toHaveLength(52)
  expect(within(rows[0]).getByText('Alice')).toBeTruthy()
  expect(within(rows.at(-1)!).getByText('Carol')).toBeTruthy()
  expect(screen.queryByText('Duplicate Alice')).toBeNull()
  expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull()
})

it('refreshes from cursor zero and discards the old pages and their cursors', async () => {
  viewers
    .mockResolvedValueOnce(page([alice], '48'))
    .mockResolvedValueOnce(page([bob]))
    .mockResolvedValueOnce(page([carol], '96'))
    .mockResolvedValueOnce(page([]))
  mount()

  fireEvent.click(await screen.findByRole('button', { name: 'Load more' }))
  await screen.findByText('bob')
  const list = screen.getByRole('group', { name: 'Viewer list' })
  list.scrollTop = 300
  fireEvent.click(screen.getByRole('button', { name: 'Refresh viewers' }))

  await screen.findByText('Carol')
  expect(list.scrollTop).toBe(0)
  expectCursorCall(3, '0')
  expect(viewers).toHaveBeenCalledTimes(3)
  expect(screen.getAllByRole('listitem')).toHaveLength(1)
  expect(screen.queryByText('Alice')).toBeNull()
  expect(screen.queryByText('bob')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Load more' }))
  await waitFor(() => expectCursorCall(4, '96'))
})

it.each([
  { label: 'syncing', error: unavailable(503, 1016), message: 'Viewer list is syncing. Try again in a moment.' },
  { label: 'service error', error: unavailable(503, 1003), message: 'Unable to load viewers.' },
  { label: 'network error', error: new Error('Network error'), message: 'Unable to load viewers.' },
])('shows a retry action after a $label', async ({ error, message }) => {
  viewers.mockRejectedValueOnce(error).mockResolvedValueOnce(page([alice]))
  mount()

  await screen.findByText(message)
  expect(viewers).toHaveBeenCalledTimes(1)
  expect(screen.queryByText('No viewers right now.')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Try again' }))

  await screen.findByText('Alice')
  expectCursorCall(2, '0')
})

it('opens the login modal for an expired session', async () => {
  viewers.mockRejectedValueOnce(unavailable(401, 1001))
  mount()

  await screen.findByText('Your session has expired.')
  fireEvent.click(screen.getByRole('button', { name: 'Log in' }))
  expect(useAuthStore.getState()).toMatchObject({ isAuthModalOpen: true, authModalTab: 'login' })
})

it('retries an initial syncing error once when the count becomes available', async () => {
  viewers.mockRejectedValue(unavailable(503, 1016))
  const update = mount()

  await screen.findByText('Viewer list is syncing. Try again in a moment.')
  expect(viewers).toHaveBeenCalledTimes(1)
  expect(screen.getByLabelText('Current viewer count').textContent).toBe('—')

  update({ currentViewerCount: 0 })
  await waitFor(() => expectCursorCall(2, '0'))
  await screen.findByText('Viewer list is syncing. Try again in a moment.')
  update({ currentViewerCount: 1 })
  expect(viewers).toHaveBeenCalledTimes(2)
})

it('keeps loaded rows on a next-page error and retries the same cursor', async () => {
  viewers
    .mockResolvedValueOnce(page([alice], '48'))
    .mockRejectedValueOnce(unavailable(503, 1016))
    .mockResolvedValueOnce(page([carol]))
  const update = mount()

  fireEvent.click(await screen.findByRole('button', { name: 'Load more' }))
  await screen.findByText("Couldn't load more viewers.")
  expect(screen.getByText('Alice')).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
  update({ currentViewerCount: 2 })
  expect(viewers).toHaveBeenCalledTimes(2)

  fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
  await screen.findByText('Carol')
  expectCursorCall(2, '48')
  expectCursorCall(3, '48')
  expect(screen.getByText('Alice')).toBeTruthy()
})
