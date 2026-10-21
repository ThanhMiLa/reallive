import assert from 'node:assert/strict'
import axios from 'axios'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { renderToStaticMarkup } from 'react-dom/server'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createServer } from 'vite'

let server, schemas, authApi, apiClient, ProfilePage, useAuthStore, AppRoutes, BecomeStreamerPage
before(async () => {
  server = await createServer({ server: { middlewareMode: true, watch: null, ws: false } })
  schemas = await server.ssrLoadModule('/src/pages/profile/profile.schema.ts')
  ;({ authApi } = await server.ssrLoadModule('/src/api/auth.api.ts'))
  ;({ apiClient } = await server.ssrLoadModule('/src/api/client.ts'))
  ;({ AppRoutes } = await server.ssrLoadModule('/src/App.tsx'))
  ;({ BecomeStreamerPage } = await server.ssrLoadModule('/src/pages/become-streamer/BecomeStreamerPage.tsx'))
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

function renderProfile(user, checking = false, component = ProfilePage) {
  useAuthStore.setState({ user, isAuthenticated: Boolean(user), isCheckingAuth: checking })
  Object.assign(useAuthStore.getInitialState(), useAuthStore.getState())
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  if (user) client.setQueryData(['profile', user.userId], user)
  const html = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(MemoryRouter, null, createElement(component))))
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
  assert.equal((nav?.match(/<a /g) || []).length, 3)
  assert.match(nav, /href="\/category-preferences"/)
})

test('browse routes retain the original site navigation', () => {
  const html = renderRoute('/browse/categories')
  assert.match(html, /Toggle navigation/)
  assert.match(html, /Search streams/)
  assert.match(html, />Browse</)
  assert.doesNotMatch(html, /aria-label="Account navigation"/)
})

test('unverified profiles show a warning and verification action below email', () => {
  const { html, client } = renderProfile({ userId: 'viewer-unverified', displayName: 'Viewer', email: 'viewer@example.com', emailVerified: false, dob: null, avatarUrl: null })
  assert.match(html, /Your email is not verified\./)
  assert.match(html, />Verify email</)
  assert.ok(html.indexOf('id="profile-email"') < html.indexOf('Your email is not verified.'))
  assert.doesNotMatch(html, />Email verified</)
  client.clear()
})

test('verified profiles show their status without requesting another code', () => {
  const { html, client } = renderProfile({ userId: 'viewer-verified', displayName: 'Viewer', email: 'viewer@example.com', emailVerified: true, dob: null, avatarUrl: null })
  assert.match(html, />Email verified</)
  assert.doesNotMatch(html, /Your email is not verified|>Verify email<|id="email-verification-otp"/)
  client.clear()
})

test('OTP validation preserves leading zeros and only accepts six ASCII digits', () => {
  assert.equal(schemas.emailOtpSchema.parse('001234'), '001234')
  for (const code of ['', '12345', '1234567', 'abcdef', '123 45', '１２３４５６', ' 123456']) {
    assert.equal(schemas.emailOtpSchema.safeParse(code).success, false)
  }
})

test('email verification APIs use authenticated POSTs and read their result contracts', async () => {
  const original = apiClient.defaults.adapter
  const expiresAt = '2026-10-20T12:01:00Z'
  const requests = []
  apiClient.defaults.adapter = async (config) => {
    requests.push(config)
    const sending = config.url.endsWith('/otp')
    return {
      data: { result: sending ? { expiresAt } : { emailVerified: true } },
      status: sending ? 202 : 200, statusText: 'OK', headers: {}, config,
    }
  }
  try {
    assert.deepEqual(await authApi.requestEmailVerificationOtp(), { expiresAt })
    assert.deepEqual(await authApi.verifyEmailOtp('001234'), { emailVerified: true })
    assert.equal(requests[0].url, '/user/api/users/me/email-verification/otp')
    assert.equal(requests[0].data, undefined)
    assert.equal(requests[1].url, '/user/api/users/me/email-verification/verify')
    assert.deepEqual(JSON.parse(requests[1].data), { otp: '001234' })
    for (const request of requests) {
      assert.equal(request.method, 'post')
      assert.equal(request.withCredentials, true)
    }
  } finally { apiClient.defaults.adapter = original }
})

test('verification failures are surfaced without marking the session verified', async () => {
  const original = apiClient.defaults.adapter
  useAuthStore.setState({ user: { userId: 'unverified-user', emailVerified: false } })
  apiClient.defaults.adapter = async (config) => {
    throw new axios.AxiosError('Verification failed', 'ERR_BAD_REQUEST', config, null, {
      status: config.url.endsWith('/otp') ? 429 : 400,
      data: { code: config.url.endsWith('/otp') ? 1021 : 1024 },
      headers: {}, statusText: 'Error', config,
    })
  }
  try {
    await assert.rejects(authApi.requestEmailVerificationOtp(), (error) => error.response.data.code === 1021)
    await assert.rejects(authApi.verifyEmailOtp('123456'), (error) => error.response.data.code === 1024)
    assert.equal(useAuthStore.getState().user.emailVerified, false)
  } finally { apiClient.defaults.adapter = original }
})

