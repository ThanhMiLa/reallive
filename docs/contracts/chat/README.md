# Livestream chat contract

## Scope and ownership

A chat room belongs to one stream session. Only authenticated eligible accounts may read it; sending additionally requires channel chat permission. Read/send are allowed only for PUBLIC sessions in LIVE or RECONNECTING. READY and ENDED reject access. Closing the room removes the Web history view; persisted messages are not deleted by closing.

Chat Service owns MongoDB messages and counters. Stream Service owns lifecycle, Channel Service owns bans, User Service owns auth state and profile snapshots. Chat ban management is provided by Channel Service; mute/delete management, VOD replay, retention cleanup and horizontal Chat scaling are deferred. Run exactly one Chat Service instance in this MVP.

## Gateway and authentication

External paths:

- HTTP `GET /chat/api/streams/{sessionId}/messages`.
- Socket.IO path `/chat/socket.io/`, default namespace `/`, WebSocket transport only.

Gateway proxies these to HTTP/WS on Chat Service port 3001. It never routes `/internal/**` or `/{service}/internal/**`. The Socket.IO Origin must be explicitly allowed; no wildcard origins or browser-readable JWT are required. Browser requests use the existing HttpOnly `access_token` cookie.

The chat HTTP route deduplicates `Access-Control-Allow-Origin` and `Access-Control-Allow-Credentials`, retaining the Gateway's first value when NestJS also adds CORS headers. Each response must contain only one value for each header so the browser can read credentialed chat history responses.

Chat verifies HS512 signature, issuer, ACCESS type, subject UUID, issued-at/expiry and JTI; then reads `auth:status`, `auth:authorities` and access-token revocation keys from the same Redis database as User Service. Missing/corrupt state or dependency errors fail closed. Signed token scope is ignored, matching Auth State. Tokens and account state are revalidated on join, send and history actions. Background revalidation of idle sockets is deferred.

## Internal integrations

The three chat integrations use `permitAll()` on their private service endpoints. Chat and Stream call them directly without an internal token or user JWT, relying on the system's private service network. They return the existing Java `ApiResponse` envelope with `code=1000`, `status=200`, and `result`:

| Provider | Path relative to its servlet context | Result |
|---|---|---|
| Stream | `GET /internal/api/streams/{sessionId}/chat-context` | `{sessionId, channelId, visibility, status}` |
| Channel | `POST /internal/api/channels/{channelId}/chat-access`, body `{userId: UUID}` | `{channelId, userId, canView, canRead, canSend}` |
| User | `GET /internal/api/users/{userId}/chat-profile` | `{userId, displayName, avatarUrl}` |

Full URLs include existing context paths: `/stream/internal/...`, `/channel/internal/...`, `/user/internal/...`. Their exact GET/POST paths are permitted by each service's normal security configuration; other external APIs retain their authentication requirements. Chat Service itself has no inbound internal business API; `/internal/health/live` and `/internal/health/ready` are operational only.

Each channel and user has at most one active ban, and unban deletes that ban. CHAT_BAN allows viewing/reading but blocks sending. LIVE_VIEW_BAN blocks livestream and read/send chat, leaving profile/VOD unaffected. FULL_BAN includes those blocks and represents a future all-channel restriction for profile/VOD owners to enforce. Channel unban removes whichever ban type is active. Inactive/deleted channels cannot be viewed/chatted. Stream playback-access checks canView before minting credentials; this does not change already-issued media-token revocation semantics.

## Channel moderation

Channel owners and assigned moderators can manage all three ban levels on their channel. Channel Service checks the authenticated caller against the channel owner and `channel_moderators`. The channel owner cannot be banned. The channel must be active and have a known owner.

| Method | Gateway path | Result |
|---|---|---|
| POST | `/channel/api/channels/{channelId}/users/{userId}/ban` | State after setting the requested ban |
| POST | `/channel/api/channels/{channelId}/users/{userId}/unban` | State after removing the active ban |
| GET | `/channel/api/channels/{channelId}/users/{userId}/ban` | Current state and target-specific capabilities |
| GET | `/channel/api/channels/{channelId}/bans?page=0&size=20` | Paginated active bans and target-specific capabilities |
| GET | `/channel/api/channels/{channelId}/moderation-context` | Caller capabilities scoped to the channel |
| GET | `/channel/api/channels/{channelId}/moderators?page=0&size=20` | `200` with paginated moderator assignments |
| GET | `/channel/api/channels/{channelId}/moderators/candidate?displayName=…&page=0&size=20` | `200` with paginated accounts and their assignment eligibility |
| POST | `/channel/api/channels/{channelId}/moderators/{userId}` | `201` with `{channelId,userId}` in `ApiResponse.result` |
| DELETE | `/channel/api/channels/{channelId}/moderators/{userId}` | `204` |

