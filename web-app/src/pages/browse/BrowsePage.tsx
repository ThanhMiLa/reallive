import { NavLink, Outlet } from 'react-router-dom'
import { Radio, LayoutGrid, Users } from 'lucide-react'

const tabs = [
  {
    name: 'Livestreams',
    path: '/browse/livestreams',
    icon: Radio,
  },
  {
    name: 'Categories',
    path: '/browse/categories',
    icon: LayoutGrid,
  },
  {
    name: 'Channels',
    path: '/browse/channels',
    icon: Users,
  },
]

export function BrowsePage() {
  return (
    <main className="flex-1 overflow-y-auto px-4 sm:px-6 lg:px-8 py-6 select-none">
      {/* Top Section: Header & Live Metrics */}
      <div className="flex flex-col gap-6 mb-6">
        <div className="flex flex-col md:flex-row md:items-end justify-between gap-4">
          <div>
            <span className="text-[#00f0ff] text-xs font-bold uppercase tracking-widest block mb-1">
              Discovery Hub
            </span>
            <h1 className="text-3xl sm:text-4xl font-extrabold text-white tracking-tight font-outfit">
              Browse
            </h1>
          </div>

          {/* Live Status Pill */}
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-2 bg-[#151822] border border-[#232838] px-3.5 py-1.5 rounded-full text-xs text-gray-300">
              <span className="w-2 h-2 rounded-full bg-[#00f0ff] animate-pulse" />
              <span>
                <strong className="text-white font-semibold">1,482</strong>{' '}
                Channels Broadcasting
              </span>
            </div>
          </div>
        </div>

        {/* Navigation Tabs (Livestreams / Categories / Channels) */}
        <div className="flex items-center gap-8 border-b border-[#232838] pt-1">
          {tabs.map((tab) => {
            const Icon = tab.icon
            return (
              <NavLink
                key={tab.path}
                to={tab.path}
                className={({ isActive }) =>
                  `group relative pb-3.5 text-sm sm:text-base flex items-center gap-2 transition cursor-pointer ${
                    isActive
                      ? 'text-[#00f0ff] font-bold border-b-2 border-[#00f0ff]'
                      : 'text-gray-400 hover:text-white font-medium'
                  }`
                }
              >
                <Icon className="w-4 h-4" />
                <span>{tab.name}</span>
              </NavLink>
            )
          })}
        </div>
      </div>

      {/* Sub-view Content Outlet */}
      <Outlet />
    </main>
  )
}
