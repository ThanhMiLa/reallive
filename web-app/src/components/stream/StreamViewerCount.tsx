import { Eye, UserRound } from 'lucide-react'
import type { StreamStatus } from '@/types/stream.types'

const viewerNumber = new Intl.NumberFormat('en-US')

interface Props {
  count?: number | null
  status?: StreamStatus
  unavailable?: boolean
  variant?: 'default' | 'thumbnail' | 'watch'
}

export function StreamViewerCount({ count, status, unavailable = false, variant = 'default' }: Props) {
  const inactive = status === 'READY' || status === 'ENDED'
  const value = inactive ? 0 : unavailable ? null : count
  const formatted = value == null ? '—' : viewerNumber.format(value)

  if (variant === 'thumbnail') {
    return (
      <span
        role="group"
        aria-label="Current viewers"
        className="inline-flex items-center rounded-sm bg-black/75 px-1.5 py-0.5 text-sm font-semibold leading-tight text-white tabular-nums"
      >
        {formatted} viewers
      </span>
    )
  }

  if (variant === 'watch') {
    return (
      <span
        role="group"
        aria-label="Current viewers"
        className="inline-flex items-center gap-1 text-sm font-semibold tabular-nums text-[#f87171]"
      >
        <UserRound aria-hidden="true" className="h-4 w-4" />
        <span>{formatted}</span>
      </span>
    )
  }

  return (
    <span
      role="group"
      aria-label="Current viewers"
      className="inline-flex items-center gap-1.5 rounded-lg border border-[#1f1f26] bg-[#101014] px-2.5 py-1 text-xs text-gray-400"
    >
      <Eye aria-hidden="true" className="h-3.5 w-3.5" />
      <span>Viewers</span>
      <span className="font-semibold tabular-nums text-gray-200">
        {formatted}
      </span>
    </span>
  )
}
