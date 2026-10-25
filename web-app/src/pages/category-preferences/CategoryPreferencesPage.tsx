import { useState, useMemo, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { Check, Gamepad2, LogIn, Search, X } from 'lucide-react'
import { toast } from 'sonner'
import { categoryPreferencesApi } from '@/api/category-preferences.api'
import { channelApi } from '@/api/channel.api'
import { Button } from '@/components/ui/Button'
import { categoryPreferencesSchema } from '@/schemas/category-preferences.schema'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse } from '@/types/auth.types'
import type { CategoryPreferences } from '@/types/category-preferences.types'
import type { CategoryItem } from '@/types/channel.types'

const FALLBACK_GRADIENTS = [
  {
    bg: 'from-rose-950/70 via-[#101014] to-[#050507]',
    iconBg: 'bg-rose-500/10',
    iconColor: 'text-[#ff2b42]',
    border: 'border-[#ff2b42]/30',
  },
  {
    bg: 'from-purple-950/70 via-[#101014] to-[#050507]',
    iconBg: 'bg-purple-500/10',
    iconColor: 'text-purple-400',
    border: 'border-purple-500/30',
  },
  {
    bg: 'from-emerald-950/70 via-[#101014] to-[#050507]',
    iconBg: 'bg-emerald-500/10',
    iconColor: 'text-emerald-400',
    border: 'border-emerald-500/30',
  },
  {
    bg: 'from-indigo-950/70 via-[#101014] to-[#050507]',
    iconBg: 'bg-indigo-500/10',
    iconColor: 'text-indigo-400',
    border: 'border-indigo-500/30',
  },
  {
    bg: 'from-red-950/70 via-[#101014] to-[#050507]',
    iconBg: 'bg-red-500/10',
    iconColor: 'text-red-400',
    border: 'border-red-500/30',
  },
  {
    bg: 'from-amber-950/70 via-[#101014] to-[#050507]',
    iconBg: 'bg-amber-500/10',
    iconColor: 'text-amber-400',
    border: 'border-amber-500/30',
  },
]

function getCategoryGradient(name: string) {
  const colorIndex =
    Math.abs(
      name
        .split('')
        .reduce((acc, char) => acc + char.charCodeAt(0), 0),
    ) % FALLBACK_GRADIENTS.length
  return FALLBACK_GRADIENTS[colorIndex]
}

function SelectableCategoryCard({
  category,
  isSelected,
  onToggle,
  disabled,
}: {
  category: CategoryItem
  isSelected: boolean
  onToggle: () => void
  disabled?: boolean
}) {
  const [imgError, setImgError] = useState(false)
  const hasThumbnail = Boolean(
    category.thumbnailUrl && category.thumbnailUrl.trim() !== '' && !imgError,
  )
  const gradient = getCategoryGradient(category.name)

  return (
    <div
      role="checkbox"
      aria-checked={isSelected}
      tabIndex={disabled ? -1 : 0}
      title={category.name}
      onClick={() => {
        if (!disabled) onToggle()
      }}
      onKeyDown={(e) => {
        if (!disabled && (e.key === ' ' || e.key === 'Enter')) {
          e.preventDefault()
          onToggle()
        }
      }}
      className={`group flex flex-col transition-transform duration-200 select-none outline-none ${
        disabled
          ? 'cursor-not-allowed opacity-40'
          : 'cursor-pointer hover:-translate-y-1'
      }`}
    >
      {/* Category Thumbnail / Poster */}
      <div
        className={`relative w-full aspect-[3/4] rounded-xl overflow-hidden bg-[#101014] shadow-md transition-all duration-300 ${
          isSelected
            ? 'border-2 border-[#ff2b42] shadow-[0_0_20px_rgba(255,43,66,0.35)] ring-1 ring-[#ff2b42]'
            : 'border border-[#1f1f26] group-hover:border-[#ff2b42]/50 group-hover:shadow-[0_0_20px_rgba(255,43,66,0.15)]'
        }`}
      >
        {/* Selection Badge at Top Right */}
        <div className="absolute top-2 right-2 z-20">
          {isSelected ? (
            <div className="flex h-6 w-6 items-center justify-center rounded-full bg-[#ff2b42] text-white shadow-lg ring-2 ring-black/40">
              <Check className="h-3.5 w-3.5 stroke-[3]" />
            </div>
          ) : !disabled ? (
            <div className="flex h-6 w-6 items-center justify-center rounded-full border border-white/30 bg-black/50 text-white/40 opacity-0 group-hover:opacity-100 transition backdrop-blur-xs">
              <Check className="h-3.5 w-3.5" />
            </div>
          ) : null}
        </div>

        {hasThumbnail ? (
          <>
            <img
              src={category.thumbnailUrl!}
              alt={category.name}
              className={`w-full h-full object-cover transition-transform duration-300 ${
                isSelected ? 'scale-105' : 'group-hover:scale-105'
              }`}
              onError={() => setImgError(true)}
            />
            <div
              className={`absolute inset-0 bg-gradient-to-t from-black/85 via-transparent to-transparent pointer-events-none transition-opacity ${
                isSelected ? 'opacity-90' : 'opacity-70'
              }`}
            />
          </>
        ) : (
          <div
            className={`w-full h-full bg-gradient-to-br ${gradient.bg} flex flex-col items-center justify-center p-3 relative overflow-hidden`}
          >
            <div className="absolute -top-10 -right-10 w-28 h-28 rounded-full bg-white/5 blur-xl pointer-events-none" />
            <div className="relative z-10 flex flex-col items-center text-center gap-2">
              <div
                className={`w-11 h-11 sm:w-12 sm:h-12 rounded-xl ${gradient.iconBg} flex items-center justify-center border ${gradient.border} shadow-lg`}
              >
                <Gamepad2 className={`w-5 h-5 sm:w-6 sm:h-6 ${gradient.iconColor}`} />
              </div>
              <span className="text-[11px] sm:text-xs font-bold uppercase tracking-wider text-gray-300/90 line-clamp-2 px-1">
                {category.name}
              </span>
            </div>
          </div>
        )}
      </div>

      {/* Category Name below */}
      <div className="pt-2 flex flex-col min-w-0">
        <h3
          className={`font-semibold text-xs sm:text-sm transition-colors truncate ${
            isSelected
              ? 'text-[#ff2b42]'
              : 'text-gray-200 group-hover:text-white'
          }`}
        >
          {category.name}
        </h3>
      </div>
    </div>
  )
}

