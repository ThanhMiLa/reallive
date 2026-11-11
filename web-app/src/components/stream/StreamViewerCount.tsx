import { Eye } from 'lucide-react'
import type { StreamStatus } from '@/types/stream.types'

const viewerNumber = new Intl.NumberFormat('en-US')

interface Props {
  count?: number | null
  status?: StreamStatus
  unavailable?: boolean
}

export function StreamViewerCount({ count, status, unavailable = false }: Props) {
  const inactive = status === 'READY' || status === 'ENDED'
  const value = inactive ? 0 : unavailable ? null : count

  return (
    <span
      role="group"
      aria-label="Current viewers"
      className="inline-flex items-center gap-1.5 rounded-lg border border-[#1f1f26] bg-[#101014] px-2.5 py-1 text-xs text-gray-400"
    >
      <Eye aria-hidden="true" className="h-3.5 w-3.5" />
      <span>Viewers</span>
      <span className="font-semibold tabular-nums text-gray-200">
        {value == null ? '—' : viewerNumber.format(value)}
      </span>
    </span>
  )
}
