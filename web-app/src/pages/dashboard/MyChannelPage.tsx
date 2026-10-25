import { useState, useRef, useEffect, useMemo, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { isAxiosError } from 'axios'
import {
  AlertCircle,
  Camera,
  Check,
  ChevronDown,
  ChevronUp,
  Copy,
  Gamepad2,
  Layers,
  Pencil,
  RotateCcw,
  Search,
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
      tabIndex={0}
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
      className={`group flex flex-col cursor-pointer transition-transform duration-200 hover:-translate-y-1 select-none outline-none ${
        disabled ? 'cursor-not-allowed opacity-50' : ''
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
          ) : (
            <div className="flex h-6 w-6 items-center justify-center rounded-full border border-white/30 bg-black/50 text-white/40 opacity-0 group-hover:opacity-100 transition backdrop-blur-xs">
              <Check className="h-3.5 w-3.5" />
            </div>
          )}
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

interface ChannelEditorProps {
  channel: OwnChannel
  allCategories: CategoryItem[]
  isCategoriesLoading: boolean
}

function ChannelEditor({ channel, allCategories, isCategoriesLoading }: ChannelEditorProps) {
  const queryClient = useQueryClient()

  // Draft form states initialized directly from channel prop
  const [displayName, setDisplayName] = useState<string>(channel.name)
  const [isEditingName, setIsEditingName] = useState(false)
  const [avatarFile, setAvatarFile] = useState<File | null>(null)
  const [avatarPreviewUrl, setAvatarPreviewUrl] = useState<string | null>(null)
  const [avatarErrorMsg, setAvatarErrorMsg] = useState<string | null>(null)

  const [description, setDescription] = useState<string>(channel.description || '')
  const [isEditingBio, setIsEditingBio] = useState(false)
  const [selectedCategoryIds, setSelectedCategoryIds] = useState<string[]>(
    channel.categories.map((c) => c.id),
  )
  const [bannerFile, setBannerFile] = useState<File | null>(null)
  const [bannerPreviewUrl, setBannerPreviewUrl] = useState<string | null>(null)
  const [bannerError, setBannerError] = useState<string | null>(null)
  const [categorySearch, setCategorySearch] = useState<string>('')
  const [isCategoriesExpanded, setIsCategoriesExpanded] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [copiedLink, setCopiedLink] = useState(false)

  const avatarInputRef = useRef<HTMLInputElement>(null)
  const bannerInputRef = useRef<HTMLInputElement>(null)
  const categoriesSectionRef = useRef<HTMLDivElement>(null)

  // Expand categories and scroll to the categories section
  const handleOpenCategoriesEditor = () => {
    setIsCategoriesExpanded(true)
    setTimeout(() => {
      categoriesSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }, 50)
  }

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

  // Filtered categories for selection grid
  const filteredCategories = useMemo(() => {
    if (!categorySearch.trim()) return allCategories
    const query = categorySearch.toLowerCase().trim()
    return allCategories.filter((cat) => cat.name.toLowerCase().includes(query))
  }, [allCategories, categorySearch])

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

  // Category selection handler (toggle checkbox)
  const handleToggleCategory = (categoryId: string) => {
    if (selectedCategoryIds.includes(categoryId)) {
      if (selectedCategoryIds.length <= 1) {
        toast.warning('A channel must have at least one broadcasting category.')
        return
      }
      setSelectedCategoryIds((prev) => prev.filter((id) => id !== categoryId))
    } else {
      setSelectedCategoryIds((prev) => [...prev, categoryId])
    }
  }

  // Reset all drafts back to saved channel values
  const handleResetAll = () => {
    setDisplayName(channel.name)
    setIsEditingName(false)
    handleResetAvatar()
    setDescription(channel.description || '')
    setIsEditingBio(false)
    setSelectedCategoryIds(channel.categories.map((c) => c.id))
    setIsCategoriesExpanded(false)
    setCategorySearch('')
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
                  {isEditingName ? (
                    <div className="flex items-center gap-2">
                      <input
                        type="text"
                        value={displayName}
                        maxLength={100}
                        onChange={(e) => setDisplayName(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') {
                            e.preventDefault()
                            setIsEditingName(false)
                          } else if (e.key === 'Escape') {
                            setDisplayName(channel.name)
                            setIsEditingName(false)
                          }
                        }}
                        onBlur={() => setIsEditingName(false)}
                        autoFocus
                        className="rounded-lg border border-[#ff2b42] bg-[#121217] px-2.5 py-1 text-xl sm:text-2xl font-black text-white font-outfit tracking-tight focus:outline-none focus:ring-1 focus:ring-[#ff2b42] max-w-xs sm:max-w-sm"
                      />
                      <button
                        type="button"
                        onMouseDown={(e) => {
                          e.preventDefault()
                          setIsEditingName(false)
                        }}
                        className="rounded-lg bg-[#181822] p-1.5 text-emerald-400 hover:bg-[#20202e] border border-[#262636] transition cursor-pointer"
                        title="Done editing name"
                      >
                        <Check className="h-4 w-4" />
                      </button>
                    </div>
                  ) : (
                    <button
                      type="button"
                      onClick={() => setIsEditingName(true)}
                      className="group/name flex items-center gap-2 text-left cursor-pointer rounded-lg hover:bg-white/5 px-2 -mx-2 py-0.5 transition"
                      title="Click to change channel name"
                    >
                      <h2 className="text-xl sm:text-2xl font-black text-white font-outfit tracking-tight">
                        {displayName || channel.name}
                      </h2>
                      <Pencil className="h-4 w-4 text-gray-500 opacity-60 group-hover/name:opacity-100 group-hover/name:text-[#ff2b42] transition" />
                    </button>
                  )}
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

                <div className="flex flex-wrap items-center gap-3 text-xs text-gray-400">
                  <div className="flex items-center gap-1.5">
                    <Users className="h-3.5 w-3.5 text-gray-400" />
                    <strong className="text-white">
                      {channel.numberOfFollower.toLocaleString()}
                    </strong>{' '}
                    followers
                  </div>
                  {avatarFile && (
                    <button
                      type="button"
                      onClick={handleResetAvatar}
                      className="inline-flex items-center gap-1 text-[11px] text-amber-300 hover:text-red-400 transition cursor-pointer"
                      title="Revert avatar to saved picture"
                    >
                      <RotateCcw className="h-3 w-3" />
                      Revert new avatar
                    </button>
                  )}
                </div>
              </div>
            </div>

            {/* Categories Pills (Placed below) */}
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

              <button
                type="button"
                onClick={handleOpenCategoriesEditor}
                className="group/cat-btn flex items-center justify-center rounded-lg hover:bg-white/5 p-1.5 transition cursor-pointer"
                title="Click to edit broadcasting categories"
                aria-label="Edit broadcasting categories"
              >
                <Pencil className="h-3.5 w-3.5 text-gray-500 opacity-60 group-hover/cat-btn:opacity-100 group-hover/cat-btn:text-[#ff2b42] transition" />
              </button>
            </div>

            {/* Bio Section (Inline Editable) */}
            <div className="mt-3 pt-3 border-t border-[#1c1c24] max-w-3xl">
              {isEditingBio ? (
                <div className="space-y-2">
                  <textarea
                    rows={3}
                    value={description}
                    maxLength={1000}
                    onChange={(e) => setDescription(e.target.value)}
                    onKeyDown={(e) => {
                      if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
                        e.preventDefault()
                        setIsEditingBio(false)
                      } else if (e.key === 'Escape') {
                        setDescription(channel.description || '')
                        setIsEditingBio(false)
                      }
                    }}
                    autoFocus
                    placeholder="Add a bio to introduce your channel..."
                    className="w-full resize-y rounded-xl border border-[#ff2b42] bg-[#121217] p-3 text-xs text-gray-200 placeholder-gray-500 focus:outline-none focus:ring-1 focus:ring-[#ff2b42]"
                  />
                  <div className="flex items-center justify-between">
                    <span className="text-[11px] font-mono text-gray-500">
                      {description.length}/1,000 characters
                    </span>
                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        onClick={() => {
                          setDescription(channel.description || '')
                          setIsEditingBio(false)
                        }}
                        className="rounded-lg px-2.5 py-1 text-xs text-gray-400 hover:text-white hover:bg-white/5 transition cursor-pointer"
                      >
                        Cancel
                      </button>
                      <button
                        type="button"
                        onClick={() => setIsEditingBio(false)}
                        className="inline-flex items-center gap-1 rounded-lg bg-[#ff2b42] px-3 py-1 text-xs font-semibold text-white hover:bg-[#e02438] transition cursor-pointer"
                      >
                        <Check className="h-3.5 w-3.5" />
                        Done
                      </button>
                    </div>
                  </div>
                </div>
              ) : (
                <button
                  type="button"
                  onClick={() => setIsEditingBio(true)}
                  className="group/bio w-full text-left rounded-lg p-2 -m-2 hover:bg-white/5 transition cursor-pointer flex items-start justify-between gap-3"
                  title="Click to edit channel bio"
                >
                  <p
                    className={`text-xs leading-relaxed ${
                      description ? 'text-gray-300' : 'text-gray-500 italic'
                    }`}
                  >
                    {description || 'Click to add a channel bio / description...'}
                  </p>
                  <Pencil className="h-3.5 w-3.5 shrink-0 text-gray-500 opacity-60 group-hover/bio:opacity-100 group-hover/bio:text-[#ff2b42] transition mt-0.5" />
                </button>
              )}
            </div>
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
          {/* Broadcasting Categories Selector */}
          <div
            ref={categoriesSectionRef}
            className="rounded-2xl border border-[#1f1f26] bg-[#0c0c10] p-6 shadow-xl space-y-4 scroll-mt-6"
          >
            {/* Header Accordion Toggle */}
            <button
              type="button"
              onClick={() => setIsCategoriesExpanded((prev) => !prev)}
              className="flex w-full items-center justify-between text-left cursor-pointer group"
            >
              <div className="space-y-0.5">
                <div className="flex items-center gap-2.5">
                  <h3 className="text-base font-bold text-white font-outfit">
                    Broadcasting Categories
                  </h3>
                  <span className="rounded-full bg-[#181822] border border-[#232330] px-2.5 py-0.5 text-xs font-semibold text-gray-300">
                    {selectedCategoryIds.length} selected
                  </span>
                </div>
                <p className="text-xs text-gray-400">
                  {isCategoriesExpanded
                    ? 'Choose categories that represent your live broadcasts. Select at least 1 category.'
                    : 'Click to open and manage your broadcasting categories.'}
                </p>
              </div>

              <div className="flex items-center gap-2">
                <span className="text-xs font-semibold text-[#ff2b42] group-hover:underline hidden sm:inline">
                  {isCategoriesExpanded ? 'Collapse' : 'Edit Categories'}
                </span>
                <div className="rounded-lg bg-[#14141d] p-1.5 text-gray-400 group-hover:text-white border border-[#232330] transition">
                  {isCategoriesExpanded ? (
                    <ChevronUp className="h-4 w-4" />
                  ) : (
                    <ChevronDown className="h-4 w-4" />
                  )}
                </div>
              </div>
            </button>

            {/* Collapsed View: Preview Pills */}
            {!isCategoriesExpanded && selectedCategoryDetails.length > 0 && (
              <div className="flex flex-wrap items-center gap-2 pt-3 border-t border-[#1a1a24]">
                {selectedCategoryDetails.map((cat) => (
                  <span
                    key={cat.id}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-[#262636] bg-[#14141c] px-3 py-1.5 text-xs font-medium text-gray-200"
                  >
                    <Layers className="h-3.5 w-3.5 text-[#ff2b42]" />
                    {cat.name}
                  </span>
                ))}
              </div>
            )}

            {/* Expanded View: Search + Full Category Grid (No inner vertical scroll) */}
            {isCategoriesExpanded && (
              <div className="space-y-5 pt-3 border-t border-[#1a1a24]">
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

                {/* Category Grid: Full Height matching Browse Categories */}
                {isCategoriesLoading ? (
                  <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3 sm:gap-4">
                    {Array.from({ length: 12 }).map((_, idx) => (
                      <div key={idx} className="flex flex-col gap-2">
                        <div className="w-full aspect-[3/4] rounded-xl bg-[#101014] border border-[#1f1f26] animate-pulse" />
                        <div className="h-3.5 w-3/4 bg-[#101014] rounded animate-pulse" />
                      </div>
                    ))}
                  </div>
                ) : filteredCategories.length === 0 ? (
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
                      const checked = selectedCategoryIds.includes(category.id)
                      return (
                        <SelectableCategoryCard
                          key={category.id}
                          category={category}
                          isSelected={checked}
                          disabled={isSubmitting}
                          onToggle={() => handleToggleCategory(category.id)}
                        />
                      )
                    })}
                  </div>
                )}

                {/* Bottom Collapse Button */}
                <div className="flex justify-end pt-2 border-t border-[#1a1a24]">
                  <button
                    type="button"
                    onClick={() => setIsCategoriesExpanded(false)}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-[#262636] bg-[#14141c] px-3.5 py-1.5 text-xs font-semibold text-gray-300 hover:bg-[#1a1a24] hover:text-white transition cursor-pointer"
                  >
                    <ChevronUp className="h-3.5 w-3.5" />
                    Collapse categories
                  </button>
                </div>
              </div>
            )}
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
