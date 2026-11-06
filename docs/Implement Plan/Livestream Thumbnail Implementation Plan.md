# Kế hoạch triển khai Livestream Thumbnail

File đích: docs/Implement Plan/Livestream Thumbnail Implementation Plan.md  
Trạng thái: Step 1 (THM-101–THM-104) hoàn tất ngày 2026-11-04; Step 2 (THM-201–THM-204) hoàn tất ngày 2026-11-05; Step 3 (THM-301–THM-306) hoàn tất ngày 2026-11-05; Step 4 (THM-401–THM-405) đã triển khai và kiểm chứng tích hợp trực tiếp Stream Service → Media Worker → Redis/PostgreSQL; kiểm chứng crash/shutdown khi pipeline đang chạy chuyển sang Step 5; Step 5–8 chưa triển khai.

Nguồn: docs/architecture/Livestream Thumbnail Architecture and Flow.md.

## 1. Mục tiêu và phạm vi

Triển khai phần tạo và cập nhật thumbnail tương ứng bước 1–11 của workflow kiến trúc: Stream Service lập lịch theo từng session, giao công việc cho Media Worker, Worker dùng FFmpeg tạo JPEG và upload R2, sau đó callback để backend cập nhật kết quả vào DB.

Kiến trúc sử dụng FeignClient + HTTP, NestJS, BullMQ/Redis, FFmpeg và Cloudflare R2 theo tài liệu đã chốt.

Phạm vi đợt này gồm scheduler, queue, xử lý ảnh, upload R2, callback, cập nhật DB và cleanup. Bước 12 về cung cấp dữ liệu và hiển thị thumbnail sẽ triển khai sau; API danh sách livestream, dữ liệu card từ Channel Service, bổ sung thumbnail vào API đọc hiện có và giao diện Browse chưa thuộc đợt này. Trang watch, transcoding, viewer count, chat, donate và upload thumbnail thủ công nằm ngoài plan này.

### Thành phần tái sử dụng

| Thành phần hiện có | Cách sử dụng |
|---|---|
| Lifecycle của Stream Service | Session LIVE chưa có ảnh được xét ngay ở lượt scheduler kế tiếp; giữ nguyên startedAt và thumbnail_updated_at khi reconnect |
| StreamSessionRepository | Bổ sung truy vấn đến hạn và cập nhật job dưới khóa |
| MediaJwtIssuer | Tái sử dụng logic ký Read JWT với quyền đọc đúng path |
| FeignClient và cấu hình không retry | Gửi batch; scheduler chịu trách nhiệm gửi lại |
| Redis trong Docker Compose | Lưu queue BullMQ với prefix riêng |

## 2. Quyết định triển khai và contract

### 2.1. Dữ liệu trong Stream Service

Bổ sung migration mới; không sửa các migration đã chạy.

| Column | Kiểu | Mục đích |
|---|---|---|
| thumbnail_url | VARCHAR(1000), nullable | URL ảnh hiện tại |
| thumbnail_object_key | VARCHAR(500), nullable | Object R2 đang được tham chiếu |
| thumbnail_updated_at | TIMESTAMP UTC, nullable | Thời điểm backend cập nhật thumbnail thành công gần nhất |
| thumbnail_job_id | UUID, nullable | ID lượt xử lý gần nhất |
| thumbnail_job_status | VARCHAR(30), nullable | Trạng thái công việc |
| thumbnail_job_deadline_at | TIMESTAMP UTC, nullable | Hạn kết thúc lượt xử lý |

Trạng thái công việc gồm PENDING_DISPATCH, ACCEPTED, SUCCEEDED, FAILED. NULL nghĩa là chưa từng giao job.

Các quy tắc:

- Chưa từng cập nhật ảnh (thumbnail_updated_at IS NULL): được xét ngay ở lượt scheduler kế tiếp khi session LIVE, không chờ X giây từ started_at.
- Đã cập nhật ảnh: đến hạn khi thumbnail_updated_at + X <= now.
- Chỉ tạo job mới khi session LIVE và không có job đang chờ (PENDING_DISPATCH hoặc ACCEPTED), kể cả khi thumbnail_updated_at vẫn NULL.
- Callback thành công: cập nhật ảnh, đặt thumbnail_updated_at = now theo đồng hồ backend, chuyển SUCCEEDED và giữ job ID trong cùng transaction.
- Tạo/gửi job, ACK, callback bị bỏ qua hoặc lượt xử lý thất bại không thay đổi thumbnail_updated_at.
- Lỗi giao HTTP: giữ PENDING_DISPATCH và cùng ID.
- Job quá hạn: chuyển FAILED, giữ ảnh hiện tại và ID.
- Session đang RECONNECTING hoặc ENDED: không giao công việc.
- Không dùng updated_at để tính lịch thumbnail; không gán thumbnail_updated_at bằng started_at - X hoặc một thời điểm giả khi chưa cập nhật ảnh thành công.

### 2.2. API giữa Stream Service và Worker

Worker nhận batch:

```http
POST /internal/api/thumbnail-jobs/batch
X-Internal-Token: ...
```

