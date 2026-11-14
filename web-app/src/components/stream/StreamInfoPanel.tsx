import { useEffect, useState } from 'react'
import { ChevronDown, Gamepad2, Info, Pencil, X } from 'lucide-react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Button } from '@/components/ui/Button'
import { streamDetailsSchema } from '@/schemas/stream.schema'
import type { CategoryItem } from '@/types/channel.types'
import type { StreamConfigStatus, StreamDetails } from '@/types/stream.types'

interface Props {
  initial: StreamDetails
  categories: CategoryItem[]
  categoriesLoading: boolean
  categoriesError: boolean
  onRetryCategories: () => void
  configStatus?: StreamConfigStatus
  pending: boolean
  onSubmit: (value: StreamDetails) => Promise<boolean>
}

function EditStreamInfoModal({
  initial,
  categories,
  categoriesLoading,
  categoriesError,
  onRetryCategories,
  configStatus,
  pending,
  onSubmit,
  onClose,
}: Props & { onClose: () => void }) {
  const {
    register,
    handleSubmit,
    formState: { errors, isDirty },
    control,
    reset,
  } = useForm<StreamDetails>({
    resolver: zodResolver(streamDetailsSchema),
    defaultValues: initial,
  })
  const title = useWatch({ control, name: 'title' }) || ''
  const categoryId = useWatch({ control, name: 'categoryId' })

  useEffect(() => {
    if (!isDirty) reset({ title: initial.title, categoryId: initial.categoryId })
  }, [initial.title, initial.categoryId, isDirty, reset])

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !pending) {
        onClose()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onClose, pending])

  const selectedCategory = categories.find((category) => category.id === categoryId)
  const missingCategory = categoryId && !selectedCategory
  const needsSetup = configStatus === 'DRAFT' || configStatus === 'BLOCKED'
  const canSubmitClean = needsSetup && Boolean(initial.title.trim() || initial.categoryId)

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="edit-stream-info-title"
      onClick={(event) => {
        if (event.target === event.currentTarget && !pending) onClose()
      }}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4 backdrop-blur-xs"
    >
      <div className="w-full max-w-md rounded-2xl border border-[#1f1f26] bg-[#0a0a0d] shadow-2xl">
        <div className="flex items-center justify-between border-b border-[#1f1f26] px-6 py-4">
          <h2 id="edit-stream-info-title" className="font-outfit text-lg font-bold text-white">
            Edit Stream Info
          </h2>
          <button
            type="button"
            disabled={pending}
            onClick={onClose}
            aria-label="Close"
            className="rounded-lg p-1.5 text-gray-400 transition hover:bg-white/5 hover:text-white disabled:opacity-50 cursor-pointer"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <form
          className="space-y-5 p-6"
          onSubmit={handleSubmit(async (value) => {
            if (await onSubmit(value)) {
              reset(value)
              onClose()
            }
          })}
        >
          <fieldset disabled={pending} className="space-y-5 disabled:opacity-65">
            <div>
              <label
                htmlFor="stream-title"
                className="mb-1.5 block text-xs font-semibold text-gray-300"
              >
                Stream title <span className="text-[#ff2b42]">*</span>
              </label>
              <textarea
                id="stream-title"
                rows={3}
                maxLength={255}
                placeholder="What are you streaming today?"
                aria-invalid={Boolean(errors.title)}
                className="w-full resize-none rounded-lg border border-[#1f1f26] bg-[#101014] px-3.5 py-2.5 text-sm text-white placeholder-gray-500 transition focus:border-[#ff2b42] focus:outline-none focus:ring-1 focus:ring-[#ff2b42]"
                {...register('title')}
              />
              <div className="mt-1 flex items-center justify-between gap-2">
                {errors.title ? (
                  <p className="text-[11px] font-medium text-red-400">{errors.title.message}</p>
                ) : (
                  <span />
                )}
                <p className="text-right text-xs text-gray-400">{title.length} / 255</p>
              </div>
            </div>

            <div>
              <label
                htmlFor="stream-category"
                className="mb-1.5 block text-xs font-semibold text-gray-300"
              >
                Category <span className="text-[#ff2b42]">*</span>
              </label>
              <div className="flex items-center gap-2.5">
                {selectedCategory?.thumbnailUrl && (
                  <img
                    src={selectedCategory.thumbnailUrl}
                    alt=""
                    aria-hidden="true"
                    className="h-10 w-8 shrink-0 rounded border border-[#1f1f26] object-cover"
                  />
                )}
                <div className="relative min-w-0 flex-1">
                  <select
                    id="stream-category"
                    {...register('categoryId')}
                    className="w-full appearance-none rounded-lg border border-[#1f1f26] bg-[#101014] py-2.5 pl-3.5 pr-10 text-sm text-white focus-visible:outline-2 focus-visible:outline-[#ff2b42] cursor-pointer"
                  >
                    <option value="">
                      {categoriesLoading ? 'Loading categories…' : 'Choose a category'}
                    </option>
                    {missingCategory && (
                      <option value={categoryId} disabled>
                        Current category unavailable
                      </option>
                    )}
                    {categories.map((category) => (
                      <option key={category.id} value={category.id}>
                        {category.name}
                      </option>
                    ))}
                  </select>
                  <ChevronDown className="pointer-events-none absolute right-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
                </div>
              </div>
              {errors.categoryId && (
                <p className="mt-1 text-xs text-red-400">{errors.categoryId.message}</p>
              )}
              {categoriesError && (
                <p className="mt-2 text-sm text-amber-300">
                  Categories unavailable.{' '}
                  <button type="button" onClick={onRetryCategories} className="underline">
                    Try again
                  </button>
                </p>
              )}
            </div>
          </fieldset>

          <div className="flex items-center justify-end gap-3 pt-1">
            <Button type="button" variant="secondary" disabled={pending} onClick={onClose}>
              Cancel
            </Button>
            <Button
              type="submit"
              isLoading={pending}
              variant="primary"
              disabled={
                pending ||
                categoriesLoading ||
                categoriesError ||
                (!isDirty && !canSubmitClean && configStatus !== 'DRAFT')
              }
            >
              {pending ? 'Saving…' : 'Save'}
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}

