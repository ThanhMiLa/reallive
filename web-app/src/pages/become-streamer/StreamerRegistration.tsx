import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { CheckCircle2 } from 'lucide-react'
import { toast } from 'sonner'
import { Link } from 'react-router-dom'
import { streamerApi } from '@/api/streamer.api'
import { Button } from '@/components/ui/Button'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse, UserProfile } from '@/types/auth.types'

export function StreamerRegistration({ profile }: { profile: UserProfile }) {
  const client = useQueryClient()
  const [isSubmitting, setSubmitting] = useState(false)
  const [registrationComplete, setRegistered] = useState(false)
  const registered = registrationComplete || Boolean(profile.roles?.includes('STREAMER'))
  const [accepted, setAccepted] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const active = useRef(true)
  const inFlight = useRef(false)

  useEffect(() => {
    active.current = true
    return () => { active.current = false }
  }, [])

  const register = async () => {
    if (inFlight.current || registered || !profile.emailVerified || !accepted) return
    inFlight.current = true
    setSubmitting(true)
    setError(null)
    const isCurrentUser = () => active.current && useAuthStore.getState().user?.userId === profile.userId
    try {
      const result = await streamerApi.becomeStreamer()
      if (!isCurrentUser()) return
      if (result.userId !== profile.userId) {
        setError('Unable to confirm your streamer registration. Please try again.')
        return
      }
      setRegistered(true)
      const currentUser = useAuthStore.getState().user
      if (currentUser?.userId === profile.userId) {
        useAuthStore.getState().setUser({ ...currentUser, roles: result.roles })
      }
      client.setQueryData<UserProfile>(['profile', profile.userId], (current) =>
        current ? { ...current, roles: result.roles } : current,
      )
      void client.invalidateQueries({ queryKey: ['channels'] })
      toast.success('Streamer registration complete. Your channel has been created.')
    } catch (cause) {
      if (!isCurrentUser()) return
      if (isAxiosError<ApiResponse>(cause)) {
        switch (cause.response?.data?.code) {
          case 1030:
            setRegistered(true)
            void client.invalidateQueries({ queryKey: ['profile', profile.userId] })
            return
          case 1027:
            setError('Verify your email before becoming a streamer.')
            void client.invalidateQueries({ queryKey: ['profile', profile.userId] })
            return
          case 1031:
          case 1037:
            setError('Streamer registration is temporarily unavailable. Please try again later.')
            return
          case 1044:
            setError('Registration could not be confirmed and may have completed. Please try again shortly.')
            return
        }
        if (cause.response?.status === 401) {
          setError('Your session has expired. Please log in again.')
          return
        }
        setError(cause.response?.data?.message || 'Unable to confirm your registration. Check your connection and try again.')
      } else {
        setError('Unable to register as a streamer. Please try again.')
      }
    } finally {
      inFlight.current = false
      if (active.current) setSubmitting(false)
    }
  }

  return (
    <section aria-labelledby="streamer-registration-heading" className="space-y-3 rounded-xl border border-[#1f1f26] bg-[#08080a] p-4 sm:px-5">
      <h2 id="streamer-registration-heading" className="sr-only">Streamer registration</h2>
      {registered ? (
        <p role="status" className="flex items-center gap-2 text-sm text-emerald-400"><CheckCircle2 className="h-5 w-5 shrink-0" />Your streamer registration is complete.</p>
      ) : (
        <>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <label className="flex cursor-pointer items-start gap-2.5 text-sm leading-relaxed text-gray-300">
              <input type="checkbox" checked={accepted} disabled={isSubmitting}
                onChange={(event) => setAccepted(event.target.checked)}
                className="mt-1 h-4 w-4 shrink-0 cursor-pointer accent-[#ff2b42] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42]" />
              <span>I have read and accept the streamer guidelines.</span>
            </label>
            <Button type="button" size="sm" className="self-start shrink-0 sm:self-auto" isLoading={isSubmitting} disabled={!profile.emailVerified || !accepted}
              aria-describedby={!profile.emailVerified ? 'streamer-email-required' : !accepted ? 'streamer-acceptance-required' : undefined}
              onClick={register}>Become a streamer</Button>
          </div>
          {!profile.emailVerified ? (
            <p id="streamer-email-required" className="text-xs text-amber-400">Verify your email in <Link to="/profile" className="rounded font-semibold underline focus-visible:outline-2 focus-visible:outline-[#ff2b42]">Profile</Link> before registering.</p>
          ) : <p id="streamer-acceptance-required" className="sr-only">Accept the guidelines to enable registration.</p>}
        </>
      )}
      {error && <p role="alert" className="rounded-lg border border-red-500/30 bg-red-500/10 p-3 text-sm text-red-400">{error}</p>}
    </section>
  )
}
