import assert from 'node:assert/strict'
import axios from 'axios'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { renderToStaticMarkup } from 'react-dom/server'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createServer } from 'vite'

let server, schemas, authApi, apiClient, ProfilePage, useAuthStore, AppRoutes
before(async () => {
  server = await createServer({ server: { middlewareMode: true, watch: null, ws: false } })
  schemas = await server.ssrLoadModule('/src/pages/profile/profile.schema.ts')
  ;({ authApi } = await server.ssrLoadModule('/src/api/auth.api.ts'))
  ;({ apiClient } = await server.ssrLoadModule('/src/api/client.ts'))
  ;({ AppRoutes } = await server.ssrLoadModule('/src/App.tsx'))
  ;({ ProfilePage } = await server.ssrLoadModule('/src/pages/profile/ProfilePage.tsx'))
  ;({ useAuthStore } = await server.ssrLoadModule('/src/stores/auth.store.ts'))
})
after(async () => { await server?.close() })

test('profile rejects blank names, invalid dates and future birthdays', () => {
  const parse = (displayName, dob) => schemas.profileSchema.safeParse({ displayName, dob })
  assert.equal(parse('   ', '').success, false)
  assert.equal(parse('a'.repeat(101), '').success, false)
  for (const dob of ['2025-02-29', '2020-02-30', '0000-01-01', '2999-01-01']) {
    assert.equal(parse('Viewer', dob).success, false)
  }
  assert.deepEqual(parse('  Viewer  ', '2000-02-29').data, { displayName: 'Viewer', dob: '2000-02-29' })
  assert.equal(parse('Viewer', '').success, true)
})

test('password validation requires a different password and matching confirmation', () => {
  const valid = { currentPassword: 'old-pass', newPassword: 'new-pass', confirmPassword: 'new-pass' }
  assert.equal(schemas.passwordSchema.safeParse(valid).success, true)
  for (const invalid of [
    { ...valid, currentPassword: 'a' },
    { ...valid, newPassword: '    ', confirmPassword: '    ' },
    { ...valid, confirmPassword: 'different' },
    { ...valid, newPassword: 'old-pass', confirmPassword: 'old-pass' },
  ]) assert.equal(schemas.passwordSchema.safeParse(invalid).success, false)
})

test('avatars accept backend-supported formats within upload limit', () => {
  assert.equal(schemas.avatarError(new File(['image'], 'avatar.png', { type: 'image/png' })), null)
  assert.ok(schemas.avatarError(new File(['svg'], 'avatar.svg', { type: 'image/svg+xml' })))
  assert.ok(schemas.avatarError(new File([], 'avatar.png', { type: 'image/png' })))
  assert.ok(schemas.avatarError(new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'large.jpg', { type: 'image/jpeg' })))
})

test('profile PATCH keeps multipart payload; password POST sends backend field names with cookies', async () => {
  const requests = []
  const original = apiClient.defaults.adapter
  apiClient.defaults.adapter = async (config) => {
    requests.push(config)
    return { data: { result: { userId: 'viewer-1', displayName: 'Updated' } }, status: 200, statusText: 'OK', headers: {}, config }
  }
  try {
    const payload = new FormData()
    payload.append('displayName', 'Updated')
    payload.append('dob', '2000-02-29')
    payload.append('avatar', new File(['image'], 'avatar.png', { type: 'image/png' }))
    const updated = await authApi.updateProfile(payload)
    assert.equal(updated.displayName, 'Updated')
    assert.equal(requests[0].url, '/user/api/users/me')
    assert.equal(requests[0].method, 'patch')
    assert.equal(requests[0].data, payload)
    assert.notEqual(requests[0].headers.get('Content-Type'), 'application/json')
    assert.equal(requests[0].data.get('avatar').name, 'avatar.png')
    assert.equal(requests[0].withCredentials, true)
    const password = { currentPassword: 'old-pass', newPassword: 'new-pass', confirmPassword: 'new-pass' }
    await authApi.changePassword(password)
    assert.equal(requests[1].url, '/user/api/auth/change-password')
    assert.equal(requests[1].method, 'post')
    assert.deepEqual(JSON.parse(requests[1].data), password)
    assert.equal(requests[1].withCredentials, true)
  } finally { apiClient.defaults.adapter = original }
})

