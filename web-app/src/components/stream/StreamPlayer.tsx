import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import Hls, { FetchLoader } from 'hls.js'
import { isAxiosError } from 'axios'
import { streamApi } from '@/api/stream.api'
import type { StreamMetadata } from '@/types/stream.types'

interface Props {
  sessionId: string
  refreshMetadata: () => Promise<StreamMetadata | null>
}

const mediaBase = import.meta.env.VITE_STREAM_HLS_BASE_URL || 'http://localhost:8898'

export function StreamPlayer({ sessionId, refreshMetadata }: Props) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const refreshRef = useRef(refreshMetadata)
  useLayoutEffect(() => {
    refreshRef.current = refreshMetadata
  }, [refreshMetadata])
  const [attempt, setAttempt] = useState(0)
  const [message, setMessage] = useState('Opening preview…')
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    const abort = new AbortController()
    let alive = true
    let hls: Hls | undefined
    let renewed = false
    let decodeRecovered = false

    const fail = (text: string) => {
      if (alive) {
        setMessage(text)
        setFailed(true)
      }
    }

    const open = async (checkStatus: boolean) => {
      try {
        if (!Hls.isSupported()) {
          fail('Preview is not supported in this browser. Try Chrome or Edge on desktop.')
          return
        }
        if (checkStatus) {
          const latest = await refreshRef.current()
          if (!alive) return
          if (!latest) {
            fail('Could not refresh stream status. Check your connection and try again.')
            return
          }
          if (latest.status !== 'LIVE') return
        }
        const access = await streamApi.access(sessionId, abort.signal)
        if (!alive || access.sessionId !== sessionId) return
        const base = new URL(mediaBase)
        const prefix = `/live/${encodeURIComponent(sessionId)}/`
        const manifest = new URL(access.playbackUrl)
        if (
          manifest.origin !== base.origin ||
          manifest.pathname !== `${prefix}index.m3u8` ||
          manifest.username ||
          manifest.password
        ) {
          fail('The preview URL does not match the configured media server.')
          return
        }
        // MediaMTX's HLS handshake redirects to cookieCheck=1. Requests omit cookies,
        // so start at that URL and let MediaMTX use its query-based HLS session.
        manifest.searchParams.set('cookieCheck', '1')
        hls?.destroy()
        const retry = {
          maxNumRetry: 2,
          retryDelayMs: 1000,
          maxRetryDelayMs: 4000,
          backoff: 'exponential' as const,
        }
        const policy = {
          maxTimeToFirstByteMs: 10000,
          maxLoadTimeMs: 20000,
          timeoutRetry: retry,
          errorRetry: retry,
        }
        const instance = new Hls({
          loader: FetchLoader,
          lowLatencyMode: false,
          manifestLoadPolicy: { default: policy },
          playlistLoadPolicy: { default: policy },
          fragLoadPolicy: { default: policy },
          fetchSetup: async (context, init) => {
            const target = new URL(context.url, manifest)
            if (
              target.origin !== base.origin ||
              !target.pathname.startsWith(prefix) ||
              target.username ||
              target.password
            ) {
              throw new Error('Media request outside the stream path')
            }
            const headers = new Headers(init.headers)
            headers.set('Authorization', `Bearer ${access.readToken}`)
            return new Request(target, { ...init, headers, credentials: 'omit', redirect: 'error' })
          },
        })
        hls = instance
        instance.on(Hls.Events.MANIFEST_PARSED, () => {
          if (!alive) return
          setFailed(false)
          setMessage('')
          void video.play().catch(() => {
            if (alive) setMessage('Press play to start the preview.')
          })
        })
        instance.on(Hls.Events.ERROR, (_event, data) => {
          if (!alive || !data.fatal) return
          const status = data.response?.code
          if ((status === 401 || status === 403) && !renewed) {
            renewed = true
            instance.destroy()
            void open(true)
          } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !decodeRecovered) {
            decodeRecovered = true
            instance.recoverMediaError()
          } else {
            instance.destroy()
            fail(
              data.type === Hls.ErrorTypes.MEDIA_ERROR
                ? 'The preview cannot decode this video.'
                : 'Preview unavailable. Check your connection and try again.',
            )
          }
        })
        instance.loadSource(manifest.href)
        instance.attachMedia(video)
      } catch (error) {
        if (!alive) return
        if (isAxiosError(error) && error.response?.status === 409) {
          await refreshRef.current().catch(() => null)
          fail('Stream status changed. Waiting for an update.')
        } else {
          fail('Could not open the preview. Check your connection and try again.')
        }
      }
    }
    void open(attempt > 0)
    return () => {
      alive = false
      abort.abort()
      hls?.destroy()
      video.removeAttribute('src')
      video.load()
    }
  }, [sessionId, attempt])

  return (
    <div className="relative aspect-video bg-black">
      <video
        ref={videoRef}
        className="h-full w-full"
        controls
        muted
        playsInline
        aria-label="Stream preview"
      />
      {message && (
        <div
          role="status"
          className="absolute inset-x-4 bottom-16 rounded-lg bg-black/85 p-3 text-center text-sm text-gray-200"
        >
          {message}
          {failed && (
            <button
              type="button"
              className="ml-3 text-[#ff5366] underline focus-visible:outline-2"
              onClick={() => {
                setFailed(false)
                setMessage('Opening preview…')
                setAttempt((value) => value + 1)
              }}
            >
              Retry preview
            </button>
          )}
        </div>
      )}
    </div>
  )
}
