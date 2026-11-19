import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/api/client'
import { AppRoutes } from '@/App'
import { useAuthStore } from '@/stores/auth.store'

const base = '/channel/api/channels/own-channel/moderators'
const profile = {
  userId: 'target',
  displayName: 'Alex',
  avatarUrl: null,
}
const assignedAt = '2026-11-18T14:30:00Z'
const user = {
  userId: 'owner', displayName: 'Owner', email: 'owner@example.test',
  roles: ['STREAMER'], avatarUrl: null, emailVerified: true, dob: null,
}

function reply(config: InternalAxiosRequestConfig, result: unknown, status = 200) {
  return {
    config, status, statusText: '', headers: {},
    data: status === 204 ? '' : { code: 1000, result },
  }
}

function fail(config: InternalAxiosRequestConfig, status: number, code?: number): never {
  throw new AxiosError('Request failed', undefined, config, undefined, {
    ...reply(config, null, status), data: { code },
  })
}

const originalAdapter = apiClient.defaults.adapter
const dialogMethods = {
  showModal: Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal'),
  close: Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close'),
}
const requests = vi.fn()
let api: (config: InternalAxiosRequestConfig) => ReturnType<typeof reply> | Promise<ReturnType<typeof reply>>
let client: QueryClient

function mount(path = '/dashboard/channel/moderators?channelId=other-channel') {
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function openAdd() {
  const add = await screen.findByRole('button', { name: 'Add moderator' })
  add.focus()
  fireEvent.click(add)
  const dialog = screen.getByRole('dialog', { name: 'Add moderator' })
  return { add, dialog, input: within(dialog).getByLabelText('Display name') }
}

function search(input: HTMLElement, displayName = profile.displayName) {
  fireEvent.change(input, { target: { value: displayName } })
  fireEvent.submit(input.closest('form')!)
}

const page = (content: unknown[] = [], page = 0, totalPages = content.length ? 1 : 0) => ({
  content, page, size: 20, totalElements: page * 20 + content.length, totalPages,
})
const ownChannel = { id: 'own-channel', name: 'RealLive Studio', status: 'ACTIVE', categories: [], numberOfFollower: 0 }

beforeEach(() => {
  requests.mockClear()
  useAuthStore.setState({ user, isAuthenticated: true, isCheckingAuth: false })
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  Object.defineProperties(HTMLDialogElement.prototype, {
    showModal: { configurable: true, value(this: HTMLDialogElement) { this.setAttribute('open', '') } },
    close: { configurable: true, value(this: HTMLDialogElement) { this.removeAttribute('open') } },
  })
  api = (config) => {
    if (config.url === base) return reply(config, page())
    if (config.url === '/channel/api/channels/me') return reply(config, ownChannel)
    if (config.url?.endsWith('/moderation-context')) return reply(config, { canViewBans: true })
    if (config.url === '/channel/api/categories') return reply(config, [])
    if (config.url === '/api/auth/csrf') return reply(config, { headerName: 'X-XSRF-TOKEN', token: 'test-token' })
    throw new Error(`Unexpected request: ${config.method} ${config.url}`)
  }
  apiClient.defaults.adapter = async (config) => {
    requests(config)
    return api(config)
  }
})

afterEach(() => {
  cleanup()
  client.clear()
  for (const [name, descriptor] of Object.entries(dialogMethods)) {
    if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor)
    else Reflect.deleteProperty(HTMLDialogElement.prototype, name)
  }
  apiClient.defaults.adapter = originalAdapter
  useAuthStore.setState({ isAuthModalOpen: false })
})

