import { useCallback, useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { channelApi } from '@/api/channel.api'
import {
  chatModerationApi,
  parseChatModerationError,
} from '@/api/chat-moderation.api'
import type {
  BanChatUserResponse,
  ChatModerationContext,
  TargetChatBanStatus,
  UnbanChatUserResponse,
} from '@/types/chat-moderation.types'

export const DEFAULT_BANS_PAGE_SIZE = 20

export const chatModerationKeys = {
  all: ['chat-moderation'] as const,
  ownChannel: (userId: string) =>
    ['chat-moderation', 'own-channel', userId] as const,
  context: (userId: string, channelId: string) =>
    ['chat-moderation', 'context', userId, channelId] as const,
  targetBan: (userId: string, channelId: string, targetUserId: string) =>
    ['chat-moderation', 'target-ban', userId, channelId, targetUserId] as const,
  bansByChannel: (userId: string, channelId: string) =>
    ['chat-moderation', 'bans', userId, channelId] as const,
  bansPage: (
    userId: string,
    channelId: string,
    page: number,
    size: number,
  ) => ['chat-moderation', 'bans', userId, channelId, page, size] as const,
}

export function clearChatModerationCache(client: QueryClient) {
  const filters = { queryKey: chatModerationKeys.all }
  void client.cancelQueries(filters)
  client.removeQueries(filters)
}

function revokeCapability(
  queryClient: QueryClient,
  userId: string,
  channelId: string,
  capability: 'canBan' | 'canUnban' | 'canViewBans',
) {
  if (!userId || !channelId) return
  const contextKey = chatModerationKeys.context(userId, channelId)
  queryClient.setQueryData<ChatModerationContext>(contextKey, (previous) =>
    previous
      ? { ...previous, [capability]: false }
      : {
          channelId,
          ownerUserId: '',
          canBan: false,
          canUnban: false,
          canViewBans: false,
        },
  )
  void queryClient.invalidateQueries({ queryKey: contextKey })
}

export function useOwnChannelForModeration(userId: string) {
  return useQuery({
    queryKey: chatModerationKeys.ownChannel(userId),
    queryFn: ({ signal }) => channelApi.getOwnChannel(signal),
    enabled: Boolean(userId),
    retry: false,
    refetchOnWindowFocus: false,
  })
}

export function useChatModerationContext(
  userId: string,
  channelId?: string | null,
) {
  const safeChannelId = channelId?.trim() || ''
  return useQuery({
    queryKey: chatModerationKeys.context(userId, safeChannelId),
    queryFn: async ({ signal }) => {
      try {
        return await chatModerationApi.getModerationContext(
          safeChannelId,
          signal,
        )
      } catch (error) {
        const parsed = parseChatModerationError(error)
        if (parsed.isForbidden) {
          return {
            channelId: safeChannelId,
            ownerUserId: '',
            canBan: false,
            canUnban: false,
            canViewBans: false,
          } satisfies ChatModerationContext
        }
        throw error
      }
    },
    enabled: Boolean(userId && safeChannelId),
    retry: false,
    refetchOnWindowFocus: false,
  })
}

export function useTargetBanStatus(
  userId: string,
  channelId: string | undefined,
  targetUserId: string | null,
) {
  const queryClient = useQueryClient()
  const safeChannelId = channelId?.trim() || ''
  const safeTargetUserId = targetUserId?.trim() || ''

  return useQuery({
    queryKey: chatModerationKeys.targetBan(
      userId,
      safeChannelId,
      safeTargetUserId,
    ),
    queryFn: async ({ signal }) => {
      try {
        return await chatModerationApi.getTargetBanStatus(
          safeChannelId,
          safeTargetUserId,
          signal,
        )
      } catch (error) {
        const parsed = parseChatModerationError(error)
        if (parsed.isForbidden) {
          revokeCapability(queryClient, userId, safeChannelId, 'canBan')
          toast.error(parsed.message)
        }
        throw error
      }
    },
    enabled: Boolean(userId && safeChannelId && safeTargetUserId),
    staleTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
  })
}

export function useBanChatUserMutation(userId: string, channelId?: string) {
  const queryClient = useQueryClient()
  const safeChannelId = channelId?.trim() || ''
  const inFlightRef = useRef(false)
  const activeRef = useRef(true)

  useEffect(() => {
    activeRef.current = true
    inFlightRef.current = false
    return () => {
      activeRef.current = false
    }
  }, [userId, safeChannelId])

  const mutation = useMutation<BanChatUserResponse, Error, string>({
    mutationFn: async (targetUserId: string) => {
      if (!userId || !safeChannelId) {
        throw new Error('Channel information is not available.')
      }
      return chatModerationApi.banChatUser(safeChannelId, targetUserId)
    },
    onSuccess: async (_data, targetUserId) => {
      if (!userId || !safeChannelId) return
      const targetKey = chatModerationKeys.targetBan(
        userId,
        safeChannelId,
        targetUserId,
      )
      queryClient.setQueryData<TargetChatBanStatus>(targetKey, {
        channelId: safeChannelId,
        userId: targetUserId,
        banType: 'CHAT_BAN',
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: targetKey }),
        queryClient.invalidateQueries({
          queryKey: chatModerationKeys.bansByChannel(userId, safeChannelId),
        }),
      ])
    },
    onError: async (error, targetUserId) => {
      if (!userId || !safeChannelId) return
      const parsed = parseChatModerationError(error)
      if (parsed.isForbidden) {
        revokeCapability(queryClient, userId, safeChannelId, 'canBan')
      } else if (parsed.isConflict) {
        await Promise.all([
          queryClient.invalidateQueries({
            queryKey: chatModerationKeys.targetBan(
              userId,
              safeChannelId,
              targetUserId,
            ),
          }),
          queryClient.invalidateQueries({
            queryKey: chatModerationKeys.bansByChannel(userId, safeChannelId),
          }),
          queryClient.invalidateQueries({
            queryKey: chatModerationKeys.context(userId, safeChannelId),
          }),
        ])
      }
    },
  })

  const mutateBan = useCallback(
    async (targetUserId: string) => {
      if (inFlightRef.current || mutation.isPending) return null
      inFlightRef.current = true
      try {
        return await mutation.mutateAsync(targetUserId)
      } finally {
        if (activeRef.current) {
          inFlightRef.current = false
        }
      }
    },
    [mutation],
  )

  return {
    ...mutation,
    mutateBan,
  }
}

