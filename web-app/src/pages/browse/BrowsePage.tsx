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
      {/* Top Section: Header */}
      <div className="flex flex-col gap-6 mb-6">
        <div>
          <h1 className="text-3xl sm:text-4xl font-extrabold text-white tracking-tight font-outfit">
            Browse
          </h1>
        </div>

        {/* Navigation Tabs (Livestreams / Categories / Channels) */}
        <div className="flex items-center gap-8 border-b border-[#1f1f26] pt-1">
          {tabs.map((tab) => {
            const Icon = tab.icon
            return (
              <NavLink
                key={tab.path}
                to={tab.path}
                className={({ isActive }) =>
                  `group relative pb-3.5 text-sm sm:text-base flex items-center gap-2 transition cursor-pointer ${
                    isActive
                      ? 'text-[#ff2b42] font-bold border-b-2 border-[#ff2b42]'
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
