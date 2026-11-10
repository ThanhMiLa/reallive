import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { createServer } from 'vite'

let server, AccountLayout
before(async () => {
  server = await createServer({ server: { middlewareMode: true, watch: null, ws: false } })
  ;({ AccountLayout } = await server.ssrLoadModule('/src/components/layout/AccountLayout.tsx'))
})
after(async () => { await server?.close() })

function renderWithStorage(getItem) {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem } })
  try {
    return renderToStaticMarkup(createElement(MemoryRouter, { initialEntries: ['/profile'] }, createElement(AccountLayout)))
  } finally {
    if (original) Object.defineProperty(globalThis, 'localStorage', original)
    else delete globalThis.localStorage
  }
}

test('account sidebar defaults to expanded with accessible navigation and collapse control', () => {
  const html = renderWithStorage(() => null)
  assert.match(html, /md:w-64/)
  assert.match(html, /aria-label="Collapse sidebar"/)
  assert.match(html, /aria-expanded="true"/)
  for (const label of ['Profile', 'Category Interests', 'Become a streamer', 'Back to home']) {
    assert.ok(html.includes(`aria-label="${label}"`))
  }
})

test('account sidebar restores its own collapsed preference and retains mobile labels', () => {
  const html = renderWithStorage((key) => {
    assert.equal(key, 'reallive_account_sidebar_collapsed')
    return 'true'
  })
  assert.match(html, /md:w-20/)
  assert.match(html, /aria-label="Expand sidebar"/)
  assert.match(html, /aria-expanded="false"/)
  assert.match(html, /RealLive%20RL%20logo%20with%20transparent%20background/)
  assert.match(html, /md:opacity-100/)
  assert.match(html, /md:max-w-0/)
  assert.match(html, /duration-300/)
})

test('account sidebar remains usable when browser storage is blocked', () => {
  const html = renderWithStorage(() => { throw new Error('Storage blocked') })
  assert.match(html, /md:w-64/)
  assert.match(html, /aria-label="Account navigation"/)
})
