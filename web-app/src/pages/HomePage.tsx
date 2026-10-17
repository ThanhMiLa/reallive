import { useState } from 'react'
import {
  Play,
  Volume2,
  VolumeX,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Eye,
  MessageSquare,
  Users,
  Sliders,
  Smile,
  Send,
} from 'lucide-react'
import { toast } from 'sonner'
import { useAuthStore } from '@/stores/auth.store'

interface ChatMessage {
  id: string
  badge?: string
  badgeColor?: string
  user?: string
  color?: string
  text?: string
  isReply?: boolean
  replyTo?: string
  replyText?: string
}

interface FeaturedStream {
  id: string
  title: string
  streamer: string
  avatar: string
  viewers: string
  category: string
  language: string
  tag: string
  snapshot: string
}

const featuredStreams: FeaturedStream[] = [
  {
    id: '1',
    title: 'LIVE: Team Vitality vs. MOUZ - ESL Pro League Grand Final',
    streamer: 'ESLCS',
    avatar:
      'https://images.unsplash.com/photo-1566492031773-4f4e44671857?w=100&h=100&fit=crop',
    viewers: '3.6K',
    category: 'Counter-Strike 2',
    language: 'English',
    tag: 'ESL Official',
    snapshot:
      'https://images.unsplash.com/photo-1542751371-adc38448a05e?w=1600&h=900&fit=crop',
  },
  {
    id: '2',
    title: 'VALORANT Champions Tour 2026 - Americas Stage 1 Playoffs',
    streamer: 'VALORANT_Americas',
    avatar:
      'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100&h=100&fit=crop',
    viewers: '8.9K',
    category: 'Valorant',
    language: 'English',
    tag: 'Official VCT',
    snapshot:
      'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=1600&h=900&fit=crop',
  },
  {
    id: '3',
    title: '🔥 ROAD TO RANK 1 RADIANT | DUO W/ TARIK',
    streamer: 'TenZ',
    avatar:
      'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=100&h=100&fit=crop',
    viewers: '14.2K',
    category: 'Valorant',
    language: 'English',
    tag: 'Pro Player',
    snapshot:
      'https://images.unsplash.com/photo-1511512578047-dfb367046420?w=1600&h=900&fit=crop',
  },
]

const initialMessages: ChatMessage[] = [
  {
    id: '1',
    badge: '10',
    badgeColor: 'bg-yellow-500/20 text-yellow-400',
    user: 'comegetsum2345',
    color: 'text-emerald-400',
    text: 'botx',
  },
  {
    id: '2',
    badge: '👑 SUB',
    badgeColor: 'bg-purple-600/30 text-purple-300',
    user: 'PrestoMagic',
    color: 'text-amber-400',
    text: "🐶 LET'S GOOO!",
  },
  {
    id: '3',
    badge: '37',
    badgeColor: 'bg-rose-500/20 text-rose-300',
    user: 'Almdar_Ali',
    color: 'text-pink-400',
    text: 'lets gooo',
  },
  {
    id: '4',
    user: 'KansasDan620',
    color: 'text-green-400',
    text: 'Mouz are dogwater',
  },
  {
    id: '5',
    badge: '43',
    badgeColor: 'bg-blue-600/20 text-blue-300',
    user: 'markokokotko',
    color: 'text-[#ff2b42]',
    text: '🔥',
  },
  {
    id: '6',
    user: 'smithereens117',
    color: 'text-indigo-400',
    text: 'damn I bet my whole house on mouz',
  },
  {
    id: '7',
    isReply: true,
    replyTo: 'smithereens117',
    replyText: 'rip bro',
  },
  {
    id: '8',
    badge: '43',
    badgeColor: 'bg-blue-600/20 text-blue-300',
    user: 'markokokotko',
    color: 'text-[#ff2b42]',
    text: 'gg wp',
  },
  {
    id: '9',
    user: 'P0rsche_M0nkey',
    color: 'text-purple-400',
    text: 'lets go vita',
  },
  {
    id: '10',
    badge: '11',
    badgeColor: 'bg-yellow-500/20 text-yellow-400',
    user: 'xThomasTv',
    color: 'text-red-400',
    text: 'mezii fullshock typical UK gaming',
  },
]

