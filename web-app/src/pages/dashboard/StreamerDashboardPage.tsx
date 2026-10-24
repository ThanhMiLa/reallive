import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Radio,
  Copy,
  Check,
  Eye,
  EyeOff,
  Video,
  Settings,
  HelpCircle,
  ExternalLink,
  ShieldCheck,
  Flame,
} from 'lucide-react'
import { toast } from 'sonner'
import { useAuthStore } from '@/stores/auth.store'
import { hasStreamerRole } from '@/lib/utils'
import { Button } from '@/components/ui/Button'

export function StreamerDashboardPage() {
  const { user, isAuthenticated, isCheckingAuth, openAuthModal } = useAuthStore()
  const isStreamer = hasStreamerRole(user)

  const [copiedServer, setCopiedServer] = useState(false)
  const [copiedKey, setCopiedKey] = useState(false)
  const [showKey, setShowKey] = useState(false)

  // Default RTMP server endpoint based on architecture specification
  const serverUrl = 'rtmp://localhost:1935/live'
  const mockStreamKey = user?.userId ? `live_${user.userId.slice(0, 8)}?token=eyJhbGciOi...` : 'live_session_token'

  const copyToClipboard = async (text: string, type: 'server' | 'key') => {
    try {
      await navigator.clipboard.writeText(text)
      if (type === 'server') {
        setCopiedServer(true)
        setTimeout(() => setCopiedServer(false), 2000)
      } else {
        setCopiedKey(true)
        setTimeout(() => setCopiedKey(false), 2000)
      }
      toast.success(`${type === 'server' ? 'Server URL' : 'Stream key'} copied to clipboard!`)
    } catch {
      toast.error('Failed to copy to clipboard')
    }
  }

  if (isCheckingAuth) {
    return (
      <main className="flex-1 overflow-y-auto p-6 lg:p-8">
        <div className="mx-auto max-w-6xl">
          <p className="text-sm text-gray-400">Loading dashboard...</p>
        </div>
      </main>
    )
  }

  if (!isAuthenticated) {
    return (
      <main className="flex-1 overflow-y-auto p-6 lg:p-8">
        <div className="mx-auto max-w-xl text-center py-16 space-y-4 rounded-2xl border border-[#1f1f26] bg-[#0c0c10] p-8">
          <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-[#ff2b42]/10 text-[#ff2b42] border border-[#ff2b42]/20">
            <Radio className="h-7 w-7" />
          </div>
          <h1 className="text-2xl font-bold text-white font-outfit">Log in to view Streamer Dashboard</h1>
          <p className="text-sm text-gray-400">
            You need to be signed in to manage your channel and stream settings.
          </p>
          <div className="pt-2">
            <Button onClick={() => openAuthModal('login')}>Log in</Button>
          </div>
        </div>
      </main>
    )
  }

  if (!isStreamer) {
    return (
      <main className="flex-1 overflow-y-auto p-6 lg:p-8">
        <div className="mx-auto max-w-xl text-center py-16 space-y-4 rounded-2xl border border-[#1f1f26] bg-[#0c0c10] p-8">
          <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-amber-500/10 text-amber-400 border border-amber-500/20">
            <ShieldCheck className="h-7 w-7" />
          </div>
          <h1 className="text-2xl font-bold text-white font-outfit">Streamer Access Required</h1>
          <p className="text-sm text-gray-400">
            You need to register as a streamer before accessing the streamer dashboard.
          </p>
          <div className="pt-2">
            <Link
              to="/become-streamer"
              className="inline-flex items-center gap-2 rounded-xl bg-[#ff2b42] px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-[#e0243a]"
            >
              Become a streamer
            </Link>
          </div>
        </div>
      </main>
    )
  }

  return (
    <main className="flex-1 overflow-y-auto p-4 sm:p-6 lg:p-8">
      <div className="mx-auto max-w-6xl space-y-6">
        {/* Top Header */}
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between border-b border-[#1f1f26] pb-6">
          <div className="flex items-center gap-4">
            <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl border border-[#ff2b42]/30 bg-[#ff2b42]/10 text-[#ff2b42]">
              <Radio className="h-6 w-6" />
            </div>
            <div>
              <div className="flex items-center gap-3">
                <h1 className="text-2xl sm:text-3xl font-extrabold tracking-tight text-white font-outfit">
                  Streamer Dashboard
                </h1>
                <span className="flex items-center gap-1.5 rounded-full border border-gray-700/50 bg-[#16161d] px-2.5 py-0.5 text-xs font-semibold text-gray-300">
                  <span className="h-2 w-2 rounded-full bg-gray-500" />
                  Offline
                </span>
              </div>
              <p className="mt-1 text-sm text-gray-400">
                Welcome back, <span className="text-white font-medium">{user?.displayName || 'Streamer'}</span>. Set up OBS and manage your live stream.
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2.5">
            <Link
              to="/profile"
              className="flex items-center gap-2 rounded-xl border border-[#1f1f26] bg-[#121217] px-4 py-2.5 text-xs font-semibold text-gray-300 hover:bg-[#1a1a22] hover:text-white transition"
            >
              <Settings className="h-4 w-4 text-gray-400" />
              Settings
            </Link>
          </div>
        </div>

        {/* Quick Stats Grid */}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div className="rounded-2xl border border-[#1f1f26] bg-[#101014] p-5">
            <div className="flex items-center justify-between text-gray-400">
              <span className="text-xs font-medium uppercase tracking-wider">Stream Status</span>
              <Video className="h-4 w-4 text-gray-500" />
            </div>
            <p className="mt-2 text-xl font-bold text-white font-outfit">Ready to Stream</p>
            <p className="mt-0.5 text-xs text-gray-400">Configure OBS with credentials below</p>
          </div>

          <div className="rounded-2xl border border-[#1f1f26] bg-[#101014] p-5">
            <div className="flex items-center justify-between text-gray-400">
              <span className="text-xs font-medium uppercase tracking-wider">Channel Status</span>
              <Flame className="h-4 w-4 text-[#ff2b42]" />
            </div>
            <p className="mt-2 text-xl font-bold text-emerald-400 font-outfit">Active</p>
            <p className="mt-0.5 text-xs text-gray-400">Channel verified and discoverable</p>
          </div>

          <div className="rounded-2xl border border-[#1f1f26] bg-[#101014] p-5">
            <div className="flex items-center justify-between text-gray-400">
              <span className="text-xs font-medium uppercase tracking-wider">Channel Identity</span>
              <ExternalLink className="h-4 w-4 text-gray-500" />
            </div>
            <p className="mt-2 truncate text-xl font-bold text-white font-outfit">
              {user?.displayName || 'My Channel'}
            </p>
            <p className="mt-0.5 text-xs text-gray-400 truncate">{user?.email}</p>
          </div>
        </div>

        {/* Stream Credentials Card */}
        <section aria-labelledby="stream-setup-heading" className="rounded-2xl border border-[#1f1f26] bg-[#101014] p-5 sm:p-7 space-y-6">
          <div>
            <h2 id="stream-setup-heading" className="text-lg font-bold text-white font-outfit">
              OBS Stream Setup
            </h2>
            <p className="mt-1 text-xs sm:text-sm text-gray-400">
              Use these credentials to connect OBS Studio or your broadcasting software to RealLive.
            </p>
          </div>

          <div className="space-y-4">
            {/* RTMP Server URL */}
            <div className="space-y-1.5">
              <label htmlFor="rtmp-server-url" className="text-xs font-medium text-gray-300">
                Server URL (RTMP)
              </label>
              <div className="flex rounded-xl border border-[#1f1f26] bg-[#0c0c10] overflow-hidden focus-within:border-[#ff2b42]/70">
                <input
                  id="rtmp-server-url"
                  type="text"
                  readOnly
                  value={serverUrl}
                  className="flex-1 bg-transparent px-3.5 py-2.5 text-xs sm:text-sm text-gray-200 focus:outline-none select-all"
                />
                <button
                  type="button"
                  onClick={() => copyToClipboard(serverUrl, 'server')}
                  aria-label="Copy server URL"
                  className="flex items-center gap-1.5 border-l border-[#1f1f26] bg-[#14141a] px-3.5 text-xs font-medium text-gray-300 hover:bg-[#1a1a22] hover:text-white transition cursor-pointer"
                >
                  {copiedServer ? <Check className="h-4 w-4 text-emerald-400" /> : <Copy className="h-4 w-4" />}
                  <span className="hidden sm:inline">{copiedServer ? 'Copied' : 'Copy'}</span>
                </button>
              </div>
            </div>

            {/* Stream Key */}
            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <label htmlFor="stream-key-input" className="text-xs font-medium text-gray-300">
                  Stream Key
                </label>
                <span className="text-[11px] text-amber-400">Keep this private — never share your stream key</span>
              </div>
              <div className="flex rounded-xl border border-[#1f1f26] bg-[#0c0c10] overflow-hidden focus-within:border-[#ff2b42]/70">
                <input
                  id="stream-key-input"
                  type={showKey ? 'text' : 'password'}
                  readOnly
                  value={mockStreamKey}
                  className="flex-1 bg-transparent px-3.5 py-2.5 text-xs sm:text-sm font-mono text-gray-200 focus:outline-none select-all"
                />
                <button
                  type="button"
                  onClick={() => setShowKey(!showKey)}
                  aria-label={showKey ? 'Hide stream key' : 'Show stream key'}
                  className="border-l border-[#1f1f26] bg-[#14141a] px-3 text-gray-400 hover:text-white transition cursor-pointer"
                >
                  {showKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
                <button
                  type="button"
                  onClick={() => copyToClipboard(mockStreamKey, 'key')}
                  aria-label="Copy stream key"
                  className="flex items-center gap-1.5 border-l border-[#1f1f26] bg-[#14141a] px-3.5 text-xs font-medium text-gray-300 hover:bg-[#1a1a22] hover:text-white transition cursor-pointer"
                >
                  {copiedKey ? <Check className="h-4 w-4 text-emerald-400" /> : <Copy className="h-4 w-4" />}
                  <span className="hidden sm:inline">{copiedKey ? 'Copied' : 'Copy'}</span>
                </button>
              </div>
            </div>
          </div>

          {/* OBS Studio Step-by-step Guide */}
          <div className="rounded-xl border border-white/5 bg-[#14141a] p-4 text-xs space-y-2 text-gray-300">
            <div className="flex items-center gap-2 font-semibold text-white">
              <HelpCircle className="h-4 w-4 text-[#ff2b42]" />
              How to configure OBS Studio:
            </div>
            <ol className="list-decimal list-inside space-y-1 pl-1 text-gray-400">
              <li>Open OBS Studio and click <span className="text-gray-200">Settings</span> → <span className="text-gray-200">Stream</span>.</li>
              <li>Select Service: <span className="text-gray-200">Custom...</span></li>
              <li>Paste the <span className="text-gray-200">Server URL</span> and <span className="text-gray-200">Stream Key</span> from above.</li>
              <li>Click <span className="text-gray-200">Apply</span>, then click <span className="text-gray-200">Start Streaming</span> to go live!</li>
            </ol>
          </div>
        </section>
      </div>
    </main>
  )
}
