import { useEffect, useRef, useState } from 'react'
import { isAxiosError } from 'axios'
import { CheckCircle2, TriangleAlert } from 'lucide-react'
import { toast } from 'sonner'
import { authApi } from '@/api/auth.api'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse, UserProfile } from '@/types/auth.types'
import { emailOtpSchema } from './profile.schema'

function verificationError(error: unknown) {
  if (isAxiosError<ApiResponse>(error)) {
    switch (error.response?.data?.code) {
      case 1021: return 'A code has already been requested. Check your inbox or wait before requesting another.'
      case 1024: return 'Incorrect code. Check your email and try again.'
      case 1025: return 'This code has expired or is no longer available. Please request a new code.'
    }
    if (error.response?.status === 401) return 'Your session has expired. Please log in again.'
    return error.response?.data?.message || 'Unable to connect to the server. Please try again.'
  }
  return 'Email verification failed. Please try again.'
}

export function EmailVerification({ profile, onVerified }: {
  profile: UserProfile
  onVerified: () => void
}) {
  const [expanded, setExpanded] = useState(false)
  const [otp, setOtp] = useState('')
  const [expiresAt, setExpiresAt] = useState<number | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const [pending, setPending] = useState<'send' | 'verify' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [requested, setRequested] = useState(false)
  const otpInput = useRef<HTMLInputElement>(null)
  const active = useRef(true)
  const secondsLeft = expiresAt === null ? null : Math.max(0, Math.ceil((expiresAt - now) / 1000))
  const expired = secondsLeft === 0
  const busy = pending !== null

  useEffect(() => {
    active.current = true
    return () => { active.current = false }
  }, [])

  useEffect(() => {
    if (!expanded || expiresAt === null || expired || profile.emailVerified) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [expanded, expiresAt, expired, profile.emailVerified])

  useEffect(() => {
    if (expanded && pending === null) otpInput.current?.focus()
  }, [expanded, pending])

  const isCurrentUser = () => active.current && useAuthStore.getState().user?.userId === profile.userId

  const handleFailure = (cause: unknown) => {
    if (!isCurrentUser()) return
    if (isAxiosError<ApiResponse>(cause)) {
      if (cause.response?.data?.code === 1020) {
        onVerified()
        return
      }
      if (cause.response?.data?.code === 1025) {
        setExpiresAt(Date.now())
        setNow(Date.now())
      }
    }
    setError(verificationError(cause))
  }

  const requestCode = async () => {
    if (busy || (secondsLeft !== null && secondsLeft > 0)) return
    setExpanded(true)
    setPending('send')
    setError(null)
    try {
      const result = await authApi.requestEmailVerificationOtp()
      if (!isCurrentUser()) return
      setExpiresAt(Date.parse(result.expiresAt))
      setNow(Date.now())
      setOtp('')
      setRequested(true)
      otpInput.current?.focus()
    } catch (cause) {
      handleFailure(cause)
    } finally {
      if (active.current) setPending(null)
    }
  }

  const verifyCode = async () => {
    if (busy || expired) return
    const validation = emailOtpSchema.safeParse(otp)
    if (!validation.success) {
      setError(validation.error.issues[0].message)
      otpInput.current?.focus()
      return
    }
    setPending('verify')
    setError(null)
    try {
      const result = await authApi.verifyEmailOtp(validation.data)
      if (!isCurrentUser()) return
      if (result.emailVerified) {
        onVerified()
        setOtp('')
        toast.success('Email verified successfully.')
      } else {
        setError('Email verification did not complete. Please try again.')
      }
    } catch (cause) {
      handleFailure(cause)
    } finally {
      if (active.current) setPending(null)
    }
  }

  if (profile.emailVerified) {
    return <p role="status" className="mt-2 flex items-center gap-1.5 text-xs text-emerald-400"><CheckCircle2 className="h-3.5 w-3.5" />Email verified</p>
  }

  return (
    <div className="mt-3 space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="flex items-center gap-1.5 text-xs text-amber-400"><TriangleAlert className="h-3.5 w-3.5 shrink-0" />Your email is not verified.</p>
        {!expanded && <Button type="button" size="sm" variant="secondary" onClick={requestCode}>Verify email</Button>}
      </div>
      {expanded && (
        <div className="space-y-3 rounded-lg border border-[#1f1f26] bg-[#101014] p-3.5">
          <p className="text-xs leading-relaxed text-gray-400" role="status">
            {requested ? 'Verification code requested. Check your inbox and spam folder.' : 'Enter the code from your email, or request a new one when available.'}
          </p>
          <Input
            ref={otpInput}
            id="email-verification-otp"
            label="Verification code"
            placeholder="6-digit code"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            value={otp}
            disabled={busy || expired}
            aria-describedby={error ? 'email-verification-error' : undefined}
            aria-invalid={Boolean(error)}
            onChange={(event) => setOtp(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                void verifyCode()
              }
            }}
          />
          {error && <p id="email-verification-error" role="alert" className="text-xs text-red-400">{error}</p>}
          {secondsLeft !== null && (
            <p className={`text-xs ${expired ? 'text-amber-400' : 'text-gray-400'}`}>
              {expired ? 'The code has expired. Request a new one.' : `Code expires in ${secondsLeft}s. You can resend after it expires.`}
            </p>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" size="sm" isLoading={pending === 'verify'} disabled={busy || expired || !emailOtpSchema.safeParse(otp).success} onClick={verifyCode}>Verify code</Button>
            <Button type="button" size="sm" variant="ghost" isLoading={pending === 'send'} disabled={busy || (secondsLeft !== null && secondsLeft > 0)} onClick={requestCode}>Resend code</Button>
          </div>
        </div>
      )}
    </div>
  )
}