Assigning and removing moderators require `CHANNEL_MODERATOR_MANAGE` and ownership of the active channel. User Service V14 grants the authority to `STREAMER` and queues Redis updates for existing streamers. Deploy User Service and apply V14 first, verify the authority is available in Redis, then deploy Channel Service.

The two moderator reads require the same authority and channel ownership. The list uses zero-based pages, with a default size of 20 and a maximum of 50. `ApiResponse.result` contains `content`, `page`, `size`, `totalElements`, and `totalPages`. Each row contains `{userId,username,displayName,avatarUrl,assignedAt}`. Rows sort by assignment time descending and user ID ascending. `assignedAt` is an ISO 8601 UTC timestamp. Channel Service fetches the page's profiles through the existing batch chat-profile endpoint, which now includes `username`. If a profile or User Service is unavailable, the assignment still appears with nullable profile fields.

The candidate endpoint matches a literal substring of the display name, ignoring case. It trims surrounding whitespace and rejects a blank term or a term longer than 100 characters with `400`. Paging uses the same defaults and limits as the moderator list. Results sort by case-insensitive display name, then by user ID ascending. A search with no matches returns `200` with empty `content`. A failed identity search returns `503`. Each item contains `{userId,displayName,avatarUrl,eligibility}`. The eligibility values, in check order, are `CHANNEL_OWNER`, `ALREADY_MODERATOR`, `CHANNEL_BANNED`, `ACCOUNT_INACTIVE`, and `ELIGIBLE`. Use `userId` to select a candidate. Assignment checks eligibility again when it writes.

Channel Service searches candidates through `GET /user/internal/api/users/identities?displayName=…&page=0&size=20` on the private service network. Each item contains `{userId,displayName,avatarUrl,status}`. Deploy the updated User Service before the Channel Service reads, then deploy the frontend.

Assignment checks the channel before looking up the target. Conflicts include self-assignment (1027), an existing assignment (1028), a ban on that channel (1029), and an inactive target account (1030). A missing target account returns `404` (1022). Channel Service calls User Service directly via `GET /user/internal/api/users/{userId}/status`, which returns `{userId,status}` in `ApiResponse.result`. The internal route uses the private service network without credentials. If status lookup fails, assignment returns `503` (1008).

Removal acts on the channel and user pair and returns `404` (1031) when the assignment is absent. Assignment, removal, ban and unban share the channel row lock. Ban and unban check the caller's moderation access before entering the transaction. A request that passes this check can complete after the caller's assignment is removed.

Ban requires JSON `{ "banType": "CHAT_BAN" | "LIVE_VIEW_BAN" | "FULL_BAN" }`. A new ban checks target profile existence. An authorized caller can change the active ban to any other level or remove it. The response state is `{channelId,userId,banType,allowedBanTypes,canUnban}`. A conflict 1023 means the requested level is already active, 1024 means no active ban and 1025 protects the owner. Conflict 1026 requires removing the target's moderator assignment on that channel before applying a ban.

The context returns `{channelId,ownerUserId,allowedBanTypes,allowedUnbanTypes,canViewBans}`. Owners and assigned moderators of active channels receive all three levels and `canViewBans=true`. Other authenticated callers receive empty permission lists and `canViewBans=false`. Each list item returns `{userId,displayName,avatarUrl,banType,createdAt,allowedBanTypes,canUnban}`. Profile fields may be null if User Service is unavailable. The list uses private User Service `POST /internal/api/users/chat-profiles` with `{userIds}` containing 1–50 UUIDs. Changing a level preserves `createdAt` and updates `performed_by` and the fixed reason.

Existing chat access and playback checks enforce live/chat restrictions. Channel/VOD restrictions for FULL_BAN are deferred until those capabilities are implemented. Already issued media tokens are not revoked. Chat authorization keeps the existing 1,750 ms cache.

Channel Service migration V6 retains the highest active ban and removes bans targeting known owners. Stop Channel Service versions that still require the removed event columns before applying V6. Migration V7 creates moderator membership. Replace every Channel Service instance that checks global ban permissions before applying User Service V13, which removes those permissions and queues Redis auth-state synchronization for affected users. Ownerless channels cannot use moderation until their owner mapping is backfilled. Deploy the backend and frontend together when upgrading from the older moderation routes under `/chat`.

## Message and ordering

Public message:

