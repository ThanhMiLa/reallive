# Kế hoạch triển khai Streaming MVP

- Ngày lập: 2026-10-24.
- Trạng thái: giai đoạn 0 đã hoàn tất STR-001–006 trên môi trường local, có bằng chứng runtime và xác nhận OBS/browser của người dùng. Giai đoạn 1–7 và backend nghiệp vụ chưa triển khai; xem [kết quả kiểm chứng](../testing/Streaming%20MVP%20Tests.md) để phân biệt dữ liệu tự động với xác nhận thủ công.
- Kết quả cần đạt: streamer tạo session, cấu hình OBS, phát video/audio; người dùng đăng nhập có thể vào web xem và nhận đúng trạng thái khi nguồn mất kết nối hoặc kết thúc.
- Module chính: `stream-service`, `web-app`, `api-gateway`, cấu hình Docker/MediaMTX. Bổ sung API nội bộ nhỏ trong `user-service` và `channel-service` khi cần.
- Kiến trúc nguồn: [Streaming MVP Architecture and Flow](../architecture/Streaming%20MVP%20Architecture%20and%20Flow.md).
- Backlog theo task và mốc tiến độ: [Streaming MVP Task Breakdown](Streaming%20MVP%20Task%20Breakdown.md).
- Kết quả rà soát giai đoạn 0: [Streaming MVP Phase 0 Review](Streaming%20MVP%20Phase%200%20Review.md).
- Tài liệu liên quan: [System Architecture](../architecture/System%20Architecture.md), [Data Model](../data-model/Data%20Model.md), [Auth State](../contracts/Auth%20State.md), [Features](../product/Features.md), [Actor Roles](../product/Actor_Roles.md).