export function StreamInfoPanel(props: Props) {
  const [isEditing, setIsEditing] = useState(false)
  const { initial, categories, categoriesLoading } = props

  const selectedCategory = categories.find((category) => category.id === initial.categoryId)
  const categoryLabel = selectedCategory
    ? selectedCategory.name
    : initial.categoryId
      ? categoriesLoading
        ? 'Loading category…'
        : 'Current category unavailable'
      : 'No category selected'

  return (
    <>
      <section
        aria-label="Stream Information"
        className="rounded-xl border border-brand-border bg-brand-card"
      >
        <div className="flex items-center justify-between gap-2 border-b border-brand-border px-4 py-3">
          <div className="flex items-center gap-2">
            <Info className="h-4 w-4 text-gray-400" />
            <h2 className="font-outfit text-sm font-bold text-white">Stream Information</h2>
          </div>
          <button
            type="button"
            onClick={() => setIsEditing(true)}
            aria-label="Edit stream information"
            title="Edit stream information"
            className="rounded-lg p-1.5 text-gray-400 transition hover:bg-white/5 hover:text-white focus-visible:outline-2 focus-visible:outline-[#ff2b42] cursor-pointer"
          >
            <Pencil className="h-4 w-4" />
          </button>
        </div>

        <div className="space-y-4 p-4">
          <div>
            {initial.title.trim() ? (
              <p className="text-sm font-semibold leading-snug text-white break-words">
                {initial.title}
              </p>
            ) : (
              <p className="text-sm italic text-gray-500">No stream title set</p>
            )}
          </div>

          <div className="flex items-center gap-2.5">
            <div className="flex h-14 w-11 shrink-0 items-center justify-center overflow-hidden rounded-md border border-brand-border bg-[#101014]">
              {selectedCategory?.thumbnailUrl ? (
                <img
                  src={selectedCategory.thumbnailUrl}
                  alt={selectedCategory.name}
                  className="h-full w-full object-cover"
                />
              ) : (
                <Gamepad2 className="h-5 w-5 text-gray-500" />
              )}
            </div>
            <div className="min-w-0 flex-1">
              <p
                className={`truncate text-sm font-medium ${
                  selectedCategory ? 'text-gray-200' : 'text-gray-400'
                }`}
              >
                {categoryLabel}
              </p>
            </div>
          </div>

          {(!initial.title.trim() || !initial.categoryId) && (
            <Button
              type="button"
              variant="secondary"
              size="sm"
              className="w-full"
              onClick={() => setIsEditing(true)}
            >
              Set title &amp; category
            </Button>
          )}
        </div>
      </section>

      {isEditing && <EditStreamInfoModal {...props} onClose={() => setIsEditing(false)} />}
    </>
  )
}
