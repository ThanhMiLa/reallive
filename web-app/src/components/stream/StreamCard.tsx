import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Radio } from 'lucide-react'
import { StreamViewerCount } from '@/components/stream/StreamViewerCount'
import type { LiveStreamItem } from '@/types/stream.types'

export function StreamCard({ stream }: { stream: LiveStreamItem }) {
  const [thumbError, setThumbError] = useState(false)
  const [avatarError, setAvatarError] = useState(false)

  const channelName = stream.channel?.name?.trim() || 'Unknown Creator'
  const title = stream.title?.trim() || 'Untitled Stream'
  const categoryName = stream.category?.name?.trim()
  const categoryId = stream.category?.id || stream.categoryId
  const avatarUrl = stream.channel?.avatarUrl?.trim()
  const thumbnailUrl = stream.thumbnailUrl?.trim()

  const hasThumbnail = Boolean(thumbnailUrl && !thumbError)
  const hasAvatar = Boolean(avatarUrl && !avatarError)
  const initial = channelName.charAt(0).toUpperCase() || 'L'
  const watchPath = `/watch/${encodeURIComponent(stream.id)}`

  return (
    <article className="group flex flex-col">
      <Link
        to={watchPath}
        className="flex flex-col cursor-pointer rounded-xl focus:outline-none focus-visible:ring-2 focus-visible:ring-[#ff2b42]"
      >
        {/* 1. Thumbnail */}
        <div className="relative aspect-video w-full rounded-xl overflow-hidden bg-[#101014] border border-[#1f1f26] group-hover:border-[#ff2b42] group-hover:shadow-[0_0_16px_rgba(255,43,66,0.35)] transition-all duration-300">
          {hasThumbnail ? (
            <img
              src={thumbnailUrl!}
              alt={title}
              loading="lazy"
              onError={() => setThumbError(true)}
              className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
            />
          ) : (
            <div className="w-full h-full bg-[#101014] flex flex-col items-center justify-center p-3 text-gray-600">
              <Radio className="w-7 h-7 text-gray-600 mb-1" />
              <span className="text-[11px] font-medium text-gray-500">
                Live Stream
              </span>
            </div>
          )}

          {/* Live indicator badge */}
          <div className="absolute top-2 left-2 z-10">
            <span className="inline-flex items-center rounded-sm bg-[#eb0400] px-1.5 py-0.5 text-xs font-bold leading-tight text-white shadow">
              LIVE
            </span>
          </div>
          <div className="absolute bottom-2 left-2 z-10">
            <StreamViewerCount count={stream.currentViewerCount} variant="thumbnail" />
          </div>
        </div>

        {/* 2. Avatar, 3. Streamer name, 4. Stream title */}
        <div className="flex items-start gap-3 mt-3 min-w-0">
          {/* Streamer Avatar */}
          <div className="relative flex-shrink-0">
            {hasAvatar ? (
              <img
                src={avatarUrl!}
                alt={channelName}
                onError={() => setAvatarError(true)}
                className="w-10 h-10 rounded-full object-cover bg-[#101014] border border-[#1f1f26]"
              />
            ) : (
              <div className="w-10 h-10 rounded-full bg-gradient-to-tr from-rose-600 to-indigo-600 flex items-center justify-center text-sm font-bold text-white border border-[#1f1f26]">
                {initial}
              </div>
            )}
          </div>

          {/* Info Column */}
          <div className="flex-1 min-w-0">
            {/* Streamer Name */}
            <h3
              title={channelName}
              className="text-sm font-semibold text-white group-hover:text-[#ff2b42] transition-colors truncate"
            >
              {channelName}
            </h3>

            {/* Title Streams Session */}
            <p
              title={title}
              className="text-xs text-gray-400 group-hover:text-gray-300 transition-colors truncate mt-0.5"
            >
              {title}
            </p>
          </div>
        </div>
      </Link>

      {/* 5. Category Name (independent link) */}
      {categoryName && (
        <div className="mt-1.5 pl-[52px] min-w-0">
          {categoryId ? (
            <Link
              to={`/browse/categories/${encodeURIComponent(categoryId)}`}
              title={categoryName}
              className="inline-block text-[11px] font-medium text-gray-400 bg-[#14141a] hover:bg-[#1a1a22] hover:text-white border border-[#1f1f26] px-2 py-0.5 rounded truncate max-w-full transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-[#ff2b42]"
            >
              {categoryName}
            </Link>
          ) : (
            <span
              title={categoryName}
              className="inline-block text-[11px] font-medium text-gray-400 bg-[#14141a] border border-[#1f1f26] px-2 py-0.5 rounded truncate max-w-full"
            >
              {categoryName}
            </span>
          )}
        </div>
      )}
    </article>
  )
}
