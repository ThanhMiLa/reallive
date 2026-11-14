import type { ReactNode } from 'react'
import { isAxiosError } from 'axios'
import { Button } from '@/components/ui/Button'
import { streamErrorMessage } from '@/hooks/useStreamManager'
import { useAuthStore } from '@/stores/auth.store'

export function StreamAuthGate({
  title,
  loginMessage,
  children,
}: {
  title: string
  loginMessage: string
  children: (userId: string) => ReactNode
}) {
  const { user, isAuthenticated, isCheckingAuth, openAuthModal } = useAuthStore()

  if (isCheckingAuth) {
    return (
      <main className="p-8">
        <h1 className="font-outfit text-3xl font-bold">{title}</h1>
        <p className="mt-8 text-gray-400" role="status">
          Checking your session…
        </p>
      </main>
    )
  }

  if (!isAuthenticated || !user) {
    return (
      <main className="p-8">
        <h1 className="font-outfit text-3xl font-bold">{title}</h1>
        <p className="mt-6 text-gray-400">{loginMessage}</p>
        <Button className="mt-4" onClick={() => openAuthModal('login')}>
          Log in
        </Button>
      </main>
    )
  }

  return <>{children(user.userId)}</>
}

export function StreamSetupFallback({
  title,
  isPending,
  isError,
  error,
  onRetry,
}: {
  title: string
  isPending: boolean
  isError: boolean
  error: unknown
  onRetry: () => void
}) {
  if (isPending) {
    return (
      <main className="p-8">
        <h1 className="font-outfit text-3xl font-bold">{title}</h1>
        <p className="mt-8 text-gray-400" role="status">
          Loading your stream setup…
        </p>
      </main>
    )
  }

  if (isError) {
    return (
      <main className="p-8">
        <h1 className="font-outfit text-3xl font-bold">{title}</h1>
        <p className="mt-6 text-gray-400">{streamErrorMessage(error)}</p>
        <div className="mt-4 flex gap-3">
          {isAxiosError(error) && error.response?.status === 401 && (
            <Button onClick={() => useAuthStore.getState().openAuthModal('login')}>Log in</Button>
          )}
          <Button variant="secondary" onClick={onRetry}>
            Try again
          </Button>
        </div>
      </main>
    )
  }

  return null
}
