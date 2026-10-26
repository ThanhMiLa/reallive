# Giai đoạn 3 — Media adapter và session provisioning

- Ngày triển khai: 2026-10-26.
- Phạm vi: STR-301–308 trong [Task Breakdown](Streaming%20MVP%20Task%20Breakdown.md).
- Code và kiểm thử tự động được triển khai; nghiệm thu OBS do người dùng tự thực hiện theo [Phase 3 Test Plan](../testing/Streaming%20MVP%20Phase%203%20Test%20Plan.md). Chưa tuyên bố M3 đạt khi chưa có bằng chứng OBS.
- Không thay schema, migration hoặc dữ liệu ứng dụng; không thêm queue, worker, retry nền, reconciler hoặc optimistic version.

## 1. STR-301 — Control API adapter

`stream/adapter/media/MediaServerAdapter` và `MediaMtxAdapter` cung cấp:

- Get/ensure/delete exact path `live/{UUID}`; input không nhận URL/path tùy ý từ client.
- Get nguồn thực tế với `online`, connection ID và source type; phân biệt cấu hình tồn tại với nguồn online.
- List config/path với pagination, giới hạn 1–100 item mỗi trang.
- Kick connection qua đúng endpoint RTMP hoặc RTMPS, không dùng session ID thay connection ID.
- JWT quyền `api` do `MediaJwtIssuer` ký cho từng request; không trả credential quản trị ra API owner.
- WebClient/Reactor Netty tắt retry kết nối và redirect; connect timeout 2 giây, giới hạn request 5 giây, đều cấu hình được. Không wiretap/log request, response hoặc JWT.

