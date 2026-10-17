import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Gamepad2, AlertCircle, RefreshCw } from 'lucide-react'
import { channelApi } from '@/api/channel.api'
import type { CategoryItem } from '@/types/channel.types'


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

function CategoryCard({ category }: { category: CategoryItem }) {
  const [imgError, setImgError] = useState(false)
  const hasThumbnail = Boolean(
    category.thumbnailUrl && category.thumbnailUrl.trim() !== '' && !imgError,
  )

  const colorIndex =
    Math.abs(
      category.name
        .split('')
        .reduce((acc, char) => acc + char.charCodeAt(0), 0),
    ) % FALLBACK_GRADIENTS.length

  const gradient = FALLBACK_GRADIENTS[colorIndex]

  return (
    <div
      title={category.name}
      className="group flex flex-col cursor-pointer transition-transform duration-200 hover:-translate-y-1"
    >
      {/* Category Thumbnail / Poster */}
      <div className="relative w-full aspect-[3/4] rounded-xl overflow-hidden bg-[#101014] border border-[#1f1f26] shadow-md group-hover:border-[#ff2b42]/50 group-hover:shadow-[0_0_20px_rgba(255,43,66,0.2)] transition-all duration-300">
        {hasThumbnail ? (
          <>
            <img
              src={category.thumbnailUrl!}
              alt={category.name}
              className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
              onError={() => setImgError(true)}
            />
            <div className="absolute inset-0 bg-gradient-to-t from-[#000000]/85 via-transparent to-transparent pointer-events-none" />
          </>
        ) : (
          <div
            className={`w-full h-full bg-gradient-to-br ${gradient.bg} flex flex-col items-center justify-center p-4 relative overflow-hidden`}
          >
            <div className="absolute -top-10 -right-10 w-28 h-28 rounded-full bg-white/5 blur-xl pointer-events-none" />
            <div className="relative z-10 flex flex-col items-center text-center gap-2">
              <div
                className={`w-12 h-12 rounded-xl ${gradient.iconBg} flex items-center justify-center border ${gradient.border} shadow-lg`}
              >
                <Gamepad2 className={`w-6 h-6 ${gradient.iconColor}`} />
              </div>
              <span className="text-xs font-bold uppercase tracking-wider text-gray-300/90 line-clamp-2 px-1">
                {category.name}
              </span>
            </div>
          </div>
        )}
      </div>

      {/* Category Name below */}
      <div className="pt-2.5 flex flex-col min-w-0">
        <h3 className="font-semibold text-sm sm:text-base text-gray-200 group-hover:text-[#ff2b42] transition-colors truncate">
          {category.name}
        </h3>
      </div>
    </div>
  )
}

export function BrowseCategoriesTab() {
  const {
    data: categories = [],
    isLoading,
    isError,
    refetch,
    isFetching,
  } = useQuery({
    queryKey: ['categories'],
    queryFn: () => channelApi.getCategories(),
  })

  return (
    <div className="pb-12">
      {/* Loading Skeleton State */}
      {isLoading ? (
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 2xl:grid-cols-7 gap-4 sm:gap-5">
          {Array.from({ length: 12 }).map((_, idx) => (
            <div key={idx} className="flex flex-col gap-2.5">
              <div className="w-full aspect-[3/4] rounded-xl bg-[#101014] border border-[#1f1f26] animate-pulse" />
              <div className="h-4 w-3/4 bg-[#101014] rounded animate-pulse" />
            </div>
          ))}
        </div>
      ) : isError ? (
        /* Error State */
        <div className="flex flex-col items-center justify-center py-16 text-center border border-[#1f1f26] rounded-xl bg-[#08080a] px-4">
          <AlertCircle className="w-10 h-10 text-rose-500 mb-3" />
          <h3 className="text-base font-semibold text-gray-200 mb-1">
            Failed to load categories
          </h3>
          <p className="text-xs text-gray-400 mb-4 max-w-sm">
            Could not fetch categories from server. Please check your connection
            and try again.
          </p>
          <button
            type="button"
            onClick={() => refetch()}
            disabled={isFetching}
            className="flex items-center gap-2 bg-[#101014] hover:bg-[#18181f] text-[#ff2b42] border border-[#1f1f26] text-xs font-semibold px-4 py-2 rounded-lg transition-colors cursor-pointer"
          >
            <RefreshCw
              className={`w-3.5 h-3.5 ${isFetching ? 'animate-spin' : ''}`}
            />
            Retry
          </button>
        </div>
      ) : categories.length === 0 ? (
        /* Empty State */
        <div className="flex flex-col items-center justify-center py-16 text-center border border-[#1f1f26] rounded-xl bg-[#08080a] px-4">
          <Gamepad2 className="w-10 h-10 text-gray-600 mb-3" />
          <h3 className="text-base font-semibold text-gray-200 mb-1">
            No categories found
          </h3>
          <p className="text-xs text-gray-400">
            There are currently no categories available.
          </p>
        </div>
      ) : (
        /* Categories Grid */
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 2xl:grid-cols-7 gap-4 sm:gap-5">
          {categories.map((category) => (
            <CategoryCard key={category.id} category={category} />
          ))}
        </div>
      )}
    </div>
  )
}
