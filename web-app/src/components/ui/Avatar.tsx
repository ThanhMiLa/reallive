import { useState } from 'react'

export function Avatar({ url, name }: { url: string | null; name: string }) {
  const [failed, setFailed] = useState<string | null>(null)
  return url && failed !== url ? (
    <img src={url} onError={() => setFailed(url)} alt="" className="h-7 w-7 shrink-0 rounded-full object-cover" />
  ) : (
    <span aria-hidden="true" className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-[#25252d] text-xs text-gray-300">
      {name.charAt(0).toUpperCase()}
    </span>
  )
}