```json
{
  "jobs": [
    {
      "jobId": "uuid",
      "sessionId": "uuid",
      "playbackUrl": "http://media-server:8888/live/uuid/index.m3u8",
      "readToken": "...",
      "readExpiresAt": "2026-11-03T10:05:00Z",
      "jobDeadlineAt": "2026-11-03T10:04:00Z"
    }
  ]
}
```

Response 202 trả kết quả theo từng phần tử:

```json
{
  "acceptedJobIds": ["uuid"],
  "rejectedJobs": [
    {
      "jobId": "uuid",
      "reason": "QUEUE_FULL"
    }
  ]
}
```

- Job đã tồn tại trong BullMQ vẫn được xác nhận tiếp nhận.
- Chỉ ID thuộc acceptedJobIds được chuyển sang ACCEPTED.
- Redis lỗi khiến kết quả tiếp nhận không xác định: trả 503; backend gửi lại cùng ID.
- Request sai schema: 400; sai token nội bộ: 401.
- Không áp dụng transaction toàn batch giữa HTTP, Redis và PostgreSQL.

Worker callback kết quả:

```http
POST /stream/internal/api/thumbnail-results
X-Internal-Token: ...
```

```json
{
  "jobId": "uuid",
  "sessionId": "uuid",
  "objectKey": "livestream-thumbnails/uuid/uuid.jpg",
  "contentType": "image/jpeg",
  "sizeBytes": 48210,
  "width": 640,
  "height": 360
}
```

Backend tự dựng URL từ public R2 base URL và object key đã kiểm tra, đồng thời tự đặt thumbnail_updated_at theo thời điểm cập nhật thành công. Worker không gửi thời điểm chụp ảnh hoặc quyết định mốc cập nhật DB. Response 200 có outcome APPLIED hoặc IGNORED.

Callback áp dụng khi đúng job ID, trạng thái còn chờ, chưa quá deadline và session vẫn LIVE. Kết quả trùng, cũ hoặc không đủ điều kiện được bỏ qua.

Hai API dùng token nội bộ từ biến môi trường, không dùng account cookie và không được Gateway public ra ngoài.

### 2.3. Cấu hình mặc định

| Cấu hình | Mặc định |
|---|---|
| Khoảng cập nhật X | Dev 60 giây; production 300 giây |
| Chu kỳ scheduler | 10 giây; chạy thêm một lượt khi application ready |
| Batch gửi Worker | Tối đa 50 job/request |
| Deadline một lượt thumbnail | 240 giây |
| Read JWT | Tái sử dụng TTL hiện tại, mặc định 300 giây |
| Feign connect/read timeout | 2 giây / 5 giây |
| Worker concurrency | 2 |
| Giới hạn job chưa kết thúc trong queue | 1.000 |
| Timeout FFmpeg | 20 giây |
| Timeout upload R2 | 10 giây |
| Timeout callback | 5 giây |
| JPEG | Khung 640 × 360, giữ tỉ lệ bằng padding |
| Giữ job BullMQ đã kết thúc | Tối thiểu 24 giờ |
| Quét dọn rác | Mỗi giờ; chỉ xét dữ liệu cũ trên 24 giờ |

Tất cả các giá trị trên phải cấu hình được qua biến môi trường. Validate quan hệ giữa deadline, timeout và thời gian giữ job khi khởi động.

## 3. Các Step/Task triển khai

### Step 1 — Chuẩn bị contract và nền tảng Media Worker

Vai trò: Tạo nền tảng để hai service có thể phát triển độc lập theo cùng contract.

Phụ thuộc: Không.

| Task | Công việc | Trạng thái |
|---|---|---|
| THM-101 | Ghi lại schema batch, response từng job, callback và error contract; tạo dữ liệu mẫu dùng chung cho kiểm thử Java/Node | Hoàn tất 2026-11-04 |
| THM-102 | Tạo ứng dụng độc lập media-worker/ bằng NestJS + TypeScript; chia module tiếp nhận job, queue, processor, FFmpeg, storage và callback | Hoàn tất 2026-11-04 |
| THM-103 | Thiết lập configuration validation, health/readiness, lint, unit test, build và Dockerfile có FFmpeg | Hoàn tất 2026-11-04 |
| THM-104 | Thiết lập xác thực token nội bộ; giới hạn batch, request body và URL nguồn theo MediaMTX base URL được cấu hình | Hoàn tất 2026-11-04 |

Điều kiện hoàn tất: Worker build được, khởi động được với cấu hình mẫu; request thiếu token hoặc sai schema bị từ chối trước khi thêm queue.

Bằng chứng hoàn tất Step 1:

