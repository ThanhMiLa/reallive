# Channel profile updates

`PATCH /channel/api/channels/me` (through the gateway) requires `CHANNEL_UPDATE`
and accepts `multipart/form-data`:

| Field | Type | Behavior |
| --- | --- | --- |
| `displayName` | Optional text, 1–100 characters, nonblank | Updates the user profile display name and channel name; surrounding whitespace is trimmed |
| `avatar` | Optional image file | Uses User Service avatar validation and R2 upload, then copies the resulting URL to the channel |
| `description` | Optional text | Updates only the channel |
| `bannerUrl` | Optional image file | Updates only the channel banner |
| `categoryIds` | Optional UUID list | Updates only channel categories |

Omitted fields remain unchanged. Avatar URLs supplied by clients are not accepted.
Responses retain the channel fields `name` and `avatarUrl`.

Channel Service resolves the authenticated user's active streamer and checks the
channel and supplied categories/banner before sending profile changes to
`PATCH /internal/api/users/{userId}/channel-profile` in User Service (under its
`/user` servlet context). This internal endpoint accepts only `displayName` and
`avatar`; it performs the same profile validation, upload and persistence as the
public profile endpoint, without calling Channel Service back.

The internal route permits service calls without credentials and bypasses JWT
processing. Gateway routes expose `/user/api/**`, not `/user/internal/**`.
The deployment must also restrict direct network access to User Service; local
source and gateway tests do not establish production network isolation.

User Service commits first. Channel Service then locks and updates the channel
in a local transaction. It holds no channel row lock during the profile call,
which avoids lock inversion with edits originating in User Service. Existing
public profile edits continue to synchronize supplied fields to Channel Service.

These are separate database transactions, without a distributed transaction or
outbox reconciliation. A timeout can have an unknown remote outcome. Failure
of the local channel commit after a successful profile update, or overlapping
edits through both endpoints, can leave the two copies inconsistent. A later
successful edit of the affected fields can reconcile the values.