export function useBannedUsersQuery(
  userId: string,
  channelId: string | undefined,
  page: number,
  size: number = DEFAULT_BANS_PAGE_SIZE,
  enabled: boolean = true,
) {
  const queryClient = useQueryClient()
  const safeChannelId = channelId?.trim() || ''

  return useQuery({
    queryKey: chatModerationKeys.bansPage(userId, safeChannelId, page, size),
    queryFn: async ({ signal }) => {
      try {
        return await chatModerationApi.getBannedUsers(
          safeChannelId,
          { page, size },
          signal,
        )
      } catch (error) {
        const parsed = parseChatModerationError(error)
        if (parsed.isForbidden) {
          revokeCapability(queryClient, userId, safeChannelId, 'canViewBans')
        }
        throw error
      }
    },
    enabled: Boolean(userId && safeChannelId && enabled),
    retry: false,
    refetchOnWindowFocus: false,
  })
}

export function useUnbanChatUserMutation(userId: string, channelId?: string) {
  const queryClient = useQueryClient()
  const safeChannelId = channelId?.trim() || ''
  const scope = `${userId}:${safeChannelId}`
  const inFlightUsersRef = useRef(new Set<string>())
  const [pendingTargets, setPendingTargets] = useState<
    Array<{ scope: string; targetUserId: string }>
  >([])
  const activeRef = useRef(true)

  useEffect(() => {
    activeRef.current = true
    const inFlight = inFlightUsersRef.current
    return () => {
      activeRef.current = false
      inFlight.clear()
    }
  }, [userId, safeChannelId])

  const pendingUserIds = pendingTargets
    .filter((item) => item.scope === scope)
    .map((item) => item.targetUserId)

  const mutation = useMutation<UnbanChatUserResponse, Error, string>({
    mutationFn: async (targetUserId: string) => {
      if (!userId || !safeChannelId) {
        throw new Error('Channel information is not available.')
      }
      return chatModerationApi.unbanChatUser(safeChannelId, targetUserId)
    },
    onSuccess: async (_data, targetUserId) => {
      if (!userId || !safeChannelId) return
      const targetKey = chatModerationKeys.targetBan(
        userId,
        safeChannelId,
        targetUserId,
      )
      queryClient.setQueryData<TargetChatBanStatus>(targetKey, {
        channelId: safeChannelId,
        userId: targetUserId,
        banType: null,
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: targetKey }),
        queryClient.invalidateQueries({
          queryKey: chatModerationKeys.bansByChannel(userId, safeChannelId),
        }),
      ])
    },
    onError: async (error, targetUserId) => {
      if (!userId || !safeChannelId) return
      const parsed = parseChatModerationError(error)
      if (parsed.isForbidden) {
        revokeCapability(queryClient, userId, safeChannelId, 'canUnban')
      } else if (parsed.isConflict) {
        await Promise.all([
          queryClient.invalidateQueries({
            queryKey: chatModerationKeys.targetBan(
              userId,
              safeChannelId,
              targetUserId,
            ),
          }),
          queryClient.invalidateQueries({
            queryKey: chatModerationKeys.bansByChannel(userId, safeChannelId),
          }),
          queryClient.invalidateQueries({
            queryKey: chatModerationKeys.context(userId, safeChannelId),
          }),
        ])
      }
    },
  })

  const unbanUser = useCallback(
    async (targetUserId: string) => {
      const flightKey = `${scope}:${targetUserId}`
      if (inFlightUsersRef.current.has(flightKey)) return null
      inFlightUsersRef.current.add(flightKey)
      setPendingTargets((prev) => [...prev, { scope, targetUserId }])
      try {
        return await mutation.mutateAsync(targetUserId)
      } finally {
        inFlightUsersRef.current.delete(flightKey)
        if (activeRef.current) {
          setPendingTargets((prev) =>
            prev.filter(
              (item) =>
                !(item.scope === scope && item.targetUserId === targetUserId),
            ),
          )
        }
      }
    },
    [mutation, scope],
  )

  return {
    ...mutation,
    pendingUserIds,
    isUserPending: (targetUserId: string) =>
      pendingUserIds.includes(targetUserId),
    unbanUser,
  }
}
