# MediaMTX startup callback

Media Server calls Stream Service directly, after its local MediaMTX Control API is ready:

```http
POST /stream/internal/api/media/restore-ingest-paths
Accept: application/json
```

No request body, account cookie or service token is required. The servlet context is `/stream`; the controller mapping is `/internal/api/media/restore-ingest-paths`. Gateway must not route this endpoint. It is intended for the internal media/backend network; the local backend binds to loopback by default and MediaMTX Control API is published only on `127.0.0.1:9997`.

Successful response (`200`, `Cache-Control: no-store`):

```json
{
  "status": 200,
  "code": 1000,
  "message": "Media ingest paths restored",
  "result": { "restoredChannels": 2 },
  "timestamp": "2026-11-18T00:00:00Z"
}
```

`restoredChannels` counts active channel paths successfully ensured, including paths that already existed; zero is valid. Recovery uses the current `channel_stream_configs.current_ingest_key_id`, preserves the OBS key, skips `DRAFT`/`BLOCKED` configurations, and does not create broadcast sessions or playback paths. Each configuration is locked and rechecked before any media mutation, serializing restoration with key replacement/blocking. Repeated calls are safe; stale keys are never selected from an earlier unlocked snapshot.

Media or persistence failures use the standard error envelope (`503`); no successful acknowledgement is sent on partial failure. Media changes already completed remain in effect, so the caller retries the entire request. Recovery is synchronous with a separate transaction per channel, without a scheduler, operation table or database migration.

The container entrypoint waits for its local `/v3/info`, calls this endpoint and retries startup failures every two seconds until a successful acknowledgement or shutdown. Each request times out after five seconds. Readiness remains false until the callback succeeds; after success, the callback stops. The backend can start later than MediaMTX. The fixture Compose configuration explicitly disables this callback because fixture paths are provisioned manually.

This callback assumes the single MediaMTX instance configured by Stream Service, as in the local Compose stack. RSA signing keys must remain unchanged for previously issued publish JWTs to validate. It does not repair interrupted stream key replacement, infer missed source events, or resume old playback sessions.
