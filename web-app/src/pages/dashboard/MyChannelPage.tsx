import { useState, useRef, useEffect, useMemo, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { isAxiosError } from 'axios'
import {
  AlertCircle,
  Camera,
  Check,
  Copy,
  Layers,
  Plus,
  RotateCcw,
  Sparkles,
  Tv,
  Users,
  X,
  UserRound,
} from 'lucide-react'
import { toast } from 'sonner'
import { authApi } from '@/api/auth.api'
import { channelApi } from '@/api/channel.api'
import { Button } from '@/components/ui/Button'
import { avatarError } from '@/pages/profile/profile.schema'
import { validateBannerFile, updateChannelSchema } from '@/schemas/channel.schema'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse } from '@/types/auth.types'
import type { CategoryItem, OwnChannel, UpdateChannelPayload } from '@/types/channel.types'

function errorMessage(error: unknown): string {
  if (isAxiosError<ApiResponse>(error)) {
    if (error.response?.status === 401) return 'Your session has expired. Please log in again.'
    if (error.response?.status === 403) return 'You do not have permission to manage this channel.'
    if (error.response?.status === 404) return 'Channel not found.'
    return error.response?.data?.message || 'Unable to connect to the server. Please try again.'
  }
  return 'Something unexpected happened. Please try again.'
}

interface ChannelEditorProps {
  channel: OwnChannel
  allCategories: CategoryItem[]
  isCategoriesLoading: boolean
}

