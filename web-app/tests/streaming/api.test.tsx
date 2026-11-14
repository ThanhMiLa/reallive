import { afterEach, expect, it } from 'vitest'
import { apiClient } from '@/api/client'
import { streamApi } from '@/api/stream.api'
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'

const originalAdapter = apiClient.defaults.adapter

afterEach(() => {
  apiClient.defaults.adapter = originalAdapter
})

it('binds the owner and playback API contract through the account client', async () => {
  const requests: InternalAxiosRequestConfig[] = []
  apiClient.defaults.adapter = async (config): Promise<AxiosResponse> => {
    requests.push(config)
    return { data: { result: null }, status: 200, statusText: 'OK', headers: {}, config }
  }
  const id = '462c912c-ee66-4477-9852-bed13d377297'
  const payload = {
    title: 'Morning',
    categoryId: '00000000-0000-4000-8000-000000000001',
  }
  const controller = new AbortController()
  await streamApi.setup(controller.signal)
  await streamApi.saveSetup(payload, controller.signal)
  await streamApi.issueKey(controller.signal)
  await streamApi.current(controller.signal)
  await streamApi.metadata(id)
  await streamApi.details(id)
  await streamApi.access(id)
  expect(requests.map((request: AxiosRequestConfig) => [request.method, request.url])).toEqual([
    ['get', '/stream/api/streams/me/setup'],
    ['put', '/stream/api/streams/me/setup'],
    ['post', '/stream/api/streams/me/stream-key'],
    ['get', '/stream/api/streams/me/current'],
    ['get', `/stream/api/streams/${id}`],
    ['get', `/stream/api/streams/${id}/details`],
    ['post', `/stream/api/streams/${id}/playback-access`],
  ])
  expect(requests.every((request) => request.withCredentials)).toBe(true)
  expect(requests[0].signal).toBe(controller.signal)
  expect(JSON.parse(requests[1].data)).toEqual(payload)
  expect(requests[2].data).toBeUndefined()
  expect(requests[6].data).toBeUndefined()
})

it('calls GET /stream/api/streams with pagination, optional categoryId, and no sort parameter', async () => {
  const requests: InternalAxiosRequestConfig[] = []
  const pageResult = {
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
  }
  apiClient.defaults.adapter = async (config): Promise<AxiosResponse> => {
    requests.push(config)
    return {
      data: { result: pageResult },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }
  }

  const controller = new AbortController()
  const categoryId = '00000000-0000-4000-8000-000000000010'

  const res1 = await streamApi.list(undefined, controller.signal)
  const res2 = await streamApi.list({ page: 2, size: 40, categoryId: '   ' })
  const res3 = await streamApi.list({ page: 0, size: 20, categoryId })
  const res4 = await streamApi.list({
    page: 0,
    size: 20,
    categoryIds: [categoryId, '00000000-0000-4000-8000-000000000020'],
  })

  expect(res1).toEqual(pageResult)
  expect(res2).toEqual(pageResult)
  expect(res3).toEqual(pageResult)
  expect(res4).toEqual(pageResult)

  expect(requests).toHaveLength(4)
  expect(requests[0].method).toBe('get')
  expect(requests[0].url).toBe('/stream/api/streams')
  expect(requests[0].params).toEqual({ page: 0, size: 20 })
  expect(requests[0].params).not.toHaveProperty('categoryId')
  expect(requests[0].params).not.toHaveProperty('sort')
  expect(requests[0].signal).toBe(controller.signal)
  expect(requests[0].withCredentials).toBe(true)

  expect(requests[1].params).toEqual({ page: 2, size: 40 })
  expect(requests[1].params).not.toHaveProperty('categoryId')

  expect(requests[2].params).toEqual({ page: 0, size: 20, categoryId })
  expect(requests[2].params).not.toHaveProperty('sort')

  expect(requests[3].params).toEqual({
    page: 0,
    size: 20,
    categoryIds: [categoryId, '00000000-0000-4000-8000-000000000020'],
  })
  expect(requests[3].params).not.toHaveProperty('sort')
})