```typescript
interface Message {
  id: string;
  cursor: string;
  streamId: string;
  channelId: string;
  userId: string;
  clientMessageId: string;
  sequence: number;
  content: string;
  sender: { displayName: string; avatarUrl: string | null };
  createdAt: string; // UTC ISO timestamp
}
```

Content is trimmed and must contain 1–500 Unicode code points. It is displayed as literal text, not HTML. Identity, channel, sequence, snapshot and time come from the server. User Service retains previous committed avatar objects; rollback still cleans new uploads. No avatar cleanup is scheduled until retention is defined.

A per-session counter and message are committed in one MongoDB transaction, using snapshot read concern and majority write concern. The per-room application queue serializes admission, insert and delivery. Sequence determines history order even if timestamps are equal or clocks change. Indexes: unique `(stream_id, sequence)`, unique `(stream_id, user_id, client_message_id)`, and default `_id`. Counter `_id` is the session UUID. Indexes must be provisioned before serving traffic.

## HTTP history

Query parameters: `before`, `after`, `through`. At most one of before/after may be supplied. All are opaque, versioned, session-bound cursor values. Through fixes the upper watermark; omitted through uses the current committed head. No total-count query or numeric page offset is used.

Every result contains at most 50 items in ascending sequence order:

```typescript
interface HistoryPage {
  items: Message[];
  olderCursor: string | null;
  newerCursor: string | null;
  hasOlder: boolean;
  hasNewer: boolean;
  watermark: string;
}
```

The cursors describe page boundaries, including when hasOlder/hasNewer is false. An empty page has a newerCursor at its watermark, permitting later catch-up. Clients must use hasOlder/hasNewer to decide whether an adjacent page currently exists. Replies use the existing success envelope and `Cache-Control: no-store`. Auth and lifecycle are checked before fetching and before returning data.

## Socket events and recovery

| Direction | Event | Payload/result |
|---|---|---|
| Client → server | `chat:join` | `{sessionId}`; ACK data `{sessionId, canRead, canSend, watermark}` |
| Client → server | `chat:leave` | Leaves this socket's current room |
| Client → server | `chat:send` | `{sessionId, clientMessageId, content}`; ACK data is persisted Message |
| Server → client | `chat:message` | Persisted Message |

ACK shape: `{ok:true,data:T}` or `{ok:false,error:{code,status,message,retryable}}`. HTTP errors use the same error fields plus `result:null` and `timestamp`. Codes include UNAUTHENTICATED (401), ACCOUNT_BANNED/CHAT_FORBIDDEN/CHAT_BANNED (403), CHAT_NOT_FOUND (404), CHAT_REQUEST_INVALID (400), CHAT_CLOSED/CHAT_MESSAGE_CONFLICT (409), CHAT_UNAVAILABLE (503, retryable).

Success is acknowledged only after persistence. A retry with the same session/sender/clientMessageId and normalized content returns the original record; different content is a conflict. An unconfirmed send keeps its original ID on retry. Socket.IO delivery alone is not a durable log.

Room subscription precedes bootstrap watermark capture in the room queue. HTTP retrieves history through a committed watermark. Realtime duplicates are ignored by message ID/sequence; a gap is filled with after from the last boundary. Rejoining marks the current tail for catch-up. Catch-up is permitted only while read access and lifecycle still allow it.

## Bounded authorization and Web memory

Access checks use a per-session/per-user cache with a maximum age of 1,750 ms. An expired or missing entry is refreshed on the next join/send/history access check using Stream context and the single-user Channel access endpoint. There is no polling timer, permission watchdog, periodic permission event or periodic watermark event. Existing room membership permits receiving new messages while idle; delivery is not gated by the short access-cache TTL. Delivery skips recipients whose JWT has expired, without a background disconnect timer.

Automatic removal after a ban, account revocation or stream end while the user remains idle is deferred. These changes are enforced on subsequent actions, subject to the short channel/session access cache; no under-two-second idle closure guarantee is made. Web still clears chat when stream metadata changes to an inactive state, on logout or when leaving the room. Catch-up uses message sequence gaps and reconnect/join watermarks.

Web uses cursor pagination, TanStack Query and TanStack Virtual. It retains at most 20 pages of at most 50 messages; evicted pages can be loaded again in either direction. New messages do not grow an unbounded buffer while reading older history: only a counter and boundary are retained. Older-page prepend preserves the visible anchor. Back to latest resets to the current head. Focus does not refetch every cached page. Socket auth refresh shares the existing HTTP refresh promise and permits one attempt until a successful join, preventing refresh loops on configuration errors.
