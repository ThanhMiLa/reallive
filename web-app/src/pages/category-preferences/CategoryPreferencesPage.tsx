import { useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { Gamepad2, LogIn } from 'lucide-react'
import { toast } from 'sonner'
import { categoryPreferencesApi } from '@/api/category-preferences.api'
import { channelApi } from '@/api/channel.api'
import { Button } from '@/components/ui/Button'
import { categoryPreferencesSchema } from '@/schemas/category-preferences.schema'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse } from '@/types/auth.types'
import type { CategoryPreferences } from '@/types/category-preferences.types'
import type { CategoryItem } from '@/types/channel.types'

function CategoryThumbnail({ category }: { category: CategoryItem }) {
  const [failed, setFailed] = useState(false)
  return (
    <div className="flex h-20 w-14 shrink-0 items-center justify-center overflow-hidden rounded-md bg-[#18181f]">
      {category.thumbnailUrl && !failed ? (
        <img src={category.thumbnailUrl} alt="" className="h-full w-full object-cover" onError={() => setFailed(true)} />
      ) : <Gamepad2 className="h-6 w-6 text-gray-500" />}
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
  const [isSaving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const availableIds = new Set(categories.map((category) => category.id))
  const selectedIds = new Set((draft ?? preferences.categoryIds).filter((id) => availableIds.has(id)))
  const savedIds = new Set(preferences.categoryIds)
  const isDirty = selectedIds.size !== savedIds.size || [...selectedIds].some((id) => !savedIds.has(id))
  const hasUnavailable = preferences.categoryIds.some((id) => !availableIds.has(id))
  const isValid = categoryPreferencesSchema.safeParse({ categoryIds: [...selectedIds] }).success

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
    <form onSubmit={save} className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
        <p className="text-gray-400">Choose between 1 and 20 categories.</p>
        <p className="font-medium text-gray-300" aria-live="polite">{selectedIds.size} / 20 selected</p>
      </div>
      {hasUnavailable && <p role="status" className="text-xs text-amber-400">Some saved categories are no longer available. Save your selection to update your preferences.</p>}
      {error && <p role="alert" className="rounded-lg border border-red-500/30 bg-red-500/10 p-3 text-sm text-red-400">{error}</p>}
      <fieldset disabled={isSaving} className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3 disabled:opacity-60">
        <legend className="sr-only">Favorite categories</legend>
        {categories.map((category) => {
          const checked = selectedIds.has(category.id)
          const disabled = !checked && selectedIds.size >= 20
          return (
            <label key={category.id} className={`flex items-center gap-3 rounded-lg border p-3 ${checked ? 'border-[#ff2b42] bg-[#ff2b42]/5' : 'border-[#1f1f26] bg-[#101014]'} ${disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer hover:border-[#ff2b42]/60'}`}>
              <CategoryThumbnail key={category.thumbnailUrl || category.id} category={category} />
              <span className="min-w-0 flex-1 break-words text-sm font-medium text-gray-200">{category.name}</span>
              <input type="checkbox" className="h-4 w-4 shrink-0 cursor-pointer accent-[#ff2b42] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#ff2b42] disabled:cursor-not-allowed"
                checked={checked} disabled={disabled}
                onChange={() => {
                  const next = new Set(selectedIds)
                  if (checked) next.delete(category.id)
                  else if (next.size < 20) next.add(category.id)
                  setDraft([...next])
                  setError(null)
                }} />
            </label>
          )
        })}
      </fieldset>
      <div className="flex flex-wrap justify-end gap-2 border-t border-[#1f1f26] pt-3">
        <Button type="button" size="sm" variant="secondary" disabled={!isDirty || isSaving} onClick={() => { setDraft(null); setError(null) }}>Cancel changes</Button>
        <Button type="submit" size="sm" isLoading={isSaving} disabled={!isDirty || !isValid}>Save changes</Button>
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
      <div className="mx-auto max-w-5xl space-y-7 pb-10">
        <div>
          <h1 className="text-3xl font-extrabold tracking-tight text-white sm:text-4xl font-outfit">Category Interests</h1>
          <p className="mt-2 text-sm text-gray-400">Choose the categories you enjoy to personalize your recommendations.</p>
        </div>
        <section aria-label="Category Interests" className="rounded-xl border border-[#1f1f26] bg-[#08080a] p-5 sm:p-7">
          {isCheckingAuth ? <p role="status" className="text-sm text-gray-400">Checking your session…</p>
            : !isAuthenticated ? (
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
            ) : categories.isPending || preferences.isPending ? <p role="status" className="text-sm text-gray-400">Loading your categories…</p>
              : categories.data.length === 0 ? <p className="text-sm text-gray-400">No categories are available right now. Please check back later.</p>
                : user && <PreferencesForm key={user.userId} userId={user.userId} categories={categories.data} preferences={preferences.data} />}
        </section>
      </div>
    </main>
  )
}