function PreferencesForm({ userId, categories, preferences }: {
  userId: string
  categories: CategoryItem[]
  preferences: CategoryPreferences
}) {
  const client = useQueryClient()
  const [draft, setDraft] = useState<string[] | null>(null)
  const [categorySearch, setCategorySearch] = useState('')
  const [isSaving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const availableIds = new Set(categories.map((category) => category.id))
  const selectedIds = new Set((draft ?? preferences.categoryIds).filter((id) => availableIds.has(id)))
  const savedIds = new Set(preferences.categoryIds)
  const isDirty = selectedIds.size !== savedIds.size || [...selectedIds].some((id) => !savedIds.has(id))
  const hasUnavailable = preferences.categoryIds.some((id) => !availableIds.has(id))
  const isValid = categoryPreferencesSchema.safeParse({ categoryIds: [...selectedIds] }).success

  const filteredCategories = useMemo(() => {
    if (!categorySearch.trim()) return categories
    const query = categorySearch.toLowerCase().trim()
    return categories.filter((cat) => cat.name.toLowerCase().includes(query))
  }, [categories, categorySearch])

  const handleToggleCategory = (categoryId: string) => {
    const next = new Set(selectedIds)
    if (next.has(categoryId)) {
      next.delete(categoryId)
    } else if (next.size < 20) {
      next.add(categoryId)
    }
    setDraft([...next])
    setError(null)
  }

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (isSaving) return
    const validation = categoryPreferencesSchema.safeParse({ categoryIds: [...selectedIds] })
    if (!validation.success) {
      setError(validation.error.issues[0].message)
      return
    }
    setError(null)
    setSaving(true)
    try {
      const result = await categoryPreferencesApi.update(validation.data.categoryIds)
      if (useAuthStore.getState().user?.userId !== userId) return
      client.setQueryData(['category-preferences', userId], result)
      setDraft(null)
      void client.invalidateQueries({ queryKey: ['channels', 'unfollowed', userId] })
      toast.success('Category interests saved.')
    } catch (cause) {
      setError(isAxiosError<ApiResponse>(cause)
        ? cause.response?.data?.message || 'Unable to connect to the server. Please try again.'
        : 'Unable to save your preferences. Please try again.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <form onSubmit={save} className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="text-xs sm:text-sm text-gray-400">Choose between 1 and 20 categories.</p>
        <span
          className="self-start sm:self-auto rounded-full bg-[#181822] border border-[#232330] px-3 py-1 text-xs font-semibold text-gray-300"
          aria-live="polite"
        >
          {selectedIds.size} / 20 selected
        </span>
      </div>

      {hasUnavailable && (
        <p role="status" className="text-xs text-amber-400">
          Some saved categories are no longer available. Save your selection to update your preferences.
        </p>
      )}

      {error && (
        <p role="alert" className="rounded-lg border border-red-500/30 bg-red-500/10 p-3 text-sm text-red-400">
          {error}
        </p>
      )}

      {/* Category Search Filter */}
      <div className="relative max-w-sm">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-500" />
        <input
          type="text"
          value={categorySearch}
          onChange={(e) => setCategorySearch(e.target.value)}
          placeholder="Search categories..."
          className="w-full rounded-xl border border-[#1f1f26] bg-[#121217] pl-9 pr-9 py-2 text-xs text-white placeholder-gray-500 focus:border-[#ff2b42] focus:outline-none transition"
        />
        {categorySearch && (
          <button
            type="button"
            onClick={() => setCategorySearch('')}
            aria-label="Clear category search"
            className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-500 hover:text-white transition cursor-pointer"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        )}
      </div>

      {/* Categories Grid */}
      {filteredCategories.length === 0 ? (
        <div className="rounded-xl border border-[#1f1f26] bg-[#101014] py-12 text-center">
          <Gamepad2 className="mx-auto h-8 w-8 text-gray-600 mb-2" />
          <p className="text-xs text-gray-400">
            {categorySearch.trim()
              ? `No categories matching "${categorySearch}".`
              : 'No categories available right now.'}
          </p>
          {categorySearch && (
            <button
              type="button"
              onClick={() => setCategorySearch('')}
              className="mt-2 text-xs font-semibold text-[#ff2b42] hover:underline cursor-pointer"
            >
              Clear filter
            </button>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3 sm:gap-4">
          {filteredCategories.map((category) => {
            const checked = selectedIds.has(category.id)
            const disabled = !checked && selectedIds.size >= 20
            return (
              <SelectableCategoryCard
                key={category.id}
                category={category}
                isSelected={checked}
                disabled={isSaving || disabled}
                onToggle={() => handleToggleCategory(category.id)}
              />
            )
          })}
        </div>
      )}

      {/* Action Bar */}
      <div className="flex flex-wrap justify-end gap-3 border-t border-[#1f1f26] pt-4">
        <Button
          type="button"
          size="sm"
          variant="secondary"
          disabled={!isDirty || isSaving}
          onClick={() => {
            setDraft(null)
            setError(null)
          }}
        >
          Cancel changes
        </Button>
        <Button
          type="submit"
          size="sm"
          isLoading={isSaving}
          disabled={!isDirty || !isValid}
        >
          Save changes
        </Button>
      </div>
    </form>
  )
}

export function CategoryPreferencesPage() {
  const { user, isAuthenticated, isCheckingAuth, openAuthModal } = useAuthStore()
  const enabled = isAuthenticated && !isCheckingAuth && Boolean(user?.userId)
  const categories = useQuery({
    queryKey: ['categories'],
    queryFn: ({ signal }) => channelApi.getCategories(signal),
    enabled,
  })
  const preferences = useQuery({
    queryKey: ['category-preferences', user?.userId],
    queryFn: ({ signal }) => categoryPreferencesApi.get(signal),
    enabled,
    gcTime: 0,
    refetchOnReconnect: false,
  })

  return (
    <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl space-y-7 pb-10">
        <div>
          <h1 className="text-3xl font-extrabold tracking-tight text-white sm:text-4xl font-outfit">Category Interests</h1>
        </div>
        <section aria-label="Category Interests" className="rounded-2xl border border-[#1f1f26] bg-[#08080a] p-5 sm:p-7 shadow-xl">
          {isCheckingAuth ? (
            <p role="status" className="text-sm text-gray-400">Checking your session…</p>
          ) : !isAuthenticated ? (
            <div className="space-y-4">
              <p className="text-sm text-gray-300">Log in to choose your favorite categories.</p>
              <Button onClick={() => openAuthModal('login')} leftIcon={<LogIn className="h-4 w-4" />}>Log in</Button>
            </div>
          ) : categories.isError || preferences.isError ? (
            <div className="space-y-4">
              <p role="alert" className="text-sm text-red-400">Unable to load your categories and preferences. Please try again.</p>
              <Button variant="secondary" size="sm" isLoading={categories.isFetching || preferences.isFetching} onClick={() => {
                if (categories.isError) void categories.refetch()
                if (preferences.isError) void preferences.refetch()
              }}>Try again</Button>
            </div>
          ) : categories.isPending || preferences.isPending ? (
            <div className="space-y-6">
              <div className="h-4 w-48 bg-[#14141a] rounded animate-pulse" />
              <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3 sm:gap-4">
                {Array.from({ length: 12 }).map((_, idx) => (
                  <div key={idx} className="flex flex-col gap-2">
                    <div className="w-full aspect-[3/4] rounded-xl bg-[#14141a] border border-[#1f1f26] animate-pulse" />
                    <div className="h-3.5 w-3/4 bg-[#14141a] rounded animate-pulse" />
                  </div>
                ))}
              </div>
            </div>
          ) : categories.data.length === 0 ? (
            <p className="text-sm text-gray-400">No categories are available right now. Please check back later.</p>
          ) : (
            user && <PreferencesForm key={user.userId} userId={user.userId} categories={categories.data} preferences={preferences.data} />
          )}
        </section>
      </div>
    </main>
  )
}
