import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { CheckCircle2, Circle, LogIn, Settings2, Users, Video } from 'lucide-react'
import { authApi } from '@/api/auth.api'
import { profileStaleTime } from '@/lib/query-client'
import { Button } from '@/components/ui/Button'
import { useAuthStore } from '@/stores/auth.store'
import { StreamerRegistration } from './StreamerRegistration'

const benefits = [
  { icon: Video, title: 'Your own channel', description: 'Get a dedicated channel that viewers can discover and follow.' },
  { icon: Settings2, title: 'Make it yours', description: 'Start with your profile name and picture, then manage your channel identity.' },
  { icon: Users, title: 'Build your community', description: 'Give your viewers one place to follow your channel and keep coming back.' },
]

const guidelines = [
  { title: 'Respect your community', description: 'Do not harass, threaten, discriminate against or encourage abuse of other people.' },
  { title: 'Share content responsibly', description: 'Only broadcast content you own or have permission to use. Respect copyright and other creators.' },
  { title: 'Protect privacy and safety', description: 'Do not expose private information or share illegal content or content that encourages harm.' },
  { title: 'Represent your channel honestly', description: 'Use accurate titles and categories. Avoid impersonation, scams and misleading content.' },
  { title: 'Follow moderation decisions', description: 'Cooperate with platform moderation. Violations may result in streaming restrictions, suspension or an account ban.' },
]

export function BecomeStreamerPage() {
  const { user, isAuthenticated, isCheckingAuth, openAuthModal } = useAuthStore()
  const { data: profile, isPending, isError, refetch, isFetching } = useQuery({
    queryKey: ['profile', user?.userId],
    queryFn: () => authApi.getProfile(),
    enabled: isAuthenticated && !isCheckingAuth && Boolean(user?.userId),
    staleTime: profileStaleTime,
    refetchOnReconnect: false,
  })

  return (
    <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-5xl space-y-7 pb-10">
        <div>
          <h1 className="text-3xl font-extrabold tracking-tight text-white sm:text-4xl font-outfit">Become a streamer</h1>
        </div>

        <div className="grid gap-6 lg:grid-cols-2">
        <section aria-labelledby="streamer-benefits" className="rounded-xl border border-[#1f1f26] bg-[#08080a] p-5 sm:p-7">
          <h2 id="streamer-benefits" className="text-lg font-bold text-white font-outfit">A channel of your own</h2>
          <div className="mt-5 space-y-4">
            {benefits.map(({ icon: Icon, title, description }) => (
              <div key={title} className="flex items-start gap-3">
                <Icon className="mt-0.5 h-5 w-5 shrink-0 text-[#ff2b42]" />
                <div className="space-y-1">
                <h3 className="text-sm font-semibold text-gray-100">{title}</h3>
                <p className="text-xs leading-relaxed text-gray-400">{description}</p>
                </div>
              </div>
            ))}
          </div>
        </section>
          <section aria-labelledby="streamer-requirements" className="rounded-xl border border-[#1f1f26] bg-[#08080a] p-5 sm:p-7">
            <h2 id="streamer-requirements" className="text-lg font-bold text-white font-outfit">Before you register</h2>
            <ul className="mt-5 space-y-5 text-sm">
              <li className="flex items-start gap-2.5">
                {isAuthenticated ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-400" /> : <Circle className="mt-0.5 h-4 w-4 shrink-0 text-gray-500" />}
                <div><p className="font-medium text-gray-200">An active RealLive account</p><p className="mt-1 text-xs text-gray-400">Your account must be active and must not be banned or suspended.</p></div>
              </li>
              <li className="flex items-start gap-2.5">
                {profile?.emailVerified ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-400" /> : <Circle className="mt-0.5 h-4 w-4 shrink-0 text-gray-500" />}
                <div><p className="font-medium text-gray-200">A verified email address</p><p className="mt-1 text-xs text-gray-400">{profile?.emailVerified ? 'Your email is verified.' : <>Verify your email in <Link to="/profile" className="rounded text-[#ff2b42] underline focus-visible:outline-2 focus-visible:outline-[#ff2b42]">Profile</Link> before registering.</>}</p></div>
              </li>
              <li className="flex items-start gap-2.5">
                <Circle className="mt-0.5 h-4 w-4 shrink-0 text-gray-500" />
                <div><p className="font-medium text-gray-200">18 years or older</p><p className="mt-1 text-xs text-gray-400">You must be at least 18 years old to become a streamer.</p></div>
              </li>
            </ul>
          </section>
        </div>
          <section aria-labelledby="streamer-guidelines" className="rounded-xl border border-[#1f1f26] bg-[#08080a] p-5 sm:p-7">
            <h2 id="streamer-guidelines" className="text-lg font-bold text-white font-outfit">Streamer guidelines</h2>
            <ul className="mt-5 grid gap-5 sm:grid-cols-2">
              {guidelines.map(({ title, description }) => <li key={title}><h3 className="text-sm font-semibold text-gray-200">{title}</h3><p className="mt-1 text-xs leading-relaxed text-gray-400">{description}</p></li>)}
            </ul>
          </section>

        {isCheckingAuth ? <p role="status" className="text-sm text-gray-400">Checking your session…</p> : !isAuthenticated ? (
          <div className="space-y-4 rounded-xl border border-[#1f1f26] bg-[#08080a] p-5 sm:p-7">
            <p className="text-sm text-gray-300">Log in to register as a streamer.</p>
            <Button onClick={() => openAuthModal('login')} leftIcon={<LogIn className="h-4 w-4" />}>Log in</Button>
          </div>
        ) : isError ? (
          <div className="space-y-4"><p role="alert" className="text-sm text-red-400">Unable to check your profile. Please try again.</p><Button size="sm" variant="secondary" isLoading={isFetching} onClick={() => refetch()}>Try again</Button></div>
        ) : isPending ? <p role="status" className="text-sm text-gray-400">Checking your registration requirements…</p>
          : profile && <StreamerRegistration key={profile.userId} profile={profile} />}
      </div>
    </main>
  )
}
