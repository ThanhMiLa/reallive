import { useState } from 'react'

export function Avatar({ url, name, role }: { url: string | null; name: string; role?: 'owner' | 'moderator' }) {
  const [failed, setFailed] = useState<string | null>(null)
  const roleStyle = role === 'owner' ? 'avatar-owner' : role === 'moderator' ? 'avatar-moderator' : ''
  return url && failed !== url ? (
    <img src={url} onError={() => setFailed(url)} alt="" className={`h-7 w-7 shrink-0 rounded-full object-cover ${roleStyle}`} />
  ) : (
    <span aria-hidden="true" className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-[#25252d] text-xs text-gray-300 ${roleStyle}`}>
      {name.charAt(0).toUpperCase()}
    </span>
  )
}
