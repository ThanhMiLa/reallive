import { useMemo, useState } from 'react'
import { useLocation, useParams } from 'react-router-dom'
import { isAxiosError } from 'axios'
import { useQuery } from '@tanstack/react-query'
import {
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  Gamepad2,
  Radio,
  RefreshCw,
} from 'lucide-react'
import { channelApi } from '@/api/channel.api'
import { streamApi } from '@/api/stream.api'
import { StreamCard } from '@/components/stream/StreamCard'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse } from '@/types/auth.types'
import type { CategoryItem } from '@/types/channel.types'

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

const FALLBACK_GRADIENTS = [
  {
    bg: 'from-rose-950/70 via-[#101014] to-[#050507]',
    iconBg: 'bg-rose-500/10',
    iconColor: 'text-[#ff2b42]',
    border: 'border-[#ff2b42]/30',
  },
  {
    bg: 'from-purple-950/70 via-[#101014] to-[#050507]',
    iconBg: 'bg-purple-500/10',
    iconColor: 'text-purple-400',
    border: 'border-purple-500/30',
  },
  {
    bg: 'from-emerald-950/70 via-[#101014] to-[#050507]',
    iconBg: 'bg-emerald-500/10',
    iconColor: 'text-emerald-400',
    border: 'border-emerald-500/30',
  },
  {
    bg: 'from-indigo-950/70 via-[#101014] to-[#050507]',
    iconBg: 'bg-indigo-500/10',
    iconColor: 'text-indigo-400',
    border: 'border-indigo-500/30',
  },
  {
    bg: 'from-red-950/70 via-[#101014] to-[#050507]',
    iconBg: 'bg-red-500/10',
    iconColor: 'text-red-400',
    border: 'border-red-500/30',
  },
  {
    bg: 'from-amber-950/70 via-[#101014] to-[#050507]',
    iconBg: 'bg-amber-500/10',
    iconColor: 'text-amber-400',
    border: 'border-amber-500/30',
  },
]

interface CategoryLocationState {
  category?: CategoryItem
}

export function CategoryDetailPage() {
  const { categoryId = '' } = useParams<{ categoryId: string }>()
  const location = useLocation()
  const stateCategory = (location.state as CategoryLocationState | null)
    ?.category

  const { user, isAuthenticated } = useAuthStore()
  const authScopeKey = isAuthenticated
    ? (user?.userId ?? 'authenticated')
    : 'guest'

  const [page, setPage] = useState(0)
  const [prevCategoryId, setPrevCategoryId] = useState(categoryId)
  const [logoError, setLogoError] = useState(false)

  if (prevCategoryId !== categoryId) {
    setPrevCategoryId(categoryId)
    setPage(0)
    setLogoError(false)
  }

  const { data: categories = [], isLoading: isCategoriesLoading } = useQuery({
    queryKey: ['categories'],
    queryFn: ({ signal }) => channelApi.getCategories(signal),
  })

  const matchedCategory = useMemo(() => {
    const fromList = categories.find(
      (item) => item.id === categoryId || item.slug === categoryId,
    )
    if (fromList) return fromList
    if (
      stateCategory &&
      (stateCategory.id === categoryId || stateCategory.slug === categoryId)
    ) {
      return stateCategory
    }
    return null
  }, [categories, categoryId, stateCategory])

  const resolvedCategoryId = matchedCategory?.id ?? categoryId

  const {
    data: streamsPage,
    isLoading: isStreamsLoading,
    isError,
    error,
    refetch,
    isFetching,
  } = useQuery({
    queryKey: [
      'streams',
      'live',
      authScopeKey,
      { page, size: PAGE_SIZE, categoryId: resolvedCategoryId },
    ],
    queryFn: ({ signal }) =>
      streamApi.list(
        { page, size: PAGE_SIZE, categoryId: resolvedCategoryId },
        signal,
      ),
    enabled: Boolean(resolvedCategoryId),
  })

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

  const categoryName =
    matchedCategory?.name?.trim() ||
    streams[0]?.category?.name?.trim() ||
    'Category'
  const thumbnailUrl = matchedCategory?.thumbnailUrl?.trim()
  const hasThumbnail = Boolean(thumbnailUrl && !logoError)

  const colorIndex =
    Math.abs(
      categoryName
        .split('')
        .reduce((acc, char) => acc + char.charCodeAt(0), 0),
    ) % FALLBACK_GRADIENTS.length
  const gradient = FALLBACK_GRADIENTS[colorIndex]

  const showHeaderSkeleton =
    isCategoriesLoading && !matchedCategory && streams.length === 0

  return (
    <main className="flex-1 overflow-y-auto px-4 sm:px-6 lg:px-8 py-6 select-none">
      <div className="pb-12">
        {/* Top Section: Category Logo & Name */}
        <div className="flex items-center gap-5 sm:gap-6 mb-8">
          {showHeaderSkeleton ? (
            <>
              <div className="w-28 sm:w-36 aspect-[3/4] rounded-xl bg-[#101014] border border-[#1f1f26] animate-pulse flex-shrink-0" />
              <div className="h-8 w-48 sm:w-64 bg-[#101014] rounded-lg animate-pulse" />
            </>
          ) : (
            <>
              {/* Category Logo / Poster */}
              <div className="relative w-28 sm:w-36 aspect-[3/4] rounded-xl overflow-hidden bg-[#101014] border border-[#1f1f26] shadow-lg flex-shrink-0">
                {hasThumbnail ? (
                  <img
                    src={thumbnailUrl!}
                    alt={categoryName}
                    onError={() => setLogoError(true)}
                    className="w-full h-full object-cover"
                  />
                ) : (
                  <div
                    className={`w-full h-full bg-gradient-to-br ${gradient.bg} flex flex-col items-center justify-center p-3 relative overflow-hidden`}
                  >
                    <div className="absolute -top-8 -right-8 w-20 h-20 rounded-full bg-white/5 blur-xl pointer-events-none" />
                    <div className="relative z-10 flex flex-col items-center text-center gap-2">
                      <div
                        className={`w-10 h-10 rounded-xl ${gradient.iconBg} flex items-center justify-center border ${gradient.border} shadow-lg`}
                      >
                        <Gamepad2 className={`w-5 h-5 ${gradient.iconColor}`} />
                      </div>
                      <span className="text-[11px] font-bold uppercase tracking-wider text-gray-300/90 line-clamp-2 px-1">
                        {categoryName}
                      </span>
                    </div>
                  </div>
                )}
              </div>

              {/* Category Name */}
              <div className="min-w-0">
                <h1 className="text-2xl sm:text-4xl font-extrabold text-white tracking-tight font-outfit break-words">
                  {categoryName}
                </h1>
              </div>
            </>
          )}
        </div>

        {/* Section Header Bar */}
        <div className="flex items-center justify-between border-b border-[#1f1f26] mb-6">
          <div className="pb-3 text-sm sm:text-base font-bold text-[#ff2b42] border-b-2 border-[#ff2b42]">
            Livestreams
          </div>

          {isFetching && !isStreamsLoading && (
            <span title="Updating..." className="pb-3">
              <RefreshCw className="w-3.5 h-3.5 animate-spin text-gray-400" />
            </span>
          )}
        </div>

        {/* Bottom Section: Livestreams of this Category */}
        {isStreamsLoading ? (
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
              There are currently no active livestreams in {categoryName}.
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
              <div className="flex flex-col sm:flex-row items-center justify-between gap-3 pt-6 mt-6 border-t border-[#1f1f26] text-xs text-gray-400">
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
    </main>
  )
}
