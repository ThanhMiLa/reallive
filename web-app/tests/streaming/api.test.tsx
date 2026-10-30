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
    requestId: '00000000-0000-4000-8000-000000000002',
  }
  const controller = new AbortController()
  await streamApi.current(controller.signal)
  await streamApi.create(payload)
  await streamApi.update(id, { title: 'Changed' })
  await streamApi.credentials(id)
  await streamApi.metadata(id)
  await streamApi.access(id)
  await streamApi.end(id)
  expect(requests.map((request: AxiosRequestConfig) => [request.method, request.url])).toEqual([
    ['get', '/stream/api/streams/me/current'],
    ['post', '/stream/api/streams'],
    ['patch', `/stream/api/streams/${id}`],
    ['post', `/stream/api/streams/${id}/publish-credentials`],
    ['get', `/stream/api/streams/${id}`],
    ['post', `/stream/api/streams/${id}/playback-access`],
    ['post', `/stream/api/streams/${id}/end`],
  ])
  expect(requests.every((request) => request.withCredentials)).toBe(true)
  expect(requests[0].signal).toBe(controller.signal)
  expect(JSON.parse(requests[1].data)).toEqual(payload)
  expect(JSON.parse(requests[2].data)).toEqual({ title: 'Changed' })
  expect(requests[5].data).toBeUndefined()
})