- Contract và fixtures dùng chung tại `docs/contracts/thumbnail/`; error envelope, giới hạn batch/body và quy tắc URL nguồn được ghi rõ.
- Ứng dụng `media-worker/` có API, queue port, processor/FFmpeg/storage/callback port, config/auth/health dùng chung. Queue chưa tích hợp nên request hợp lệ trả `503 QUEUE_UNAVAILABLE`, không trả `202` hoặc `acceptedJobIds` giả; readiness trả `503` với queue `down`.
- `npm run lint`, `npm test` (14 test), `npm run test:e2e` (28 test) và `npm run build` đều đạt sau khi bổ sung regression cho timestamp đầy đủ có múi giờ, retention tối thiểu 24 giờ và fixture theo URL local.
- Đã chạy lệnh kiểm tra trong README với cấu hình local từ `.env.example`; fixture được đổi URL trong bộ nhớ và API trả `503 QUEUE_UNAVAILABLE` đúng chủ đích.
- Docker image build đạt; container chạy bằng UID/GID 10001, có FFmpeg 5.1.9, liveness trả `200`, readiness và batch hợp lệ trả `503` đúng chủ đích.
- Chưa triển khai queue/processor/FFmpeg/R2/callback thật; các phần này được triển khai từ Step 4 trở đi.

### Step 2 — Bổ sung dữ liệu và lịch theo từng session

Vai trò: Lưu bền vững thời điểm cập nhật thumbnail thành công và trạng thái công việc; chuẩn bị dữ liệu để lượt đầu được xét ngay khi LIVE, các lượt sau tính hạn từ lần cập nhật thành công gần nhất.

Phụ thuộc: Step 1.

| Task | Công việc | Trạng thái |
|---|---|---|
| THM-201 | Thêm migration, entity fields, enum và index phục vụ truy vấn LIVE chưa có ảnh hoặc đến hạn theo thumbnail_updated_at, job pending và job quá deadline | Hoàn tất 2026-11-05 |
| THM-202 | Khi session lần đầu LIVE, giữ thumbnail_updated_at NULL để được xét ngay ở lượt scheduler kế tiếp nếu không có job đang chờ; giữ startedAt của lần LIVE đầu tiên, event trùng và reconnect không reset các mốc | Hoàn tất 2026-11-05 |
| THM-203 | Với session hiện có chưa có thumbnail, giữ thumbnail_updated_at NULL; session LIVE và không có job đang chờ được xét ngay ở lượt scheduler kế tiếp, không chờ startedAt + X và không backfill thời điểm cập nhật thành công giả | Hoàn tất 2026-11-05 |
| THM-204 | Cập nhật mapper, snapshot và DTO nội bộ cần thiết cho điều phối/callback thumbnail; việc bổ sung thumbnail vào response API đọc sẽ triển khai ở đợt sau | Hoàn tất 2026-11-05 |

Dữ liệu thumbnail ban đầu là NULL, bao gồm thumbnail_updated_at. Migration không tạo job, ký JWT hoặc gán thời điểm cập nhật thành công khi chưa có ảnh.

Điều kiện hoàn tất: Dữ liệu của session mới và session có sẵn hỗ trợ xét lượt đầu ngay khi LIVE, không chờ X; các lượt sau tính từ thumbnail_updated_at. Reconnect, event trùng và chỉnh title/category không làm thay đổi các mốc này.

Bằng chứng hoàn tất Step 2:

- `V4__add_stream_session_thumbnail_schedule.sql` thêm đúng sáu cột nullable `thumbnail_url`, `thumbnail_object_key`, `thumbnail_updated_at`, `thumbnail_job_id`, `thumbnail_job_status`, `thumbnail_job_deadline_at`; không backfill, không tạo job và không sửa V1–V3.
- Ràng buộc DB giới hạn `thumbnail_job_status` trong `PENDING_DISPATCH`, `ACCEPTED`, `SUCCEEDED`, `FAILED` và buộc status/job ID/deadline cùng NULL hoặc cùng có giá trị. Enum `ThumbnailJobStatus` lưu dạng chuỗi và cho phép NULL.
- Ba partial index: session LIVE không có job đang chờ theo `(thumbnail_updated_at NULLS FIRST, id)`, job `PENDING_DISPATCH` theo `(thumbnail_job_deadline_at, id)`, và job `PENDING_DISPATCH`/`ACCEPTED` quá hạn theo `(thumbnail_job_deadline_at, id)`. Predicate không dùng `now()`.
- `StreamSessionSnapshot` và mapper nội bộ giữ đủ sáu trường thumbnail/job; response owner, metadata, create/update và playback-access giữ nguyên contract, không lộ trường điều phối.
- Lifecycle tái sử dụng `onMediaOnline`: lần LIVE đầu giữ `thumbnail_updated_at` NULL và `startedAt` là mốc LIVE đầu tiên; event trùng, reconnect và cập nhật title/category không reset `startedAt` hoặc làm mất thumbnail/job đã có.
- Migration test chứng minh cài mới chạy 4 migration, nâng cấp từ V1/V2/V3 giữ nguyên dữ liệu và để sáu trường mới NULL, mọi trạng thái job hợp lệ được chấp nhận, cấu hình job thiếu/không hợp lệ bị từ chối.
- `./mvnw verify` đạt 189 test, 0 failure, 0 error, 0 skip; build và đóng gói thành công.