Điểm đọc nhanh: [Database](#4-thiết-kế-database), [Contract API](#6-contract-api-đề-xuất), [MediaMTX](#8-mediamtx-docker-và-control-api), [Trình tự triển khai](#14-trình-tự-triển-khai-và-điều-kiện-hoàn-tất), [Kiểm thử](#15-chiến-lược-kiểm-thử), [Nghiệm thu](#17-checklist-nghiệm-thu-mvp).

## 1. Phạm vi và các quyết định

### 1.1. Các quyết định đã chốt

| Nội dung | Cách triển khai |
|---|---|
| Media server | Một instance MediaMTX, chạy trong một container Docker |
| Đường đi video/audio | OBS → MediaMTX → React + hls.js; không đi qua backend Java |
| Xác thực media | `authMethod: jwt`; MediaMTX tự verify cả token phát và token xem bằng public key lấy qua JWKS |
| Phát từ OBS | Backend ký Publish JWT bằng private key; ô Stream Key chứa `{sessionId}?token={PublishJWT}` |
| Xem trên web | Backend cấp `playbackUrl` + Read JWT; player gửi JWT qua header `Authorization` |
| Quyền token | Publish JWT chỉ có `publish`; Read JWT chỉ có `read`; giới hạn đúng path của session |
| Lưu key | Không lưu Publish JWT gốc, không lưu hash của stream key trong DB |
| Trạng thái | `READY`, `LIVE`, `RECONNECTING`, `ENDED` |
| Bắt đầu LIVE | Nguồn OBS thực sự online, được xác nhận bởi MediaMTX; verify JWT thành công chưa chuyển LIVE |
| Reconnect | Mặc định 60 giây từ khi backend chuyển sang `RECONNECTING`, cấu hình được |
| Giới hạn session | Tối đa một session `READY`/`LIVE`/`RECONNECTING` trên mỗi channel |
| Quyền xem MVP | Stream `PUBLIC`; người xem phải đăng nhập để xin Read JWT |
| Kết thúc | Backend đóng path session trên MediaMTX để ngăn dùng lại Publish JWT còn hạn |
| Phiên đã kết thúc | Không mở lại; buổi tiếp theo tạo session/path/token mới |
| Bảo vệ MVP | CORS giới hạn frontend + JWT; chấp nhận việc người xem hợp lệ sao chép token hoặc ghi lại video |

### 1.2. Đề xuất mặc định để triển khai

Các giá trị dưới đây là đề xuất trong plan, chưa phải các lựa chọn đã được người dùng xác nhận riêng. Tách chúng thành cấu hình hoặc contract rõ ràng để thay đổi có kiểm soát.

| Nội dung | Đề xuất MVP | Hệ quả |
|---|---|---|
| Publish JWT TTL | 24 giờ kể từ lần cấp | OBS cần token mới nếu reconnect sau khi token cũ hết hạn; đây không phải giới hạn cứng thời lượng buổi phát |
| Read JWT TTL | 15 phút | Player xin lại token khi cần mở lại phiên HLS; không ngắt phiên đang xem chỉ vì đến `exp` |
| Token Control API | 5 phút, chỉ backend giữ | Có quyền `api`, không cấp cho frontend hoặc OBS |
| Cấp lại thông tin OBS | Nút cấp JWT mới cho cùng session | Không vô hiệu hóa JWT đã cấp trước đó |
| Thu hồi key ngay trong MVP | Kết thúc session và tạo session mới | Rotate làm mất hiệu lực key cũ trong cùng session là phần mở rộng riêng, không giả lập bằng cách cấp thêm JWT |
| Session READY chưa phát | Giữ READY đến khi owner hủy/kết thúc | Khi quay lại dashboard, tiếp tục session hiện tại; không tự tạo session mới |
| HLS | HLS `fmp4`, một chất lượng | Không giả định segment luôn là `.ts`; LL-HLS có thể đánh giá sau |
| OBS | H.264 + AAC; keyframe interval khởi điểm 2 giây | Không cần transcoding/FFmpeg trong luồng chạy thật của MVP |
| Trình duyệt nghiệm thu đầu tiên | Chrome/Edge desktop có `Hls.isSupported()` | Safari/native HLS cần cách gửi credential riêng, không tự fallback sang phát không xác thực |
| Cập nhật trạng thái UI | Poll metadata khoảng 3 giây khi tab hiển thị | Chưa thêm WebSocket/SSE chỉ để cập nhật trạng thái stream |
| Worker backend | Retry task mỗi 5 giây; đối soát MediaMTX mỗi 5 giây, chia batch | Không gọi backend để auth từng playlist/segment |

### 1.3. Ngoài phạm vi MVP

- CDN, nhiều MediaMTX, cân bằng tải media, adaptive bitrate/transcoding.
- Donate, viewer count, chat, thông báo follower, analytics và recording/VOD.
- Stream PRIVATE/UNLISTED, DRM, chống quay màn hình và chống relay bằng token hợp lệ.
- Đổi cơ chế đăng nhập tài khoản hiện tại hoặc dùng media JWT thay account access token.
- Giao diện moderation mới; xử lý suspend/ban đang diễn ra giữa buổi phát cần luồng thu hồi quyền riêng về sau.
- Mobile Android và hỗ trợ đầy đủ native HLS trên Safari/iOS trong lần nghiệm thu đầu.

## 2. Hiện trạng repository và phần cần thay đổi

| Thành phần | Hiện có | Công việc cần làm |
|---|---|---|
| `stream-service` | Java 21, Spring Boot, JPA/Flyway, PostgreSQL, Redis authorities, Feign/WebClient, exception/security chung | Module nghiệp vụ stream, schema, JWT media, MediaMTX adapter, callback, scheduler và API |
| DB Stream Service | Chưa có migration feature stream trong `src/main/resources/db/migration` | Tạo migration đầu tiên; không để Hibernate tự tạo schema production |
| `api-gateway` | Route `/stream/api/**` đến port 8082; account auth qua cookie | Bổ sung ngoại lệ chính xác cho JWKS/callback; giữ các API stream khác được bảo vệ |
| `user-service` | API nội bộ tìm streamer ACTIVE theo user ID | Bổ sung contract eligibility có email verification nếu API hiện tại chưa đủ |
| `channel-service` | Channel, category, validation category nội bộ | Tra cứu channel theo streamer, kiểm tra sở hữu/trạng thái; batch metadata nếu cần cho danh sách |
| `web-app` | React/TypeScript/Vite, Axios, TanStack Query, Zustand, hls.js | Nối dashboard với API, danh sách stream và trang xem thật |
| Dashboard | Có `StreamerDashboardPage.tsx`, route `/dashboard`; server/key đang là giá trị mẫu | Giữ bố cục hiện có, thay mock bằng dữ liệu và hành động thật |
| Browse livestream | `BrowseLivestreamsTab.tsx` hiện chưa hiển thị danh sách | Hiển thị danh sách LIVE tối thiểu và link vào trang xem |
| Docker | PostgreSQL/Redis/Kafka và các hạ tầng dùng chung | Thêm đúng một service `mediamtx`, cấu hình, helper callback và healthcheck |
| Test web | Đã có `npm test` dùng `node --test tests/*.test.mjs` | Giữ bộ test hiện tại; thêm kiểm chứng hành vi mới phù hợp, cộng kiểm thử browser thật cho HLS |

Không sửa các tính năng account/channel đang hoạt động ngoài các contract tích hợp cần thiết. Trước mỗi giai đoạn, đọc lại file thực tế vì repository có thể tiếp tục được chỉnh sửa.

## 3. Kiến trúc và mạng triển khai local

```text
VIDEO/AUDIO:
OBS -- RTMP :1935 --> MediaMTX -- HLS HTTP :8898 --> React + hls.js

API NGƯỜI DÙNG:
React --> Gateway :8888 --> Stream Service :8082 --> PostgreSQL Stream
                                  |
                                  +--> Redis authorities
                                  +--> User Service / Channel Service

MEDIA VỚI BACKEND, LOCAL DEVELOPMENT:
MediaMTX --> Gateway :8888 --> Stream Service: GET JWKS / POST callback có chữ ký
Stream Service --> MediaMTX :9997: Control API với JWT quyền api
```

### 3.1. Port và địa chỉ

| Kết nối | Local đề xuất | Ghi chú |
|---|---|---|
| React → Gateway | `http://localhost:8888` | Giữ base URL hiện tại |
| Gateway → Stream Service | `http://localhost:8082/stream` | Backend tiếp tục bind loopback khi chạy trên host |
| OBS → MediaMTX | `rtmp://localhost:1935/live` | Địa chỉ public ingest tách khỏi địa chỉ Control API |
| Browser → HLS | `http://localhost:8898` | Docker map `127.0.0.1:8898:8888`, tránh trùng port Gateway |
| Backend → MediaMTX Control API | `http://127.0.0.1:9997` | Docker map `127.0.0.1:9997:9997` trong local |
| MediaMTX → JWKS | `http://host.docker.internal:8888/stream/api/media/jwks` | Qua Gateway để không cần mở port business của Stream Service cho container |
| MediaMTX → callback | `http://host.docker.internal:8888/stream/api/media/events` | Không gửi account cookie; có HMAC riêng |

MediaMTX HLS mặc định dùng port 8888, được xác nhận trong [config v1.21.2](https://github.com/bluenviron/mediamtx/blob/v1.21.2/mediamtx.yml#L289-L318). Port 8898 là lựa chọn của ứng dụng ở phía host, không thay đổi port Gateway.

### 3.2. Ranh giới truy cập

- Các API business vẫn đi qua Gateway. `CustomJwtDecoder` downstream hiện tin kết quả xác thực từ Gateway, nên không mở trực tiếp port 8082 ra Internet để giải quyết kết nối Docker.
- JWKS là endpoint không yêu cầu account cookie. Callback được đi qua lớp account auth bằng ngoại lệ method/path cụ thể, nhưng bắt buộc qua kiểm tra HMAC ở Stream Service.
- Local macOS dùng `host.docker.internal`; hướng dẫn Linux thêm `host-gateway` và kiểm tra kết nối thực tế trong giai đoạn đầu.
- Production dùng RTMPS/HTTPS và DNS media/API được cấu hình. Control API giữ trong mạng riêng; JWKS/callback có thể đi trực tiếp qua mạng nội bộ tin cậy thay vì Gateway.
- Địa chỉ public trả cho browser/OBS không được lấy từ tên service Docker hoặc từ `Host` header do client cung cấp.

## 4. Thiết kế database

### 4.1. Migration

- Tạo `stream-service/src/main/resources/db/migration/V1__create_stream_sessions.sql` với session, history, task đồng bộ media và index.
- Nếu đến lúc implement đã xuất hiện migration khác, dùng version tiếp theo; không sửa migration đã chạy ở môi trường dùng chung.
- Dùng UUID cho các ID. `channel_id`, `streamer_id`, `owner_user_id`, `category_id` chỉ là ID tham chiếu giữa service, không có FK xuyên database.
- Tái sử dụng `common.entity.BaseEntity` cho `created_at`, `updated_at` khi phù hợp. Thời gian lưu theo UTC, JVM/JDBC cấu hình nhất quán; JSON trả ISO-8601 có múi giờ.
- Sửa `stream_status_history` trong tài liệu data model từ `CREATED` thành `READY` khi triển khai migration này. Không thêm lại cột chứa JWT/stream key/hash.

### 4.2. `stream_sessions`

Giữ các trường nghiệp vụ đã có trong data model và bổ sung trường phục vụ tính nhất quán:

| Trường | Kiểu/giá trị | Mục đích |
|---|---|---|
| `id` | UUID PK | ID session, cũng dùng tạo path `live/{id}` |
| `channel_id`, `streamer_id`, `category_id` | UUID NOT NULL | Metadata đã kiểm tra qua service sở hữu |
| `owner_user_id` | UUID NOT NULL | Subject tài khoản đã xác thực; dùng kiểm tra owner, không nhầm với streamer ID |
| `title` | VARCHAR(255), NOT NULL | Trim, không rỗng |
| `visibility` | VARCHAR(30), NOT NULL | MVP chỉ ghi PUBLIC |
| `status` | VARCHAR(30), NOT NULL | READY/LIVE/RECONNECTING/ENDED |
| `playback_path` | VARCHAR(500), NOT NULL, UNIQUE | `/live/{id}/index.m3u8`, không chứa token |
| `creation_request_id` | UUID NOT NULL | Dedupe yêu cầu tạo session của cùng owner |
| `media_path_state` | VARCHAR(30), NOT NULL | PENDING_OPEN/OPEN/PENDING_CLOSE/CLOSED; trạng thái đồng bộ hạ tầng |
| `media_source_id` | UUID, nullable | Connection/source ID hiện được backend ghi nhận từ MediaMTX |
| `media_source_type` | VARCHAR(30), nullable | Phân biệt `rtmpConn`/`rtmpsConn` khi điều khiển kết nối |
| `disconnected_at` | TIMESTAMP, nullable | Lần bắt đầu mất nguồn hiện tại |
| `reconnect_deadline_at` | TIMESTAMP, nullable | Deadline 60 giây được lưu bền vững |
| `started_at`, `ended_at` | TIMESTAMP, nullable | Lần đầu LIVE và thời điểm kết thúc |
| `version` | BIGINT NOT NULL | JPA optimistic version, kết hợp khóa khi chuyển trạng thái |
| `created_at`, `updated_at` | TIMESTAMP NOT NULL | Theo convention entity của service |

`media_path_state` không phải trạng thái livestream mới. UI nghiệp vụ vẫn sử dụng bốn trạng thái đã chốt; trường này cho biết backend đã tạo/đóng path thực tế hay đang chờ thử lại.

Ràng buộc cần có:

- Unique `(owner_user_id, creation_request_id)` để retry không tạo thêm session.
- CHECK cho các enum; status RECONNECTING phải có deadline; ENDED phải có `ended_at`.
- Session active không được có media state CLOSED/PENDING_CLOSE; khi chuyển ENDED phải đồng thời chuyển media state về PENDING_CLOSE.
- Không tái sử dụng path của session khác hoặc session đã ENDED.
- Index danh sách LIVE theo visibility/status/started_at/id; index tìm deadline RECONNECTING và current session theo owner.

Giữ slot của channel trong cả thời gian chờ đóng nguồn cũ:

```sql
CREATE UNIQUE INDEX uq_stream_sessions_channel_slot
ON stream_sessions (channel_id)
WHERE status IN ('READY', 'LIVE', 'RECONNECTING')
   OR media_path_state <> 'CLOSED';
```

Đây là cơ chế bảo vệ khi hai request tạo stream đến cùng lúc. Backend vẫn trả lỗi nghiệp vụ dễ hiểu khi vi phạm index. Partial unique index cần được kiểm thử bằng PostgreSQL thật, theo [tài liệu PostgreSQL 17](https://www.postgresql.org/docs/17/indexes-partial.html).

### 4.3. `stream_status_history`

- Giữ `id`, `stream_id`, `status`, `reason`, `created_at`.
- Bổ sung `previous_status`, `media_source_id`, `media_event_id` nullable nếu cần nhận diện sự kiện.
- FK `stream_id` đến session cùng database; index `(stream_id, created_at, id)`.
- `media_event_id` có unique index khi có giá trị, hỗ trợ dedupe các callback đã tạo transition.
- Ghi history cùng transaction cập nhật session. Callback lặp không tạo thêm transition hoặc kéo dài deadline.
- `started_at` chỉ ghi khi LIVE lần đầu; reconnect không reset thời điểm bắt đầu buổi phát.

### 4.4. `stream_media_sync_tasks`

Task bền vững để tạo/đóng path và phục hồi sau lỗi; không cần thêm Kafka cho MVP.

| Trường | Mục đích |
|---|---|
| `stream_id` UUID PK/FK | Mỗi session tối đa một task đang cần đồng bộ |
| `revision` BIGINT | Nhận biết task/desired state đã thay đổi trong khi gọi MediaMTX |
| `next_attempt_at`, `attempt_count` | Backoff và theo dõi retry |
| `lease_until`, `lease_owner` nullable | Claim task có thời hạn, phục hồi khi worker chết |
| `last_error` nullable | Mã lỗi/chẩn đoán đã loại secret, không lưu token hoặc URL có token |
| `created_at`, `updated_at` | Theo dõi vận hành |

Tạo hoặc cập nhật task trong cùng transaction thay đổi trạng thái session. Worker đọc lại session để quyết định phải mở hay đóng path; không thực thi mù một lệnh CREATE cũ khi session đã ENDED.

## 5. State machine và quy tắc nghiệp vụ

### 5.1. Các transition hợp lệ

| Trạng thái trước | Trigger | Điều kiện | Trạng thái sau |
|---|---|---|---|
| Chưa có | Create session | Owner/streamer/channel/category hợp lệ, chưa chiếm slot | READY |
| READY | Verify Publish JWT | Token hợp lệ và path được mở | READY |
| READY | Nguồn online | MediaMTX xác nhận nguồn thực tế, session chưa kết thúc | LIVE |
| LIVE | Nguồn offline | Sự kiện thuộc nguồn hiện tại, không có nguồn mới thay thế | RECONNECTING |
| RECONNECTING | Nguồn online | Trong thời gian cho phép, nguồn thực tế hợp lệ | LIVE |
| RECONNECTING | Hết deadline | Khóa/recheck trạng thái hiện tại trước khi kết thúc | ENDED |
| READY/LIVE/RECONNECTING | Owner kết thúc | Đúng owner; không yêu cầu streamer vẫn ACTIVE để được dừng | ENDED |
| ENDED | Callback online/offline hoặc OBS reconnect | Không mở lại; tiếp tục đóng path nếu còn nguồn | ENDED |

### 5.2. Quy tắc chuyển trạng thái

1. Owner lấy từ principal của account access cookie qua Gateway; không lấy user/streamer ID tùy ý từ body.
2. Tạo session và cấp Publish JWT cần streamer ACTIVE, đủ điều kiện email và channel active thuộc streamer đó.
3. READY chưa có media không được hiển thị như đang LIVE. Lỗi auth hoặc lỗi codec từ OBS không được tự chuyển LIVE.
4. Nguồn offline lần đầu tạo deadline `now + reconnectGrace`. Offline lặp lại giữ nguyên deadline đó.
5. Khi reconnect thành công, đổi source ID hiện tại và xóa deadline hiện hành; giữ `started_at` cũ.
6. Callback offline của connection cũ không được làm nguồn mới đang LIVE thành RECONNECTING.
7. Khi deadline đã hết, online đến muộn không làm session LIVE trở lại. Nếu callback bị chậm, đối chiếu `onlineTime`/source thực tế để phân biệt nguồn đã về trước deadline; kiểm thử với clock kiểm soát được.
8. Client chỉ đóng tab hoặc mất mạng web không ảnh hưởng session nếu OBS vẫn gửi media.
9. OBS Stop Streaming/đóng OBS và mất mạng đều đi qua offline → RECONNECTING; kết thúc trên web đi thẳng ENDED.
10. Account bị cấm hoặc quyền streamer bị thu hồi không tự thu hồi media JWT đã cấp. MVP kiểm tra khi cấp token; luồng moderation giữa buổi cần gọi kết thúc session qua backend, không dựa vào native JWT để tra DB.

## 6. Contract API đề xuất

### 6.1. API qua Gateway cho người dùng

Controller của Stream Service dùng `/api/...`; Gateway thấy tiền tố `/stream` do servlet context path.

| Method và URL qua Gateway | Quyền | Hành vi chính |
|---|---|---|
| `POST /stream/api/streams` | Đăng nhập, streamer đủ điều kiện | Tạo READY, đăng ký path và trả thông tin OBS khi media sẵn sàng |
| `GET /stream/api/streams/me/current` | Đăng nhập | Current session của owner, gồm session đang chờ đóng nguồn; không có thì `result: null` |
| `PATCH /stream/api/streams/{id}` | Owner, session chưa ENDED | Cập nhật title/category; không đổi JWT/path vì thay metadata |
| `POST /stream/api/streams/{id}/publish-credentials` | Owner, streamer đủ điều kiện | Cấp Publish JWT mới cho session đang hoạt động và path sẵn sàng |
| `POST /stream/api/streams/{id}/end` | Đăng nhập và đúng owner | Kết thúc idempotent, yêu cầu đóng path; READY dùng API này để hủy |
| `GET /stream/api/streams` | Đăng nhập trong MVP | Danh sách PUBLIC/LIVE có phân trang, chưa có recommendation |
| `GET /stream/api/streams/{id}` | Đăng nhập | Metadata/trạng thái, không trả Publish JWT |
| `POST /stream/api/streams/{id}/playback-access` | Đăng nhập, session PUBLIC/LIVE | Trả playbackUrl + Read JWT và thời hạn |

### 6.2. Request/response

`CreateStreamSessionRequest`:

```json
{
  "requestId": "<UUID do frontend sinh cho một lần tạo>",
  "title": "<title không rỗng, tối đa 255 ký tự>",
  "categoryId": "<UUID category>"
}
```

- Backend tự tìm streamer/channel của principal và cố định visibility PUBLIC.
- Request ID giữ nguyên khi retry cùng lần tạo; không dùng nó cho lần tạo buổi phát tiếp theo.
- Nếu request ID đã tạo session cho cùng owner, trả lại session đó/cùng tiến trình provisioning; không áp dụng lại body để sửa title/category. Muốn đổi metadata dùng PATCH.
- Một request ID khác khi channel còn chiếm slot trả `409`, không âm thầm kết thúc session cũ.

`UpdateStreamSessionRequest`: chỉ nhận `title`, `categoryId` theo cùng validation. Field thiếu giữ nguyên; field có mặt nhưng `null`, title rỗng sau trim, body không có field cập nhật hoặc field ngoài contract đều trả `400`. Không nhận field `status`, `owner`, `playbackPath` từ client.

`StreamSessionResponse` tối thiểu:

- `id`, `channelId`, `title`, `categoryId`, `visibility`, `status`.
- `startedAt`, `endedAt`, `reconnectDeadlineAt`, `serverTime`.
- `publishReady`, `mediaCleanupPending` cho màn hình owner; DTO metadata/viewer không lộ chi tiết lỗi nội bộ. `publishReady` chỉ cho biết path đã được provision để OBS kết nối, không có nghĩa nguồn đã LIVE.
- Không chứa token trong response metadata/list/polling.

`StreamPublishCredentialsResponse`:

```text
sessionId
serverUrl          = rtmp://localhost:1935/live
streamKey          = {sessionId}?token={PublishJWT}
publishExpiresAt   = ISO-8601
```

`CreateStreamSessionResponse` chứa `session` và `publishCredentials` khi provisioning đã thành công. Frontend copy nguyên `streamKey`, không tự ghép token/path bằng quy tắc khác.

`StreamPlaybackAccessResponse`:

```text
sessionId
playbackUrl        = http://localhost:8898/live/{sessionId}/index.m3u8
readToken          = Read JWT
readExpiresAt      = ISO-8601
```

Danh sách dùng `ApiResponse<PageResponse<StreamListItem>>`; không tạo wrapper `StreamListResponse` khác. `PageResponse` có `content`, `page`, `size`, `totalElements`, `totalPages` theo convention hiện tại của Channel Service. `page` mặc định 0, `size` 20; `page < 0`, `size < 1` hoặc `size > 100` trả `400`. Lọc PUBLIC/LIVE và sort `started_at DESC, id ASC` trong DB trước pagination; count dùng cùng predicate. Metadata channel được lấy theo batch nếu hiển thị, không gọi từng service theo từng card.

### 6.3. HTTP và lỗi

- Dùng envelope `ApiResponse` hiện tại cho business API; status trong body khớp HTTP status.
- Create thành công lần đầu `201`; retry cùng request ID trả `200` khi đã có kết quả.
- End trả `200` khi đóng nguồn đã xác nhận; `202` khi đã ghi nhận ENDED nhưng còn chờ đóng media. Response cho biết `mediaCleanupPending`.
- Provisioning lỗi có thể trả `503` sau khi session READY đã được commit. Client dùng `me/current` hoặc retry cùng request ID; không suy ra rằng DB chưa có session.
- `Cache-Control: no-store` cho response chứa Publish JWT/Read JWT; frontend không đưa các response này vào cache bền vững.

| Nhóm lỗi | HTTP | Frontend xử lý |
|---|---|---|
| Account token không hợp lệ | 401 | Theo cơ chế refresh/login hiện tại |
| Không phải owner/không đủ điều kiện streamer | 403 | Hiển thị lý do, không retry vô hạn |
| Session không tồn tại | 404 | Màn hình không tìm thấy |
| Đã có session, session đã kết thúc, chưa LIVE | 409 | Nạp lại metadata/current session hoặc hiển thị trạng thái |
| Title/category/request không hợp lệ | 400 | Lỗi form theo convention hiện tại |
| User/Channel/MediaMTX không truy cập được | 503 | Giữ dữ liệu nhập và cho thử lại; không tạo request ID mới cho retry create |
| Callback sai HMAC/thời gian | 401/403 | Không thay đổi session |

Thêm tên lỗi riêng vào `common.exception.ErrorCode` với số code duy nhất sau các code hiện có. Ví dụ: `STREAM_SESSION_NOT_FOUND`, `STREAM_SESSION_NOT_OWNED`, `STREAM_SESSION_ALREADY_ACTIVE`, `STREAM_SESSION_STATE_INVALID`, `STREAM_NOT_LIVE`, `STREAMER_NOT_ELIGIBLE`, `MEDIA_SERVER_UNAVAILABLE`, `MEDIA_CALLBACK_UNAUTHORIZED`. Dùng `new AppException(ErrorCode...)`, không tạo exception class theo từng feature.

## 7. JWT media, JWKS và account authentication

### 7.1. Tách hai loại credential

| Credential | Ai cấp | Ai kiểm tra | Cách gửi |
|---|---|---|---|
| Account ACCESS/REFRESH | User Service | Gateway và security hiện tại | Cookie theo Auth State contract |
| Publish JWT | Stream Service | MediaMTX | Query `token` trong thông tin RTMP của OBS |
| Read JWT | Stream Service | MediaMTX khi mở phiên HLS | Authorization Bearer từ hls.js |
| Control API JWT | Stream Service | MediaMTX | Authorization Bearer, backend giữ riêng |
| Chữ ký callback | Helper MediaMTX | Stream Service | HMAC header với secret nội bộ |

Giữ nguyên account signer/cookie flow hiện có. Media JWT dùng cặp khóa RSA riêng và không được chấp nhận như account access token. Custom claim phân loại token chỉ hỗ trợ ứng dụng; quyền mà MediaMTX thực thi nằm trong `mediamtx_permissions`.

### 7.2. Nội dung và ký token

- Dùng RS256, RSA tối thiểu 2048 bit; `kid` cố định theo bộ khóa đang triển khai.
- Các claim chung: `iss`, `aud`, `sub`, `iat`, `exp`, `jti`; thêm session ID nếu cần cho chẩn đoán.
- Publish subject là owner user ID, Read subject là viewer user ID. Không dùng subject làm quyền sở hữu path ở MediaMTX.
- `iss = reallive-stream`, `aud = reallive-media` là mặc định đề xuất, phải khớp cấu hình MediaMTX.
- Mỗi token chỉ cấp một nhóm quyền cần thiết; không cấp read cho Publish JWT hoặc api cho token trả về browser.

```json
{
  "mediamtx_permissions": [
    { "action": "publish", "path": "live/<sessionId>" }
  ]
}
```

Read JWT dùng `action: read` trên cùng path. Token Control API dùng `action: api`, không gắn với account cookie và không xuất hiện trong JSON trả cho client.

### 7.3. Nạp khóa và JWKS

- Nạp private/public key từ environment/secret; không sinh cặp khóa mới mỗi lần backend khởi động hoặc mỗi session.
- Đề xuất cấu hình một dòng Base64 cho private key PKCS#8 và public key X.509, tránh lỗi escape newline trong `.env`.
- Validate khi khởi động: parse được, đúng RSA, cặp khóa khớp nhau, có `kid` và issuer/audience hợp lệ. Thiếu khóa thì fail cấu hình rõ ràng.
- `GET /stream/api/media/jwks` trả JSON JWKS chuẩn với `keys`, public `kty`, `kid`, `alg`, `use`, `n`, `e`. Không bọc `ApiResponse`, không trả RSA private parameters.
- Gateway và Stream Service cho phép đúng GET này không có account cookie; token cookie được gửi kèm vẫn tuân thủ Auth State contract hiện tại.
- MediaMTX v1.21.2 lấy JWKS khi lần đầu cần verify JWT, dùng cache chung và thử refresh ở lần xác thực sau khi đủ 1 giờ; đây không phải TTL tùy chỉnh của ứng dụng. Xem [auth manager v1.21.2](https://github.com/bluenviron/mediamtx/blob/v1.21.2/internal/auth/manager.go#L234-L309).
- Backend phải phục vụ JWKS trước các thao tác Control API cần auth để tránh phụ thuộc vòng khi bootstrap.

### 7.4. Giới hạn thu hồi và cấp lại

- Cấp lại Publish JWT cho cùng path không thu hồi token cũ; `jti` hoặc thay đổi DB không được MediaMTX tự đối chiếu để revoke.
- Kết thúc session phải xóa cấu hình path và chấm dứt nguồn thực tế. Không chỉ đợi JWT hết hạn hoặc chỉ kick một connection.
- JWT exp được kiểm tra tại lúc xác thực. Phiên HLS đã mở không tự hết quyền đúng thời điểm `exp`; token mới cần khi tạo lại phiên. Xem [HLS session v1.21.2](https://github.com/bluenviron/mediamtx/blob/v1.21.2/internal/servers/hls/session.go#L112).
- Không rotate signing key khi restart thông thường. Thay bộ khóa là thao tác vận hành riêng, cần tính cả cache JWKS và token đang còn hạn.

## 8. MediaMTX, Docker và Control API

### 8.1. Artifact hạ tầng

Các file dự kiến:

```text
infra/mediamtx/
  Dockerfile
  mediamtx.yml
  media_hook.py
  README.md
scripts/dev/
  generate-stream-media-keys.sh
scripts/testing/streaming/
  README.md
  smoke-test.sh
```

- Pin binary/image MediaMTX `1.21.2`; không dùng tag `latest` hoặc `1` trôi phiên bản trong nghiệm thu.
- Image chuẩn là `scratch`, không có shell/công cụ HTTP. Tạo custom image nhẹ có `/bin/sh`, Python 3 và CA certificates; copy binary từ image đã pin và helper vào cùng container. Python helper chỉ dùng thư viện chuẩn cho JSON/HMAC/HTTP/retry, không thêm một media server/container khác. Xem [Dockerfile chính thức](https://github.com/bluenviron/mediamtx/blob/v1.21.2/docker/standard.Dockerfile) và [hướng dẫn custom image](https://mediamtx.org/docs/kickoff/install).
- Không thêm FFmpeg vào runtime để chỉ gọi callback.
- Mount config read-only. Secret callback đưa qua env/secret file, không bake vào image. Các file key sinh local phải được ignore và không in private key vào log.

### 8.2. Cấu hình tối thiểu cần hiện thực và kiểm chứng

```yaml
authMethod: jwt
authJWTJWKS: http://host.docker.internal:8888/stream/api/media/jwks
authJWTClaimKey: mediamtx_permissions
authJWTIssuer: reallive-stream
authJWTAudience: reallive-media
authJWTExclude: []

api: true
apiAddress: :9997
rtmp: true
rtmpAddress: :1935
hls: true
hlsAddress: :8888
hlsVariant: fmp4
hlsAllowOrigins: ['http://localhost:5173']

rtsp: false
webrtc: false
srt: false
moq: false
playback: false

pathDefaults:
  source: publisher
  overridePublisher: false
  runOnOnline: python3 /hooks/media_hook.py online
  runOnOffline: python3 /hooks/media_hook.py offline

paths: {}
```

Đây là cấu hình đích local để hiện thực/kiểm thử, chưa phải cấu hình đã được chạy nghiệm thu. Phải kiểm tra cả cách env override và kết quả parse với binary đã pin. Đặc biệt: không để `all_others`, regex bắt mọi path hoặc `authJWTExclude` cho publish/read vô tình cho phép bỏ auth. `overridePublisher: false` tránh connection mới chiếm chỗ nguồn đang phát. Các tham số dựa trên [config MediaMTX v1.21.2](https://github.com/bluenviron/mediamtx/blob/v1.21.2/mediamtx.yml).

### 8.3. Các thao tác của MediaMTX adapter

| Nhu cầu | Endpoint MediaMTX v1.21.2 |
|---|---|
| Kiểm tra cấu hình path | `GET /v3/config/paths/get/{name}` |
| Đăng ký path chính xác | `POST /v3/config/paths/add/{name}` |
| Xóa cấu hình path | `DELETE /v3/config/paths/delete/{name}` |
| Kiểm tra nguồn thực tế | `GET /v3/paths/get/{name}` |
| Đối soát nhiều path | Các API list path/config có pagination |
| Kick RTMP nếu cần | `POST /v3/rtmp/conns/kick/{id}` |
| Kick RTMPS nếu cần | `POST /v3/rtmps/conns/kick/{id}` |

Contract lấy từ [OpenAPI v1.21.2](https://github.com/bluenviron/mediamtx/blob/v1.21.2/api/openapi.yaml). Không dùng tên endpoint cũ dạng `rtmpconns`; connection ID của MediaMTX khác session ID trong DB.

- Dùng WebClient đã có trong service, timeout connect/read hữu hạn. Không để request treo khi MediaMTX unavailable.
- Serialize/encode path đúng theo API của phiên bản này; không ghép URL từ input bất kỳ của client.
- `ensurePath` idempotent: kiểm tra path đã tồn tại và cấu hình đúng thì giữ nguyên; không replace path đang LIVE vô điều kiện.
- DELETE nhận 404 được xem như path đã đóng sau khi kiểm tra đúng trường hợp. Timeout là kết quả chưa rõ, phải đọc lại trạng thái và retry.
- Không tự mở lại path của ENDED. Khởi động lại MediaMTX phải đồng bộ từ DB vì cấu hình tạo qua API không được coi là dữ liệu bền vững.
- Control API JWT chỉ ở backend; không mở Control API qua Gateway hoặc trả URL quản trị cho frontend.

## 9. Callback và đồng bộ trạng thái

### 9.1. Contract callback

`POST /stream/api/media/events` nhận body nhỏ, không chứa Publish JWT hoặc `MTX_QUERY`:

```json
{
  "eventId": "<UUID giữ nguyên khi retry sự kiện này>",
  "eventType": "ONLINE",
  "path": "live/<sessionId>",
  "sourceId": "<MTX_SOURCE_ID>",
  "sourceType": "rtmpConn",
  "occurredAt": "<ISO-8601 UTC>"
}
```

- OFFLINE dùng cùng cấu trúc. Helper lấy `MTX_PATH`, `MTX_SOURCE_ID`, `MTX_SOURCE_TYPE` từ môi trường của hook, không dùng `runOnConnect` để kết luận nguồn LIVE.
- Mỗi lần gửi: `X-Media-Timestamp` là Unix timestamp theo giây của lần gửi hiện tại; `X-Media-Signature` là `sha256=<hex HMAC-SHA256>` trên `timestamp + newline + rawBody` với timestamp đúng chuỗi header. Body và event ID giữ nguyên khi retry; Content-Type là `application/json`.
- Backend kiểm tra kích thước, timestamp lệch trong cửa sổ cấu hình, chữ ký constant-time, DTO và path đúng namespace/UUID.
- Shared secret callback tách khỏi account signer và RSA private key.
- Gateway cho phép đúng POST callback đi qua khi không có account cookie. Nếu bật CSRF ở Gateway, loại trừ đúng webhook này khỏi CSRF, không loại trừ toàn bộ `/stream/**`.
- Response 2xx chỉ khi transition đã commit hoặc sự kiện hợp lệ được xác định là duplicate/stale/no-op. Lỗi tạm thời trả 503 cho helper retry.
- Log event/session/source ID và kết quả, không log Authorization, Stream Key, raw query hoặc secret.
- Helper retry với backoff hữu hạn, ví dụ 1/2/4/8 giây trong tối đa 30 giây; mỗi lần gửi có timeout riêng. Xử lý SIGINT khi hook online bị dừng, không để process cũ retry mãi sau khi nguồn đã offline.

MediaMTX hook là command được chạy theo sự kiện, không phải hàng đợi webhook bền vững. Helper cần timeout/retry hữu hạn; backend vẫn cần đối soát để phục hồi khi hook không gửi được. Xem [MediaMTX Hooks](https://mediamtx.org/docs/features/hooks#runononline).

### 9.2. Chống sai thứ tự và callback lặp

1. Kiểm tra HMAC trước khi thực hiện bất kỳ thay đổi DB hoặc Control API nào.
2. Parse session ID từ path và đối chiếu path mà backend đã cấp.
3. Với ONLINE, xác nhận path đang online với source ID/type tương ứng qua Control API; không tin một callback cũ để chuyển LIVE.
4. Với OFFLINE, đối chiếu source hiện tại trong DB và nguồn thực tế; nếu connection mới đã online thì bỏ qua offline của connection cũ.
5. Khóa/reload row trước khi áp dụng transition. Ghi history và source/deadline cùng transaction.
6. Sự kiện lặp giữ nguyên `started_at` và deadline; sự kiện không tạo thay đổi không ghi history mới.
7. Callback cho ENDED luôn giữ ENDED; nếu MediaMTX còn nguồn thì tạo/retry task đóng nguồn.
8. Callback sai thứ tự khi session còn READY phải đối chiếu trạng thái thực tế, không tạo LIVE giả chỉ để khớp thứ tự sự kiện.

### 9.3. Scheduler và recovery

- Reconnect worker tìm deadline đã hết, khóa row, đọc lại status/source để tránh timeout cạnh tranh với reconnect; thắng transition nào phải có điều kiện rõ ràng và history tương ứng.
- Media sync worker claim task có lease; cùng session không được có hai thao tác tạo/xóa path đang chạy cạnh tranh. Dùng một cơ chế serialize thống nhất cho cả API thử đồng bộ ngay và worker nền.
- Không giữ transaction ghi DB dài trong lúc chờ HTTP. Sau I/O phải đọc lại revision/status; nếu CREATE cũ vừa hoàn tất trong lúc session đã ENDED thì xóa bù và giữ task CLOSE đến khi xác nhận xong.
- Retry có backoff giới hạn; đóng ENDED chưa thành công không được mất task sau restart hoặc chỉ retry vài lần rồi bỏ.
- Reconciler so sánh DB với MediaMTX theo batch: phục hồi path active bị mất, xử lý nguồn online/offline bị lỡ callback, đóng path ENDED/orphan trong namespace ứng dụng sở hữu.
- Control API timeout là trạng thái chưa biết, không tự coi mọi session là offline. Chỉ xử lý mất nguồn dựa trên callback hợp lệ hoặc quan sát đáng tin cậy.
- Khi backend restart: dùng deadline đã lưu, không cộng lại 60 giây. Khi MediaMTX restart: khôi phục path active trước để OBS còn token có thể reconnect, không mở ENDED hoặc session hết deadline.
- MVP chạy một replica Stream Service; DB constraint, row lock và task lease vẫn bảo vệ request/worker đồng thời trong process và phục hồi sau crash.

Quy trình claim/xác nhận task cần hiện thực rõ ràng:

1. Transaction ngắn chọn task đến hạn bằng `FOR UPDATE SKIP LOCKED`, ghi lease owner, deadline và revision đã claim.
2. Reload session, quyết định desired state; trước mỗi thao tác HTTP xác nhận lease còn thuộc worker hiện tại. API muốn thử sync ngay cũng phải đi qua quy trình này.
3. Gọi MediaMTX ngoài transaction ghi DB, với timeout nhỏ hơn thời hạn lease; không chạy thêm thao tác cùng session khi lease còn hợp lệ ở worker khác.
4. Transaction xác nhận chỉ cập nhật OPEN/CLOSED và xóa task khi revision/status vẫn phù hợp. Nếu End đã ghi desired state mới, giữ task mới và thực hiện CLOSE thay vì xác nhận CREATE cũ.
5. Khi HTTP lỗi hoặc kết quả chưa rõ, giữ task và lịch retry. Lease hết hạn được claim lại; request End trả pending khi chưa giành được quyền đồng bộ, không tự mở một luồng DELETE cạnh tranh.

## 10. Workflow backend cần hiện thực

### 10.1. Create và trả thông tin OBS

```text
Client --> Gateway --> Stream Service: POST create + requestId
Stream Service --> User Service: Xác nhận streamer đủ điều kiện của principal
Stream Service --> Channel Service: Channel sở hữu, trạng thái, category
Stream Service --> DB: Transaction ghi READY + history + task OPEN
Stream Service --> MediaMTX: Đồng bộ exact path, xác nhận path có cấu hình đúng
Stream Service --> DB: Ghi media OPEN nếu session vẫn hợp lệ
Stream Service --> Client: 201 session + serverUrl + streamKey chứa Publish JWT
```

- Deduplicate request trước khi tạo mới; DB unique slot vẫn là hàng rào cuối cùng.
- Nếu MediaMTX lỗi sau commit: trả 503, giữ request ID/current session để retry và để worker khôi phục.
- Chỉ cấp Publish JWT khi kiểm tra lại owner, eligibility, status và media path còn hợp lệ.
- Nếu End cạnh tranh với Create/provisioning, không trả credential cho session ENDED; tác vụ tạo path hoàn tất muộn phải được đóng bù.

### 10.2. OBS phát và reconnect

```text
OBS --> MediaMTX: Kết nối RTMP, path + Publish JWT
MediaMTX: Verify JWT bằng public key cache, kiểm tra quyền publish/path
MediaMTX --> OBS: NetStream.Publish.Start khi chấp nhận yêu cầu publish
OBS --> MediaMTX: Gửi video/audio
MediaMTX --> Backend: Hook ONLINE
Backend: Xác nhận nguồn, transaction READY/RECONNECTING → LIVE
```

Không có HTTP callback verify stream key về backend. Khi mất mạng, OBS tự reconnect nếu đã bật Automatic Reconnect và còn retry. Khi bấm Stop/đóng OBS, streamer cần Start Streaming lại; server/key đã lưu trong OBS profile không phải nhập lại nếu còn dùng được.

### 10.3. Xin quyền xem và lấy HLS

```text
Player --> Gateway --> Stream Service: POST playback-access
Stream Service: Account đã đăng nhập, session PUBLIC/LIVE
Stream Service --> Player: playbackUrl + Read JWT + readExpiresAt
Player --> MediaMTX: GET index.m3u8 + Authorization Bearer
MediaMTX: Verify Read JWT, tạo phiên HLS
MediaMTX --> Player: Playlist
Player <--> MediaMTX: Cập nhật media playlist và tải segment theo buffer
```

`playbackUrl` được ghép từ base URL cấu hình và `playback_path` lưu trong DB, không mang Publish JWT. Backend không xử lý request tải segment và không tính viewer count trong MVP.

### 10.4. Kết thúc và chặn phát lại

```text
Client --> Backend: POST end
Backend --> DB: ENDED + endedAt + PENDING_CLOSE + history + task CLOSE
Backend --> MediaMTX: Xóa cấu hình path, ngắt publisher còn tồn tại nếu cần
Backend --> MediaMTX: Xác nhận path không còn nhận publish/không còn nguồn
Backend --> DB: CLOSED; hoàn tất task, giải phóng slot channel
Backend --> Client: 200 hoàn tất, hoặc 202 nếu còn cleanup đang chờ
```

Callback offline đến sau giữ ENDED. Publish JWT cũ còn đúng chữ ký cũng không mở được path đã đóng. Tạo buổi tiếp theo cần slot được giải phóng, dùng session/path/token mới.

## 11. Tổ chức code Java và tích hợp service khác

### 11.1. Package đề xuất

```text
com.reallive.streamservice/
  common/
    configuration/     # properties, Clock, HTTP/JWT beans thực sự dùng chung
    security/          # account security và kiểm tra credential callback
    exception/         # AppException, ErrorCode, handler hiện có
    entity/            # BaseEntity hiện có
  stream/
    controller/
      external/        # create/update/current/list/end/playback-access
      media/           # JWKS và callback MediaMTX
    service/
      impl/            # use case session, playback, lifecycle, media sync
    repository/
    entity/            # session, history, sync task và enum
    mapper/
    dto/request/
    dto/response/
    adapter/media/     # MediaMTX HTTP, JWT issuer/JWKS, remote DTO
    client/user/       # eligibility API + DTO riêng của caller
    client/channel/    # channel context/category/batch metadata API + DTO
    scheduler/         # trigger lịch; nghiệp vụ nằm trong service
```

- MediaMTX là hạ tầng nên HTTP client của nó ở `adapter/media`; User/Channel là application microservice nên ở `client`.
- Controller bind/validate input và trả response; service giữ business rules/transaction; mapper không gọi HTTP hoặc quyết định nghiệp vụ.
- Reuse Spring Security/Nimbus JOSE đang có theo dependency BOM; không nâng phiên bản Spring hoặc thêm thư viện JWT khác nếu không cần.
- Chỉ tạo class/interface có use case thực tế; không sinh folder/class rỗng theo sơ đồ.
- Mọi method của service interface có English Javadoc, mô tả ownership, idempotency, trạng thái trả về và side effect có ý nghĩa.
- Áp dụng [code-architecture](../../.agents/skills/code-architecture/SKILL.md), [code-style](../../.agents/skills/code-style/SKILL.md), [exception-handling](../../.agents/skills/exception-handling/SKILL.md), [service-javadoc](../../.agents/skills/service-javadoc/SKILL.md).

### 11.2. User Service

- Tái sử dụng `GET /user/internal/api/streamers/by-user/{userId}/active` nếu đủ contract.
- Hiện API này xác nhận streamer ACTIVE nhưng chưa kiểm tra email verification trong implementation. Bổ sung endpoint eligibility chuyên dụng hoặc mở rộng qua use case mới, không âm thầm đổi contract của caller khác.
- Eligibility tối thiểu trả/đảm bảo streamer ID đúng user, trạng thái ACTIVE và điều kiện email của việc livestream.
- Không thêm kiểm tra `users.status` vào Stream Service; Gateway sở hữu account-active check theo Auth State contract.
- Map lỗi remote rõ ràng: không đủ điều kiện → 403; unavailable/response sai contract → 503.

### 11.3. Channel Service

- Bổ sung lookup nội bộ theo streamer để Stream Service biết channel ID, ownership, trạng thái; không nhận channel ID tùy ý rồi bỏ qua ownership.
- Tái sử dụng `POST /channel/internal/api/categories/validate` để kiểm tra category đang active.
- Nếu danh sách cần channel name/avatar, bổ sung batch summary theo tập channel ID có giới hạn; không gọi N request cho N card hoặc join xuyên database.
- Không thay đổi API following/discovery hiện có chỉ để phục vụ streaming.
- Bảo vệ đường nội bộ bằng network policy hiện tại, không route các API internal qua Gateway public.

## 12. Frontend cần triển khai

### 12.1. File và route

```text
web-app/src/
  api/stream.api.ts
  types/stream.types.ts
  schemas/stream.schema.ts
  pages/dashboard/StreamerDashboardPage.tsx   # nối vào màn hình hiện có
  pages/watch/WatchStreamPage.tsx
  components/stream/StreamPlayer.tsx
  pages/browse/tabs/BrowseLivestreamsTab.tsx
  App.tsx                                   # thêm /watch/:sessionId
```

Giữ Axios/account refresh, auth store, TanStack Query và style hiện có. Các helper/hook bổ sung chỉ tách khi có trách nhiệm rõ ràng; không refactor toàn bộ dashboard/auth.

### 12.2. Dashboard streamer

- Khi vào trang, chờ kết quả auth rồi tải `me/current`; chưa có session thì hiện form tạo, không tự tạo session chỉ vì mở dashboard.
- Form có title/category, visibility PUBLIC cố định; validate Zod/Jakarta đồng nhất.
- Submit sinh request ID một lần, disable double-submit; lỗi retry dùng lại ID và kiểm tra current session.
- Khi có session: hiển thị READY/LIVE/RECONNECTING/ENDED từ API, không dùng badge Offline cố định hoặc key mock.
- Copy server URL và nguyên stream key. Key mặc định che, có thao tác hiện/copy và thời hạn của token được cấp cho web.
- Sau reload: tải metadata; nút lấy/cấp lại thông tin OBS gọi endpoint credentials. Không cố phục hồi token từ hash, localStorage hoặc URL.
- Không fetch lại Publish JWT trong mỗi lần poll. Cấp mới không cập nhật OBS tự động và không thu hồi token OBS đang giữ.
- Cập nhật title/category không đổi key; state READY hướng dẫn Start Streaming trong OBS, không dùng nút web để giả lập LIVE.
- Nút kết thúc/hủy gọi end; chỉ báo hoàn tất khi cleanup được xác nhận, còn 202 thì hiển thị đang ngắt nguồn và poll tiếp.
- Countdown reconnect dùng deadline/server time, không tự gia hạn 60 giây mỗi render/reload.
- Nếu owner mất quyền tạo stream nhưng còn session, vẫn cho xem trạng thái và kết thúc theo quyền owner của API; UI guard không thay cho backend authorization.

### 12.3. Trang xem và hls.js

- `/watch/:sessionId` tải metadata, yêu cầu đăng nhập để lấy playback access; guest được dẫn tới auth flow và quay lại stream sau login.
- READY hiện chưa bắt đầu; RECONNECTING hiện đang nối lại; ENDED dừng player và hiện đã kết thúc. Không quay vòng xin token cho session ENDED.
- Khi LIVE, xin Read JWT rồi tạo một `Hls` instance cho session. Dùng `xhrSetup` của loader hiện có để gắn Authorization cho request playlist/segment đúng media origin/path.
- Không dùng Axios account client cho segment; không gửi account cookie, Publish JWT hoặc token Control API tới media player.
- Token đọc giữ trong memory/ref. Không lưu URL kèm token, không ghi token vào console/analytics hoặc React Query cache được persist.
- Chỉ gắn token khi URL thuộc media origin/path được cấu hình; không làm lộ Authorization cho URL bên ngoài do redirect/playlist khác origin.
- Cleanup hls.js, event listener, timer và request khi unmount/đổi stream; tránh tạo nhiều player trong React StrictMode.
- Cho play/pause, âm lượng và fullscreen tối thiểu. Xử lý trình duyệt chặn autoplay bằng nút Play rõ ràng.
- 401/403 từ media: xin lại access một lần có kiểm soát nếu metadata còn LIVE, tạo lại phiên khi cần; nếu account auth thất bại thì yêu cầu login. Không retry vô hạn.
- Lỗi tải media tạm thời: backoff hữu hạn, theo dõi metadata để phân biệt đang reconnect với ended. Lỗi decode/codec phải hiển thị chẩn đoán phù hợp, không quy mọi lỗi thành token hết hạn.
- Đến `readExpiresAt` không bắt buộc destroy một phiên đang chạy bình thường; trước khi mở lại phiên cần credential còn hạn.
- `Hls.isSupported()` false thì hiển thị phạm vi hỗ trợ MVP; không dùng native video fallback có URL công khai mà bỏ xác thực.

Tham khảo [API hls.js v1.7.3](https://github.com/video-dev/hls.js/blob/v1.7.3/docs/API.md) cho loader, error recovery và destroy lifecycle; giữ version trong lockfile, không update dependency ngoài nhu cầu.

### 12.4. Danh sách livestream

- Nối `BrowseLivestreamsTab` với API list LIVE, loading/error/empty state và pagination.
- Card có title, metadata channel/category sẵn có và link trang xem; không hiển thị viewer count hoặc ảnh preview giả như dữ liệu thật.
- Khi session vừa kết thúc nhưng card chưa refresh, trang watch phải xử lý metadata mới đúng trạng thái.
- Poll khi cần và ngừng poll tab ẩn/session đã kết thúc; không thêm recommendation/following logic vào đợt này.

## 13. Cấu hình cần bổ sung

| Biến đề xuất | Giá trị local/mặc định | Nơi dùng |
|---|---|---|
| `STREAM_MEDIA_PRIVATE_KEY_BASE64` | Bắt buộc, không commit giá trị | Stream Service ký JWT |
| `STREAM_MEDIA_PUBLIC_KEY_BASE64` | Bắt buộc, public key tương ứng | Stream Service phục vụ JWKS |
| `STREAM_MEDIA_KEY_ID` | `stream-rs256-v1` | JWT header/JWKS |
| `STREAM_MEDIA_ISSUER` / `STREAM_MEDIA_AUDIENCE` | `reallive-stream` / `reallive-media` | Backend và MediaMTX phải khớp |
| `STREAM_PUBLISH_TOKEN_TTL` | `24h`, đề xuất | Backend |
| `STREAM_READ_TOKEN_TTL` | `15m`, đề xuất | Backend |
| `STREAM_CONTROL_TOKEN_TTL` | `5m`, đề xuất | Backend |
| `STREAM_RECONNECT_GRACE` | `60s`, đã chốt | Backend |
| `STREAM_RTMP_PUBLIC_URL` | `rtmp://localhost:1935/live` | Trả cho OBS |
| `STREAM_HLS_PUBLIC_BASE_URL` | `http://localhost:8898` | Ghép playbackUrl |
| `STREAM_MEDIAMTX_CONTROL_URL` | `http://127.0.0.1:9997` | Backend adapter |
| `STREAM_MEDIAMTX_JWKS_URL` | URL qua host Gateway như mục 3 | Env override `authJWTJWKS` của MediaMTX |
| `STREAM_MEDIA_CALLBACK_URL` | URL callback qua host Gateway | Helper |
| `STREAM_MEDIA_CALLBACK_SECRET` | Bắt buộc, hai bên cùng giá trị | Helper và Stream Service |
| `STREAM_MEDIA_CALLBACK_MAX_SKEW` | `60s`, đề xuất | Kiểm tra HMAC timestamp |
| `STREAM_MEDIA_SYNC_DELAY` / `STREAM_MEDIA_RECONCILE_DELAY` | `5s` / `5s`, đề xuất | Scheduler |
| `STREAM_RECONNECT_SWEEP_DELAY` / `STREAM_MEDIA_SYNC_LEASE` | `1s` / `30s`, đề xuất | Quét deadline và claim task có thời hạn |
| `STREAM_MEDIAMTX_CONNECT_TIMEOUT` / `STREAM_MEDIAMTX_READ_TIMEOUT` | `2s` / `5s`, đề xuất | Adapter |
| `STREAM_MEDIA_ALLOWED_ORIGINS` | `http://localhost:5173` | Map sang `hlsAllowOrigins` đúng dạng list |

- Dùng `@ConfigurationProperties`/validation cho nhóm cấu hình khi phù hợp; map rõ tên env ứng dụng sang tên config/env mà MediaMTX thực sự hiểu.
- Cập nhật `.env.example` root và Stream Service bằng placeholder đã sanitize. Spring Boot không tự load `.env`; ghi đúng hướng dẫn export/IDE hiện có.
- Không đặt secret trong biến `VITE_*`; browser chỉ cần public URL/token được API cấp.
- CORS Gateway giữ luồng cookie API; CORS MediaMTX phải cho preflight Authorization từ frontend đã allowlist. Không dùng `*` trong môi trường nghiệm thu bảo vệ origin.
- Script sinh khóa local ghi file vào nơi được ignore, không overwrite signing key đã dùng một cách âm thầm.

## 14. Trình tự triển khai và điều kiện hoàn tất

### Giai đoạn 0 — Kiểm chứng media và khóa contract

Dùng fixture kỹ thuật tối thiểu cho JWKS, cấp token và nhận callback trước khi hoàn thiện API nghiệp vụ; không dùng fixture này làm cơ chế auth production. Kết quả được thay bằng integration thật ở giai đoạn 2–4.

- [x] Đối chiếu các đề xuất ở mục 1.2 trước khi viết contract cố định; ghi thay đổi vào plan nếu có chỉ đạo mới.
- [x] Tạo cấu hình/custom image MediaMTX đã pin, phục vụ được JWKS chuẩn và ký được token thử bằng cặp khóa local.
- [x] Chạy OBS thật với đúng `serverUrl` và stream key dạng JWT; kiểm tra nguồn online/offline và dữ liệu hook.
- [x] Chạy hls.js với header Authorization: nhận playlist và phát cả hình/tiếng; không giả định đuôi segment.
- [x] Kiểm tra đăng ký exact path, xóa path trong lúc phát, và OBS dùng token cũ reconnect bị từ chối sau khi path đã đóng.
- [x] Kiểm tra image có helper chạy được, callback tới fixture và HLS port không xung đột. JWKS/callback qua Gateway với backend thật được kiểm chứng tiếp ở giai đoạn 2–4.

**Hoàn tất khi:** các cơ chế nền tảng chạy được cùng nhau trên đúng version, có ghi lại config và kết quả thực tế. Không tiếp tục xây toàn bộ UI dựa trên một cấu hình auth/chuyển path chưa được kiểm chứng.

**Kết quả 2026-10-24:** M0 đạt. Xem [Streaming MVP Tests](../testing/Streaming%20MVP%20Tests.md) cho 12/12 smoke, 16/16 test fixture/helper và các quan sát OBS/browser. Các mặc định đề xuất ở mục 1.2 vẫn giữ trạng thái đề xuất; nghiệm thu local không đồng nghĩa đã nghiệm thu production.

### Giai đoạn 1 — Domain và database

- [ ] Migration session/history/media task, index/constraint, sửa data model READY và các trường bổ sung.
- [ ] Entity/enums/repository/mapper theo package feature; clock UTC và transaction boundaries.
- [ ] Khóa/chuyển trạng thái có điều kiện; không dùng check-then-insert làm bảo vệ duy nhất.
- [ ] Regression test concurrent create, dedupe request, first LIVE, reconnect/deadline và ENDED terminal.

**Hoàn tất khi:** schema chạy được trên PostgreSQL 17, tạo cạnh tranh chỉ chiếm một slot và history/state nhất quán.

### Giai đoạn 2 — Account integration, eligibility và JWT media

- [ ] User/Channel client và API nội bộ thiếu; kiểm tra đúng user ID/streamer ID/channel ID.
- [ ] Business controller/service skeleton với ownership và error mapping.
- [ ] Media JWT issuer, nạp khóa, JWKS raw response, token TTL/issuer/audience/permissions.
- [ ] Gateway/Stream Service security cho đúng JWKS/callback, giữ account Auth State behavior.
- [ ] Test token ký/verify, quyền path/action, không lộ private key và response no-store.

**Hoàn tất khi:** chỉ account/owner hợp lệ xin được credential và MediaMTX kiểm tra được token do backend thật cấp.

### Giai đoạn 3 — MediaMTX adapter và provisioning

- [ ] Thêm service Docker, config/helper/healthcheck/env example và hướng dẫn mạng local.
- [ ] Implement get/ensure/delete path, source inspection, kick đúng protocol, timeout và token Control API.
- [ ] Implement task đồng bộ, serialize theo session, backoff/retry và kết quả chưa rõ khi HTTP timeout.
- [ ] Create API trả credential sau provisioning; 503 sau commit có recovery qua current session/cùng request ID.
- [ ] Test không replace nguồn LIVE khi ensurePath lặp; tạo path muộn sau End được đóng bù.

**Hoàn tất khi:** dashboard/API tạo session, copy sang OBS và MediaMTX nhận media với path đúng, không có callback verify key về backend.

### Giai đoạn 4 — Lifecycle, callback, End và recovery

- [ ] Helper ký callback/retry; backend xác thực, dedupe, source check và transaction state/history.
- [ ] ONLINE/OFFLINE, RECONNECTING 60 giây, timeout worker và xử lý callback đến muộn.
- [ ] End idempotent; giữ slot đến khi đóng media được xác nhận; task không mất qua restart.
- [ ] Reconciler cho mất callback, restart backend/MediaMTX và path orphan trong namespace sở hữu.
- [ ] Regression test callback cũ, callback lặp, End đấu với reconnect/create, Control API unavailable.

**Hoàn tất khi:** trạng thái theo nguồn thật, OBS reconnect trong grace được phục hồi và session ENDED không bị phát lại bằng key cũ.

### Giai đoạn 5 — Playback access và discovery tối thiểu

- [ ] API list LIVE/detail/current, DTO/pagination và batch channel metadata nếu cần.
- [ ] Playback access chỉ cho account hợp lệ và PUBLIC/LIVE; trả URL/path/token đúng.
- [ ] GET metadata không chứa token; playback API không gọi xử lý media hoặc đếm viewer.
- [ ] Test auth/visibility/state/path và pagination/count.

**Hoàn tất khi:** viewer lấy được thông tin phát và Read JWT hợp lệ, chưa LIVE/ENDED bị từ chối cấp quyền đúng contract.

### Giai đoạn 6 — Nối frontend

- [ ] Stream API/types/schema và TanStack Query keys cho metadata/list.
- [ ] Dashboard: tạo/cập nhật/current/copy/cấp lại credential/end/countdown, bỏ mock.
- [ ] Trang watch và StreamPlayer: auth flow, JWT header, lifecycle cleanup, recovery và trạng thái stream.
- [ ] Browse livestreams: list thật, empty/error/loading/pagination, link watch.
- [ ] Regression các test auth/dashboard hiện có; build/lint/test module web.

**Hoàn tất khi:** streamer thực hiện quy trình trên UI và một user khác xem được hình/tiếng bằng browser trong phạm vi MVP.

### Giai đoạn 7 — Nghiệm thu, runbook và tài liệu

- [ ] Chạy ma trận kiểm thử ở mục 15 trên cấu hình thật; ghi kết quả/pass/fail và giới hạn chưa hỗ trợ.
- [ ] Kiểm tra không đưa token/key vào log, DB, URL playback hoặc frontend persistent storage.
- [ ] Cập nhật architecture/data model/README/env examples và hướng dẫn OBS/khởi động/recovery theo code cuối.
- [ ] Ghi hướng dẫn deploy HTTPS/RTMPS, private Control API, backup signing key và xử lý task pending.
- [ ] Chụp UI cần thiết cho review khi có thay đổi giao diện; không coi screenshot là bằng chứng đủ cho media auth.

**Hoàn tất khi:** đạt checklist nghiệm thu ở mục 17, các giới hạn được ghi đúng và có hướng dẫn chạy lại từ môi trường sạch.

## 15. Chiến lược kiểm thử

### 15.1. Backend tự động

- Unit/service test cho policy/transition/TTL với `Clock` kiểm soát được, không dùng sleep để chờ 60 giây.
- Controller/security test cho method/path, owner, auth cookie, raw JWKS, callback HMAC và HTTP/error envelope.
- Adapter test với HTTP stub cho JSON contract, token api, timeout, retry/idempotency và lỗi response.
- PostgreSQL integration test cho Flyway, partial unique index, transaction/history, deadline và concurrent create/End. H2 hiện có không thay thế phần kiểm chứng PostgreSQL này.
- Giữ test mặc định chạy được không cần OBS/MediaMTX thật. Tạo profile integration rõ ràng nếu bổ sung Testcontainers/Failsafe; đánh dấu lệnh dự kiến và prerequisite trong README.

### 15.2. Ma trận chức năng và lỗi bắt buộc

| Kịch bản | Kết quả cần đạt |
|---|---|
| Tạo session hợp lệ | READY, một exact path, trả thông tin OBS; chưa LIVE |
| Hai create đồng thời khác request ID | Một session chiếm slot; request còn lại 409 |
| Retry cùng request ID/mất response | Không tạo session thứ hai; lấy lại tiến trình/kết quả của session cũ |
| MediaMTX unavailable khi create | Không trả credential như đã sẵn sàng; có cách retry/recover session đã commit |
| Tài khoản không có quyền/streamer không ACTIVE/email chưa đủ điều kiện | Không cấp Publish JWT |
| User khác truy cập credential/update/end | 403, không thay đổi session |
| Token sai chữ ký/hết hạn/sai path/sai action | MediaMTX từ chối; Read JWT không publish được, Publish JWT không có quyền api/read |
| OBS auth thành công nhưng chưa có nguồn đọc được | Session vẫn READY |
| OBS gửi H.264/AAC hợp lệ | MediaMTX online, session LIVE, browser có hình và tiếng |
| Watch chưa đăng nhập | Chưa nhận Read JWT/video; auth flow có thể quay lại trang xem |
| Không có JWT khi mở HLS mới | Bị từ chối cấp phiên xem |
| Playlist/segment trong phiên HLS đã có | Player tiếp tục xem; không gọi Stream Service để auth từng file |
| Token Read hết hạn trong phiên đang chạy | Không hứa ngắt ngay; mở phiên mới cần token còn hạn |
| Origin ngoài allowlist | Browser JS dùng hls.js gặp giới hạn CORS; không coi CORS là chặn mọi phần mềm/relay |
| Chỉ đóng tab web streamer | OBS tiếp tục phát, session vẫn LIVE |
| OBS mất mạng rồi tự reconnect trong grace | Cùng session/key còn hạn, trở lại LIVE; startedAt không reset |
| OBS Stop/đóng ứng dụng | RECONNECTING; muốn phát lại cần Start trong OBS, thông tin profile được giữ |
| Callback offline lặp | Deadline không bị kéo dài |
| Offline connection cũ đến sau online connection mới | Session vẫn LIVE với source mới |
| Backend restart khi RECONNECTING | Giữ deadline cũ; không nhận thêm 60 giây |
| Hết grace | ENDED và path đóng; online/token cũ không mở lại phiên |
| End từ web trong lúc LIVE hoặc READY | ENDED, ngắt/đóng path; callback muộn không đổi trạng thái |
| Media API lỗi khi End | Có task CLOSE bền vững, UI báo pending; giữ slot đến khi xác nhận đóng |
| Provisioning hoàn tất sau End | Không trả key; path vừa mở bị đóng bù |
| MediaMTX restart | Restore đúng path active hợp lệ; không restore ENDED/expired reconnect |
| Cấp lại Publish JWT | Token mới dùng được; không tuyên bố token cũ đã revoke |
| Kết thúc rồi dùng lại Publish JWT cũ còn hạn | Không phát lại được vì path đã đóng và không có catch-all |
| Thay title/category khi LIVE | Metadata thay đổi; video và credential hiện tại tiếp tục hoạt động |
| Route/unmount/đổi stream nhanh | Không có player/request/timer cũ tiếp tục chạy hoặc gắn JWT sai stream |

### 15.3. Frontend và kiểm thử tích hợp thật

- Chạy `npm test`, `npm run build`, `npm run lint` theo script hiện có. Chỉ bổ sung test có khả năng phát hiện sai behavior; không coi test đọc chuỗi source là bằng chứng player hoạt động.
- Chạy OBS thật và hai browser session/account; kiểm tra Network có manifest/segment, header đúng, không có request media qua Java backend.
- Dùng token TTL ngắn chỉ trong môi trường test để kiểm chứng expiry/recovery, không thay TTL mặc định production cho tiện test.
- Chạy smoke auth/path qua curl hoặc công cụ HTTP độc lập để phân biệt lỗi CORS với auth thực tế.
- Ghi kết quả vào `docs/testing/Streaming MVP Tests.md` khi đã chạy, gồm version, cấu hình, lệnh, kết quả và lỗi còn lại; không đánh dấu pass trước khi thực thi.

Lệnh kiểm chứng dự kiến khi implement xong:

```text
stream-service:  ./mvnw clean verify
api-gateway:     ./mvnw clean verify
user-service:    ./mvnw clean verify   (nếu sửa contract eligibility)
channel-service: ./mvnw clean verify   (nếu thêm API nội bộ)
web-app:         npm test
web-app:         npm run build
web-app:         npm run lint
Docker:          docker compose config --quiet với env local đã nạp
Media/browser:   smoke test và các case OBS/HLS thật ở trên
```

## 16. Khởi động, triển khai và phục hồi

### 16.1. Runbook local cần viết kèm implementation

1. Chuẩn bị PostgreSQL Stream/Redis và các User/Channel/Gateway service cần cho login/eligibility. Không yêu cầu các feature donate/chat chạy để phát MVP.
2. Sinh hoặc nạp cặp khóa media ổn định, callback secret và env; không ghi secret vào tài liệu, lịch sử shell hoặc output kiểm thử.
3. Chạy Stream Service và Gateway, kiểm tra raw JWKS không cần account cookie và protected API vẫn yêu cầu login.
4. Build/start một container MediaMTX; kiểm tra callback helper, host networking, Control API auth và map port HLS.
5. Login streamer → dashboard → tạo session → copy server/key vào OBS → Start Streaming.
6. Login viewer → browse/link watch → nhận Read JWT → xem hình và tiếng.
7. Thử reconnect và End, xác nhận task/path/source thực tế trước khi kết luận buổi phát đã đóng.

### 16.2. Khi lỗi hoặc restart

- DB READY nhưng media chưa sẵn sàng: dùng current session/task retry; không tạo thêm session mới để che lỗi.
- DB ENDED nhưng còn nguồn/path: ưu tiên CLOSE, giữ pending và quan sát lỗi; không reset DB về LIVE để đồng bộ ngược theo media.
- Callback mất: worker đối soát nguồn thực tế, không dựa hoàn toàn vào retry helper.
- Backend restart: giữ signing key, task và deadline; không regenerate key hoặc gia hạn reconnect tự động.
- MediaMTX restart: backend chỉ dựng lại path active được phép; Control API phải sẵn sàng và JWKS truy cập được trước khi cấp credential mới.
- JWT hết hạn lúc reconnect: owner xin Publish JWT mới và cập nhật OBS. Việc đó không hồi sinh session đã ENDED.

### 16.3. Rollout và rollback

- Deploy migration trước code dùng schema, nạp secret, kiểm tra network/JWKS, sau đó bật các API/UI streaming.
- Production xác minh HTTPS/RTMPS và Control API private bằng cấu hình thật; không chỉ dựa vào việc chạy được localhost.
- Nếu rollback tính năng, dừng cấp credential mới, kết thúc/đóng các path đã cấp và xác minh media đã dừng trước khi tắt backend worker. Không để token còn hạn phát vào MediaMTX không còn backend quản lý.
- Không drop session/history/task hoặc thay signing key như một bước rollback mặc định. Giữ schema tương thích để có thể đọc pending cleanup và audit.

## 17. Checklist nghiệm thu MVP

- [ ] Một container MediaMTX nhận OBS và phục vụ HLS, dùng JWT auth cho cả publish/read.
- [ ] Streamer tạo/cập nhật session trên web và copy credential thật vào OBS; không còn key mock.
- [ ] Verify JWT và online callback có trách nhiệm riêng; READY chỉ chuyển LIVE khi nguồn online thực tế.
- [ ] Một viewer đăng nhập mở stream và xem được hình/tiếng trên browser được hỗ trợ.
- [ ] Playlist và segment đi trực tiếp giữa player và MediaMTX; backend không gánh media hoặc auth mỗi file.
- [ ] Publish/Read/API token có quyền/path đúng, JWKS chỉ có public key; DB không có stream key/hash/JWT thô.
- [ ] Channel không có hai session cạnh tranh; retry create không tạo session trùng.
- [ ] Mất mạng/OBS dừng đi qua RECONNECTING, hồi phục đúng trong grace, timeout không bị reset qua retry/restart.
- [ ] End đóng path thật; Publish JWT cũ còn hạn không phát lại được; callback đến muộn không hồi sinh session.
- [ ] Lỗi MediaMTX không làm mất task cleanup hoặc báo UI hoàn tất giả.
- [ ] Có smoke test/kiểm thử thực tế và runbook tái hiện; tài liệu kiến trúc/data model phản ánh code cuối.
- [ ] Các đề xuất/default chưa xác nhận riêng và giới hạn MVP được ghi rõ khi bàn giao.
