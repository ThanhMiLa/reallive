import assert from 'node:assert/strict'
import { after, afterEach, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { JSDOM } from 'jsdom'
import { createServer } from 'vite'

let server
let apiClient
let channelApi
let paymentApi
let channelModerationKeys
let useAuthStore
let AppRoutes
let StreamerDashboardLayout
let StreamerRevenuePage
let StreamerRegistration
let testingLibrary

const dom = new JSDOM('<!doctype html><html><body></body></html>', {
  url: 'http://localhost:5173/dashboard',
})

function installDomGlobals() {
  globalThis.IS_REACT_ACT_ENVIRONMENT = true
  globalThis.window = dom.window
  globalThis.document = dom.window.document
  globalThis.HTMLElement = dom.window.HTMLElement
  globalThis.Element = dom.window.Element
  globalThis.Node = dom.window.Node
  globalThis.Event = dom.window.Event
  globalThis.MouseEvent = dom.window.MouseEvent
  globalThis.KeyboardEvent = dom.window.KeyboardEvent
  globalThis.DocumentFragment = dom.window.DocumentFragment
  globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window)
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: dom.window.navigator,
  })
}

const streamerUser = {
  userId: 'streamer-1',
  displayName: 'StreamerOne',
  email: 'streamer@reallive.dev',
  emailVerified: true,
  roles: ['USER', 'STREAMER'],
  avatarUrl: null,
  dob: null,
}

const viewerUser = {
  userId: 'viewer-1',
  displayName: 'ViewerOne',
  email: 'viewer@reallive.dev',
  emailVerified: true,
  roles: ['USER'],
  avatarUrl: null,
  dob: null,
}

const sampleChannel = {
  id: 'ch-1',
  streamerId: 'streamer-1',
  name: 'StreamerOne',
  slug: 'streamerone',
  description: 'Welcome!',
  avatarUrl: null,
  bannerUrl: null,
  status: 'ACTIVE',
  categories: [],
  numberOfFollower: 0,
}

const sampleStreamerWallet = {
  walletId: 'sw-1',
  streamerId: 'streamer-1',
  availableBalance: 3250,
}

before(async () => {
  installDomGlobals()
  testingLibrary = await import('@testing-library/react')
  server = await createServer({ server: { middlewareMode: true, watch: null, ws: false } })
  ;({ apiClient } = await server.ssrLoadModule('/src/api/client.ts'))
  ;({ channelApi } = await server.ssrLoadModule('/src/api/channel.api.ts'))
  ;({ paymentApi } = await server.ssrLoadModule('/src/api/payment.api.ts'))
  ;({ channelModerationKeys } = await server.ssrLoadModule('/src/hooks/useChannelModeration.ts'))
  ;({ useAuthStore } = await server.ssrLoadModule('/src/stores/auth.store.ts'))
  ;({ AppRoutes } = await server.ssrLoadModule('/src/App.tsx'))
  ;({ StreamerDashboardLayout } = await server.ssrLoadModule('/src/components/layout/StreamerDashboardLayout.tsx'))
  ;({ StreamerRevenuePage } = await server.ssrLoadModule('/src/pages/dashboard/StreamerRevenuePage.tsx'))
  ;({ StreamerRegistration } = await server.ssrLoadModule('/src/pages/become-streamer/StreamerRegistration.tsx'))
})

afterEach(() => {
  testingLibrary?.cleanup()
})

after(async () => {
  await server?.close()
  dom.window.close()
})

function setAuth(user, checking = false) {
  useAuthStore.setState({
    user,
    isAuthenticated: Boolean(user),
    isCheckingAuth: checking,
  })
  Object.assign(useAuthStore.getInitialState(), useAuthStore.getState())
}

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
    },
  })
}

async function flushEffects() {
  await testingLibrary.act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 25))
  })
}

