import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createServer } from 'vite'

let server
let BrowseChannelsTab
let useAuthStore

before(async () => {
  server = await createServer({
    server: { middlewareMode: true, watch: null, ws: false },
  })
  const componentModule = await server.ssrLoadModule(
    '/src/pages/browse/tabs/BrowseChannelsTab.tsx',
  )
  const authModule = await server.ssrLoadModule('/src/stores/auth.store.ts')
  BrowseChannelsTab = componentModule.BrowseChannelsTab
  useAuthStore = authModule.useAuthStore
})

after(async () => {
  await server?.close()
})

function renderSession({ userId, isCheckingAuth = false } = {}) {
  useAuthStore.setState({
    user: userId ? { userId } : null,
    isAuthenticated: Boolean(userId),
    isCheckingAuth,
  })
  // SSR reads Zustand's initial snapshot rather than its current state.
  Object.assign(useAuthStore.getInitialState(), useAuthStore.getState())
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  })
  const render = () => renderToStaticMarkup(
    createElement(QueryClientProvider, { client }, createElement(BrowseChannelsTab)),
  )
  render()
  const queries = client.getQueryCache().getAll()
  const following = queries.find(({ queryKey }) => queryKey[1] === 'following')
  const recommended = queries.find(({ queryKey }) => queryKey[1] === 'unfollowed')
  return { client, following, recommended, render }
}

function seedChannel(client, query, name) {
  client.setQueryData(query.queryKey, {
    content: [{ id: name, name, numberOfFollower: 12 }],
    page: 0,
    size: 20,
    totalPages: 1,
    totalElements: 1,
  })
}

test('guests see recommended channels without the following section', () => {
  const { client, following, recommended, render } = renderSession()
  assert.equal(recommended.options.enabled, true)
  assert.equal(following.options.enabled, false)
  seedChannel(client, recommended, 'Public creator')
  const html = render()
  assert.match(html, /Public creator/)
  assert.doesNotMatch(html, />Following</)
  assert.doesNotMatch(html, /Sign in to view followed channels/)
  assert.match(html, /Search channels/)
  assert.doesNotMatch(html, /following-sort-select/)
  client.clear()
})

test('authenticated sessions enable both lists with separate user cache keys', () => {
  const { client, following, recommended, render } = renderSession({ userId: 'viewer-1' })
  assert.equal(following.options.enabled, true)
  assert.equal(recommended.options.enabled, true)
  assert.equal(recommended.queryKey[2], 'viewer-1')
  seedChannel(client, following, 'Followed creator')
  seedChannel(client, recommended, 'Suggested creator')
  const html = render()
  assert.match(html, /Followed creator/)
  assert.match(html, /Suggested creator/)
  assert.match(html, /following-sort-select/)
  assert.doesNotMatch(html, /Sign in to view followed channels/)
  client.clear()
})

test('lists wait for the initial session check', () => {
  const { client, following, recommended, render } = renderSession({ isCheckingAuth: true })
  assert.equal(following.options.enabled, false)
  assert.equal(recommended.options.enabled, false)
  const html = render()
  assert.match(html, /animate-pulse/)
  assert.doesNotMatch(html, />Following</)
  client.clear()
})

test('pagination placeholders are preserved only for the same viewer', () => {
  for (const userId of [undefined, 'viewer-1']) {
    const { client, following, recommended } = renderSession({ userId })
    const previousData = { content: [{ name: 'Previous creator' }] }
    for (const query of [following, recommended]) {
      const placeholder = query.options.placeholderData
      assert.equal(placeholder(previousData, query), previousData)
      const otherViewer = { queryKey: [...query.queryKey] }
      otherViewer.queryKey[2] = userId ? undefined : 'viewer-1'
      assert.equal(placeholder(previousData, otherViewer), undefined)
    }
    client.clear()
  }
})
