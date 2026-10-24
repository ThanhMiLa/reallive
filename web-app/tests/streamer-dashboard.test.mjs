import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createServer } from 'vite'

let server, useAuthStore, apiClient, authApi, queryClient, Header, hasStreamerRole, AppRoutes

before(async () => {
  server = await createServer({ server: { middlewareMode: true, watch: null, ws: false } })
  ;({ useAuthStore } = await server.ssrLoadModule('/src/stores/auth.store.ts'))
  ;({ apiClient } = await server.ssrLoadModule('/src/api/client.ts'))
  ;({ authApi } = await server.ssrLoadModule('/src/api/auth.api.ts'))
  ;({ queryClient } = await server.ssrLoadModule('/src/lib/query-client.ts'))
  ;({ Header } = await server.ssrLoadModule('/src/components/layout/Header.tsx'))
  ;({ hasStreamerRole } = await server.ssrLoadModule('/src/lib/utils.ts'))
  ;({ AppRoutes } = await server.ssrLoadModule('/src/App.tsx'))
})

after(async () => {
  queryClient?.clear()
  await server?.close()
})

test('hasStreamerRole accurately identifies streamer roles across different formats', () => {
  assert.equal(hasStreamerRole(null), false)
  assert.equal(hasStreamerRole(undefined), false)
  assert.equal(hasStreamerRole({ roles: ['USER'] }), false)
  assert.equal(hasStreamerRole({ roles: [] }), false)

  // Standard uppercase array
  assert.equal(hasStreamerRole({ roles: ['USER', 'STREAMER'] }), true)
  // Lowercase array
  assert.equal(hasStreamerRole({ roles: ['streamer'] }), true)
  // Single role property (e.g. from API login)
  assert.equal(hasStreamerRole({ role: 'streamer' }), true)
  assert.equal(hasStreamerRole({ role: 'STREAMER' }), true)
  assert.equal(hasStreamerRole({ role: 'Streamer' }), true)
  assert.equal(hasStreamerRole({ role: 'ROLE_STREAMER' }), true)
  // Spring Security authorities format
  assert.equal(hasStreamerRole({ authorities: ['ROLE_STREAMER'] }), true)
  // Object array format
  assert.equal(hasStreamerRole({ roles: [{ name: 'STREAMER' }] }), true)
})

test('Header menu shows Streamer Dashboard only when user has streamer role', () => {
  const renderHeader = (user) => {
    useAuthStore.setState({ user, isAuthenticated: Boolean(user), isCheckingAuth: false })
    Object.assign(useAuthStore.getInitialState(), useAuthStore.getState())
    return renderToStaticMarkup(
      createElement(
        MemoryRouter,
        null,
        createElement(Header, { defaultMenuOpen: true })
      )
    )
  }

  // 1. Regular user without streamer role
  const viewerHtml = renderHeader({
    userId: 'user-1',
    displayName: 'RegularViewer',
    email: 'viewer@test.com',
    roles: ['USER'],
  })
  assert.match(viewerHtml, />Settings</)
  assert.match(viewerHtml, />Logout</)
  assert.doesNotMatch(viewerHtml, /Streamer Dashboard/)

  // 2. Streamer with roles array
  const streamerHtml = renderHeader({
    userId: 'streamer-1',
    displayName: 'ThanhMila',
    email: 'vntvlogs@gmail.com',
    roles: ['USER', 'STREAMER'],
  })
  assert.match(streamerHtml, /Streamer Dashboard/)
  assert.match(streamerHtml, /href="\/dashboard"/)
  assert.match(streamerHtml, />Settings</)
  assert.match(streamerHtml, />Logout</)

  // 3. Streamer with role property returned by API
  const roleStreamerHtml = renderHeader({
    userId: 'streamer-2',
    displayName: 'ThanhMila2',
    email: 'streamer@test.com',
    roles: ['USER'],
    role: 'streamer',
  })
  assert.match(roleStreamerHtml, /Streamer Dashboard/)
  assert.match(roleStreamerHtml, /href="\/dashboard"/)
})

test('auth store login merges role returned in API response into user state', async () => {
  const original = apiClient.defaults.adapter
  const profile = {
    userId: 'u-100',
    displayName: 'ThanhMila',
    email: 'streamer@test.com',
    emailVerified: true,
    roles: ['USER'],
    avatarUrl: null,
    dob: null,
  }

  // Simulate API returning role: 'streamer' upon login
  apiClient.defaults.adapter = async (config) => {
    if (config.url.endsWith('/login')) {
      return {
        data: {
          status: 200,
          code: 1000,
          message: 'Login successful',
          result: {
            accessToken: 'mock-token',
            role: 'streamer',
          },
        },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    if (config.url.endsWith('/users/me')) {
      return {
        data: { result: profile },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    throw new Error(`Unexpected request: ${config.url}`)
  }

  try {
    await useAuthStore.getState().login({ username: 'thanh', password: 'password' })
    const currentUser = useAuthStore.getState().user
    assert.ok(currentUser)
    assert.equal(currentUser.role, 'streamer')
    assert.ok(currentUser.roles.includes('streamer') || currentUser.roles.includes('STREAMER') || currentUser.role === 'streamer')
    assert.equal(hasStreamerRole(currentUser), true)
  } finally {
    apiClient.defaults.adapter = original
  }
})

test('dashboard route renders dedicated streamer layout with sidebar navbar and content frame', () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  useAuthStore.setState({
    user: {
      userId: 'streamer-abc',
      displayName: 'ThanhMila',
      email: 'vntvlogs@gmail.com',
      emailVerified: true,
      roles: ['USER', 'STREAMER'],
      avatarUrl: null,
      dob: null,
    },
    isAuthenticated: true,
    isCheckingAuth: false,
  })
  Object.assign(useAuthStore.getInitialState(), useAuthStore.getState())

  try {
    const html = renderToStaticMarkup(
      createElement(
        QueryClientProvider,
        { client },
        createElement(MemoryRouter, { initialEntries: ['/dashboard'] }, createElement(AppRoutes))
      )
    )

    // Dedicated Streamer Navigation sidebar
    assert.match(html, /aria-label="Streamer navigation"/)
    assert.match(html, /Stream Manager/)
    assert.match(html, /Streamer Dashboard/)
    assert.match(html, /Back to home/)
    assert.match(html, /Streamer Dashboard Content Frame/)
    // Must not show the browsing site header search bar
    assert.doesNotMatch(html, /Search streams, categories, or streamers\.\.\./)
  } finally {
    client.clear()
  }
})
