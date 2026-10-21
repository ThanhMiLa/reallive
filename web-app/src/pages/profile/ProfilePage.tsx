import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { isAxiosError } from 'axios'
import { Camera, Eye, EyeOff, LockKeyhole, LogIn, UserRound } from 'lucide-react'
import { toast } from 'sonner'
import type { z } from 'zod'
import { authApi } from '@/api/auth.api'
import { profileStaleTime } from '@/lib/query-client'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse, UserProfile } from '@/types/auth.types'
import { EmailVerification } from './EmailVerification'
import { avatarError, passwordSchema, profileSchema, todayDate } from './profile.schema'

function errorMessage(error: unknown) {
  if (isAxiosError<ApiResponse>(error)) {
    if (error.response?.status === 401) return 'Your session has expired. Please log in again.'
    return error.response?.data?.message || 'Unable to connect to the server. Please try again.'
  }
  return 'Something went wrong. Please try again.'
}

function FormError({ message }: { message: string | null }) {
  return message ? <p role="alert" className="rounded-lg border border-red-500/30 bg-red-500/10 p-3 text-sm text-red-400">{message}</p> : null
}

function ProfileForm({ profile, onSaved, onVerified }: {
  profile: UserProfile
  onSaved: (profile: UserProfile) => void
  onVerified: () => void
}) {
  const [avatar, setAvatar] = useState<File | null>(null)
  const [preview, setPreview] = useState<string | null>(null)
  const [imageError, setImageError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const { register, handleSubmit, reset, formState: { errors, isSubmitting, isDirty } } = useForm<z.infer<typeof profileSchema>>({
    resolver: zodResolver(profileSchema),
    defaultValues: { displayName: profile.displayName || '', dob: profile.dob || '' },
  })

  useEffect(() => {
    return () => { if (preview) URL.revokeObjectURL(preview) }
  }, [preview])

  const cancel = () => {
    reset()
    setAvatar(null)
    setPreview(null)
    setImageError(null)
    setError(null)
    if (fileInput.current) fileInput.current.value = ''
  }

  const save = handleSubmit(async (values) => {
    setError(null)
    if (profile.dob && !values.dob) {
      setError('Please select a date of birth. Removing a saved date is not currently supported.')
      return
    }
    const payload = new FormData()
    payload.append('displayName', values.displayName)
    // Empty dates are omitted: the backend does not support clearing an existing date.
    if (values.dob) payload.append('dob', values.dob)
    if (avatar) payload.append('avatar', avatar)
    try {
      const updated = await authApi.updateProfile(payload)
      if (useAuthStore.getState().user?.userId !== profile.userId) return
      reset({ displayName: updated.displayName || '', dob: updated.dob || '' })
      setAvatar(null)
      setPreview(null)
      setImageError(null)
      onSaved(updated)
      toast.success('Profile updated successfully.')
    } catch (cause) {
      setError(errorMessage(cause))
    }
  })

  const avatarUrl = preview || profile.avatarUrl
  return (
    <form onSubmit={save} className="space-y-6" noValidate>
      <FormError message={error} />
      <fieldset disabled={isSubmitting} className="grid items-start gap-6 disabled:opacity-60 lg:grid-cols-[6rem_minmax(0,1fr)] lg:gap-8">
        <div className="space-y-2">
          <button
            type="button"
            aria-label="Change profile picture"
            onClick={() => fileInput.current?.click()}
            className="group relative flex h-24 w-24 cursor-pointer items-center justify-center overflow-hidden rounded-full border-2 border-[#1f1f26] bg-[#18181f] focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-[#ff2b42]"
          >
            {avatarUrl ? <img src={avatarUrl} alt="Profile picture" className="h-full w-full object-cover" /> : <UserRound className="h-10 w-10 text-gray-400" />}
            <span className="pointer-events-none absolute inset-0 flex items-center justify-center bg-black/60 opacity-0 group-hover:opacity-100 group-focus-visible:opacity-100 [@media(hover:none)]:opacity-100">
              <Camera className="h-6 w-6 text-white" aria-hidden="true" />
            </span>
          </button>
          <input ref={fileInput} type="file" accept="image/jpeg,image/png,image/webp" aria-label="Choose profile picture" className="hidden" onChange={(event) => {
            const file = event.target.files?.[0]
            if (!file) return
            const message = avatarError(file)
            setImageError(message)
            if (!message) {
              setAvatar(file)
              setPreview(URL.createObjectURL(file))
            }
            event.target.value = ''
          }} />
          {imageError && <p role="alert" className="text-xs text-red-400">{imageError}</p>}
        </div>
        <div className="grid min-w-0 gap-5 sm:grid-cols-2">
          <Input id="profile-name" label="Display name" autoComplete="nickname" maxLength={100} error={errors.displayName?.message} {...register('displayName')} />
          <div>
            <Input id="profile-dob" label="Date of birth" type="date" max={todayDate()} error={errors.dob?.message} {...register('dob')} />
          </div>
          <div>
            <Input id="profile-email" label="Email" type="email" value={profile.email} readOnly className="text-gray-400" />
            <EmailVerification profile={profile} onVerified={onVerified} />
          </div>
          <Input id="profile-joined" label="Joined" value={profile.create_at ? new Intl.DateTimeFormat('en-US', { year: 'numeric', month: 'long', day: 'numeric' }).format(new Date(profile.create_at)) : 'Not available'} readOnly className="text-gray-400" />
        </div>
      </fieldset>
      <div className="flex flex-wrap justify-end gap-2 border-t border-[#1f1f26] pt-3">
        <Button type="button" size="sm" variant="secondary" disabled={isSubmitting || (!isDirty && !avatar)} onClick={cancel}>Cancel changes</Button>
        <Button type="submit" size="sm" isLoading={isSubmitting} disabled={(!isDirty && !avatar) || Boolean(imageError)}>Save changes</Button>
      </div>
    </form>
  )
}

function PasswordForm() {
  const [visible, setVisible] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const { register, handleSubmit, reset, formState: { errors, isSubmitting } } = useForm<z.infer<typeof passwordSchema>>({
    resolver: zodResolver(passwordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  })
  const save = handleSubmit(async (values) => {
    setError(null)
    try {
      await authApi.changePassword(values)
      reset()
      setVisible(false)
      toast.success('Password changed successfully.')
    } catch (cause) {
      setError(errorMessage(cause))
    }
  })
  return (
    <form onSubmit={save} className="space-y-5" noValidate>
      <FormError message={error} />
      <fieldset disabled={isSubmitting} className="grid gap-5 sm:grid-cols-2 disabled:opacity-60">
        <div className="sm:col-span-2 sm:max-w-[calc(50%-10px)]">
          <Input id="current-password" label="Current password" type={visible ? 'text' : 'password'} autoComplete="current-password" error={errors.currentPassword?.message} {...register('currentPassword')} />
        </div>
        <Input id="new-password" label="New password" type={visible ? 'text' : 'password'} autoComplete="new-password" error={errors.newPassword?.message} {...register('newPassword')} />
        <Input id="confirm-password" label="Confirm new password" type={visible ? 'text' : 'password'} autoComplete="new-password" error={errors.confirmPassword?.message} {...register('confirmPassword')} />
      </fieldset>
      <div className="flex flex-wrap items-center justify-between gap-4 border-t border-[#1f1f26] pt-5">
        <button type="button" aria-pressed={visible} onClick={() => setVisible(!visible)} className="flex items-center gap-2 rounded text-xs text-gray-400 hover:text-white focus-visible:outline-2 focus-visible:outline-[#ff2b42] cursor-pointer">
          {visible ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
          {visible ? 'Hide passwords' : 'Show passwords'}
        </button>
        <Button type="submit" size="sm" isLoading={isSubmitting}>Change password</Button>
      </div>
    </form>
  )
}

export function ProfilePage() {
  const { user, isAuthenticated, isCheckingAuth, openAuthModal, setUser } = useAuthStore()
  const client = useQueryClient()
  const queryKey = ['profile', user?.userId]
  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey,
    queryFn: () => authApi.getProfile(),
    enabled: isAuthenticated && !isCheckingAuth,
    staleTime: profileStaleTime,
    refetchOnReconnect: false,
  })

  return (
    <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-5xl space-y-7 pb-10">
        <div>
          <h1 className="text-3xl font-extrabold tracking-tight text-white sm:text-4xl font-outfit">Profile</h1>
          <p className="mt-2 text-sm text-gray-400">Manage your personal information and RealLive account security.</p>
        </div>
        {isCheckingAuth ? <p role="status" className="text-sm text-gray-400">Checking your session…</p> : !isAuthenticated ? (
          <div className="rounded-xl border border-[#1f1f26] bg-[#08080a] p-6 space-y-4">
            <p className="text-sm text-gray-300">Log in to view and update your profile.</p>
            <Button onClick={() => openAuthModal('login')} leftIcon={<LogIn className="h-4 w-4" />}>Log in</Button>
          </div>
        ) : isPending ? <p role="status" className="text-sm text-gray-400">Loading your profile…</p> : isError ? (
          <div className="space-y-4"><FormError message={errorMessage(error)} /><Button variant="secondary" onClick={() => refetch()}>Try again</Button></div>
        ) : data && (
          <>
            <section aria-labelledby="personal-info" className="rounded-xl border border-[#1f1f26] bg-[#08080a] p-5 sm:p-7">
              <div className="mb-6 flex items-center gap-3 border-b border-[#1f1f26] pb-5">
                <UserRound className="h-5 w-5 text-[#ff2b42]" />
                <h2 id="personal-info" className="text-lg font-bold text-white font-outfit">Personal information</h2>
              </div>
              <ProfileForm key={data.userId} profile={data} onSaved={(updated) => {
                const currentUser = useAuthStore.getState().user
                const profile = { ...updated, emailVerified: updated.emailVerified || Boolean(currentUser?.emailVerified) }
                setUser(profile)
                client.setQueryData(queryKey, profile)
              }} onVerified={() => {
                const currentUser = useAuthStore.getState().user
                if (currentUser?.userId !== data.userId) return
                setUser({ ...currentUser, emailVerified: true })
                client.setQueryData<UserProfile>(queryKey, (current) =>
                  current ? { ...current, emailVerified: true } : current,
                )
              }} />
            </section>
            <section aria-labelledby="password-heading" className="rounded-xl border border-[#1f1f26] bg-[#08080a] p-5 sm:p-7">
              <div className="mb-6 border-b border-[#1f1f26] pb-5">
                <div className="flex items-center gap-3"><LockKeyhole className="h-5 w-5 text-[#ff2b42]" /><h2 id="password-heading" className="text-lg font-bold text-white font-outfit">Change password</h2></div>
                <p className="mt-2 text-xs text-gray-400">Your new password must contain at least 5 characters and differ from your current password.</p>
              </div>
              <PasswordForm key={data.userId} />
            </section>
          </>
        )}
      </div>
    </main>
  )
}
