import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import axios from 'axios'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { QueryClientProvider } from '@tanstack/react-query'
import { createServer } from 'vite'

let server, useAuthStore, apiClient, authApi, queryClient, Page
const profile = {
  userId: 'session-viewer', displayName: 'Viewer', email: 'viewer@example.com',
  emailVerified: true, roles: ['USER'], avatarUrl: null, dob: null,
}

before(async () => {
  server = await createServer({ server: { middlewareMode: true, watch: null, ws: false } })
  ;({ useAuthStore } = await server.ssrLoadModule('/src/stores/auth.store.ts'))
  ;({ apiClient } = await server.ssrLoadModule('/src/api/client.ts'))
  ;({ authApi } = await server.ssrLoadModule('/src/api/auth.api.ts'))
  ;({ queryClient } = await server.ssrLoadModule('/src/lib/query-client.ts'))
  ;({ BecomeStreamerPage: Page } = await server.ssrLoadModule('/src/pages/become-streamer/BecomeStreamerPage.tsx'))
})
after(async () => { queryClient?.clear(); await server?.close() })

test('duplicate startup effects and page loading share a single /me request', async () => {
  queryClient.clear()
  useAuthStore.setState({ user: null, isAuthenticated: false, isCheckingAuth: true })
  const original = apiClient.defaults.adapter
  let release
  const pending = new Promise((resolve) => { release = resolve })
  let requests = 0
  apiClient.defaults.adapter = async (config) => {
    requests++
    assert.equal(config.url, '/user/api/users/me')
    await pending
    return { data: { result: profile }, status: 200, statusText: 'OK', headers: {}, config }
  }
  try {
    const first = useAuthStore.getState().checkAuth()
    const second = useAuthStore.getState().checkAuth()
    assert.equal(first, second)
    release()
    await Promise.all([first, second])
    assert.equal(requests, 1)
    assert.deepEqual(queryClient.getQueryData(['profile', profile.userId]), profile)
    Object.assign(useAuthStore.getInitialState(), useAuthStore.getState())
    renderToStaticMarkup(createElement(QueryClientProvider, { client: queryClient },
      createElement(MemoryRouter, null, createElement(Page))))
    const query = queryClient.getQueryCache().find({ queryKey: ['profile', profile.userId] })
    await queryClient.fetchQuery(query.options)
    await queryClient.fetchQuery(query.options)
    assert.equal(requests, 1)

    await queryClient.invalidateQueries({ queryKey: ['profile', profile.userId], refetchType: 'none' })
    await queryClient.fetchQuery(query.options)
    assert.equal(requests, 2)
  } finally {
    release()
    apiClient.defaults.adapter = original
    queryClient.clear()
  }
})

test('failed checks release the shared request and retain the session on 503', async () => {
  const original = apiClient.defaults.adapter
  let attempts = 0
  useAuthStore.setState({ user: profile, isAuthenticated: true })
  apiClient.defaults.adapter = async (config) => {
    attempts++
    throw new axios.AxiosError('Unavailable', 'ERR_BAD_RESPONSE', config, null, {
      status: 503, data: {}, statusText: 'Unavailable', headers: {}, config,
    })
  }
  try {
    await Promise.all([useAuthStore.getState().checkAuth(), useAuthStore.getState().checkAuth()])
    assert.equal(attempts, 1)
    assert.equal(useAuthStore.getState().isAuthenticated, true)
    assert.equal(useAuthStore.getState().isCheckingAuth, false)
    await useAuthStore.getState().checkAuth()
    assert.equal(attempts, 2)
  } finally { apiClient.defaults.adapter = original }
})

test('logout clears cached private profiles', async () => {
  const original = authApi.logout
  authApi.logout = async () => undefined
  queryClient.setQueryData(['profile', profile.userId], profile)
  try {
    await useAuthStore.getState().logout()
    assert.equal(queryClient.getQueryData(['profile', profile.userId]), undefined)
    assert.equal(useAuthStore.getState().user, null)
  } finally { authApi.logout = original }
})