test('streamer signup requires explicit acceptance and shows the email requirement', () => {
  for (const verified of [false, true]) {
    const { html, client } = renderProfile({ userId: 'viewer-streamer', displayName: 'Viewer', email: 'viewer@example.com', emailVerified: verified, dob: null, avatarUrl: null }, false, BecomeStreamerPage)
    const button = html.match(/<button([^>]*)>Become a streamer<\/button>/)?.[1]
    assert.ok(button)
    assert.match(button, /type="button"/)
    assert.match(button, /disabled=""/)
    const checkbox = html.match(/<input[^>]*type="checkbox"[^>]*>/)?.[0]
    assert.ok(checkbox)
    assert.doesNotMatch(checkbox, /checked=""/)
    assert.match(html, /I have read and accept the streamer guidelines/)
    assert.match(html, /Before you register/)
    assert.match(html, /Streamer guidelines/)
    if (verified) {
      assert.doesNotMatch(html, /id="streamer-email-required"/)
    } else {
      assert.match(html, /id="streamer-email-required"/)
    }
    client.clear()
  }
})

test('streamer onboarding has a dedicated dashboard route and is absent from the profile form', () => {
  const signup = renderRoute('/become-streamer')
  assert.match(signup, /aria-current="page"[^>]*href="\/become-streamer"/)
  assert.match(signup, /A channel of your own/)
  assert.match(signup, /Log in to register as a streamer/)
  assert.doesNotMatch(signup, /Search streams|Toggle navigation/)
  const { html, client } = renderProfile({ userId: 'viewer-streamer', emailVerified: true })
  assert.doesNotMatch(html, /streamer-registration-heading|>Become a streamer<\/button>/)
  client.clear()
})

test('become-streamer POST uses session cookies without accepting a user ID payload', async () => {
  const { streamerApi } = await server.ssrLoadModule('/src/api/streamer.api.ts')
  const original = apiClient.defaults.adapter
  const result = { userId: 'viewer-1', streamerId: 'streamer-1', status: 'ACTIVE', roles: ['USER', 'STREAMER'] }
  let request
  apiClient.defaults.adapter = async (config) => {
    request = config
    return { data: { result }, status: 200, statusText: 'OK', headers: {}, config }
  }
  try {
    assert.deepEqual(await streamerApi.becomeStreamer(), result)
    assert.equal(request.url, '/user/api/users/me/become-streamer')
    assert.equal(request.method, 'post')
    assert.equal(request.withCredentials, true)
    assert.equal(request.data, undefined)
  } finally { apiClient.defaults.adapter = original }
})

test('registration API preserves backend constraint errors without retrying the operation', async () => {
  const { streamerApi } = await server.ssrLoadModule('/src/api/streamer.api.ts')
  const original = apiClient.defaults.adapter
  let attempts = 0
  try {
    for (const [code, status] of [[1027, 403], [1030, 409], [1031, 500], [1037, 500], [1044, 503]]) {
      apiClient.defaults.adapter = async (config) => {
        attempts++
        throw new axios.AxiosError('Registration failed', 'ERR_BAD_RESPONSE', config, null, {
          data: { code }, status, statusText: 'Error', headers: {}, config,
        })
      }
      await assert.rejects(streamerApi.becomeStreamer(), (error) => error.response.data.code === code)
    }
    assert.equal(attempts, 5)
  } finally { apiClient.defaults.adapter = original }
})


test('existing STREAMER roles hide the signup button and acceptance checkbox on first render', () => {
  const { html, client } = renderProfile({ userId: 'existing-streamer', displayName: 'Streamer', email: 'streamer@example.com', emailVerified: true, roles: ['USER', 'STREAMER'], dob: null, avatarUrl: null }, false, BecomeStreamerPage)
  assert.match(html, /Your streamer registration is complete/)
  assert.doesNotMatch(html, /<button[^>]*>Become a streamer<\/button>/)
  assert.doesNotMatch(html, /type="checkbox"/)
  client.clear()
})

test('users without the STREAMER role still see the signup action', () => {
  const { html, client } = renderProfile({ userId: 'viewer-role', displayName: 'Viewer', email: 'viewer@example.com', emailVerified: true, roles: ['USER'], dob: null, avatarUrl: null }, false, BecomeStreamerPage)
  assert.match(html, /<button[^>]*>Become a streamer<\/button>/)
  assert.match(html, /type="checkbox"/)
  assert.doesNotMatch(html, /Your streamer registration is complete/)
  client.clear()
})