const streamsForYou = [
  {
    id: '1',
    title: '17:00 VITALITY [0] VS [1] MOUZ - CS2 GRAND FINAL',
    channel: 'bladeito',
    category: 'Counter-Strike 2',
    viewers: '1.4K',
    thumbnail:
      'https://images.unsplash.com/photo-1542751371-adc38448a05e?w=600&h=340&fit=crop',
    avatar:
      'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=80&h=80&fit=crop',
  },
  {
    id: '2',
    title: '🔴 ČOKE PIDE MAJKL DŽEKSON | Speedrun',
    channel: 'Astaturo',
    category: 'Just Chatting',
    viewers: '1.7K',
    thumbnail:
      'https://images.unsplash.com/photo-1511512578047-dfb367046420?w=600&h=340&fit=crop',
    avatar:
      'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=80&h=80&fit=crop',
  },
  {
    id: '3',
    title: 'ULTRA GALAKTICKY GAMING NO SLEEP DUO',
    channel: '2SekundovyMato',
    category: 'Fortnite',
    viewers: '1.2K',
    thumbnail:
      'https://images.unsplash.com/photo-1538481199705-c710c4e965fc?w=600&h=340&fit=crop',
    avatar:
      'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=80&h=80&fit=crop',
  },
  {
    id: '4',
    title: 'Vitality (0:0) MOUZ - BO5 Watch Party',
    channel: 'oskartommy',
    category: 'Valorant',
    viewers: '850',
    thumbnail:
      'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=600&h=340&fit=crop',
    avatar:
      'https://images.unsplash.com/photo-1539571696357-5a69c17a67c6?w=80&h=80&fit=crop',
  },
]