test('channelApi.ensureOwnChannel calls POST /channel/api/channels/me', async () => {
  const original = apiClient.defaults.adapter
  const requests = []
  apiClient.defaults.adapter = async (config) => {
    requests.push(config)
    return {
      data: { status: 200, code: 1000, message: 'OK', result: sampleChannel },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }
  }

  try {
    const channel = await channelApi.ensureOwnChannel()
    assert.deepEqual(channel, sampleChannel)
    assert.equal(requests.length, 1)
    assert.equal(requests[0].method, 'post')
    assert.equal(requests[0].url, '/channel/api/channels/me')
    assert.equal(requests[0].withCredentials, true)
  } finally {
    apiClient.defaults.adapter = original
  }
})

test('paymentApi.createStreamerWallet and getStreamerWallet call /payment/api/streamer-wallets/me', async () => {
  const original = apiClient.defaults.adapter
  const requests = []
  apiClient.defaults.adapter = async (config) => {
    requests.push(config)
    return {
      data: { status: 200, code: 0, message: 'OK', result: sampleStreamerWallet },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }
  }

  try {
    const created = await paymentApi.createStreamerWallet()
    const fetched = await paymentApi.getStreamerWallet()
    assert.deepEqual(created, sampleStreamerWallet)
    assert.deepEqual(fetched, sampleStreamerWallet)
    assert.equal(requests[0].method, 'post')
    assert.equal(requests[0].url, '/payment/api/streamer-wallets/me')
    assert.equal(requests[1].method, 'get')
    assert.equal(requests[1].url, '/payment/api/streamer-wallets/me')
    assert.ok(requests.every((req) => req.withCredentials === true))
  } finally {
    apiClient.defaults.adapter = original
  }
})

test('StreamerRegistration renders CTA link to /dashboard when already registered', () => {
  const client = makeClient()
  try {
    const html = renderToStaticMarkup(
      createElement(
        QueryClientProvider,
        { client },
        createElement(
          MemoryRouter,
          null,
          createElement(StreamerRegistration, { profile: streamerUser }),
        ),
      ),
    )
    assert.match(html, /Your streamer registration is complete/)
    assert.match(html, /href="\/dashboard"/)
    assert.match(html, /Go to Streamer Dashboard/)
  } finally {
    client.clear()
  }
})

test('StreamerDashboardLayout is SSR-safe and renders Donation Statistics nav item for streamers', () => {
  setAuth(streamerUser)
  const client = makeClient()
  client.setQueryData(channelModerationKeys.ownChannel(streamerUser.userId), sampleChannel)

  try {
    const html = renderToStaticMarkup(
      createElement(
        QueryClientProvider,
        { client },
        createElement(
          MemoryRouter,
          { initialEntries: ['/dashboard'] },
          createElement(
            Routes,
            null,
            createElement(
              Route,
              { element: createElement(StreamerDashboardLayout) },
              createElement(Route, {
                path: '/dashboard',
                element: createElement('div', null, 'Dashboard Child Content'),
              }),
            ),
          ),
        ),
      ),
    )
    assert.match(html, /<aside/)
    assert.match(html, /aria-label="Streamer navigation"/)
    assert.match(html, /href="\/dashboard\/revenue"/)
    assert.match(html, /Donation Statistics/)
    assert.doesNotMatch(html, />Revenue</)
    assert.match(html, /Dashboard Child Content/)
  } finally {
    client.clear()
  }
})

