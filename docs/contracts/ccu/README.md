# Concurrent viewer presence contract

## Metadata

GET /stream/api/streams/{sessionId} adds result.currentViewerCount (integer or null). READY/ENDED return 0. LIVE/RECONNECTING return SCARD of the per-session viewer Set only when presence is ACTIVE; disabled/unavailable/recovering presence returns null. Owner session endpoints do not acquire a new count field. Existing account authentication remains required.

GET /stream/api/streams adds result.content[].currentViewerCount (integer or null) for each LIVE session in the requested page, including when filtering by categoryId. It reuses the same presence count as metadata: 0 means no viewers; null means disabled/unavailable/recovering presence. Presence is read after the database transaction, without adding viewers or calling MediaMTX. Count failures do not prevent listing streams. Pagination, authentication, and Cache-Control: no-store remain unchanged.

Web livestream cards in Browse and category detail display currentViewerCount using the shared viewer-count badge: counts are formatted with thousands separators, 0 remains visible, and null or a missing field displays —. Counts refresh with the existing list query; cards do not fetch individual session metadata or add separate polling.

## Internal reader callback

POST /stream/internal/api/viewer-events uses X-Internal-Token with STREAM_CCU_CALLBACK_TOKEN; this route is not public through Gateway. Never use account cookies or player Read JWT as callback credentials.

Fields: eventId UUID, eventType READ|UNREAD, sessionId UUID, readerId UUID, readerType hlsSession, occurredAt ISO UTC timestamp; userId UUID required for READ only. Preserve eventId/body on retry. The script resolves user from the authenticated HLS session through loopback Control API, verifying id/path; never accept an unverified user query parameter.

200 acknowledges applied/duplicate/ignored events. 400 invalid payload; 401 invalid internal credential; 409 READ before stream becomes LIVE (retry bounded); 503 Redis unavailable (retry bounded). Ignore events older than 60 seconds or more than 5 seconds in the future. Exclude ownerUserId; technical identities never produce viewer READ. No event can reopen ENDED presence.

## Media credential

Control API requests require a separate JWT with api permission, not a Read JWT. Stream Service publishes control.jwt into STREAM_CCU_CONTROL_TOKEN_DIRECTORY, mounted read-only as /run/media-hook. Tokens use the configured media control TTL (default 5 minutes), atomically replaced every minute. Startup rejects a TTL no longer than the refresh interval plus the 10-second hook budget, and rejects whitespace credentials. Private keys remain in Stream Service. Feature STREAM_CCU_ENABLED defaults false. Configure callback token (32+ characters), host directory, and STREAM_VIEWER_CALLBACK_URL before enabling. The backend and media container must share the same directory and callback token. Logs must not contain credentials or raw MTX_QUERY.

## Presence invariants

The business keys are viewers Set, per-user readers Set, and reader-users Hash under stream:{sessionId}. Closed-reader tombstones protect out-of-order events; presence-state generation fences recovery. Set membership is updated atomically. Presence is rebuilt from media observations after data loss. Read count does not increase viewers or invoke MediaMTX.

## Recovery and operations

A single reconciliation pass holds a Redis lease and runs every 30 seconds. It restores membership from all MediaMTX HLS session pages, filters owner/technical users and confirms a missing reader with GET404 before removal. RECOVERING counts remain null; generation checks prevent stale recovery from reopening CLOSED state. Source disconnect keeps presence while existing readers remain active. After committed ENDED, cleanup is best-effort and retries through maintenance. Media path recreation is outside CCU recovery.

Database reads are batched in groups of at most 100 session IDs. Cleanup and final recovery checks select only ID/status; active-session discovery uses ID/owner projections and `Slice` pagination without COUNT. The scheduler rechecks current statuses after processing media readers, rather than reusing the initial discovery snapshot, and never looks up database sessions one by one. For M tracked sessions and N active sessions, a completed pass uses approximately `ceil(M/100) + 2 * ceil(N/100)` SELECT statements (one discovery SELECT when N is zero). Missing-reader HTTP404 confirmation remains separate from these SQL queries.

Metrics expose callback received/delay, count failures, media lookup failures, credential publication failures, reconcile attempts/success/failures and last-success epoch without user/reader labels. CCU callbacks and keys contain no raw Read JWT. Unknown-reader tombstones without tracked state expire after 10 minutes; active streams retain memberships without short TTL.

Enable locally by setting STREAM_CCU_ENABLED=true, a 32+ character non-whitespace STREAM_CCU_CALLBACK_TOKEN, and the same absolute STREAM_CCU_CONTROL_TOKEN_DIRECTORY in the backend environment and Compose. The backend publishes the token file; media-server mounts the directory read-only. Rebuild the media image to include viewer_hook.py and restart the chosen development services after configuration. This change does not automatically modify local secrets or restart the existing stack.