describe('Moderators dashboard', () => {
  it('opens Moderators from the sidebar without repeating the link in My Channel', async () => {
    mount('/dashboard/channel')
    const navigation = screen.getByRole('navigation', { name: 'Streamer navigation' })
    const link = await within(navigation).findByRole('link', { name: 'Moderators' })
    await screen.findByRole('heading', { name: 'My Channel' })
    expect(screen.getAllByRole('link', { name: 'Moderators' })).toEqual([link])
    fireEvent.click(link)
    expect(await screen.findByRole('heading', { name: 'Moderators' })).toBeDefined()
    expect(await screen.findByText('No moderators yet.')).toBeDefined()
    expect(screen.queryByRole('heading', { name: ownChannel.name })).toBeNull()
  })

  it('gates direct guest access and opens login', () => {
    useAuthStore.setState({ user: null, isAuthenticated: false })
    mount()
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))
    expect(useAuthStore.getState().isAuthModalOpen).toBe(true)
    expect(requests).not.toHaveBeenCalled()
  })

  it.each([
    ['missing', 'No channel available.'],
    ['inactive', 'Your channel must be active to manage moderators.'],
    ['forbidden', 'You do not have permission to manage moderators for this channel.'],
  ])('handles a %s channel or permission on direct entry', async (condition, message) => {
    const fallback = api
    api = (config) => {
      if (config.url === '/channel/api/channels/me') {
        if (condition === 'missing') return fail(config, 404)
        if (condition === 'inactive') return reply(config, { ...ownChannel, status: 'SUSPENDED' })
      }
      if (config.url === base) return fail(config, 403)
      return fallback(config)
    }
    mount()
    expect(await screen.findByText(message)).toBeDefined()
    expect(screen.queryByRole('button', { name: 'Add moderator' })).toBeNull()
    expect(requests.mock.calls.some(([config]) => config.url === base)).toBe(condition === 'forbidden')
    expect(requests.mock.calls.some(([config]) => config.url.includes('other-channel'))).toBe(false)
  })

  it('selects the right user among matching display names, grants and removes with cookie credentials and CSRF', async () => {
    const fallback = api
    const displayName = ' Alex & '
    let assigned = false
    api = (config) => {
      if (config.url === base) {
        expect(config.params).toEqual({ page: 0, size: 20 })
        return reply(config, page(assigned ? [{ ...profile, assignedAt }] : []))
      }
      if (config.url === `${base}/candidate`) {
        expect(config.params).toEqual({ displayName: 'Alex &', page: 0, size: 20 })
        return reply(config, page([
          { ...profile, userId: 'another-user', eligibility: 'ELIGIBLE' },
          { ...profile, eligibility: 'ELIGIBLE' },
        ]))
      }
      if (config.url === `${base}/target`) {
        expect(config.withCredentials).toBe(true)
        expect(config.headers.get('X-XSRF-TOKEN')).toBe('test-token')
        assigned = config.method === 'post'
        if (assigned) return reply(config, { channelId: 'own-channel', userId: 'target' }, 201)
        expect(config.method).toBe('delete')
        return reply(config, undefined, 204)
      }
      return fallback(config)
    }
    mount()
    const { add, input, dialog } = await openAdd()
    expect(document.activeElement).toBe(input)
    search(input, displayName)
    const candidates = await within(dialog).findAllByRole('button', { name: 'Select Alex' })
    expect(candidates).toHaveLength(2)
    expect(within(dialog).queryByText('@Exact+Name')).toBeNull()
    expect(within(dialog).queryByText('@OtherAlex')).toBeNull()
    expect(within(dialog).getByRole<HTMLButtonElement>('button', { name: 'Make moderator' }).disabled).toBe(true)
    fireEvent.click(candidates[1])
    fireEvent.click(within(dialog).getByRole('button', { name: 'Make moderator' }))
    expect(await screen.findByText('Moderator added.')).toBeDefined()
    expect(document.activeElement).toBe(add)
    const remove = await screen.findByRole('button', { name: 'Remove Alex' })
    remove.focus()
    fireEvent.click(remove)
    const confirm = screen.getByRole('dialog', { name: 'Remove moderator?' })
    expect(confirm.textContent).toContain('Remove Alex as a moderator for RealLive Studio?')
    expect(document.activeElement).toBe(within(confirm).getByRole('button', { name: 'Cancel' }))
    fireEvent.click(within(confirm).getByRole('button', { name: 'Remove moderator' }))
    expect(await screen.findByText('Moderator removed.')).toBeDefined()
    expect(await screen.findByText('No moderators yet.')).toBeDefined()
    await waitFor(() => expect(document.activeElement).toBe(add))
  })

  it.each([
    ['CHANNEL_OWNER', 'This user owns the channel.'],
    ['ALREADY_MODERATOR', 'This user is already a moderator.'],
    ['CHANNEL_BANNED', 'This user is banned on this channel.'],
    ['ACCOUNT_INACTIVE', 'This account is inactive.'],
  ])('blocks %s candidates', async (eligibility, reason) => {
    const fallback = api
    api = (config) => config.url === `${base}/candidate`
      ? reply(config, page([{ ...profile, eligibility }]))
      : fallback(config)
    mount()
    const { input, dialog } = await openAdd()
    fireEvent.change(input, { target: { value: profile.displayName } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Search' }))
    expect(await within(dialog).findByText(reason)).toBeDefined()
    expect(within(dialog).getByRole<HTMLButtonElement>('button', { name: 'Make moderator' }).disabled).toBe(true)
    if (eligibility === 'CHANNEL_BANNED') {
      expect(within(dialog).getByRole('link', { name: 'Banned users' }).getAttribute('href'))
        .toBe('/dashboard/chat-bans?channelId=own-channel')
    }
  })

  it('clears selection on edit, ignores a late search and handles an empty result', async () => {
    const fallback = api
    let finish!: () => void
    api = (config) => {
      if (config.params?.displayName === 'Slow') {
        return new Promise((resolve) => {
          finish = () => resolve(reply(config, page([{ ...profile, displayName: 'Stale', eligibility: 'ELIGIBLE' }])))
        })
      }
      if (config.params?.displayName === 'Missing') return reply(config, page())
      if (config.url === `${base}/candidate`) return reply(config, page([{ ...profile, eligibility: 'ELIGIBLE' }]))
      return fallback(config)
    }
    mount()
    const { input, dialog } = await openAdd()
    const make = within(dialog).getByRole<HTMLButtonElement>('button', { name: 'Make moderator' })
    search(input)
    await within(dialog).findByText('Alex')
    expect(make.disabled).toBe(false)
    search(input, 'Slow')
    expect(within(dialog).queryByText('Alex')).toBeNull()
    expect(make.disabled).toBe(true)
    search(input, 'Missing')
    await within(dialog).findByText('No users match that display name.')
    await act(async () => finish())
    expect(within(dialog).queryByText('Stale')).toBeNull()
    expect(make.disabled).toBe(true)
  })

  it('refreshes after a concurrent grant and reflects the new assignment', async () => {
    const fallback = api
    let assigned = false
    api = (config) => {
      if (config.url === base) return reply(config, page(assigned ? [{ ...profile, assignedAt }] : []))
      if (config.method === 'post') {
        assigned = true
        return fail(config, 409, 1028)
      }
      if (config.url === `${base}/candidate`) return reply(config, page([{ ...profile, eligibility: 'ELIGIBLE' }]))
      return fallback(config)
    }
    mount()
    const { input, dialog } = await openAdd()
    search(input)
    await within(dialog).findByText('Alex')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Make moderator' }))
    expect(await screen.findByText('This user is already a moderator.')).toBeDefined()
    expect(await screen.findByRole('button', { name: 'Remove Alex' })).toBeDefined()
  })

  it.each([204, 404])('goes back a page when the only assignment is removed (%s)', async (status) => {
    const fallback = api
    let removed = false
    const rows = Array.from({ length: 20 }, (_, index) => ({ ...profile, userId: `first-${index}`, assignedAt }))
    api = (config) => {
      if (config.url === base) {
        expect(config.params.size).toBe(20)
        if (config.params.page === 1) {
          return reply(config, page(removed ? [] : [{
            userId: 'missing-profile', displayName: null, avatarUrl: null, assignedAt,
          }], 1, removed ? 1 : 2))
        }
        return reply(config, { ...page(rows, 0, removed ? 1 : 2), totalElements: removed ? 20 : 21 })
      }
      if (config.url === `${base}/missing-profile` && config.method === 'delete') {
        removed = true
        if (status === 404) return fail(config, status, 1031)
        return reply(config, undefined, status)
      }
      if (config.url === '/api/auth/csrf') return fail(config, 404)
      return fallback(config)
    }
    mount()
    fireEvent.click(await screen.findByRole('button', { name: 'Next' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Remove Unknown user' }))
    fireEvent.click(screen.getByRole('button', { name: 'Remove moderator' }))
    expect(await screen.findByText(status === 404 ? 'This moderator was already removed.' : 'Moderator removed.')).toBeDefined()
    expect(await screen.findAllByRole('listitem')).toHaveLength(20)
    await waitFor(() => expect(screen.queryByRole('navigation', { name: 'Moderators pagination' })).toBeNull())
  })

  it.each([
    [409, 1027, 'This user owns the channel.'],
    [409, 1029, 'This user is banned on this channel.'],
    [409, 1030, 'This account is inactive.'],
    [403, undefined, 'You do not have permission to manage moderators for this channel.'],
    [503, undefined, 'Unable to update the moderator assignment. Please try again.'],
  ])('reports write error %s / %s', async (status, code, message) => {
    const fallback = api
    api = (config) => {
      if (config.method === 'post') return fail(config, status, code)
      if (config.url === `${base}/candidate`) return reply(config, page([{ ...profile, eligibility: 'ELIGIBLE' }]))
      return fallback(config)
    }
    mount()
    const { input, dialog } = await openAdd()
    search(input)
    await within(dialog).findByText('Alex')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Make moderator' }))
    expect(await within(dialog).findByText(message)).toBeDefined()
    expect(within(dialog).getByRole<HTMLButtonElement>('button', { name: 'Make moderator' }).disabled).toBe(status !== 503)
  })

  it('retries a temporary list failure', async () => {
    const fallback = api
    let unavailable = true
    api = (config) => config.url === base && unavailable ? fail(config, 503) : fallback(config)
    mount()
    expect(await screen.findByText('Unable to load moderators. Please try again.')).toBeDefined()
    unavailable = false
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('No moderators yet.')).toBeDefined()
  })
})