test('StreamerDashboardLayout bootstraps POST /channel/api/channels/me, gates Outlet while pending/errored with retry, and seeds own-channel', async () => {
  setAuth(streamerUser)
  const client = makeClient()
  const original = apiClient.defaults.adapter
  let channelRequests = 0
  let shouldFail = true

  apiClient.defaults.adapter = async (config) => {
    if (config.url === '/channel/api/channels/me' && config.method === 'post') {
      channelRequests += 1
      if (shouldFail) {
        return Promise.reject({
          isAxiosError: true,
          response: { status: 503, data: { message: 'Channel service unavailable' } },
          config,
        })
      }
      return {
        data: { status: 200, code: 1000, message: 'OK', result: sampleChannel },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    if (config.url?.includes('/moderation/context')) {
      return {
        data: {
          status: 200,
          code: 1000,
          message: 'OK',
          result: {
            channelId: sampleChannel.id,
            ownerUserId: streamerUser.userId,
            allowedBanTypes: ['PERMANENT'],
            allowedUnbanTypes: ['PERMANENT'],
            canViewBans: true,
          },
        },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    throw new Error(`Unexpected request: ${config.method} ${config.url}`)
  }

  try {
    const view = testingLibrary.render(
      createElement(
        QueryClientProvider,
        { client },
        createElement(
          MemoryRouter,
          { initialEntries: ['/dashboard'] },
          createElement(
            Routes,
            null,
            createElement(
              Route,
              { element: createElement(StreamerDashboardLayout) },
              createElement(Route, {
                path: '/dashboard',
                element: createElement('div', null, 'Protected Outlet Content'),
              }),
            ),
          ),
        ),
      ),
    )

    // Initially pending -> Outlet is gated
    assert.ok(view.getByText('Setting up your channel...'))
    assert.equal(view.queryByText('Protected Outlet Content'), null)

    await flushEffects()

    // Failed -> error alert + Try again button, Outlet still gated
    assert.equal(channelRequests, 1)
    assert.ok(view.getByRole('alert'))
    assert.equal(view.queryByText('Protected Outlet Content'), null)

    // Retry succeeds -> Outlet renders and ['own-channel'] is seeded
    shouldFail = false
    const retryButton = view.getByRole('button', { name: /Try again/i })
    await testingLibrary.act(async () => {
      testingLibrary.fireEvent.click(retryButton)
    })
    await flushEffects()

    assert.equal(channelRequests, 2)
    assert.ok(view.getByText('Protected Outlet Content'))
    assert.deepEqual(client.getQueryData(['own-channel']), sampleChannel)
    assert.ok(view.getByRole('link', { name: 'Donation Statistics' }))
  } finally {
    apiClient.defaults.adapter = original
    client.clear()
  }
})

test('StreamerDashboardLayout gates Outlet while checking session to avoid premature child API calls', async () => {
  setAuth(streamerUser, true)
  const client = makeClient()
  let childRendered = false

  function ProbeChild() {
    childRendered = true
    return createElement('div', null, 'Premature Child Content')
  }

  try {
    const view = testingLibrary.render(
      createElement(
        QueryClientProvider,
        { client },
        createElement(
          MemoryRouter,
          { initialEntries: ['/dashboard/channel'] },
          createElement(
            Routes,
            null,
            createElement(
              Route,
              { element: createElement(StreamerDashboardLayout) },
              createElement(Route, {
                path: '/dashboard/channel',
                element: createElement(ProbeChild),
              }),
            ),
          ),
        ),
      ),
    )

    assert.ok(view.getByText('Checking your session...'))
    assert.equal(view.queryByText('Premature Child Content'), null)
    assert.equal(childRendered, false)
  } finally {
    client.clear()
  }
})

test('StreamerDashboardLayout skips POST /channel/api/channels/me for non-streamer moderators and renders Outlet', async () => {
  setAuth(viewerUser)
  const client = makeClient()
  const original = apiClient.defaults.adapter
  const requests = []

  apiClient.defaults.adapter = async (config) => {
    requests.push(config)
    if (config.url?.includes('/moderation/context')) {
      return {
        data: {
          status: 200,
          code: 1000,
          message: 'OK',
          result: {
            channelId: 'ch-99',
            ownerUserId: 'streamer-99',
            allowedBanTypes: ['TIMEOUT'],
            allowedUnbanTypes: ['TIMEOUT'],
            canViewBans: true,
          },
        },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    throw new Error(`Unexpected request: ${config.method} ${config.url}`)
  }

  try {
    const view = testingLibrary.render(
      createElement(
        QueryClientProvider,
        { client },
        createElement(
          MemoryRouter,
          { initialEntries: ['/dashboard/chat-bans?channelId=ch-99'] },
          createElement(
            Routes,
            null,
            createElement(
              Route,
              { element: createElement(StreamerDashboardLayout) },
              createElement(Route, {
                path: '/dashboard/chat-bans',
                element: createElement('div', null, 'Moderator Banned Users Outlet'),
              }),
            ),
          ),
        ),
      ),
    )

    await flushEffects()

    assert.ok(view.getByText('Moderator Banned Users Outlet'))
    assert.equal(view.queryByRole('link', { name: 'Donation Statistics' }), null)
    assert.equal(
      requests.some((req) => req.url === '/channel/api/channels/me'),
      false,
    )
  } finally {
    apiClient.defaults.adapter = original
    client.clear()
  }
})

test('StreamerRevenuePage invokes POST /payment/api/streamer-wallets/me, displays balance, and handles error/retry', async () => {
  setAuth(streamerUser)
  const client = makeClient()
  const original = apiClient.defaults.adapter
  let walletCalls = 0
  let shouldFail = true

  apiClient.defaults.adapter = async (config) => {
    if (config.url === '/payment/api/streamer-wallets/me' && config.method === 'post') {
      walletCalls += 1
      if (shouldFail) {
        return Promise.reject({
          isAxiosError: true,
          response: { status: 500, data: { message: 'Wallet service failed' } },
          config,
        })
      }
      return {
        data: { status: 200, code: 0, message: 'OK', result: sampleStreamerWallet },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    throw new Error(`Unexpected request: ${config.method} ${config.url}`)
  }

  try {
    const view = testingLibrary.render(
      createElement(
        QueryClientProvider,
        { client },
        createElement(MemoryRouter, null, createElement(StreamerRevenuePage)),
      ),
    )

    // Loading state
    assert.ok(view.getByRole('status', { name: /Loading streamer wallet balance/i }))

    await flushEffects()

    // Error state + retry
    assert.equal(walletCalls, 1)
    assert.ok(view.getByRole('alert'))
    shouldFail = false

    const retryBtn = view.getByRole('button', { name: /Try again/i })
    await testingLibrary.act(async () => {
      testingLibrary.fireEvent.click(retryBtn)
    })
    await flushEffects()

    assert.equal(walletCalls, 2)
    assert.ok(view.getByRole('heading', { name: 'Donation Statistics' }))
    assert.ok(view.getByText('Streamer Wallet Balance'))
    assert.equal(view.queryByText('Wallet Ready'), null)
    assert.equal(view.queryByText('How streamer revenue works'), null)
    assert.ok(view.getByText(/3,250/))
    assert.ok(view.getByText('Coins'))
    assert.ok(view.getByRole('button', { name: /Refresh streamer wallet balance/i }))
  } finally {
    apiClient.defaults.adapter = original
    client.clear()
  }
})

test('StreamerRevenuePage shows non-streamer prompt without calling streamer wallet API and AppRoutes wires /dashboard/revenue', async () => {
  setAuth(viewerUser)
  const client = makeClient()
  const original = apiClient.defaults.adapter
  let called = false
  apiClient.defaults.adapter = async (config) => {
    called = true
    throw new Error(`Unexpected request: ${config.url}`)
  }

  try {
    const html = renderToStaticMarkup(
      createElement(
        QueryClientProvider,
        { client },
        createElement(
          MemoryRouter,
          { initialEntries: ['/dashboard/revenue'] },
          createElement(AppRoutes),
        ),
      ),
    )
    assert.match(html, /Streamer account required/)
    assert.match(html, /href="\/become-streamer"/)
    assert.equal(called, false)
  } finally {
    apiClient.defaults.adapter = original
    client.clear()
  }
})
