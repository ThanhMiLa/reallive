import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createServer } from 'vite'

let server, Page, useAuthStore, api, channelApi, apiClient, schema
const ids = Array.from({ length: 25 }, (_, index) => `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`)
const categories = ids.map((id, index) => ({ id, name: `Category ${index + 1}`, slug: `category-${index + 1}`, thumbnailUrl: null }))

before(async () => {
  server = await createServer({ server: { middlewareMode: true, watch: null, ws: false } })
  ;({ CategoryPreferencesPage: Page } = await server.ssrLoadModule('/src/pages/category-preferences/CategoryPreferencesPage.tsx'))
  ;({ useAuthStore } = await server.ssrLoadModule('/src/stores/auth.store.ts'))
  ;({ categoryPreferencesApi: api } = await server.ssrLoadModule('/src/api/category-preferences.api.ts'))
  ;({ channelApi } = await server.ssrLoadModule('/src/api/channel.api.ts'))
  ;({ apiClient } = await server.ssrLoadModule('/src/api/client.ts'))
  ;({ categoryPreferencesSchema: schema } = await server.ssrLoadModule('/src/schemas/category-preferences.schema.ts'))
})
after(async () => { await server?.close() })

function render({ userId = 'viewer', checking = false, list = categories, saved = [], seed = true } = {}) {
  useAuthStore.setState({ user: userId ? { userId } : null, isAuthenticated: Boolean(userId), isCheckingAuth: checking })
  Object.assign(useAuthStore.getInitialState(), useAuthStore.getState())
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  if (seed) {
    client.setQueryData(['categories'], list)
    if (userId) client.setQueryData(['category-preferences', userId], { categoryIds: saved })
  }
  const html = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(Page)))
  const queries = client.getQueryCache().getAll()
  client.clear()
  return { html, queries }
}

test('backend selection constraints require 1 to 20 valid category IDs', () => {
  assert.equal(schema.safeParse({ categoryIds: [] }).success, false)
  assert.equal(schema.safeParse({ categoryIds: ids.slice(0, 20) }).success, true)
  assert.equal(schema.safeParse({ categoryIds: ids.slice(0, 21) }).success, false)
  assert.equal(schema.safeParse({ categoryIds: ['invalid'] }).success, false)
})

test('saved category IDs are preselected in the complete catalog', () => {
  const { html } = render({ saved: [ids[0], ids[2]] })
  const checkboxes = html.match(/<input[^>]*type="checkbox"[^>]*>/g)
  assert.equal(checkboxes.length, 25)
  assert.match(checkboxes[0], /checked=""/)
  assert.doesNotMatch(checkboxes[1], /checked=""/)
  assert.match(checkboxes[2], /checked=""/)
  assert.match(html, /2 \/ 20 selected/)
})

test('20 selected categories disable additional choices while allowing deselection', () => {
  const { html } = render({ saved: ids.slice(0, 20) })
  const checkboxes = html.match(/<input[^>]*type="checkbox"[^>]*>/g)
  assert.equal(checkboxes.filter((input) => input.includes('checked=""')).length, 20)
  assert.equal(checkboxes.filter((input) => input.includes('disabled=""')).length, 5)
  assert.doesNotMatch(checkboxes[0], /disabled=""/)
})

test('unavailable saved categories are excluded and explained', () => {
  const { html } = render({ saved: [ids[0], '00000000-0000-4000-8000-999999999999'] })
  assert.match(html, /Some saved categories are no longer available/)
  assert.match(html, /1 \/ 20 selected/)
  const inputs = html.match(/<input[^>]*type="checkbox"[^>]*>/g)
  assert.equal(inputs.filter((input) => input.includes('checked=""')).length, 1)
})

test('private preferences wait for authentication and use a viewer-specific cache', () => {
  for (const options of [{ userId: null }, { checking: true }]) {
    const { html, queries } = render(options)
    assert.equal(queries.find((query) => query.queryKey[0] === 'category-preferences').options.enabled, false)
    assert.doesNotMatch(html, /type="checkbox"/)
  }
  const { queries } = render({ userId: 'another-viewer' })
  assert.deepEqual(queries.find((query) => query.queryKey[0] === 'category-preferences').queryKey, ['category-preferences', 'another-viewer'])
})

test('loading and empty catalogs provide useful feedback', () => {
  assert.match(render({ seed: false }).html, /Loading your categories/)
  const { html } = render({ list: [] })
  assert.match(html, /No categories are available/)
  assert.doesNotMatch(html, /Save changes/)
})

test('catalog GET and preferences GET/PUT follow backend contracts and deduplicate selected IDs', async () => {
  const original = apiClient.defaults.adapter
  const requests = []
  apiClient.defaults.adapter = async (config) => {
    requests.push(config)
    return {
      data: { result: config.url.endsWith('/categories') ? categories : { categoryIds: [ids[0]] } },
      status: 200, statusText: 'OK', headers: {}, config,
    }
  }
  try {
    assert.deepEqual(await channelApi.getCategories(), categories)
    assert.deepEqual(await api.get(), { categoryIds: [ids[0]] })
    assert.deepEqual(await api.update([ids[0], ids[0]]), { categoryIds: [ids[0]] })
    assert.equal(requests[0].url, '/channel/api/categories')
    assert.equal(requests[1].url, '/user/api/users/me/category-preferences')
    assert.equal(requests[1].method, 'get')
    assert.equal(requests[2].url, requests[1].url)
    assert.equal(requests[2].method, 'put')
    assert.deepEqual(JSON.parse(requests[2].data), { categoryIds: [ids[0]] })
    assert.ok(requests.every((request) => request.withCredentials))
    await assert.rejects(api.update([]))
    await assert.rejects(api.update(ids))
    assert.equal(requests.length, 3)
  } finally { apiClient.defaults.adapter = original }
})
