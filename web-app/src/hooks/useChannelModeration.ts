import { useCallback, useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { channelApi } from '@/api/channel.api'
import {
  channelModerationApi,
  parseChannelModerationError,
} from '@/api/channel-moderation.api'
import type {
  BanType,
  ChannelModerationContext,
  TargetChannelBanState,
} from '@/types/channel-moderation.types'

export const DEFAULT_BANS_PAGE_SIZE = 20

export const channelModerationKeys = {
  all: ['channel-moderation'] as const,
  ownChannel: (actorId: string) =>
    ['channel-moderation', 'own-channel', actorId] as const,
  context: (actorId: string, channelId: string) =>
    ['channel-moderation', 'context', actorId, channelId] as const,
  moderatorsByChannel: (actorId: string, channelId: string) =>
    ['channel-moderation', 'moderators', actorId, channelId] as const,
  targetBan: (actorId: string, channelId: string, targetUserId: string) =>
    ['channel-moderation', 'target-ban', actorId, channelId, targetUserId] as const,
  bansByChannel: (actorId: string, channelId: string) =>
    ['channel-moderation', 'bans', actorId, channelId] as const,
  bansPage: (
    actorId: string,
    channelId: string,
    page: number,
    size: number,
  ) => ['channel-moderation', 'bans', actorId, channelId, page, size] as const,
}

export function clearChannelModerationCache(client: QueryClient) {
  const filters = { queryKey: channelModerationKeys.all }
  void client.cancelQueries(filters)
  client.removeQueries(filters)
}

function refreshModerationState(
  queryClient: QueryClient,
  actorId: string,
  channelId: string,
  targetUserId?: string,
) {
  if (!actorId || !channelId) return Promise.resolve([])
  const invalidations: Array<Promise<void>> = [
    queryClient.invalidateQueries({
      queryKey: channelModerationKeys.context(actorId, channelId),
    }),
    queryClient.invalidateQueries({
      queryKey: channelModerationKeys.bansByChannel(actorId, channelId),
    }),
  ]
  if (targetUserId) {
    invalidations.push(
      queryClient.invalidateQueries({
        queryKey: channelModerationKeys.targetBan(
          actorId,
          channelId,
          targetUserId,
        ),
      }),
    )
  }
  return Promise.all(invalidations)
}

export function useEnsureOwnChannel(
  actorId: string,
  enabled: boolean = true,
) {
  const queryClient = useQueryClient()
  return useQuery({
    queryKey: channelModerationKeys.ownChannel(actorId),
    queryFn: async ({ signal }) => {
      const channel = await channelApi.ensureOwnChannel(signal)
      queryClient.setQueryData(['own-channel'], channel)
      return channel
    },
    enabled: Boolean(actorId && enabled),
    retry: false,
    refetchOnWindowFocus: false,
  })
}

export function useOwnChannelForModeration(
  actorId: string,
  enabled: boolean = true,
) {
  return useQuery({
    queryKey: channelModerationKeys.ownChannel(actorId),
    queryFn: ({ signal }) => channelApi.getOwnChannel(signal),
    enabled: Boolean(actorId && enabled),
    retry: false,
    refetchOnWindowFocus: false,
  })
}

export function useChannelModerationContext(
  actorId: string,
  channelId?: string | null,
) {
  const safeChannelId = channelId?.trim() || ''
  return useQuery({
    queryKey: channelModerationKeys.context(actorId, safeChannelId),
    queryFn: async ({ signal }) => {
      try {
        return await channelModerationApi.getModerationContext(
          safeChannelId,
          signal,
        )
      } catch (error) {
        const parsed = parseChannelModerationError(error)
        if (parsed.isForbidden) {
          return {
            channelId: safeChannelId,
            ownerUserId: '',
            allowedBanTypes: [],
            allowedUnbanTypes: [],
            canViewBans: false,
          } satisfies ChannelModerationContext
        }
        throw error
      }
    },
    enabled: Boolean(actorId && safeChannelId),
    retry: false,
    refetchOnWindowFocus: false,
  })
}

export function useChannelModeratorIds(actorId: string, channelId?: string) {
  const safeChannelId = channelId?.trim() || ''
  return useQuery({
    queryKey: [...channelModerationKeys.moderatorsByChannel(actorId, safeChannelId), 'roster'],
    enabled: Boolean(actorId && safeChannelId),
    retry: false,
    refetchOnWindowFocus: true,
    queryFn: async ({ signal }) => {
      const ids = new Set<string>()
      const size = 50
      let totalPages = 1
      for (let page = 0; page < totalPages; page += 1) {
        const result = await channelModerationApi.getModeratorRoster(safeChannelId, page, size, signal)
        for (const moderator of result.content) ids.add(moderator.userId)
        totalPages = result.totalPages
      }
      return ids
    },
  })
}

export function useTargetBanState(
  actorId: string,
  channelId: string | undefined,
  targetUserId: string | null,
) {
  const queryClient = useQueryClient()
  const safeChannelId = channelId?.trim() || ''
  const safeTargetUserId = targetUserId?.trim() || ''

  return useQuery({
    queryKey: channelModerationKeys.targetBan(
      actorId,
      safeChannelId,
      safeTargetUserId,
    ),
    queryFn: async ({ signal }) => {
      try {
        return await channelModerationApi.getTargetBanState(
          safeChannelId,
          safeTargetUserId,
          signal,
        )
      } catch (error) {
        const parsed = parseChannelModerationError(error)
        if (parsed.isForbidden) {
          void queryClient.invalidateQueries({
            queryKey: channelModerationKeys.context(actorId, safeChannelId),
          })
        }
        throw error
      }
    },
    enabled: Boolean(actorId && safeChannelId && safeTargetUserId),
    staleTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
  })
}

export interface BanUserVariables {
  targetUserId: string
  banType: BanType
}

export function useChannelBanMutation(actorId: string, channelId?: string) {
  const queryClient = useQueryClient()
  const safeChannelId = channelId?.trim() || ''
  const scope = `${actorId}:${safeChannelId}`
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
  }, [actorId, safeChannelId])

  const pendingUserIds = pendingTargets
    .filter((item) => item.scope === scope)
    .map((item) => item.targetUserId)

  const mutation = useMutation<TargetChannelBanState, Error, BanUserVariables>({
    retry: false,
    mutationFn: async ({ targetUserId, banType }: BanUserVariables) => {
      if (!actorId || !safeChannelId) {
        throw new Error('Channel information is not available.')
      }
      return channelModerationApi.banUser(safeChannelId, targetUserId, banType)
    },
    onSuccess: async (data, { targetUserId }) => {
      if (!actorId || !safeChannelId) return
      const targetKey = channelModerationKeys.targetBan(
        actorId,
        safeChannelId,
        targetUserId,
      )
      queryClient.setQueryData<TargetChannelBanState>(targetKey, data)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: targetKey }),
        queryClient.invalidateQueries({
          queryKey: channelModerationKeys.bansByChannel(actorId, safeChannelId),
        }),
      ])
    },
    onError: async (error, { targetUserId }) => {
      if (!actorId || !safeChannelId) return
      const parsed = parseChannelModerationError(error)
      if (parsed.isForbidden || parsed.isConflict) {
        await refreshModerationState(
          queryClient,
          actorId,
          safeChannelId,
          targetUserId,
        )
      }
    },
  })

  const banUser = useCallback(
    async (variables: BanUserVariables) => {
      const flightKey = `${scope}:${variables.targetUserId}`
      if (inFlightUsersRef.current.has(flightKey)) return null
      inFlightUsersRef.current.add(flightKey)
      setPendingTargets((prev) => [
        ...prev,
        { scope, targetUserId: variables.targetUserId },
      ])
      try {
        return await mutation.mutateAsync(variables)
      } finally {
        inFlightUsersRef.current.delete(flightKey)
        if (activeRef.current) {
          setPendingTargets((prev) =>
            prev.filter(
              (item) =>
                !(
                  item.scope === scope &&
                  item.targetUserId === variables.targetUserId
                ),
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
    banUser,
  }
}

export function useBannedUsersQuery(
  actorId: string,
  channelId: string | undefined,
  page: number,
  size: number = DEFAULT_BANS_PAGE_SIZE,
  enabled: boolean = true,
) {
  const queryClient = useQueryClient()
  const safeChannelId = channelId?.trim() || ''

  return useQuery({
    queryKey: channelModerationKeys.bansPage(actorId, safeChannelId, page, size),
    queryFn: async ({ signal }) => {
      try {
        return await channelModerationApi.getBannedUsers(
          safeChannelId,
          { page, size },
          signal,
        )
      } catch (error) {
        const parsed = parseChannelModerationError(error)
        if (parsed.isForbidden) {
          void queryClient.invalidateQueries({
            queryKey: channelModerationKeys.context(actorId, safeChannelId),
          })
        }
        throw error
      }
    },
    enabled: Boolean(actorId && safeChannelId && enabled),
    retry: false,
    refetchOnWindowFocus: false,
  })
}

export function useChannelUnbanMutation(actorId: string, channelId?: string) {
  const queryClient = useQueryClient()
  const safeChannelId = channelId?.trim() || ''
  const scope = `${actorId}:${safeChannelId}`
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
  }, [actorId, safeChannelId])

  const pendingUserIds = pendingTargets
    .filter((item) => item.scope === scope)
    .map((item) => item.targetUserId)

  const mutation = useMutation<TargetChannelBanState, Error, string>({
    retry: false,
    mutationFn: async (targetUserId: string) => {
      if (!actorId || !safeChannelId) {
        throw new Error('Channel information is not available.')
      }
      return channelModerationApi.unbanUser(safeChannelId, targetUserId)
    },
    onSuccess: async (data, targetUserId) => {
      if (!actorId || !safeChannelId) return
      const targetKey = channelModerationKeys.targetBan(
        actorId,
        safeChannelId,
        targetUserId,
      )
      queryClient.setQueryData<TargetChannelBanState>(targetKey, data)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: targetKey }),
        queryClient.invalidateQueries({
          queryKey: channelModerationKeys.bansByChannel(actorId, safeChannelId),
        }),
      ])
    },
    onError: async (error, targetUserId) => {
      if (!actorId || !safeChannelId) return
      const parsed = parseChannelModerationError(error)
      if (parsed.isForbidden || parsed.isConflict) {
        await refreshModerationState(
          queryClient,
          actorId,
          safeChannelId,
          targetUserId,
        )
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