Tại thời điểm hoàn tất Step 2, scheduler, Feign client, ký Read JWT, BullMQ, FFmpeg, R2 và callback vẫn thuộc các step tiếp theo.

### Step 3 — Scheduler và điều phối batch tại Stream Service

Vai trò: Thực hiện bước 2–4 của workflow, bảo đảm có thể khôi phục sau restart.

Phụ thuộc: Step 2.

| Task | Công việc | Trạng thái |
|---|---|---|
| THM-301 | Tạo scheduler thumbnail riêng với reconnect scheduler; chạy khi application ready và theo fixed delay, ngăn hai lượt chạy chồng trong cùng process | Hoàn tất 2026-11-05 |
| THM-302 | Đầu mỗi lượt, kết thúc các job PENDING_DISPATCH/ACCEPTED quá deadline trong transaction ngắn | Hoàn tất 2026-11-05 |
| THM-303 | Chọn session LIVE chưa có ảnh (thumbnail_updated_at IS NULL) ngay ở lượt quét kế tiếp, hoặc đã đến hạn theo thumbnail_updated_at + X, và không còn job đang chờ; gửi lại pending còn hạn với cùng ID; giới hạn số lượng truy vấn, khóa và recheck trước khi tạo job | Hoàn tất 2026-11-05 |
| THM-304 | Dùng TransactionTemplate lưu job ID, trạng thái và deadline; commit trước khi ký token và gọi HTTP | Hoàn tất 2026-11-05 |
| THM-305 | Tạo FeignClient dưới module stream.client; ghép HLS URL đầy đủ bằng internal MediaMTX base URL và playback path; gửi batch với retry tự động tắt | Hoàn tất 2026-11-05 |
| THM-306 | Áp dụng response theo từng ID; chỉ chuyển PENDING_DISPATCH → ACCEPTED nếu ID vẫn khớp; lỗi HTTP/timeout giữ trạng thái hiện tại | Hoàn tất 2026-11-05 |

Quy tắc triển khai:

- Dùng batch truy vấn để tránh tải toàn bộ session LIVE vào RAM.
- Tính cutoff = now - X một lần mỗi lượt quét: điều kiện thời gian là thumbnail_updated_at IS NULL OR thumbnail_updated_at <= cutoff. Chỉ tạo lượt mới khi session LIVE và thumbnail_job_status không phải PENDING_DISPATCH/ACCEPTED (bao gồm trường hợp trạng thái job NULL). Lượt đầu không phụ thuộc tuổi của started_at.
- Job gửi lại giữ ID và deadline ban đầu.
- Read JWT ký bằng danh tính kỹ thuật media-worker, quyền read đúng path; không mượn account streamer và không gọi publish-credentials.
- Bổ sung method ký token kỹ thuật trong MediaJwtIssuer, dùng chung bộ ký và key hiện có.
- Callback hoàn tất trước response 202 phải được giữ nguyên.
- Dùng khóa hàng để hai scheduler không tạo hai job ID khác nhau cho cùng session; việc gửi trùng cùng ID được Worker xử lý.
- Scheduler thumbnail không được làm nghẽn reconnect scheduler; cấu hình executor riêng.

Điều kiện hoàn tất: Tắt Worker, restart backend hoặc gây timeout đều không mất công việc; DB transaction đã kết thúc trước khi gọi HTTP.

Bằng chứng hoàn tất Step 3:

