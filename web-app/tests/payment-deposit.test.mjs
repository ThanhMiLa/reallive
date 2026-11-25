import assert from 'node:assert/strict'
import { after, afterEach, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { JSDOM } from 'jsdom'
import { createServer } from 'vite'

let server
let apiClient
let paymentApi
let useAuthStore
let AppRoutes
let ProfilePage
let WalletCreditsPage
let VnpayReturnPage
let testingLibrary
let assignedUrls = []

const dom = new JSDOM('<!doctype html><html><body></body></html>', {
  url: 'http://localhost:5173/wallet',
})

const fakeLocation = {
  href: 'http://localhost:5173/wallet',
  origin: 'http://localhost:5173',
  pathname: '/wallet',
  search: '',
  assign(url) {
    assignedUrls.push(String(url))
  },
}

const windowProxy = new Proxy(dom.window, {
  get(target, prop) {
    if (prop === 'location') return fakeLocation
    const value = Reflect.get(target, prop, target)
    return typeof value === 'function' ? value.bind(target) : value
  },
})

function installDomGlobals() {
  globalThis.IS_REACT_ACT_ENVIRONMENT = true
  globalThis.window = windowProxy
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

const samplePackages = [
  { packageCode: 'COIN_100', displayName: 'Starter 100', amountVnd: 10000, coinAmount: 100 },
  { packageCode: 'COIN_500', displayName: 'Popular 500', amountVnd: 50000, coinAmount: 500 },
  { packageCode: 'COIN_1000', displayName: 'Creator 1,000', amountVnd: 100000, coinAmount: 1000 },
  { packageCode: 'COIN_2000', displayName: 'Pro 2,000', amountVnd: 200000, coinAmount: 2000 },
  { packageCode: 'COIN_5000', displayName: 'VIP 5,000', amountVnd: 500000, coinAmount: 5000 },
]

const sampleUser = {
  userId: 'user-wallet-1',
  displayName: 'CoinBuyer',
  email: 'buyer@reallive.dev',
  emailVerified: true,
  roles: ['USER'],
  avatarUrl: null,
  dob: null,
}

before(async () => {
  installDomGlobals()
  testingLibrary = await import('@testing-library/react')
  server = await createServer({ server: { middlewareMode: true, watch: null, ws: false } })
  ;({ apiClient } = await server.ssrLoadModule('/src/api/client.ts'))
  ;({ paymentApi } = await server.ssrLoadModule('/src/api/payment.api.ts'))
  ;({ useAuthStore } = await server.ssrLoadModule('/src/stores/auth.store.ts'))
  ;({ AppRoutes } = await server.ssrLoadModule('/src/App.tsx'))
  ;({ ProfilePage } = await server.ssrLoadModule('/src/pages/profile/ProfilePage.tsx'))
  ;({ WalletCreditsPage } = await server.ssrLoadModule('/src/pages/wallet/WalletCreditsPage.tsx'))
  ;({ VnpayReturnPage } = await server.ssrLoadModule('/src/pages/payment/VnpayReturnPage.tsx'))
})

afterEach(() => {
  testingLibrary?.cleanup()
  assignedUrls = []
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
    await Promise.resolve()
    await Promise.resolve()
  })
}

test('paymentApi calls backend endpoints with credentials and unwraps ApiResponse result', async () => {
  const original = apiClient.defaults.adapter
  const requests = []
  apiClient.defaults.adapter = async (config) => {
    requests.push(config)
    if (config.url === '/payment/api/wallets/me') {
      return {
        data: { status: 200, code: 1000, message: 'OK', result: { walletId: 'w-1', userId: 'u-1', balance: 1500 } },
        status: 200, statusText: 'OK', headers: {}, config,
      }
    }
    if (config.url === '/payment/api/deposits/packages') {
      return {
        data: { status: 200, code: 1000, message: 'OK', result: samplePackages },
        status: 200, statusText: 'OK', headers: {}, config,
      }
    }
    if (config.url === '/payment/api/deposits/vnpay') {
      return {
        data: {
          status: 200,
          code: 1000,
          message: 'OK',
          result: {
            paymentId: 'pay-1',
            orderCode: 1763951000123,
            packageCode: 'COIN_500',
            amountVnd: 50000,
            coinAmount: 500,
            status: 'PENDING',
            expiresAt: '2026-11-24T06:00:00Z',
            paymentUrl: 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=1763951000123',
          },
        },
        status: 200, statusText: 'OK', headers: {}, config,
      }
    }
    if (config.url === '/payment/api/deposits/1763951000123') {
      return {
        data: {
          status: 200,
          code: 1000,
          message: 'OK',
          result: {
            paymentId: 'pay-1',
            orderCode: 1763951000123,
            packageCode: 'COIN_500',
            amountVnd: 50000,
            coinAmount: 500,
            status: 'PAID',
            providerTransactionNo: '14681234',
            providerResponseCode: '00',
            walletBalance: 2000,
            paidAt: '2026-11-24T05:50:00Z',
            createdAt: '2026-11-24T05:45:00Z',
            expiresAt: '2026-11-24T06:00:00Z',
          },
        },
        status: 200, statusText: 'OK', headers: {}, config,
      }
    }
    throw new Error(`Unexpected URL: ${config.url}`)
  }

  try {
    const wallet = await paymentApi.getWalletBalance()
    const packages = await paymentApi.getDepositPackages()
    const deposit = await paymentApi.createVnpayDeposit('COIN_500')
    const status = await paymentApi.getDepositStatus(1763951000123)

    assert.deepEqual(wallet, { walletId: 'w-1', userId: 'u-1', balance: 1500 })
    assert.equal(packages.length, 5)
    assert.equal(deposit.orderCode, 1763951000123)
    assert.equal(status.status, 'PAID')

    assert.equal(requests[0].method, 'get')
    assert.equal(requests[0].url, '/payment/api/wallets/me')
    assert.equal(requests[1].method, 'get')
    assert.equal(requests[1].url, '/payment/api/deposits/packages')
    assert.equal(requests[2].method, 'post')
    assert.equal(requests[2].url, '/payment/api/deposits/vnpay')
    assert.deepEqual(JSON.parse(requests[2].data), { packageCode: 'COIN_500' })
    assert.equal(requests[3].method, 'get')
    assert.equal(requests[3].url, '/payment/api/deposits/1763951000123')
    assert.ok(requests.every((req) => req.withCredentials === true))
  } finally {
    apiClient.defaults.adapter = original
  }
})

test('ProfilePage reads the balance and directs users without a wallet to Buy Coins', () => {
  setAuth(sampleUser)
  const client = makeClient()
  client.setQueryData(['profile', sampleUser.userId], sampleUser)
  client.setQueryData(['wallet', sampleUser.userId], {
    walletId: 'w-1',
    userId: sampleUser.userId,
    balance: 2450,
  })

  try {
    const html = renderToStaticMarkup(
      createElement(
        QueryClientProvider,
        { client },
        createElement(MemoryRouter, null, createElement(ProfilePage)),
      ),
    )
    assert.match(html, /Wallet &amp; Coins/)
    assert.match(html, /2,450/)
    assert.match(html, /Coins/)
    assert.match(html, /href="\/wallet"[^>]*>.*Buy Coins/s)

    const walletQuery = client.getQueryCache().getAll().find((q) => q.queryKey[0] === 'wallet')
    assert.deepEqual(walletQuery.queryKey, ['wallet', sampleUser.userId])
    assert.equal(walletQuery.options.enabled, true)
  } finally {
    client.clear()
  }

  const errorClient = makeClient()
  errorClient.setQueryData(['profile', sampleUser.userId], sampleUser)
  const original = apiClient.defaults.adapter
  const walletMethods = []
  apiClient.defaults.adapter = async (config) => {
    if (config.url === '/payment/api/wallets/me') {
      walletMethods.push(config.method)
      const error = new Error('Not found')
      error.isAxiosError = true
      error.response = { status: 404, data: { code: 1006, message: 'Wallet not found' } }
      throw error
    }
    throw new Error(`Unexpected URL: ${config.url}`)
  }

  return (async () => {
    try {
      testingLibrary.render(
        createElement(
          QueryClientProvider,
          { client: errorClient },
          createElement(MemoryRouter, null, createElement(ProfilePage)),
        ),
      )
      await testingLibrary.waitFor(() => {
        assert.ok(testingLibrary.screen.getByText(/Open Wallet & Coins to create your wallet/))
      })
      assert.deepEqual(walletMethods, ['get'])
      assert.equal(testingLibrary.screen.getByRole('link', { name: 'Buy Coins' }).getAttribute('href'), '/wallet')
    } finally {
      apiClient.defaults.adapter = original
      errorClient.clear()
    }
  })()
})

test('AppRoutes wires /wallet and /payment/vnpay-return inside AccountLayout', () => {
  setAuth(null, false)
  const client = makeClient()
  try {
    const walletHtml = renderToStaticMarkup(
      createElement(
        QueryClientProvider,
        { client },
        createElement(MemoryRouter, { initialEntries: ['/wallet'] }, createElement(AppRoutes)),
      ),
    )
    assert.match(walletHtml, /aria-label="Account navigation"/)
    assert.match(walletHtml, /aria-current="page"[^>]*href="\/wallet"/)
    assert.match(walletHtml, /Log in to view your wallet balance and buy coins/)

    const returnHtml = renderToStaticMarkup(
      createElement(
        QueryClientProvider,
        { client },
        createElement(
          MemoryRouter,
          { initialEntries: ['/payment/vnpay-return?vnp_TxnRef=12345'] },
          createElement(AppRoutes),
        ),
      ),
    )
    assert.match(returnHtml, /aria-label="Account navigation"/)
    assert.match(returnHtml, /Payment Status/)
  } finally {
    client.clear()
  }
})

test('WalletCreditsPage selects package, creates VNPAY deposit, and redirects via window.location.assign without calling paymentUrl', async () => {
  setAuth(sampleUser)
  const client = makeClient()
  const original = apiClient.defaults.adapter
  const requests = []
  const paymentUrl = 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=999000111'
  let finishWalletCreation
  const walletCreation = new Promise((resolve) => { finishWalletCreation = resolve })

  apiClient.defaults.adapter = async (config) => {
    requests.push(config)
    if (config.url === '/payment/api/wallets/me') {
      await walletCreation
      return {
        data: { result: { walletId: 'w-1', userId: sampleUser.userId, balance: 750 } },
        status: 200, statusText: 'OK', headers: {}, config,
      }
    }
    if (config.url === '/payment/api/deposits/packages') {
      return {
        data: { result: samplePackages },
        status: 200, statusText: 'OK', headers: {}, config,
      }
    }
    if (config.url === '/payment/api/deposits/vnpay') {
      return {
        data: {
          result: {
            paymentId: 'pay-999',
            orderCode: 999000111,
            packageCode: 'COIN_1000',
            amountVnd: 100000,
            coinAmount: 1000,
            status: 'PENDING',
            expiresAt: '2026-11-24T06:00:00Z',
            paymentUrl,
          },
        },
        status: 200, statusText: 'OK', headers: {}, config,
      }
    }
    throw new Error(`Unexpected URL: ${config.url}`)
  }

  try {
    testingLibrary.render(
      createElement(
        QueryClientProvider,
        { client },
        createElement(MemoryRouter, { initialEntries: ['/wallet'] }, createElement(WalletCreditsPage)),
      ),
    )

    await testingLibrary.waitFor(() => {
      const walletRequest = requests.find((request) => request.url === '/payment/api/wallets/me')
      assert.equal(walletRequest?.method, 'post')
      assert.equal(walletRequest.data, undefined)
      assert.ok(testingLibrary.screen.getByRole('button', { name: 'Pay with VNPAY' }).disabled)
    })
    finishWalletCreation()

    await testingLibrary.waitFor(() => {
      assert.ok(testingLibrary.screen.getByText('750'))
      assert.equal(testingLibrary.screen.getAllByRole('radio').length, 5)
    })

    const radios = testingLibrary.screen.getAllByRole('radio')
    assert.equal(radios.length, 5)
    assert.equal(radios[0].getAttribute('aria-checked'), 'true')

    await testingLibrary.act(async () => {
      testingLibrary.fireEvent.click(radios[2])
    })

    assert.equal(radios[2].getAttribute('aria-checked'), 'true')
    assert.equal(testingLibrary.screen.getAllByText(/100\.000 VND/).length, 2)
    assert.ok(testingLibrary.screen.getByText('1,000 Coins'))

    const payButton = testingLibrary.screen.getByRole('button', { name: 'Pay with VNPAY' })
    await testingLibrary.act(async () => {
      testingLibrary.fireEvent.click(payButton)
    })

    await testingLibrary.waitFor(() => {
      assert.deepEqual(assignedUrls, [paymentUrl])
    })

    const postReq = requests.find((r) => r.url === '/payment/api/deposits/vnpay')
    assert.ok(postReq)
    assert.equal(postReq.method, 'post')
    assert.deepEqual(JSON.parse(postReq.data), { packageCode: 'COIN_1000' })
    assert.equal(requests.some((r) => String(r.url).includes('sandbox.vnpayment.vn')), false)
    assert.deepEqual(assignedUrls, [paymentUrl])
  } finally {
    finishWalletCreation()
    apiClient.defaults.adapter = original
    client.clear()
  }
})

test('VnpayReturnPage rejects missing vnp_TxnRef and never trusts vnp_ResponseCode=00 on URL', async () => {
  setAuth(sampleUser)
  const client = makeClient()
  const original = apiClient.defaults.adapter
  const requests = []

  apiClient.defaults.adapter = async (config) => {
    requests.push(config)
    return {
      data: {
        result: {
          paymentId: 'pay-fail',
          orderCode: 777111,
          packageCode: 'COIN_100',
          amountVnd: 10000,
          coinAmount: 100,
          status: 'FAILED',
          providerTransactionNo: null,
          providerResponseCode: '51',
          walletBalance: 500,
          paidAt: null,
          createdAt: '2026-11-24T05:00:00Z',
          expiresAt: '2026-11-24T05:15:00Z',
        },
      },
      status: 200, statusText: 'OK', headers: {}, config,
    }
  }

  try {
    // Missing vnp_TxnRef does not call backend
    const missingView = testingLibrary.render(
      createElement(
        QueryClientProvider,
        { client },
        createElement(MemoryRouter, { initialEntries: ['/payment/vnpay-return'] }, createElement(VnpayReturnPage)),
      ),
    )
    await flushEffects()
    assert.ok(testingLibrary.screen.getByText(/Missing payment reference/))
    assert.equal(requests.length, 0)
    missingView.unmount()

    // Even with vnp_ResponseCode=00 in query string, backend FAILED status controls UI
    testingLibrary.render(
      createElement(
        QueryClientProvider,
        { client },
        createElement(
          MemoryRouter,
          { initialEntries: ['/payment/vnpay-return?vnp_TxnRef=777111&vnp_ResponseCode=00'] },
          createElement(VnpayReturnPage),
        ),
      ),
    )
    await flushEffects()
    assert.equal(requests.length, 1)
    assert.equal(requests[0].url, '/payment/api/deposits/777111')
    assert.ok(testingLibrary.screen.getByText('Payment failed'))
    assert.ok(testingLibrary.screen.getByText(/Response code: 51/))
  } finally {
    apiClient.defaults.adapter = original
    client.clear()
  }
})

test('VnpayReturnPage polls every 1s after response settles, invalidates wallet cache on PAID, and handles CANCELLED', async () => {
  setAuth(sampleUser)
  const client = makeClient()
  client.setQueryData(['wallet', sampleUser.userId], {
    walletId: 'w-1',
    userId: sampleUser.userId,
    balance: 500,
  })
  const invalidatedKeys = []
  const origInvalidate = client.invalidateQueries.bind(client)
  client.invalidateQueries = async (filters, ...rest) => {
    invalidatedKeys.push(filters?.queryKey)
    return origInvalidate(filters, ...rest)
  }

  const original = apiClient.defaults.adapter
  const originalSetTimeout = globalThis.setTimeout
  const originalClearTimeout = globalThis.clearTimeout
  const scheduled = []

  globalThis.setTimeout = (fn, delay) => {
    const id = scheduled.length + 1
    scheduled.push({ id, fn, delay, cleared: false })
    return id
  }
  globalThis.clearTimeout = (id) => {
    const entry = scheduled.find((item) => item.id === id)
    if (entry) entry.cleared = true
  }

  let attempts = 0
  apiClient.defaults.adapter = async (config) => {
    attempts++
    const status = attempts < 3 ? 'PENDING' : 'PAID'
    return {
      data: {
        result: {
          paymentId: 'pay-poll',
          orderCode: 888222,
          packageCode: 'COIN_500',
          amountVnd: 50000,
          coinAmount: 500,
          status,
          providerTransactionNo: status === 'PAID' ? '14689999' : null,
          providerResponseCode: status === 'PAID' ? '00' : null,
          walletBalance: status === 'PAID' ? 1000 : 500,
          paidAt: status === 'PAID' ? '2026-11-24T05:10:00Z' : null,
          createdAt: '2026-11-24T05:09:00Z',
          expiresAt: '2026-11-24T05:24:00Z',
        },
      },
      status: 200, statusText: 'OK', headers: {}, config,
    }
  }

  try {
    const view = testingLibrary.render(
      createElement(
        QueryClientProvider,
        { client },
        createElement(
          MemoryRouter,
          { initialEntries: ['/payment/vnpay-return?vnp_TxnRef=888222'] },
          createElement(VnpayReturnPage),
        ),
      ),
    )

    await flushEffects()
    assert.equal(attempts, 1)
    assert.equal(scheduled.length, 1)
    assert.equal(scheduled[0].delay, 1000)
    assert.ok(testingLibrary.screen.getByText('Confirming your payment with VNPAY...'))

    // Fire 1st poll timer -> still PENDING -> schedules 2nd timer
    await testingLibrary.act(async () => {
      scheduled[0].fn()
      await Promise.resolve()
      await Promise.resolve()
    })
    assert.equal(attempts, 2)
    assert.equal(scheduled.length, 2)
    assert.equal(scheduled[1].delay, 1000)

    // Fire 2nd poll timer -> PAID -> stops polling & invalidates wallet cache
    await testingLibrary.act(async () => {
      scheduled[1].fn()
      await Promise.resolve()
      await Promise.resolve()
    })
    assert.equal(attempts, 3)
    assert.equal(scheduled.length, 2)
    assert.ok(testingLibrary.screen.getByText('Payment successful'))
    assert.ok(testingLibrary.screen.getByText(/\+500 Coins/))
    assert.ok(testingLibrary.screen.getByText(/1,000 Coins/))
    assert.deepEqual(invalidatedKeys, [['wallet', sampleUser.userId]])
    view.unmount()

    // CANCELLED state
    apiClient.defaults.adapter = async (config) => ({
      data: {
        result: {
          paymentId: 'pay-cancel',
          orderCode: 888333,
          packageCode: 'COIN_100',
          amountVnd: 10000,
          coinAmount: 100,
          status: 'CANCELLED',
          providerTransactionNo: null,
          providerResponseCode: '24',
          walletBalance: 500,
          paidAt: null,
          createdAt: '2026-11-24T05:09:00Z',
          expiresAt: '2026-11-24T05:24:00Z',
        },
      },
      status: 200, statusText: 'OK', headers: {}, config,
    })

    testingLibrary.render(
      createElement(
        QueryClientProvider,
        { client },
        createElement(
          MemoryRouter,
          { initialEntries: ['/payment/vnpay-return?vnp_TxnRef=888333'] },
          createElement(VnpayReturnPage),
        ),
      ),
    )
    await flushEffects()
    assert.ok(testingLibrary.screen.getByText('Payment cancelled'))
  } finally {
    globalThis.setTimeout = originalSetTimeout
    globalThis.clearTimeout = originalClearTimeout
    apiClient.defaults.adapter = original
    client.clear()
  }
})

test('VnpayReturnPage stops polling after 10s timeout, supports manual Check again, and cleans up timer on unmount', async () => {
  setAuth(sampleUser)
  const client = makeClient()
  const original = apiClient.defaults.adapter
  const originalSetTimeout = globalThis.setTimeout
  const originalClearTimeout = globalThis.clearTimeout
  const originalDateNow = Date.now

  let now = 1_000_000
  Date.now = () => now

  const scheduled = []
  globalThis.setTimeout = (fn, delay) => {
    const id = scheduled.length + 1
    scheduled.push({ id, fn, delay, cleared: false })
    return id
  }
  globalThis.clearTimeout = (id) => {
    const entry = scheduled.find((item) => item.id === id)
    if (entry) entry.cleared = true
  }

  let attempts = 0
  let resolveFinalAsPaid = false
  apiClient.defaults.adapter = async (config) => {
    attempts++
    const status = resolveFinalAsPaid ? 'PAID' : 'PENDING'
    return {
      data: {
        result: {
          paymentId: 'pay-timeout',
          orderCode: 888444,
          packageCode: 'COIN_500',
          amountVnd: 50000,
          coinAmount: 500,
          status,
          providerTransactionNo: status === 'PAID' ? '14680001' : null,
          providerResponseCode: status === 'PAID' ? '00' : null,
          walletBalance: status === 'PAID' ? 1500 : 1000,
          paidAt: status === 'PAID' ? '2026-11-24T05:20:00Z' : null,
          createdAt: '2026-11-24T05:10:00Z',
          expiresAt: '2026-11-24T05:25:00Z',
        },
      },
      status: 200, statusText: 'OK', headers: {}, config,
    }
  }

  try {
    const view = testingLibrary.render(
      createElement(
        QueryClientProvider,
        { client },
        createElement(
          MemoryRouter,
          { initialEntries: ['/payment/vnpay-return?vnp_TxnRef=888444'] },
          createElement(VnpayReturnPage),
        ),
      ),
    )

    await flushEffects()
    assert.equal(attempts, 1)
    assert.equal(scheduled.length, 1)

    // Advance past 10s and fire scheduled timer -> reaches timeout UI without scheduling more
    now += 10_000
    await testingLibrary.act(async () => {
      scheduled[0].fn()
      await Promise.resolve()
      await Promise.resolve()
    })
    assert.equal(attempts, 2)
    assert.equal(scheduled.length, 1)
    assert.ok(testingLibrary.screen.getByText('Payment verification in progress'))
    assert.ok(testingLibrary.screen.getByText(/credited automatically once VNPAY confirms/))

    // Manual Check again succeeds
    resolveFinalAsPaid = true
    await testingLibrary.act(async () => {
      testingLibrary.fireEvent.click(testingLibrary.screen.getByRole('button', { name: 'Check again' }))
      await Promise.resolve()
      await Promise.resolve()
    })
    assert.equal(attempts, 3)
    assert.ok(testingLibrary.screen.getByText('Payment successful'))
    view.unmount()

    // Unmount cleanup cancels pending timer
    resolveFinalAsPaid = false
    const pendingView = testingLibrary.render(
      createElement(
        QueryClientProvider,
        { client },
        createElement(
          MemoryRouter,
          { initialEntries: ['/payment/vnpay-return?vnp_TxnRef=888555'] },
          createElement(VnpayReturnPage),
        ),
      ),
    )
    await flushEffects()
    const lastTimer = scheduled[scheduled.length - 1]
    assert.equal(lastTimer.cleared, false)
    pendingView.unmount()
    assert.equal(lastTimer.cleared, true)
  } finally {
    Date.now = originalDateNow
    globalThis.setTimeout = originalSetTimeout
    globalThis.clearTimeout = originalClearTimeout
    apiClient.defaults.adapter = original
    client.clear()
  }
})

test('VnpayReturnPage displays 404 order not found error and Check again control', async () => {
  setAuth(sampleUser)
  const client = makeClient()
  const original = apiClient.defaults.adapter
  apiClient.defaults.adapter = async () => {
    const error = new Error('Not found')
    error.isAxiosError = true
    error.response = { status: 404, data: { code: 1008, message: 'Deposit order not found' } }
    throw error
  }

  try {
    testingLibrary.render(
      createElement(
        QueryClientProvider,
        { client },
        createElement(
          MemoryRouter,
          { initialEntries: ['/payment/vnpay-return?vnp_TxnRef=999888'] },
          createElement(VnpayReturnPage),
        ),
      ),
    )

    await testingLibrary.waitFor(() => {
      assert.ok(testingLibrary.screen.getByText(/Deposit order not found or you do not have permission/))
    })
    assert.ok(testingLibrary.screen.getByRole('button', { name: 'Check again' }))
    assert.ok(testingLibrary.screen.getByRole('link', { name: 'Back to Wallet' }))
  } finally {
    apiClient.defaults.adapter = original
    client.clear()
  }
})
