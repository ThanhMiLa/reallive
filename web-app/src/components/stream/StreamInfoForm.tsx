import { useEffect } from 'react'
import { ChevronDown } from 'lucide-react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { streamDetailsSchema } from '@/schemas/stream.schema'
import type { CategoryItem } from '@/types/channel.types'
import type { StreamDetails, StreamStatus } from '@/types/stream.types'

interface Props {
  initial: StreamDetails
  categories: CategoryItem[]
  categoriesLoading: boolean
  categoriesError: boolean
  onRetryCategories: () => void
  editing: boolean
  disabled: boolean
  pending: boolean
  sessionStatus?: StreamStatus
  onEndStream?: () => void
  endPending?: boolean
  onSubmit: (value: StreamDetails) => Promise<boolean>
}

export function StreamInfoForm({
  initial,
  categories,
  categoriesLoading,
  categoriesError,
  onRetryCategories,
  editing,
  disabled,
  pending,
  sessionStatus,
  onEndStream,
  endPending = false,
  onSubmit,
}: Props) {
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
  const missingCategory = categoryId && !categories.some((category) => category.id === categoryId)
  return (
    <section className="rounded-2xl border border-brand-border bg-brand-card p-5 sm:p-6">
      <div className="mb-5 flex items-center justify-between gap-3">
        <h2 className="font-outfit text-lg font-bold text-white">Stream information</h2>
        <span className="rounded-full border border-brand-border px-3 py-1 text-xs text-gray-300">
          Public
        </span>
      </div>
      <form
        className="space-y-5"
        onSubmit={handleSubmit(async (value) => {
          if (await onSubmit(value)) reset(value)
        })}
      >
        <fieldset disabled={disabled || pending} className="space-y-5 disabled:opacity-65">
          <div>
            <Input
              id="stream-title"
              label="Stream title"
              maxLength={255}
              placeholder="What are you streaming today?"
              error={errors.title?.message}
              {...register('title')}
            />
            <p className="mt-1 text-right text-xs text-gray-400">{title.length} / 255</p>
          </div>
          <div>
            <label
              htmlFor="stream-category"
              className="mb-1.5 block text-xs font-semibold text-gray-300"
            >
              Category
            </label>
            <div className="relative">
              <select
                id="stream-category"
                {...register('categoryId')}
                className="w-full appearance-none rounded-lg border border-brand-border bg-[#101014] pl-3.5 pr-10 py-2.5 text-sm text-white focus-visible:outline-2 focus-visible:outline-[#ff2b42] cursor-pointer"
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
              <ChevronDown className="pointer-events-none absolute right-4 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
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
        {!disabled && (
          <div className="flex items-center justify-end gap-3">
            {editing && onEndStream && (
              <Button
                type="button"
                variant="danger"
                disabled={endPending || pending}
                onClick={onEndStream}
              >
                {sessionStatus === 'READY' ? 'Cancel session' : 'End stream'}
              </Button>
            )}
            <Button
              type="submit"
              isLoading={pending}
              variant={editing ? 'secondary' : 'success'}
              disabled={
                (editing && !isDirty) || (!editing && (categoriesError || categoriesLoading))
              }
            >
              {pending
                ? editing
                  ? 'Saving…'
                  : 'Creating…'
                : editing
                  ? 'Save changes'
                  : 'Create stream'}
            </Button>
          </div>
        )}
      </form>
    </section>
  )
}