export function HomePage() {
  const [currentSlide, setCurrentSlide] = useState(0)
  const [isMuted, setIsMuted] = useState(false)
  const [messages, setMessages] = useState<ChatMessage[]>(initialMessages)
  const [inputMsg, setInputMsg] = useState('')
  const { isAuthenticated, user, openAuthModal } = useAuthStore()

  const currentStream = featuredStreams[currentSlide]

  const handleNextSlide = () => {
    setCurrentSlide((prev) => (prev + 1) % featuredStreams.length)
  }

  const handlePrevSlide = () => {
    setCurrentSlide(
      (prev) => (prev - 1 + featuredStreams.length) % featuredStreams.length,
    )
  }

  const handleSendMessage = (e: React.FormEvent) => {
    e.preventDefault()
    if (!isAuthenticated) {
      openAuthModal('login')
      return
    }

    if (!inputMsg.trim()) return

    const newMsg: ChatMessage = {
      id: Date.now().toString(),
      badge: 'YOU',
      badgeColor: 'bg-rose-500/30 text-rose-300',
      user: user?.displayName || 'You',
      color: 'text-white',
      text: inputMsg.trim(),
    }
    setMessages((prev) => [...prev, newMsg])
    setInputMsg('')
  }

  return (
    <main className="flex-1 overflow-y-auto px-4 sm:px-6 lg:px-8 py-5">
      {/* Hero Live Stream and Chat Section */}
      <section aria-label="Featured Live Stream and Chat" className="mb-10">
        <div className="grid grid-cols-1 xl:grid-cols-12 gap-4 items-stretch">
          {/* Featured Video Player (Left 9 cols) */}
          <div className="xl:col-span-9 bg-black rounded-xl overflow-hidden relative border border-[#1f1f26] flex flex-col justify-between shadow-2xl group min-h-[460px] md:min-h-[500px]">
            {/* Background Snapshot with Ambient Vignette Overlay */}
            <div className="absolute inset-0 z-0">
              <img
                alt={currentStream.title}
                className="w-full h-full object-cover filter brightness-[0.8] contrast-[1.05] transition-all duration-500"
                src={currentStream.snapshot}
              />
              <div className="absolute inset-0 bg-gradient-to-t from-[#000000] via-transparent to-black/60" />
            </div>

            {/* Top Overlays */}
            <div className="relative z-10 p-4 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="inline-flex items-center gap-1.5 bg-red-600/90 backdrop-blur-md text-white text-xs font-bold px-2.5 py-1 rounded-md shadow">
                  <span className="w-2 h-2 rounded-full bg-white animate-ping" />
                  LIVE
                </span>
                <span className="inline-flex items-center gap-1.5 bg-black/70 backdrop-blur-md text-gray-200 text-xs font-medium px-2.5 py-1 rounded-md border border-white/10">
                  <span className="w-2 h-2 rounded-full bg-emerald-400" />
                  {currentStream.viewers} viewers
                </span>
              </div>

              <div className="flex items-center gap-2">
                <span className="px-2.5 py-1 rounded bg-yellow-500/80 text-black font-extrabold text-[11px] tracking-wider uppercase">
                  {currentStream.tag}
                </span>
                <button
                  type="button"
                  onClick={() => setIsMuted(!isMuted)}
                  className="w-8 h-8 rounded-md bg-black/60 backdrop-blur-md text-gray-300 hover:text-white flex items-center justify-center border border-white/10 hover:bg-black/90 transition cursor-pointer"
                >
                  {isMuted ? (
                    <VolumeX className="w-3.5 h-3.5" />
                  ) : (
                    <Volume2 className="w-3.5 h-3.5" />
                  )}
                </button>
              </div>
            </div>

            {/* Bottom Floating Player Details Overlay */}
            <div className="relative z-10 p-4 sm:p-5">
              <div className="bg-[#08080a]/90 backdrop-blur-md rounded-xl p-4 border border-[#1f1f26] flex flex-col md:flex-row md:items-center justify-between gap-4 shadow-xl">
                {/* Streamer Info */}
                <div className="flex items-center gap-3.5 min-w-0">
                  <div className="relative flex-shrink-0">
                    <div className="w-12 h-12 rounded-full bg-gradient-to-tr from-[#ff2b42] to-amber-500 p-0.5">
                      <img
                        alt={currentStream.streamer}
                        className="w-full h-full rounded-full object-cover"
                        src={currentStream.avatar}
                      />
                    </div>
                    <span className="absolute -bottom-1 -right-1 bg-emerald-500 text-[9px] text-black font-bold p-0.5 rounded-full">
                      <CheckCircle2 className="w-3 h-3 text-white" />
                    </span>
                  </div>

                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <h2 className="font-bold text-base text-white tracking-wide truncate">
                        {currentStream.streamer}
                      </h2>
                      <CheckCircle2 className="w-3.5 h-3.5 text-[#ff2b42] flex-shrink-0" />
                    </div>
                    <p className="text-xs sm:text-sm text-gray-300 font-medium line-clamp-1">
                      {currentStream.title}
                    </p>
                    <div className="flex items-center gap-2 mt-1.5 flex-wrap">
                      <span className="text-[11px] font-medium bg-[#101014] text-gray-300 px-2 py-0.5 rounded">
                        {currentStream.category}
                      </span>
                      <span className="text-[11px] font-medium bg-[#101014] text-gray-400 px-2 py-0.5 rounded">
                        {currentStream.language}
                      </span>
                    </div>
                  </div>
                </div>

                {/* Action CTA & Arrows */}
                <div className="flex items-center justify-between md:justify-end gap-4 border-t md:border-t-0 pt-3 md:pt-0 border-gray-700/50 flex-shrink-0">
                  {/* Carousel Dots */}
                  <div className="flex items-center gap-1.5 hidden sm:flex">
                    {featuredStreams.map((_, index) => (
                      <button
                        key={index}
                        type="button"
                        onClick={() => setCurrentSlide(index)}
                        className={`transition-all rounded-full cursor-pointer ${
                          currentSlide === index
                            ? 'w-6 h-1.5 bg-[#ff2b42]'
                            : 'w-1.5 h-1.5 bg-gray-600 hover:bg-gray-400'
                        }`}
                        aria-label={`Slide ${index + 1}`}
                      />
                    ))}
                  </div>

                  {/* Arrow Controls */}
                  <div className="flex items-center gap-1">
                    <button
                      type="button"
                      onClick={handlePrevSlide}
                      aria-label="Previous slide"
                      className="w-8 h-8 rounded-lg bg-[#101014] hover:bg-[#18181f] text-gray-300 hover:text-white flex items-center justify-center transition border border-[#1f1f26] cursor-pointer"
                    >
                      <ChevronLeft className="w-3.5 h-3.5" />
                    </button>
                    <button
                      type="button"
                      onClick={handleNextSlide}
                      aria-label="Next slide"
                      className="w-8 h-8 rounded-lg bg-[#101014] hover:bg-[#18181f] text-gray-300 hover:text-white flex items-center justify-center transition border border-[#1f1f26] cursor-pointer"
                    >
                      <ChevronRight className="w-3.5 h-3.5" />
                    </button>
                  </div>

                  {/* Watch CTA Button */}
                  <button
                    type="button"
                    onClick={() => {
                      toast.info(`Đang phát: ${currentStream.title}`)
                    }}
                    className="px-5 py-2.5 bg-[#ff2b42] hover:bg-[#e02237] text-white font-bold text-xs uppercase tracking-wider rounded-lg flex items-center gap-2 neon-glow-red transition cursor-pointer"
                  >
                    <span>Watch now</span>
                    <Play className="w-2.5 h-2.5 fill-white" />
                  </button>
                </div>
              </div>
            </div>
          </div>

          {/* Embedded Live Chat (Right 3 cols) */}
          <div className="xl:col-span-3 bg-[#08080a] border border-[#1f1f26] rounded-xl flex flex-col justify-between h-[460px] md:h-[500px] overflow-hidden shadow-xl">
            {/* Chat Header */}
            <div className="px-4 py-3 border-b border-[#1f1f26] flex items-center justify-between bg-[#101014]/50">
              <div className="flex items-center gap-2">
                <MessageSquare className="w-4 h-4 text-[#ff2b42]" />
                <h3 className="text-sm font-bold text-white tracking-wide">
                  Stream Chat
                </h3>
              </div>
              <div className="flex items-center gap-2 text-gray-400">
                <button
                  type="button"
                  aria-label="Users"
                  className="hover:text-white text-xs p-1 cursor-pointer"
                >
                  <Users className="w-3.5 h-3.5" />
                </button>
                <button
                  type="button"
                  aria-label="Settings"
                  className="hover:text-white text-xs p-1 cursor-pointer"
                >
                  <Sliders className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>

            {/* Chat Message List */}
            <div className="flex-1 overflow-y-auto p-3 space-y-3 text-xs">
              {messages.map((m) =>
                m.isReply ? (
                  <div
                    key={m.id}
                    className="leading-relaxed pl-2 border-l-2 border-gray-700 text-gray-400 text-[11px]"
                  >
                    <p>
                      Replying to{' '}
                      <span className="text-indigo-300 font-medium">
                        {m.replyTo}
                      </span>
                      : {m.replyText}
                    </p>
                  </div>
                ) : (
                  <div key={m.id} className="leading-relaxed">
                    {m.badge && (
                      <span
                        className={`inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-bold mr-1 ${
                          m.badgeColor || 'bg-yellow-500/20 text-yellow-400'
                        }`}
                      >
                        {m.badge}
                      </span>
                    )}
                    <span
                      className={`font-bold hover:underline cursor-pointer ${m.color}`}
                    >
                      {m.user}:
                    </span>
                    <span className="text-gray-300 pl-1">{m.text}</span>
                  </div>
                ),
              )}
            </div>

            {/* Chat Input Bar */}
            <div className="p-3 border-t border-[#1f1f26] bg-[#050507]">
              <form
                onSubmit={handleSendMessage}
                className="relative flex items-center"
              >
                <input
                  type="text"
                  value={inputMsg}
                  onChange={(e) => setInputMsg(e.target.value)}
                  placeholder={
                    isAuthenticated
                      ? 'Send a message...'
                      : 'Log in to send a message'
                  }
                  className="w-full bg-[#101014] text-xs text-gray-200 placeholder-gray-500 pl-3 pr-16 py-2 rounded-lg border border-[#1f1f26] focus:outline-none focus:border-[#ff2b42] focus:ring-1 focus:ring-[#ff2b42]"
                />
                <div className="absolute right-2 flex items-center gap-1.5 text-gray-400">
                  <button
                    type="button"
                    aria-label="Emoji"
                    className="hover:text-yellow-400 transition cursor-pointer"
                  >
                    <Smile className="w-3.5 h-3.5" />
                  </button>
                  <button
                    type="submit"
                    aria-label="Send"
                    className="hover:text-[#ff2b42] transition cursor-pointer"
                  >
                    <Send className="w-3.5 h-3.5" />
                  </button>
                </div>
              </form>
              <div className="flex items-center justify-between mt-2 text-[11px] text-gray-500">
                <span>Slow mode: 3s</span>
                <button
                  type="button"
                  onClick={() => toast.info('Kênh đang bật quy tắc trò chuyện văn minh.')}
                  className="hover:text-gray-300 cursor-pointer"
                >
                  Rules
                </button>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Streams For You Section */}
      <section aria-label="Streams For You" className="mb-10">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-bold text-white tracking-wide font-outfit">
              <span className="text-[#ff2b42]">Streams</span> For You
            </h2>
            <span className="w-2 h-2 rounded-full bg-[#ff2b42]" />
          </div>
          <a
            href="#viewall"
            className="text-xs font-semibold text-gray-400 hover:text-[#ff2b42] transition flex items-center gap-1"
          >
            <span>View all</span>
            <ChevronRight className="w-3 h-3" />
          </a>
        </div>

        {/* 4 Cards in a row */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          {streamsForYou.map((stream) => (
            <article
              key={stream.id}
              onClick={() => {
                toast.info(`Xem stream: ${stream.title}`)
              }}
              className="bg-[#08080a] border border-[#1f1f26] rounded-xl overflow-hidden hover:border-[#ff2b42]/50 transition duration-200 flex flex-col group cursor-pointer"
            >
              <div className="relative aspect-video w-full overflow-hidden bg-gray-900">
                <img
                  alt={stream.title}
                  className="w-full h-full object-cover group-hover:scale-105 transition duration-300"
                  src={stream.thumbnail}
                />
                <div className="absolute top-2 left-2 flex items-center gap-1.5">
                  <span className="bg-red-600 text-white text-[10px] font-bold px-1.5 py-0.5 rounded shadow">
                    LIVE
                  </span>
                  <span className="bg-black/80 backdrop-blur-sm text-white text-[10px] font-medium px-1.5 py-0.5 rounded border border-white/10 flex items-center gap-1">
                    <Eye className="w-2.5 h-2.5 text-[#ff2b42]" />
                    {stream.viewers}
                  </span>
                </div>
              </div>

              <div className="p-3.5 flex gap-3 flex-1">
                <img
                  alt={stream.channel}
                  className="w-10 h-10 rounded-full object-cover flex-shrink-0 border border-[#1f1f26]"
                  src={stream.avatar}
                />
                <div className="min-w-0 flex-1">
                  <h3
                    className="text-sm font-semibold text-white truncate group-hover:text-[#ff2b42] transition"
                    title={stream.title}
                  >
                    {stream.title}
                  </h3>
                  <p className="text-xs text-gray-400 truncate mt-0.5 flex items-center gap-1">
                    {stream.channel}
                    <CheckCircle2 className="w-2.5 h-2.5 text-[#ff2b42]" />
                  </p>
                  <div className="mt-2 flex flex-wrap gap-1">
                    <span className="text-[11px] font-medium bg-[#101014] text-gray-300 px-2 py-0.5 rounded border border-[#1f1f26]">
                      {stream.category}
                    </span>
                  </div>
                </div>
              </div>
            </article>
          ))}
        </div>
      </section>
    </main>
  )
}