function ChannelEditor({ channel, allCategories, isCategoriesLoading }: ChannelEditorProps) {
  const queryClient = useQueryClient()

  // Draft form states initialized directly from channel prop
  const [displayName, setDisplayName] = useState<string>(channel.name)
  const [avatarFile, setAvatarFile] = useState<File | null>(null)
  const [avatarPreviewUrl, setAvatarPreviewUrl] = useState<string | null>(null)
  const [avatarErrorMsg, setAvatarErrorMsg] = useState<string | null>(null)

  const [description, setDescription] = useState<string>(channel.description || '')
  const [selectedCategoryIds, setSelectedCategoryIds] = useState<string[]>(
    channel.categories.map((c) => c.id),
  )
  const [bannerFile, setBannerFile] = useState<File | null>(null)
  const [bannerPreviewUrl, setBannerPreviewUrl] = useState<string | null>(null)
  const [bannerError, setBannerError] = useState<string | null>(null)
  const [categorySearch, setCategorySearch] = useState<string>('')
  const [isCategoryPickerOpen, setIsCategoryPickerOpen] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [copiedLink, setCopiedLink] = useState(false)

  const avatarInputRef = useRef<HTMLInputElement>(null)
  const bannerInputRef = useRef<HTMLInputElement>(null)
  const categoryPickerRef = useRef<HTMLDivElement>(null)

  // Clean up object preview URLs on unmount or file change
  useEffect(() => {
    return () => {
      if (bannerPreviewUrl) {
        URL.revokeObjectURL(bannerPreviewUrl)
      }
    }
  }, [bannerPreviewUrl])

  useEffect(() => {
    return () => {
      if (avatarPreviewUrl) {
        URL.revokeObjectURL(avatarPreviewUrl)
      }
    }
  }, [avatarPreviewUrl])

  // Close category picker on click outside
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (
        categoryPickerRef.current &&
        !categoryPickerRef.current.contains(event.target as Node)
      ) {
        setIsCategoryPickerOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  // Check dirty state
  const isDirty = useMemo(() => {
    if (displayName.trim() !== channel.name) return true
    if (avatarFile !== null) return true

    const originalDesc = channel.description || ''
    if (originalDesc !== description) return true

    if (bannerFile !== null) return true

    const origCatIds = new Set(channel.categories.map((c) => c.id))
    if (selectedCategoryIds.length !== origCatIds.size) return true
    for (const id of selectedCategoryIds) {
      if (!origCatIds.has(id)) return true
    }

    return false
  }, [channel, displayName, avatarFile, description, bannerFile, selectedCategoryIds])

  // Available categories for dropdown (not already selected, filtered by search)
  const availableCategoriesToAdd = useMemo(() => {
    const selectedSet = new Set(selectedCategoryIds)
    return allCategories
      .filter((cat) => !selectedSet.has(cat.id))
      .filter((cat) =>
        categorySearch.trim().length === 0
          ? true
          : cat.name.toLowerCase().includes(categorySearch.toLowerCase().trim()),
      )
  }, [allCategories, selectedCategoryIds, categorySearch])

  // Map category IDs to summary objects (using allCategories or channel.categories)
  const selectedCategoryDetails = useMemo(() => {
    const categoryMap = new Map<string, string>()
    allCategories.forEach((c) => categoryMap.set(c.id, c.name))
    channel.categories.forEach((c) => categoryMap.set(c.id, c.name))

    return selectedCategoryIds.map((id) => ({
      id,
      name: categoryMap.get(id) || 'Category',
    }))
  }, [allCategories, channel, selectedCategoryIds])

  // Avatar selection handler
  const handleAvatarChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    const validationMsg = avatarError(file)
    if (validationMsg) {
      setAvatarErrorMsg(validationMsg)
      toast.error(validationMsg)
      if (avatarInputRef.current) avatarInputRef.current.value = ''
      return
    }

    setAvatarErrorMsg(null)
    setAvatarFile(file)
    if (avatarPreviewUrl) {
      URL.revokeObjectURL(avatarPreviewUrl)
    }
    setAvatarPreviewUrl(URL.createObjectURL(file))
  }

  const handleResetAvatar = () => {
    if (avatarPreviewUrl) {
      URL.revokeObjectURL(avatarPreviewUrl)
    }
    setAvatarFile(null)
    setAvatarPreviewUrl(null)
    setAvatarErrorMsg(null)
    if (avatarInputRef.current) {
      avatarInputRef.current.value = ''
    }
  }

  // Banner selection handler
  const handleBannerChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    const validationMsg = validateBannerFile(file)
    if (validationMsg) {
      setBannerError(validationMsg)
      toast.error(validationMsg)
      if (bannerInputRef.current) bannerInputRef.current.value = ''
      return
    }

    setBannerError(null)
    setBannerFile(file)
    if (bannerPreviewUrl) {
      URL.revokeObjectURL(bannerPreviewUrl)
    }
    setBannerPreviewUrl(URL.createObjectURL(file))
  }

  const handleResetBanner = () => {
    if (bannerPreviewUrl) {
      URL.revokeObjectURL(bannerPreviewUrl)
    }
    setBannerFile(null)
    setBannerPreviewUrl(null)
    setBannerError(null)
    if (bannerInputRef.current) {
      bannerInputRef.current.value = ''
    }
  }

  // Category selection handlers
  const handleAddCategory = (categoryId: string) => {
    if (!selectedCategoryIds.includes(categoryId)) {
      setSelectedCategoryIds([...selectedCategoryIds, categoryId])
    }
    setCategorySearch('')
  }

  const handleRemoveCategory = (categoryId: string) => {
    if (selectedCategoryIds.length <= 1) {
      toast.warning('A channel must have at least one broadcasting category.')
      return
    }
    setSelectedCategoryIds(selectedCategoryIds.filter((id) => id !== categoryId))
  }

  // Reset all drafts back to saved channel values
  const handleResetAll = () => {
    setDisplayName(channel.name)
    handleResetAvatar()
    setDescription(channel.description || '')
    setSelectedCategoryIds(channel.categories.map((c) => c.id))
    handleResetBanner()
    setSubmitError(null)
    toast.info('Changes reverted to saved channel settings.')
  }

  // Copy channel link
  const handleCopyChannelLink = () => {
    const channelUrl = `${window.location.origin}/browse/channels`
    navigator.clipboard.writeText(channelUrl)
    setCopiedLink(true)
    toast.success('Channel link copied to clipboard.')
    setTimeout(() => setCopiedLink(false), 2000)
  }

  // Form submit: updates profile (user-service) and channel (channel-service)
  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    if (isSubmitting) return

    setSubmitError(null)

    // Validate displayName
    const trimmedDisplayName = displayName.trim()
    if (!trimmedDisplayName) {
      setSubmitError('Display name cannot be empty.')
      toast.error('Display name cannot be empty.')
      return
    }
    if (trimmedDisplayName.length > 100) {
      setSubmitError('Display name must not exceed 100 characters.')
      toast.error('Display name must not exceed 100 characters.')
      return
    }

    // Validate channel inputs
    const trimmedDesc = description.trim()
    const validationResult = updateChannelSchema.safeParse({
      description: trimmedDesc.length > 0 ? trimmedDesc : undefined,
      categoryIds: selectedCategoryIds,
    })

    if (!validationResult.success) {
      const firstIssue = validationResult.error.issues[0]?.message
      setSubmitError(firstIssue || 'Please check your channel input fields.')
      toast.error(firstIssue || 'Validation error')
      return
    }

    setIsSubmitting(true)
    try {
      const isDisplayNameModified = trimmedDisplayName !== channel.name
      const isAvatarModified = avatarFile !== null

      // 1. Update user profile (displayName and/or avatar) via PATCH /user/api/users/me
      if (isDisplayNameModified || isAvatarModified) {
        const profilePayload = new FormData()
        if (isDisplayNameModified) {
          profilePayload.append('displayName', trimmedDisplayName)
        }
        if (avatarFile) {
          profilePayload.append('avatar', avatarFile)
        }

        const updatedProfile = await authApi.updateProfile(profilePayload)
        useAuthStore.getState().setUser(updatedProfile)
      }

      // 2. Update channel details (description, banner, categories) via PATCH /channel/api/channels/me
      const origCatIds = new Set(channel.categories.map((c) => c.id))
      const isCategoriesModified =
        selectedCategoryIds.length !== origCatIds.size ||
        selectedCategoryIds.some((id) => !origCatIds.has(id))
      const isDescModified = trimmedDesc !== (channel.description || '')
      const isBannerModified = bannerFile !== null

      if (isDescModified || isBannerModified || isCategoriesModified) {
        const channelPayload: UpdateChannelPayload = {}

        if (isDescModified) {
          channelPayload.description = trimmedDesc
        }
        if (isBannerModified) {
          channelPayload.banner = bannerFile
        }
        if (isCategoriesModified) {
          channelPayload.categoryIds = selectedCategoryIds
        }

        await channelApi.updateChannel(channelPayload)
      }

      // Invalidate queries so that refreshed channel and profile data are retrieved
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['own-channel'] }),
        queryClient.invalidateQueries({ queryKey: ['profile'] }),
      ])

      toast.success('Channel updated successfully!')
      setBannerFile(null)
      setAvatarFile(null)
      if (bannerPreviewUrl) {
        URL.revokeObjectURL(bannerPreviewUrl)
        setBannerPreviewUrl(null)
      }
      if (avatarPreviewUrl) {
        URL.revokeObjectURL(avatarPreviewUrl)
        setAvatarPreviewUrl(null)
      }
    } catch (err) {
      const msg = errorMessage(err)
      setSubmitError(msg)
      toast.error(msg)
    } finally {
      setIsSubmitting(false)
    }
  }

  const activeAvatar = avatarPreviewUrl || channel.avatarUrl
  const activeBanner = bannerPreviewUrl || channel.bannerUrl
  const isChannelActive = channel.status === 'ACTIVE'

  return (
    <div className="space-y-8">
      {/* Header Title Section */}
      <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl sm:text-3xl font-extrabold tracking-tight text-white font-outfit">
            My Channel
          </h1>
        </div>
        <div className="flex items-center gap-2 pt-2 sm:pt-0">
          <Link
            to="/profile"
            className="inline-flex items-center gap-2 rounded-lg border border-[#22222d] bg-[#101016] px-3 py-2 text-xs font-semibold text-gray-300 hover:bg-[#181822] hover:text-white transition cursor-pointer"
          >
            <UserRound className="h-3.5 w-3.5 text-gray-400" />
            Profile Settings
          </Link>
          <button
            type="button"
            onClick={handleCopyChannelLink}
            className="inline-flex items-center gap-2 rounded-lg border border-[#22222d] bg-[#101016] px-3 py-2 text-xs font-semibold text-gray-300 hover:bg-[#181822] hover:text-white transition cursor-pointer"
          >
            {copiedLink ? <Check className="h-3.5 w-3.5 text-emerald-400" /> : <Copy className="h-3.5 w-3.5" />}
            {copiedLink ? 'Link Copied' : 'Share Channel'}
          </button>
        </div>
      </div>

      {/* Hidden File Input for Avatar */}
      <input
        ref={avatarInputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        aria-label="Upload channel avatar"
        className="hidden"
        onChange={handleAvatarChange}
      />

      {/* Channel Showcase & Hero Banner */}
      <section aria-label="Channel Showcase">
        <div className="group relative overflow-hidden rounded-2xl border border-[#1f1f26] bg-[#0c0c10] shadow-2xl">
          {/* Banner Area */}
          <div className="relative h-44 sm:h-56 md:h-64 w-full overflow-hidden bg-[#0a0a0f]">
            {activeBanner ? (
              <img
                src={activeBanner}
                alt={`${displayName || channel.name} banner`}
                className="h-full w-full object-cover transition duration-300"
              />
            ) : (
              <div className="flex h-full w-full items-center justify-center bg-gradient-to-br from-[#12121a] via-[#0b0b0e] to-[#1c080b]">
                <div className="text-center text-gray-600">
                  <Tv className="mx-auto h-12 w-12 opacity-30 text-[#ff2b42]" />
                  <p className="mt-2 text-xs font-medium text-gray-500">
                    No banner uploaded yet
                  </p>
                </div>
              </div>
            )}

            {/* Gradient Vignette Overlay for readability */}
            <div className="pointer-events-none absolute inset-0 bg-gradient-to-t from-[#0c0c10] via-black/40 to-black/20" />

            {/* Change Banner Action Button Overlay */}
            <div className="absolute right-4 top-4 flex items-center gap-2">
              {bannerFile && (
                <button
                  type="button"
                  onClick={handleResetBanner}
                  title="Revert banner"
                  className="flex items-center gap-1.5 rounded-lg border border-red-500/40 bg-black/75 px-3 py-1.5 text-xs font-medium text-red-300 hover:bg-black/90 transition backdrop-blur-md cursor-pointer"
                >
                  <RotateCcw className="h-3.5 w-3.5" />
                  Revert
                </button>
              )}
              <button
                type="button"
                onClick={() => bannerInputRef.current?.click()}
                className="flex items-center gap-1.5 rounded-lg border border-white/20 bg-black/60 px-3.5 py-1.5 text-xs font-semibold text-white shadow-lg backdrop-blur-md hover:bg-black/80 hover:border-white/40 transition cursor-pointer"
              >
                <Camera className="h-3.5 w-3.5 text-[#ff2b42]" />
                {activeBanner ? 'Change Banner' : 'Upload Banner'}
              </button>
              <input
                ref={bannerInputRef}
                type="file"
                accept="image/jpeg,image/png,image/webp"
                aria-label="Upload channel banner"
                className="hidden"
                onChange={handleBannerChange}
              />
            </div>
          </div>

          {/* Overlaid Channel Identity Row */}
          <div className="relative px-5 pb-5 pt-2 sm:px-6 sm:pb-6">
            <div className="flex items-end gap-4 -mt-14 sm:-mt-16">
              {/* Avatar with Camera Overlay */}
              <div className="relative group/avatar flex h-20 w-20 sm:h-24 sm:w-24 shrink-0 items-center justify-center overflow-hidden rounded-2xl border-4 border-[#0c0c10] bg-[#181822] shadow-2xl">
                {activeAvatar ? (
                  <img
                    src={activeAvatar}
                    alt={displayName || channel.name}
                    className="h-full w-full object-cover"
                  />
                ) : (
                  <UserRound className="h-10 w-10 text-gray-500" />
                )}
                <button
                  type="button"
                  aria-label="Change channel avatar"
                  onClick={() => avatarInputRef.current?.click()}
                  className="absolute inset-0 flex flex-col items-center justify-center bg-black/65 opacity-0 group-hover/avatar:opacity-100 transition backdrop-blur-xs cursor-pointer"
                >
                  <Camera className="h-5 w-5 text-white" />
                  <span className="text-[10px] font-semibold text-white mt-1">Change</span>
                </button>
              </div>

              {/* Name, Status & Follower Count */}
              <div className="space-y-1">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-xl sm:text-2xl font-black text-white font-outfit tracking-tight">
                    {displayName || channel.name}
                  </h2>
                  <span
                    className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-[11px] font-bold ${
                      isChannelActive
                        ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/30'
                        : 'bg-amber-500/10 text-amber-400 border border-amber-500/30'
                    }`}
                  >
                    <span
                      className={`h-1.5 w-1.5 rounded-full ${
                        isChannelActive ? 'bg-emerald-400 animate-pulse' : 'bg-amber-400'
                      }`}
                    />
                    {channel.status}
                  </span>
                </div>

                <div className="flex items-center gap-1.5 text-xs text-gray-400">
                  <Users className="h-3.5 w-3.5 text-gray-400" />
                  <strong className="text-white">
                    {channel.numberOfFollower.toLocaleString()}
                  </strong>{' '}
                  followers
                </div>
              </div>
            </div>

            {/* Categories Pills (Placed below) */}
            {selectedCategoryDetails.length > 0 && (
              <div className="flex flex-wrap items-center gap-1.5 mt-4">
                {selectedCategoryDetails.map((cat) => (
                  <span
                    key={cat.id}
                    className="inline-flex items-center gap-1 rounded-md border border-[#232330] bg-[#14141d] px-2.5 py-1 text-xs font-medium text-gray-200"
                  >
                    <Layers className="h-3 w-3 text-[#ff2b42]" />
                    {cat.name}
                  </span>
                ))}
              </div>
            )}

            {/* Bio Preview */}
            {description && (
              <div className="mt-3 pt-3 border-t border-[#1c1c24] text-xs text-gray-300 leading-relaxed max-w-3xl">
                {description}
              </div>
            )}
          </div>
        </div>
      </section>

      {/* Edit Channel Form */}
      <form onSubmit={handleSubmit} className="space-y-6" noValidate>
        {submitError && (
          <div
            role="alert"
            className="flex items-start gap-3 rounded-xl border border-red-500/40 bg-red-500/10 p-4 text-sm text-red-400 shadow-md"
          >
            <AlertCircle className="h-5 w-5 shrink-0 mt-0.5" />
            <div className="flex-1">
              <p className="font-semibold">Update failed</p>
              <p className="mt-0.5 text-xs text-red-300/90">{submitError}</p>
            </div>
          </div>
        )}

        {avatarErrorMsg && (
          <div
            role="alert"
            className="flex items-start gap-3 rounded-xl border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-300 shadow-md"
          >
            <AlertCircle className="h-5 w-5 shrink-0 mt-0.5" />
            <p className="text-xs">{avatarErrorMsg}</p>
          </div>
        )}

        {bannerError && (
          <div
            role="alert"
            className="flex items-start gap-3 rounded-xl border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-300 shadow-md"
          >
            <AlertCircle className="h-5 w-5 shrink-0 mt-0.5" />
            <p className="text-xs">{bannerError}</p>
          </div>
        )}

        <div className="space-y-6">
          {/* Channel Identity (Display Name & Avatar) */}
          <div className="rounded-2xl border border-[#1f1f26] bg-[#0c0c10] p-6 shadow-xl space-y-4">
            <h3 className="text-base font-bold text-white font-outfit">
              Channel Identity
            </h3>

            <div className="flex flex-col sm:flex-row items-start sm:items-center gap-5">
              <div className="relative group/avatar flex h-16 w-16 shrink-0 items-center justify-center overflow-hidden rounded-2xl border-2 border-[#1f1f26] bg-[#181822]">
                {activeAvatar ? (
                  <img
                    src={activeAvatar}
                    alt={displayName || channel.name}
                    className="h-full w-full object-cover"
                  />
                ) : (
                  <UserRound className="h-8 w-8 text-gray-500" />
                )}
                <button
                  type="button"
                  aria-label="Change avatar"
                  onClick={() => avatarInputRef.current?.click()}
                  className="absolute inset-0 flex items-center justify-center bg-black/60 opacity-0 group-hover/avatar:opacity-100 transition cursor-pointer"
                >
                  <Camera className="h-4 w-4 text-white" />
                </button>
              </div>

              <div className="flex-1 w-full space-y-1.5">
                <div className="flex items-center justify-between">
                  <label className="text-xs font-semibold text-gray-300">
                    Channel Display Name
                  </label>
                  <span className="text-xs font-mono text-gray-500">
                    {displayName.length}/100
                  </span>
                </div>
                <input
                  type="text"
                  value={displayName}
                  maxLength={100}
                  onChange={(e) => setDisplayName(e.target.value)}
                  placeholder="Enter your channel display name"
                  className="w-full rounded-xl border border-[#1f1f26] bg-[#121217] px-4 py-2.5 text-sm text-gray-100 placeholder-gray-500 transition focus:border-[#ff2b42] focus:outline-none focus:ring-1 focus:ring-[#ff2b42]"
                />
              </div>
            </div>

            {avatarFile && (
              <div className="flex items-center gap-2 pt-1 text-xs text-amber-300">
                <span>New avatar selected: {avatarFile.name}</span>
                <button
                  type="button"
                  onClick={handleResetAvatar}
                  className="text-red-400 hover:underline cursor-pointer"
                >
                  Revert
                </button>
              </div>
            )}
          </div>

          {/* Channel Bio / Description */}
          <div className="rounded-2xl border border-[#1f1f26] bg-[#0c0c10] p-6 shadow-xl space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-base font-bold text-white font-outfit">
                Channel Description / Bio
              </h3>
              <span
                className={`text-xs font-mono font-medium ${
                  description.length > 1000
                    ? 'text-red-400'
                    : description.length > 900
                    ? 'text-amber-400'
                    : 'text-gray-500'
                }`}
              >
                {description.length}/1,000
              </span>
            </div>

            <textarea
              rows={4}
              value={description}
              maxLength={1000}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Welcome to my channel! I stream competitive gaming, live discussions, and interact with the community every Tuesday and Thursday..."
              className="w-full resize-y rounded-xl border border-[#1f1f26] bg-[#121217] px-4 py-3 text-sm text-gray-100 placeholder-gray-500 transition focus:border-[#ff2b42] focus:outline-none focus:ring-1 focus:ring-[#ff2b42]"
            />
          </div>

          {/* Streaming Categories Selector */}
          <div className="rounded-2xl border border-[#1f1f26] bg-[#0c0c10] p-6 shadow-xl space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-base font-bold text-white font-outfit">
                Broadcasting Categories
              </h3>
              <span className="text-xs font-medium text-gray-400">
                {selectedCategoryIds.length} selected
              </span>
            </div>

            {/* Selected Category Tags */}
            <div className="space-y-3">
              <div className="flex flex-wrap gap-2">
                {selectedCategoryDetails.map((cat) => (
                  <span
                    key={cat.id}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-[#2b2b3a] bg-[#151520] pl-3 pr-2 py-1.5 text-xs font-medium text-white shadow-sm"
                  >
                    <span>{cat.name}</span>
                    <button
                      type="button"
                      aria-label={`Remove category ${cat.name}`}
                      onClick={() => handleRemoveCategory(cat.id)}
                      className="rounded p-0.5 text-gray-400 hover:bg-[#252535] hover:text-red-400 transition cursor-pointer"
                    >
                      <X className="h-3.5 w-3.5" />
                    </button>
                  </span>
                ))}
              </div>

              {/* Category Picker Dropdown */}
              <div ref={categoryPickerRef} className="relative">
                <button
                  type="button"
                  onClick={() => setIsCategoryPickerOpen((prev) => !prev)}
                  className="inline-flex items-center gap-2 rounded-xl border border-[#262633] bg-[#121217] px-3.5 py-2 text-xs font-semibold text-gray-200 hover:border-[#ff2b42]/50 hover:bg-[#181820] transition cursor-pointer"
                >
                  <Plus className="h-3.5 w-3.5 text-[#ff2b42]" />
                  Add Broadcasting Category
                </button>

                {isCategoryPickerOpen && (
                  <div className="absolute left-0 top-full z-20 mt-2 w-72 rounded-xl border border-[#242432] bg-[#101016] p-2 shadow-2xl backdrop-blur-xl">
                    <input
                      type="text"
                      value={categorySearch}
                      onChange={(e) => setCategorySearch(e.target.value)}
                      placeholder="Search categories..."
                      className="w-full rounded-lg border border-[#232330] bg-[#161622] px-3 py-1.5 text-xs text-white placeholder-gray-500 focus:border-[#ff2b42] focus:outline-none"
                      autoFocus
                    />

                    <div className="mt-2 max-h-48 overflow-y-auto space-y-1 overscroll-contain">
                      {isCategoriesLoading ? (
                        <p className="px-3 py-2 text-xs text-gray-500">
                          Loading categories...
                        </p>
                      ) : availableCategoriesToAdd.length === 0 ? (
                        <p className="px-3 py-2 text-xs text-gray-500">
                          No matching categories found
                        </p>
                      ) : (
                        availableCategoriesToAdd.map((cat) => (
                          <button
                            key={cat.id}
                            type="button"
                            onClick={() => handleAddCategory(cat.id)}
                            className="flex w-full items-center justify-between rounded-lg px-3 py-1.5 text-left text-xs text-gray-200 hover:bg-[#1c1c28] hover:text-white transition cursor-pointer"
                          >
                            <span>{cat.name}</span>
                            <Plus className="h-3 w-3 text-gray-500" />
                          </button>
                        ))
                      )}
                    </div>
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* Persistent Action Bar */}
        <div className="flex flex-col-reverse sm:flex-row sm:items-center sm:justify-end gap-3 pt-4 border-t border-[#1f1f26]">
          <Button
            type="button"
            variant="ghost"
            disabled={!isDirty || isSubmitting}
            onClick={handleResetAll}
          >
            Reset changes
          </Button>
          <Button
            type="submit"
            variant="primary"
            isLoading={isSubmitting}
            disabled={!isDirty || isSubmitting}
          >
            Save Channel Settings
          </Button>
        </div>
      </form>
    </div>
  )
}

export function MyChannelPage() {
  // Queries
  const {
    data: channel,
    isLoading: isChannelLoading,
    error: channelError,
    refetch: refetchChannel,
  } = useQuery<OwnChannel>({
    queryKey: ['own-channel'],
    queryFn: ({ signal }) => channelApi.getOwnChannel(signal),
    staleTime: 1000 * 60 * 5,
  })

  const {
    data: allCategories = [],
    isLoading: isCategoriesLoading,
  } = useQuery<CategoryItem[]>({
    queryKey: ['categories'],
    queryFn: ({ signal }) => channelApi.getCategories(signal),
    staleTime: 1000 * 60 * 10,
  })

  // Loading Skeleton State
  if (isChannelLoading) {
    return (
      <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
        <div className="mx-auto max-w-6xl space-y-6">
          <div className="space-y-2">
            <div className="h-8 w-48 rounded-lg bg-[#14141a] animate-pulse" />
          </div>
          <div className="h-64 w-full rounded-2xl bg-[#14141a] animate-pulse" />
          <div className="space-y-6">
            <div className="h-32 rounded-2xl bg-[#14141a] animate-pulse" />
            <div className="h-44 rounded-2xl bg-[#14141a] animate-pulse" />
            <div className="h-44 rounded-2xl bg-[#14141a] animate-pulse" />
          </div>
        </div>
      </main>
    )
  }

  // Not a Streamer / 404 / 403 Error State
  if (channelError || !channel) {
    const isForbiddenOrNotFound =
      isAxiosError(channelError) &&
      (channelError.response?.status === 404 || channelError.response?.status === 403)

    return (
      <main className="min-w-0 flex-1 px-4 py-8 sm:px-6 lg:px-8">
        <div className="mx-auto max-w-2xl py-12 text-center">
          <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-2xl bg-[#16161e] border border-[#22222d] text-[#ff2b42] shadow-xl">
            <Tv className="h-8 w-8" />
          </div>
          <h1 className="mt-5 text-2xl font-black tracking-tight text-white font-outfit">
            {isForbiddenOrNotFound ? 'Streamer Channel Not Found' : 'Channel Unavailable'}
          </h1>
          <p className="mt-2 text-sm text-gray-400 max-w-md mx-auto">
            {isForbiddenOrNotFound
              ? 'You do not have an active streamer channel registered with this account yet. Register as a streamer to start broadcasting.'
              : errorMessage(channelError)}
          </p>

          <div className="mt-6 flex items-center justify-center gap-3">
            {isForbiddenOrNotFound ? (
              <Link to="/become-streamer">
                <Button variant="primary">
                  <Sparkles className="h-4 w-4 mr-1.5" />
                  Become a Streamer
                </Button>
              </Link>
            ) : (
              <Button variant="secondary" onClick={() => refetchChannel()}>
                Try again
              </Button>
            )}
          </div>
        </div>
      </main>
    )
  }

  return (
    <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <ChannelEditor
          key={channel.id}
          channel={channel}
          allCategories={allCategories}
          isCategoriesLoading={isCategoriesLoading}
        />
      </div>
    </main>
  )
}