- `SchedulingConfiguration` tập trung khai báo hai bean `reconnectTaskScheduler` và `thumbnailTaskScheduler`, mỗi bean có pool 1 riêng, lần lượt dùng prefix `reconnect-scheduler-` và `thumbnail-scheduler-`. Hai tác vụ chỉ định rõ bean qua `@Scheduled(scheduler = ...)`. `ThumbnailDispatchScheduler` chạy một lượt khi `ApplicationReadyEvent` và theo fixed delay mặc định 10 giây; `AtomicBoolean` bỏ qua lượt chồng trong cùng process. Scheduler thumbnail tắt mặc định ở profile test và chỉ bật bằng `stream.thumbnail.enabled=true`; lỗi hoặc rejection ở scheduler thumbnail không ảnh hưởng reconnect scheduler. `ThumbnailConfiguration` đăng ký properties và validate khi tính năng được bật.
- Mỗi lượt giới hạn tối đa `stream.thumbnail.batch-size` (1–50). Đầu lượt, job `PENDING_DISPATCH`/`ACCEPTED` có deadline `<= now` được chuyển `FAILED` trong transaction ngắn theo từng hàng, có khóa và recheck; giữ nguyên job ID, deadline, ảnh và `thumbnail_updated_at`.
- Truy vấn đến hạn dùng predicate tường minh `thumbnail_job_status IS NULL OR thumbnail_job_status NOT IN ('PENDING_DISPATCH','ACCEPTED')` và `thumbnail_updated_at IS NULL OR thumbnail_updated_at <= cutoff`, sắp xếp `NULLS FIRST` + id và `LIMIT`; session LIVE chưa có ảnh được chọn ngay, session vừa cập nhật trong X không bị chọn. Job `PENDING_DISPATCH` còn hạn được gửi lại trước với cùng job ID và deadline gốc; job mới chỉ được tạo sau khi khóa hàng và recheck trạng thái.
- Job mới/claim được lưu bằng `TransactionTemplate` và commit trước khi ký Read JWT hoặc gọi HTTP; `ThumbnailDispatchServiceImpl` chạy với `Propagation.NEVER`.
- Feign interface `stream/client/thumbnail/ThumbnailWorkerApiClient` gọi `POST /internal/api/thumbnail-jobs/batch`; `ThumbnailWorkerClient` bọc lời gọi, gửi header `X-Internal-Token`, bắt lỗi HTTP/timeout/response hỏng và trả kết quả rỗng để job giữ `PENDING_DISPATCH`. Playback URL ghép từ internal MediaMTX HLS base URL và playback path, tắt retry tự động (`Retryer.NEVER_RETRY`) và dùng timeout cấu hình 2 giây/5 giây.
- `MediaJwtIssuer.issueWorkerRead` ký Read JWT subject `media-worker` bằng issuer/key hiện có, chỉ cấp quyền read `live/<sessionId>`, hạn mặc định 300 giây và không ngắn hơn job deadline.
- ACK chỉ áp dụng `acceptedJobIds` của batch hiện tại, loại ID vừa accepted vừa rejected, khóa hàng và chỉ chuyển `PENDING_DISPATCH` → `ACCEPTED` khi job ID còn khớp. `QUEUE_FULL`, HTTP 400/401/503, timeout, response hỏng/thiếu `acceptedJobIds`, callback `SUCCEEDED` đến trước ACK, `FAILED` và job ID bị thay thế đều không bị ghi đè; job giữ `PENDING_DISPATCH` và được gửi lại với cùng ID.
- Dispatch đọc `MEDIA_WORKER_INTERNAL_TOKEN`, không dùng `STREAM_SERVICE_INTERNAL_TOKEN` dành cho callback; regression test kiểm tra hai secret khác nhau và từ chối dùng callback token thay worker token. Dữ liệu test dùng cùng session ID trong playback path, assertion URL dựa trực tiếp trên session ID của payload.
- Kiểm thử gồm 20 test PostgreSQL cho dispatch/expiry/ACK/đồng thời, 3 test repository selection/expiry, 1 test bật scheduler với executor riêng, 1 test Worker không truy cập được, 5 test scheduler, 6 test cấu hình/Feign/Read JWT và 3 test khởi động cấu hình thumbnail sau khi tập trung scheduler, cùng 4 test wrapper HTTP client. `./mvnw clean verify` đạt 232 test, 0 failure, 0 error, 0 skip; build và đóng gói thành công.
- Tại thời điểm hoàn tất Step 3, callback endpoint, BullMQ, FFmpeg, R2 và cleanup chưa được triển khai; API/frontend thumbnail thuộc đợt sau.

### Step 4 — Tiếp nhận batch và quản lý BullMQ

Vai trò: Thực hiện bước 5–6, tách mỗi phần tử thành một công việc độc lập.

Phụ thuộc: Step 1; kiểm thử tích hợp với Step 3.

| Task | Công việc |
|---|---|
| THM-401 | Thêm queue thumbnail với prefix Redis riêng; lấy jobId từ Stream Service làm BullMQ job ID |
| THM-402 | Tách batch, kiểm tra job tồn tại trước khi xét capacity và trả kết quả tiếp nhận từng ID |
| THM-403 | Giới hạn queue, giữ completed/failed job trong thời gian chống trùng; không xóa job ngay sau xử lý |
| THM-404 | Processor lấy job theo concurrency; kiểm tra cả JWT expiry và job deadline trước khi chạy FFmpeg |
| THM-405 | Thiết lập attempts một lần, không tự chạy lại pipeline khi lỗi; xử lý shutdown, Redis disconnect và stalled job theo chính sách chấp nhận bỏ một lượt thumbnail |

Mặc định phiên bản đầu có một Worker API nhận batch; các request enqueue được tuần tự hóa qua mutex để việc kiểm tra capacity và thêm queue không vượt giới hạn do request đồng thời. Processor có thể mở rộng riêng, dùng chung queue. Không triển khai nhiều API replica trong phạm vi này.

Job tồn tại được xác nhận ngay cả khi queue đầy. Redis lỗi sau khi thêm một phần batch phải cho phép backend gửi lại an toàn.

