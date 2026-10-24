export function StreamerDashboardPage() {
  return (
    <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl space-y-6">
        <div>
          <h1 className="text-2xl sm:text-3xl font-extrabold tracking-tight text-white font-outfit">
            Stream Manager
          </h1>
          <p className="mt-1 text-sm text-gray-400">
            Manage your channel broadcasts and live streaming settings
          </p>
        </div>

        {/* Dashboard frame / container placeholder */}
        <div className="min-h-[500px] rounded-2xl border border-dashed border-[#1f1f26] bg-[#0c0c10]/60 p-8 flex items-center justify-center text-center">
          <div className="space-y-2">
            <p className="text-base font-semibold text-gray-300">Streamer Dashboard Content Frame</p>
            <p className="text-xs text-gray-500">Components and stream widgets will be built here</p>
          </div>
        </div>
      </div>
    </main>
  )
}
