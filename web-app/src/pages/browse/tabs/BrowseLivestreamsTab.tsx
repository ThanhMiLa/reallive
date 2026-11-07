import { useMemo, useState } from 'react'
import { isAxiosError } from 'axios'
import { useQuery } from '@tanstack/react-query'
import {
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  Radio,
  RefreshCw,
} from 'lucide-react'
import { streamApi } from '@/api/stream.api'
import { StreamCard } from '@/components/stream/StreamCard'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse } from '@/types/auth.types'

const PAGE_SIZE = 20

function parseStreamError(error: unknown): {
  title: string
  description: string
} {
  if (isAxiosError<ApiResponse<unknown>>(error)) {
    const status = error.response?.status
    const serverMessage = error.response?.data?.message

    if (status === 401) {
      return {
        title: 'Authentication Required',
        description: serverMessage || 'Please sign in to view live streams.',
      }
    }
    if (serverMessage) {
      return {
        title: 'Failed to load live streams',
        description: serverMessage,
      }
    }
  }
  return {
    title: 'Failed to load live streams',
    description: 'Could not fetch live streams from server. Please try again.',
  }
}

export function BrowseLivestreamsTab() {
  const { user, isAuthenticated } = useAuthStore()
  const authScopeKey = isAuthenticated
    ? (user?.userId ?? 'authenticated')
    : 'guest'
  const [page, setPage] = useState(0)

  // Stream query
  const {
    data: streamsPage,
    isLoading,
    isError,
    error,
    refetch,
    isFetching,
  } = useQuery({
    queryKey: ['streams', 'live', authScopeKey, { page, size: PAGE_SIZE }],
    queryFn: ({ signal }) => streamApi.list({ page, size: PAGE_SIZE }, signal),
  })

  // Clamp page if totalPages changes
  if (streamsPage) {
    if (streamsPage.totalPages > 0 && page >= streamsPage.totalPages) {
      setPage(streamsPage.totalPages - 1)
    } else if (streamsPage.totalPages === 0 && page !== 0) {
      setPage(0)
    }
  }

  const streams = useMemo(
    () => streamsPage?.content ?? [],
    [streamsPage?.content],
  )
  const totalElements = streamsPage?.totalElements ?? 0
  const totalPages = streamsPage?.totalPages ?? 0
  const errorDetails = isError ? parseStreamError(error) : null

  return (
    <div className="pb-12 space-y-6">
      {/* Status indicator if refreshing */}
      {isFetching && !isLoading && (
        <div className="flex justify-end">
          <span title="Updating...">
            <RefreshCw className="w-3.5 h-3.5 animate-spin text-gray-400" />
          </span>
        </div>
      )}

      {/* Content */}
      {isLoading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-4 sm:gap-5">
          {Array.from({ length: 10 }).map((_, idx) => (
            <div key={idx} className="flex flex-col animate-pulse">
              <div className="w-full aspect-video rounded-xl bg-[#101014] border border-[#1f1f26]" />
              <div className="flex items-start gap-3 mt-3">
                <div className="w-10 h-10 rounded-full bg-[#14141a] flex-shrink-0" />
                <div className="flex-1 space-y-2 py-1">
                  <div className="h-3.5 w-3/4 bg-[#14141a] rounded" />
                  <div className="h-3 w-full bg-[#14141a] rounded" />
                  <div className="h-3 w-1/2 bg-[#14141a] rounded" />
                </div>
              </div>
            </div>
          ))}
        </div>
      ) : isError ? (
        <div
          role="alert"
          className="flex flex-col items-center justify-center py-16 px-4 text-center rounded-2xl border border-[#1f1f26] bg-[#08080a]"
        >
          <AlertCircle className="w-10 h-10 text-rose-500 mb-3" />
          <h3 className="text-base font-semibold text-gray-200 mb-1">
            {errorDetails?.title || 'Failed to load live streams'}
          </h3>
          <p className="text-xs text-gray-400 mb-4 max-w-sm">
            {errorDetails?.description}
          </p>
          <button
            type="button"
            onClick={() => void refetch()}
            disabled={isFetching}
            className="flex items-center gap-2 bg-[#101014] hover:bg-[#18181f] text-[#ff2b42] border border-[#1f1f26] text-xs font-semibold px-4 py-2 rounded-lg transition-colors cursor-pointer disabled:opacity-50"
          >
            <RefreshCw
              className={`w-3.5 h-3.5 ${isFetching ? 'animate-spin' : ''}`}
            />
            Retry
          </button>
        </div>
      ) : streams.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 px-4 text-center rounded-2xl border border-[#1f1f26] bg-[#08080a]">
          <Radio className="w-10 h-10 text-gray-600 mb-3" />
          <h3 className="text-base font-semibold text-gray-200 mb-1">
            No live streams right now
          </h3>
          <p className="text-xs text-gray-400">
            There are currently no active livestreams. Check back later!
          </p>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-4 sm:gap-5">
            {streams.map((stream) => (
              <StreamCard key={stream.id} stream={stream} />
            ))}
          </div>

          {totalPages > 1 && (
            <div className="flex flex-col sm:flex-row items-center justify-between gap-3 pt-6 border-t border-[#1f1f26] text-xs text-gray-400">
              <div>
                Showing{' '}
                <span className="font-semibold text-gray-200">
                  {page * PAGE_SIZE + 1}
                </span>{' '}
                to{' '}
                <span className="font-semibold text-gray-200">
                  {Math.min((page + 1) * PAGE_SIZE, totalElements)}
                </span>{' '}
                of{' '}
                <span className="font-semibold text-gray-200">
                  {totalElements}
                </span>{' '}
                streams
              </div>

              <div className="flex items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => setPage(page - 1)}
                  disabled={page <= 0 || isFetching}
                  className="flex items-center gap-1 px-3 py-1.5 rounded-lg bg-[#101014] border border-[#1f1f26] text-gray-300 hover:text-white hover:bg-[#18181f] disabled:opacity-40 disabled:cursor-not-allowed transition cursor-pointer"
                >
                  <ChevronLeft className="w-3.5 h-3.5" />
                  <span>Previous</span>
                </button>

                <span className="px-3 py-1.5 font-medium text-gray-300">
                  Page {page + 1} of {totalPages}
                </span>

                <button
                  type="button"
                  onClick={() => setPage(page + 1)}
                  disabled={page >= totalPages - 1 || isFetching}
                  className="flex items-center gap-1 px-3 py-1.5 rounded-lg bg-[#101014] border border-[#1f1f26] text-gray-300 hover:text-white hover:bg-[#18181f] disabled:opacity-40 disabled:cursor-not-allowed transition cursor-pointer"
                >
                  <span>Next</span>
                  <ChevronRight className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}