BullMQ chỉ chống trùng ID khi bản ghi job còn tồn tại; cấu hình retention và test phải bảo vệ điều này. Tài liệu BullMQ (https://docs.bullmq.io/guide/jobs/job-ids)

Điều kiện hoàn tất: Gửi cùng batch nhiều lần chỉ tạo một công việc cho mỗi ID; job đã xử lý vẫn không bị thêm lại trong thời gian giữ bản ghi.

Cập nhật triển khai Step 4:

- Media Worker sử dụng BullMQ/Redis, giới hạn batch/capacity, chống trùng theo job ID và giữ completed/failed ít nhất 24 giờ; lỗi Redis hoặc kết quả không xác định trả `503`.
- Consumer mặc định tắt (`MEDIA_WORKER_PROCESSOR_ENABLED=false`); bật khi chưa có pipeline sẽ không khởi động. Khi được kết nối với pipeline, consumer kiểm tra JWT expiry và job deadline trước xử lý, cấu hình `attempts=1` và `maxStalledCount=0`.
- Đã chạy lint, build, 15 unit test, 28 HTTP contract test và 11 integration test với Redis trong Testcontainers, gồm HTTP `202`, queue đầy/trùng, Redis mất kết nối, thêm một phần batch, concurrency, expiry và mô phỏng mất lock.
- Đã kiểm chứng trực tiếp Stream Service → Media Worker thật → Redis/PostgreSQL: job được gửi qua HTTP, lưu đúng `jobId`/payload/retention, session chuyển `ACCEPTED`; gửi lại cùng `jobId` sau khi đưa session về `PENDING_DISPATCH` không tạo job trùng. Job đang chờ vẫn tồn tại sau SIGTERM và SIGKILL của Worker.
- Chưa kiểm chứng crash/shutdown cưỡng bức khi pipeline FFmpeg đang chạy; chuyển sang Step 5 khi có processor thật. Chưa có pipeline FFmpeg, R2 hoặc callback thật; giữ scheduler dispatch tắt khi chưa sẵn sàng xử lý.


### Step 5 — FFmpeg và JPEG

Vai trò: Thực hiện bước 7 của workflow; đọc HLS và tạo một file JPEG hợp lệ từ livestream.

Phụ thuộc: Step 4.

| Task | Công việc |
|---|---|
| THM-501 | Tạo FFmpeg adapter dùng spawn() với argument array, không dùng shell; truyền Authorization Bearer qua HTTP header |
| THM-502 | Đọc HLS từ MediaMTX, trích một frame có thể giải mã, resize/pad và xuất một JPEG vào thư mục tạm riêng của job |
| THM-503 | Giới hạn thời gian chạy, tài nguyên và dung lượng output; khi timeout/shutdown phải kết thúc tiến trình con |
| THM-504 | Kiểm tra JPEG: file tồn tại, kích thước hợp lệ, định dạng và dimensions đúng |
| THM-505 | Kiểm chứng crash/shutdown khi FFmpeg đang chạy; tiến trình con được kết thúc, không tự chạy lại pipeline và file tạm được dọn khi có thể |

Kết quả của phần trích ảnh là đường dẫn file JPEG tạm và metadata cần thiết cho bước upload. File phải tồn tại cho đến khi bước upload ở Step 6 sử dụng xong; test và công cụ chạy thử độc lập tự dọn file sau khi kiểm tra.

Consumer production tiếp tục tắt. Chưa đánh dấu job hoàn tất chỉ vì đã tạo được JPEG; chỉ ghép và bật pipeline thực tế sau khi upload và callback ở Step 6–7 sẵn sàng.

Điều kiện hoàn tất: Với stream OBS thật, FFmpeg đọc HLS bằng Read JWT và tạo JPEG hợp lệ trong thư mục tạm. MediaMTX mất kết nối, FFmpeg lỗi/timeout hoặc filesystem lỗi được kiểm chứng; không upload R2 hoặc callback trong bước này.

Cập nhật triển khai Step 5:

- Đã bổ sung FFmpeg runner, service tạo thư mục tạm riêng, kiểm tra JPEG bằng FFprobe và decode, công cụ CLI độc lập cùng các cấu hình giới hạn. Consumer production vẫn tắt.
- Đã kiểm thử HLS giả lập có Bearer bằng FFmpeg thật trên host, ảnh 640 × 360, job hết hạn/URL sai và timeout với nguồn không phản hồi; test queue Step 4 vẫn chạy.
- Chưa nghiệm thu OBS → MediaMTX thật, Docker FFmpeg, SIGKILL/crash container và toàn bộ test biên (ảnh dọc, file hỏng, I/O, shutdown, concurrency). Chưa đánh dấu Step 5 hoàn tất.

### Step 6 — Upload JPEG lên R2

Vai trò: Thực hiện phần upload và dọn file tạm ở bước 8 của workflow.

Phụ thuộc: Step 5.

| Task | Công việc |
|---|---|
| THM-601 | Tạo storage adapter trong Media Worker sử dụng S3 SDK và cấu hình Cloudflare R2 từ biến môi trường |
| THM-602 | Upload JPEG đã kiểm tra với content type image/jpeg và object key bất biến theo session/job ID; xác nhận upload thành công |
| THM-603 | Giới hạn thời gian upload; xử lý lỗi hoặc timeout bằng cách kết thúc lượt xử lý, không callback khi chưa xác nhận upload thành công |
| THM-604 | Dọn file tạm trong finally sau khi upload thành công hoặc thất bại; lỗi dọn file không ngăn callback ở Step 7 nếu upload đã thành công |
| THM-605 | Kiểm thử tích hợp tạo JPEG → upload → tải và kiểm tra ảnh trên R2; kiểm chứng lỗi upload, timeout và dọn file |

Dùng object key `livestream-thumbnails/{sessionId}/{jobId}.jpg`. Không ghi đè một URL cố định, tránh frontend/CDN giữ ảnh cũ.

Worker giữ R2 credential và upload JPEG; Stream Service không nhận binary ảnh. R2 cung cấp API tương thích S3. Tài liệu Cloudflare R2 (https://developers.cloudflare.com/r2/api/s3/api/)

Consumer production tiếp tục tắt cho đến khi callback Step 7 được triển khai và pipeline hoàn chỉnh sẵn sàng.

Điều kiện hoàn tất: Với JPEG từ Step 5, Worker upload thành công và có thể tải lại đúng ảnh từ R2; khi upload lỗi hoặc timeout không báo thành công hay callback. File tạm được dọn sau khi sử dụng, còn lỗi dọn file được ghi nhận để cleanup xử lý sau.

### Step 7 — Callback và cập nhật thumbnail nguyên tử

Vai trò: Thực hiện bước 9–11; bảo vệ kết quả khi callback và response đến không đúng thứ tự.

Phụ thuộc: Step 2 và Step 6.

| Task | Công việc |
|---|---|
| THM-701 | Worker callback một lần qua HTTP với timeout hữu hạn; tắt retry trong HTTP client |
| THM-702 | Thêm controller nội bộ tại Stream Service, security chain/token validation riêng, không phụ thuộc account cookie |
| THM-703 | Khóa session trong transaction ngắn; kiểm tra ID, trạng thái, deadline, session LIVE và thông tin ảnh |
| THM-704 | Trong cùng transaction, lưu URL/key, đặt thumbnail_updated_at = now theo đồng hồ backend, chuyển SUCCEEDED và giữ ID; scheduler tính lượt tiếp theo bằng thumbnail_updated_at + X |
| THM-705 | Trả APPLIED/IGNORED; xử lý callback trùng, callback cũ, session ENDED/RECONNECTING và response 202 đến muộn |

Quy tắc lỗi:

- Callback lỗi hoặc timeout: Worker ghi log rồi kết thúc, không gửi lại.
- Backend đã commit nhưng response mất: giữ nguyên ảnh và trạng thái SUCCEEDED.
- Backend chưa cập nhật được: scheduler hiện tại kết thúc job khi quá hạn; lượt sau có thể tạo job mới.
- Kiểm tra ảnh tại backend gồm object key đúng session/job và JPEG metadata hợp lệ; thời điểm cập nhật do backend tự đặt khi áp dụng thành công, không gọi R2 trong transaction cập nhật.

Điều kiện hoàn tất: Các thứ tự đến khác nhau của callback, ACK và scheduler không ghi đè ảnh mới bằng kết quả cũ.

### Step 8 — Dọn rác, vận hành và nghiệm thu

Vai trò: Hoàn thiện phần vận hành và kiểm chứng pipeline tạo ảnh, upload và cập nhật thumbnail vào DB.

Phụ thuộc: Step 1–7.

| Task | Công việc |
|---|---|
| THM-801 | Dọn thư mục tạm quá hạn tại Worker; chỉ xóa file không thuộc job đang chạy |
| THM-802 | Worker quét object thumbnail cũ trên 24 giờ; hỏi Stream Service theo batch object key nào còn được tham chiếu trước khi xóa |
| THM-803 | Thêm API nội bộ kiểm tra object reference; nếu backend lỗi hoặc response không đầy đủ thì bỏ qua việc xóa |
| THM-804 | Bổ sung Docker Compose/config mẫu, kết nối Worker → MediaMTX và Worker → Stream Service; giữ cổng nội bộ khỏi Gateway |
| THM-805 | Thêm log/metrics cho dispatch, queue wait, processing, callback, deadline và cleanup; che JWT, secret và credential |
| THM-806 | Viết hướng dẫn chạy, test plan và bảng nghiệm thu các rủi ro trong tài liệu kiến trúc; kiểm chứng R2 và DB trực tiếp, regression cho API hiện có |

Dọn object theo prefix thumbnail riêng, có phân trang và giới hạn mỗi lượt. Giữ mọi object còn được tham chiếu, kể cả thumbnail của session đã ENDED. Không xóa ảnh khi callback timeout vì backend có thể đã lưu thành công.

Endpoint kiểm tra reference chỉ phục vụ cleanup, không được gọi trước mỗi job thumbnail.

Điều kiện hoàn tất: Có thể khởi động môi trường bằng cấu hình mẫu; các object/file bỏ lại được dọn an toàn; với OBS stream thật, object JPEG tồn tại trên R2 và đúng session trong DB có thumbnail mới, thumbnail_updated_at cùng trạng thái SUCCEEDED. Kiểm tra bằng R2/HTTP tải ảnh và truy vấn DB, không phụ thuộc API danh sách hoặc frontend.

## 4. Kiểm thử và tiêu chí nghiệm thu

Viết test cùng từng task; Step 8 tổng hợp kết quả.

| Nhóm | Kịch bản bắt buộc |
|---|---|
| Migration và lịch | Schema mới; thumbnail_updated_at ban đầu NULL, không gán thời điểm giả; session mới hoặc có sẵn LIVE chưa có ảnh được xét ngay không chờ X, lượt sau theo thumbnail_updated_at + X; reconnect/event trùng và cập nhật title không đổi mốc |
| Scheduler | LIVE chưa có ảnh và không có job đang chờ được chọn ngay ở lượt quét kế tiếp; READY/RECONNECTING/ENDED không được chọn; đã có ảnh thì chưa đủ X không lấy và đúng hạn được lấy; không tạo job mới khi còn job đang chờ kể cả thumbnail_updated_at NULL; restart sau commit vẫn gửi tiếp cùng ID |
| Transaction | Không giữ transaction khi gọi HTTP; callback/ACK/deadline cạnh tranh không ghi đè nhau |
| Dispatch | Connect timeout, read timeout, Worker ngừng, queue đầy, response từng phần, response 202 mất |
| Queue | Gửi trùng trước/sau hoàn tất; Redis lỗi giữa batch; capacity với request đồng thời; expiry khi chờ |
| FFmpeg | HLS có JWT; JWT sai/hết hạn; MediaMTX restart; không có frame; timeout; tiến trình con được dọn |
| File và R2 | JPEG hợp lệ; ổ đĩa lỗi/đầy; upload timeout; dọn file lỗi nhưng callback vẫn chạy |
| Callback | Đến trước ACK; trùng ID đã SUCCEEDED; sai ID; quá deadline; RECONNECTING/ENDED; backend commit rồi mất response; chỉ callback được áp dụng thành công mới cập nhật thumbnail_updated_at |
| R2 và DB | Object JPEG tải được; URL/key trong DB trỏ đúng ảnh; thumbnail_updated_at và SUCCEEDED chỉ đổi khi callback được áp dụng thành công |
| Cleanup | Không xóa ảnh đang tham chiếu; callback timeout vẫn giữ ảnh; backend không truy cập được thì không xóa |
| Regression | Create/update/End, reconnect, metadata, playback-access và Stream Manager tiếp tục hoạt động |

Công cụ kiểm thử:

- Java: unit test và PostgreSQL Testcontainers cho migration, khóa và cạnh tranh cập nhật.
- Worker: Jest; integration với Redis thật; test HTTP contract và FFmpeg với nguồn media kiểm soát được.
- E2E: OBS → MediaMTX → Worker → R2 → callback → Stream Service cập nhật DB; kiểm tra object ảnh và record session trực tiếp.

Đạt nghiệm thu khi:

1. Session LIVE chưa có ảnh và không có job đang chờ được xét ở lượt scheduler kế tiếp, không chờ X giây từ startedAt; trong điều kiện pipeline hoạt động bình thường, ảnh được tạo trên R2 và cập nhật DB sau thời gian dispatch, chờ queue và xử lý.
2. Hai lần cập nhật ảnh thành công liên tiếp cách nhau tối thiểu X.
3. Không làm thay đổi luồng xem HLS và lifecycle hiện tại.
4. Gửi lại cùng job không tạo thêm pipeline xử lý trong thời gian chống trùng.
5. Mất một lượt thumbnail giữ ảnh hiện tại và không khiến session bị kẹt job vĩnh viễn.
6. Các rủi ro đã thống nhất đều có test tự động hoặc bước kiểm chứng thủ công cụ thể.

## 5. Thứ tự triển khai và đưa vào sử dụng

Thực hiện theo thứ tự Step 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8. Task có test chỉ được đánh dấu hoàn tất khi test liên quan đã qua.

- Migration và DTO nội bộ triển khai trước, có tính tương thích với dữ liệu cũ.
- Tính năng có cờ bật/tắt scheduler; test profile mặc định tắt scheduler.
- Giữ consumer production và scheduler dispatch tắt trong Step 5–6; chỉ bật sau khi Worker, upload R2 và callback Step 7 đã sẵn sàng.
- Chạy dev với X = 60 giây, nghiệm thu OBS → ảnh trên R2 → dữ liệu thumbnail trong DB; production đặt X = 300 giây.
- Khi cần dừng tính năng, tắt scheduler và ngừng nhận job mới; giữ ảnh và metadata thumbnail hiện tại.
- Theo dõi tuổi job lâu nhất, số pending quá hạn, thời gian FFmpeg và tỷ lệ callback được áp dụng để điều chỉnh batch/concurrency.

Giả định của phiên bản đầu: một Worker API nhận batch, queue dùng Redis hiện có với prefix riêng, ảnh có thể kiểm tra qua public R2 base URL; phạm vi nghiệm thu kết thúc ở ảnh trên R2 và dữ liệu thumbnail đã cập nhật trong DB. Các giá trị cấu hình trong mục 2.3 là mặc định triển khai ban đầu và phải ghi rõ trong file cấu hình mẫu.
