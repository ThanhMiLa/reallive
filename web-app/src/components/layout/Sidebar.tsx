import { Home, Compass, Heart, Gift, Settings } from 'lucide-react'

interface StreamerItem {
  id: string
  name: string
  category: string
  avatar: string
  isLive: boolean
}

const followingStreamers: StreamerItem[] = [
  {
    id: '1',
    name: 'BenjaminTran',
    category: 'Just Chatting',
    avatar:
      'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100&h=100&fit=crop&crop=faces',
    isLive: true,
  },
  {
    id: '2',
    name: 'Shyvana',
    category: 'Valorant',
    avatar:
      'https://images.unsplash.com/photo-1517841905240-472988babdf9?w=100&h=100&fit=crop&crop=faces',
    isLive: true,
  },
  {
    id: '3',
    name: 'Kaelen',
    category: 'Counter-Strike 2',
    avatar:
      'https://images.unsplash.com/photo-1539571696357-5a69c17a67c6?w=100&h=100&fit=crop&crop=faces',
    isLive: true,
  },
]

const recommendedStreamers: StreamerItem[] = [
  {
    id: '4',
    name: 'bladeito',
    category: 'Counter-Strike 2',
    avatar:
      'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=100&h=100&fit=crop&crop=faces',
    isLive: true,
  },
  {
    id: '5',
    name: 'Astaturo',
    category: 'GTA V',
    avatar:
      'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=100&h=100&fit=crop&crop=faces',
    isLive: true,
  },
  {
    id: '6',
    name: 'FrankDimes',
    category: 'Dota 2',
    avatar:
      'https://images.unsplash.com/photo-1522075469751-3a6694fb2f61?w=100&h=100&fit=crop&crop=faces',
    isLive: true,
  },
]

export function Sidebar() {
  return (
    <aside className="w-64 bg-[#0f1118] border-r border-[#232838] flex flex-col justify-between hidden lg:flex select-none flex-shrink-0">
      <div className="overflow-y-auto px-3 py-3 space-y-6">
        {/* Primary Navigation */}
        <nav className="space-y-1">
          <a
            href="/"
            className="flex items-center gap-3.5 px-3 py-2.5 rounded-lg text-sm font-semibold bg-[#151822] text-[#00f0ff] border-l-4 border-[#00f0ff] shadow-sm"
          >
            <Home className="w-4 h-4 text-center" />
            <span>Home</span>
          </a>
          <a
            href="#browse"
            className="flex items-center gap-3.5 px-3 py-2.5 rounded-lg text-sm font-medium text-gray-300 hover:text-white hover:bg-[#151822] transition"
          >
            <Compass className="w-4 h-4 text-center text-gray-400" />
            <span>Browse</span>
          </a>
          <a
            href="#following"
            className="flex items-center gap-3.5 px-3 py-2.5 rounded-lg text-sm font-medium text-gray-300 hover:text-white hover:bg-[#151822] transition"
          >
            <Heart className="w-4 h-4 text-center text-gray-400" />
            <span>Following</span>
          </a>
          <a
            href="#rewards"
            className="flex items-center gap-3.5 px-3 py-2.5 rounded-lg text-sm font-medium text-gray-300 hover:text-white hover:bg-[#151822] transition"
          >
            <Gift className="w-4 h-4 text-center text-gray-400" />
            <span>Drops &amp; Rewards</span>
          </a>
        </nav>

        {/* Following Section */}
        <div>
          <div className="flex items-center justify-between px-3 mb-2">
            <span className="text-xs font-bold text-gray-400 tracking-wider">
              FOLLOWING
            </span>
          </div>
          <div className="space-y-1">
            {followingStreamers.map((streamer) => (
              <div
                key={streamer.id}
                className="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-[#151822] transition group cursor-pointer"
              >
                <div className="relative flex-shrink-0">
                  <img
                    alt={streamer.name}
                    className="w-8 h-8 rounded-full object-cover"
                    src={streamer.avatar}
                  />
                  {streamer.isLive && (
                    <span className="absolute bottom-0 right-0 w-2 h-2 bg-emerald-400 rounded-full border border-[#0f1118]" />
                  )}
                </div>
                <div className="flex flex-col min-w-0">
                  <span className="text-xs font-semibold text-gray-200 truncate group-hover:text-white">
                    {streamer.name}
                  </span>
                  <span className="text-[11px] text-gray-400 truncate">
                    {streamer.category}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Recommended Section */}
        <div>
          <div className="flex items-center justify-between px-3 mb-2">
            <span className="text-xs font-bold text-gray-400 tracking-wider">
              RECOMMENDED
            </span>
          </div>
          <div className="space-y-1">
            {recommendedStreamers.map((streamer) => (
              <div
                key={streamer.id}
                className="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-[#151822] transition group cursor-pointer"
              >
                <div className="relative flex-shrink-0">
                  <img
                    alt={streamer.name}
                    className="w-8 h-8 rounded-full object-cover"
                    src={streamer.avatar}
                  />
                  {streamer.isLive && (
                    <span className="absolute bottom-0 right-0 w-2 h-2 bg-emerald-400 rounded-full border border-[#0f1118]" />
                  )}
                </div>
                <div className="flex flex-col min-w-0">
                  <span className="text-xs font-semibold text-gray-200 truncate group-hover:text-white">
                    {streamer.name}
                  </span>
                  <span className="text-[11px] text-gray-400 truncate">
                    {streamer.category}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Sidebar Footer */}
      <div className="p-3 border-t border-[#232838] bg-[#0c0d14] flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-pulse" />
          <span className="text-xs font-medium text-gray-300">
            Live Network Online
          </span>
        </div>
        <Settings className="w-3.5 h-3.5 text-gray-400 hover:text-white cursor-pointer" />
      </div>
    </aside>
  )
}