test('change-password refreshes an expired access token once and retries the original payload', async () => {
  const originalClientAdapter = apiClient.defaults.adapter
  const originalRefreshAdapter = axios.defaults.adapter
  let attempts = 0
  let refreshes = 0
  const password = { currentPassword: 'old-pass', newPassword: 'new-pass', confirmPassword: 'new-pass' }
  axios.defaults.adapter = async (config) => {
    refreshes++
    assert.match(config.url, /\/user\/api\/auth\/refresh$/)
    assert.equal(config.withCredentials, true)
    return { data: {}, status: 200, statusText: 'OK', headers: {}, config }
  }
  apiClient.defaults.adapter = async (config) => {
    attempts++
    if (attempts === 1) {
      throw new axios.AxiosError('Expired', 'ERR_BAD_REQUEST', config, null, {
        status: 401, data: {}, headers: {}, statusText: 'Unauthorized', config,
      })
    }
    assert.deepEqual(JSON.parse(config.data), password)
    return { data: {}, status: 200, statusText: 'OK', headers: {}, config }
  }
  try {
    await authApi.changePassword(password)
    assert.equal(attempts, 2)
    assert.equal(refreshes, 1)
  } finally {
    apiClient.defaults.adapter = originalClientAdapter
    axios.defaults.adapter = originalRefreshAdapter
  }
})

function renderProfile(user, checking = false) {
  useAuthStore.setState({ user, isAuthenticated: Boolean(user), isCheckingAuth: checking })
  Object.assign(useAuthStore.getInitialState(), useAuthStore.getState())
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  if (user) client.setQueryData(['profile', user.userId], user)
  const html = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(ProfilePage)))
  const query = client.getQueryCache().getAll()[0]
  return { html, query, client }
}

test('guests and session initialization cannot fetch or edit private information', () => {
  for (const checking of [false, true]) {
    const { html, query, client } = renderProfile(null, checking)
    assert.equal(query.options.enabled, false)
    assert.doesNotMatch(html, /id="profile-email"/)
    assert.match(html, checking ? /Checking your session/ : /Log in to view/)
    client.clear()
  }
})

test('authenticated profile displays account fields and both editable forms', () => {
  const { html, query, client } = renderProfile({ userId: 'viewer-1', displayName: 'Test viewer', email: 'viewer@example.com', dob: '2000-02-29', avatarUrl: null })
  assert.equal(query.options.enabled, true)
  assert.match(html, /id="profile-name"/)
  assert.match(html, /viewer@example.com/)
  assert.match(html, /Save changes/)
  assert.match(html, /id="current-password"/)
  assert.match(html, /id="confirm-password"/)
  assert.match(html, /id="profile-email"[^>]*readOnly/)
  client.clear()
})

function renderRoute(path) {
  useAuthStore.setState({ user: null, isAuthenticated: false, isCheckingAuth: false })
  Object.assign(useAuthStore.getInitialState(), useAuthStore.getState())
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  try {
    return renderToStaticMarkup(createElement(QueryClientProvider, { client },
      createElement(MemoryRouter, { initialEntries: [path] }, createElement(AppRoutes)),
    ))
  } finally { client.clear() }
}

test('profile uses its own account navigation without the browsing header or sidebar', () => {
  const html = renderRoute('/profile')
  assert.match(html, /aria-label="Account navigation"/)
  assert.match(html, /aria-current="page"[^>]*href="\/profile"/)
  assert.match(html, /Back to home/)
  assert.doesNotMatch(html, /Toggle navigation|Search streams|>Browse<|FOLLOWING/)
  const nav = html.match(/<nav[^>]*>(.*?)<\/nav>/s)?.[1]
  assert.equal((nav?.match(/<a /g) || []).length, 1)
})

test('browse routes retain the original site navigation', () => {
  const html = renderRoute('/browse/categories')
  assert.match(html, /Toggle navigation/)
  assert.match(html, /Search streams/)
  assert.match(html, />Browse</)
  assert.doesNotMatch(html, /aria-label="Account navigation"/)
})