Contract pin theo [MediaMTX v1.21.2 OpenAPI](https://github.com/bluenviron/mediamtx/blob/v1.21.2/api/openapi.yaml). Chỉ các 404 có lỗi đúng contract (`path configuration not found`, `path not found`, `connection not found`) mới được coi là absence/idempotent. 404 của endpoint sai, lỗi auth, JSON sai, cấu hình không tương thích, 5xx và timeout trả `MEDIA_SERVER_UNAVAILABLE`/503.

`ensurePath` kiểm tra trước khi add và xác nhận sau add; nếu đã tồn tại hợp lệ thì giữ nguyên. Race add nhận lỗi `path already exists` chỉ đọc lại để xác nhận, không replace path hoặc khởi động lại nguồn. Path cần `source: publisher`, `overridePublisher: false`.

## 2. STR-302–303 — Transaction và Create

`StreamSessionServiceImpl` kiểm tra eligibility/channel/category trước transaction ghi. Các entry point không tham gia ambient transaction: caller phải nhận kết quả sau khi transaction do use case sở hữu đã commit.

1. Reserve slot bằng domain `createOrGetSession`: session READY/OPEN và initial history còn chưa commit.
2. Giữ khóa session và gọi MediaMTX trực tiếp, chờ kết quả.
3. Chuẩn bị owner response và Publish JWT trong transaction đang khóa session.
4. Commit session/history; chỉ sau đó controller mới trả response chứa credential.

Lỗi media rollback session/history và trả 503, không tiếp tục công việc sau response. First create trả 201, replay cùng owner/request ID trả 200 và giữ metadata gốc. Unique conflict được đọc lại trong transaction mới sau rollback. Lookup request ID dùng pessimistic lock ngay từ lần đọc đầu để tránh đọc trạng thái cũ trước khi cạnh tranh với End.

`StreamSessionClosureService` cung cấp nền tảng End đồng bộ cho STR-302: khóa/recheck owner, xóa và xác nhận path, kiểm tra/kick nguồn thực tế nếu cần, rồi mới gọi domain ghi ENDED/CLOSED/history. Lỗi giữ trạng thái/slot trong DB; End lặp không gọi media hoặc ghi history thêm. **Chưa mở End HTTP API**; callback, scheduler và End endpoint vẫn thuộc Giai đoạn 4.

## 3. STR-304–306 — API owner

| API qua Gateway | Hành vi |
|---|---|
| POST `/stream/api/streams` | Principal quyết định owner; chỉ PUBLIC; trả session + credential sau provisioning/commit |
| PATCH `/stream/api/streams/{id}` | Owner sửa title/category khi chưa ENDED; validate category trước transaction, recheck owner/status dưới khóa; không media I/O |
| POST `/stream/api/streams/{id}/publish-credentials` | Recheck owner/eligibility/context/status/grace/path và ký Publish JWT mới |
| GET `/stream/api/streams/me/current` | Session active hoặc result null; serverTime/deadline/publishReady; không JWT |

Field PATCH thiếu giữ nguyên; explicit null, title trắng/quá dài, body rỗng hoặc field ngoài contract bị chặn bởi DTO của Giai đoạn 2. Cập nhật metadata không đổi path hoặc cấp thêm token.

Credential chỉ cấp khi path còn cấu hình hợp lệ. Không tự restore path bị mất, không cấp cho ENDED hoặc RECONNECTING tại/sau deadline. Cấp JWT mới không revoke JWT cũ; không lưu JWT/hash. Create và credential response dùng `Cache-Control: no-store`. Current cũng dùng no-store và kiểm tra cấu hình media để không giả báo publishReady sau media restart.

## 4. STR-307 — Cấu hình local

- Dùng lại một MediaMTX container/image `1.21.2`, config và Python hook hiện có.
- Bỏ dependency env file callback secret trong Compose: local MVP dùng callback JSON trực tiếp, không HMAC.
- MediaMTX gọi Stream Service trực tiếp qua port 8082 cho JWKS/callback; không mở route media hoặc Control API trên Gateway.
- Host ports: ingest 1935, HLS 8898 → container 8888, Control API 9997, bind loopback. Backend cần truy cập được từ container qua `host.docker.internal`.
- Stream Service thêm `STREAM_MEDIA_CONTROL_API_URL`, `STREAM_MEDIA_CONTROL_CONNECT_TIMEOUT`, `STREAM_MEDIA_CONTROL_REQUEST_TIMEOUT` trong env example.
- Healthcheck chỉ chứng minh listener hoạt động; JWT/JWKS và provisioning được kiểm chứng bằng integration test riêng.
- Backend chạy trên host nên Compose không quản lý thứ tự startup của backend: chuẩn bị key/DB/Redis, chạy User/Channel/Stream/Gateway rồi chạy media trước Create. MediaMTX tải JWKS khi cần xác thực.

## 5. STR-308 — Kiểm chứng và bàn giao

- `MediaMtxAdapterTest`: HTTP contract success/auth/error/timeout, pagination, exact path, no replace, protocol/connection ID và no retry.
- `StreamSessionControllerTest`: authentication, DTO/PATCH validation, principal, envelope/status và no-store.
- `StreamProvisioningPostgresTest`: provisioning/rollback, response chờ I/O, concurrent create/replay/slot, current/reissue, owner/category/state/deadline, End giữ khóa và credential chờ End.
- `StreamProvisioningMediaIT`: HTTP API + PostgreSQL + MediaMTX thật, nguồn FFmpeg tổng hợp, update không ngắt nguồn, exact path, reissue, closure, missing-path không tự restore và Create timeout/rollback/retry khi MediaMTX test bị pause. Redis authorities/eligibility/channel được stub trong test này; các client thật được kiểm chứng ở Giai đoạn 2.
- `scripts/testing/streaming/provisioning.py`: smoke bốn API owner, giữ request ID qua retry, credential ghi file riêng mode 0600, không in JWT/account cookie.
- OBS thật: helper opt-in chỉ phục vụ trang copy credential và chờ người dùng; **không mở, điều khiển hoặc thay cấu hình OBS**. M3/STR-308 vẫn chờ người dùng xác nhận.

Kết quả lệnh thực thi được ghi ở [Streaming MVP Tests](../testing/Streaming%20MVP%20Tests.md); thao tác OBS và case full stack ở [Phase 3 Test Plan](../testing/Streaming%20MVP%20Phase%203%20Test%20Plan.md).

## 6. Giới hạn vận hành giữ nguyên

PostgreSQL và MediaMTX không có transaction chung. Media đã tạo/xóa path nhưng backend timeout hoặc commit DB thất bại có thể gây lệch trạng thái. Rollback DB không đảo ngược MediaMTX; chưa đóng bù, tự restore path, đối soát hay retry nền. Khi cần, kiểm tra DB/path/source thủ công trước khi xử lý tiếp.

Nguồn online vẫn để DB READY khi callback handler Giai đoạn 4 chưa có. Callback hợp lệ trả 404 thay vì ACK thành công giả. Read/playback API và frontend thật thuộc Giai đoạn 5–6.
