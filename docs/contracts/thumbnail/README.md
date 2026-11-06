# Livestream Thumbnail Worker Contract

Contract dùng chung giữa Stream Service (Java) và `media-worker` (NestJS) cho tính năng thumbnail livestream. Worker tiếp nhận job qua BullMQ/Redis và đã có capture JPEG độc lập bằng FFmpeg; upload R2 thuộc Step 6, callback tại Stream Service thuộc Step 7. Consumer production vẫn tắt.

- Base URL của Worker trong môi trường nội bộ: `http://media-worker:8090` (cấu hình khi triển khai; không public qua Gateway).
- Mọi API nội bộ dùng header `X-Internal-Token`; giá trị lấy từ biến môi trường, không dùng account cookie, không dùng JWT người dùng.
- Token nội bộ không xuất hiện trong body, log, fixture hoặc thông báo lỗi. Fixtures chỉ chứa giá trị tổng hợp, không phải credential thật.

## 1. Worker nhận batch

```http
POST /internal/api/thumbnail-jobs/batch
Content-Type: application/json
X-Internal-Token: <internal token>
```

### 1.1. Request schema

| Field | Kiểu | Bắt buộc | Quy tắc |
| --- | --- | --- | --- |
| `jobs` | array | Có | 1 đến `MEDIA_WORKER_MAX_BATCH_SIZE` phần tử, mặc định tối đa 50 |
| `jobs[].jobId` | string | Có | UUID do Stream Service tạo; dùng làm BullMQ job ID |
| `jobs[].sessionId` | string | Có | UUID của stream session |
| `jobs[].playbackUrl` | string | Có | URL tuyệt đối, phải trỏ đúng MediaMTX base URL đã cấu hình và path `/live/{sessionId}/index.m3u8` |
| `jobs[].readToken` | string | Có | Read JWT do Stream Service ký; Worker không ghi giá trị này vào log/error |
| `jobs[].readExpiresAt` | string | Có | RFC 3339 timestamp có múi giờ, ví dụ `2030-01-15T10:05:00Z` |
| `jobs[].jobDeadlineAt` | string | Có | RFC 3339 timestamp có múi giờ; hạn xử lý của lượt thumbnail |

Không chấp nhận field ngoài schema. `jobs` rỗng, sai UUID, timestamp không hợp lệ, kiểu dữ liệu sai hoặc `playbackUrl` ngoài MediaMTX đều trả `400`.

Cả hai timestamp phải có ngày, giờ, phút, giây và múi giờ `Z` hoặc offset dạng `+07:00`; phần giây thập phân là tùy chọn. Không chấp nhận chỉ ngày (`2030-01-15`), giờ không có múi giờ (`2030-01-15T10:05:00`) hoặc offset thiếu dấu hai chấm (`+0700`).

Quy tắc `playbackUrl`:

- So sánh bằng URL parser, không dùng `startsWith`.
- Protocol, hostname và port phải khớp `MEDIA_WORKER_MEDIAMTX_BASE_URL`.
- Base URL đã cấu hình phải là origin `http(s)`, không có credential, query, fragment hoặc path nghiệp vụ.
- URL không được có username/password, query hoặc fragment.
- Path phải chính xác `/live/{sessionId}/index.m3u8`; `sessionId` trong path phải bằng `jobs[].sessionId`.
- Không nhận URL nguồn tùy ý, kể cả host giống nhưng port/path khác.

Request body mặc định giới hạn 262.144 byte (`MEDIA_WORKER_MAX_BODY_BYTES`). Body vượt giới hạn trả `413`.

### 1.2. Request example

```json
{
  "jobs": [
    {
      "jobId": "11111111-1111-4111-8111-111111111111",
      "sessionId": "22222222-2222-4222-8222-222222222222",
      "playbackUrl": "http://media-server:8888/live/22222222-2222-4222-8222-222222222222/index.m3u8",
      "readToken": "fixture-read-token-not-a-jwt",
      "readExpiresAt": "2030-01-15T10:05:00Z",
      "jobDeadlineAt": "2030-01-15T10:04:00Z"
    }
  ]
}
```

### 1.3. Response khi tiếp nhận

Worker trả `202 Accepted` sau khi thêm job vào BullMQ hoặc xác nhận job ID đã tồn tại.

```json
{
  "acceptedJobIds": ["11111111-1111-4111-8111-111111111111"],
  "rejectedJobs": [
    {
      "jobId": "33333333-3333-4333-8333-333333333333",
      "reason": "QUEUE_FULL"
    }
  ]
}
```

- `acceptedJobIds`: các job đã có trong queue, bao gồm job trùng ID đã tồn tại.
- `rejectedJobs`: các job chưa được nhận; `reason` hiện định nghĩa `QUEUE_FULL`.
- Chỉ các ID trong `acceptedJobIds` được Stream Service chuyển sang `ACCEPTED`.
- Nếu Redis/dependency lỗi khiến kết quả tiếp nhận không xác định, Worker trả `503` cho toàn request; Stream Service gửi lại cùng `jobId`.
- Không có transaction toàn batch giữa HTTP, Redis và PostgreSQL.

### 1.4. Trạng thái Worker hiện tại

Khi Redis sẵn sàng, Worker trả `202` sau khi lưu hoặc xác nhận job tồn tại. Nếu Redis không khả dụng hoặc kết quả tiếp nhận không xác định, API trả `503 QUEUE_UNAVAILABLE`. Consumer mặc định tắt cho đến khi pipeline upload/callback ở Step 6–7 sẵn sàng; `202` không đồng nghĩa đã tạo thumbnail. Readiness phản ánh khả năng tiếp nhận của Redis.

## 2. Callback tới Stream Service

```http
POST /stream/internal/api/thumbnail-results
Content-Type: application/json
X-Internal-Token: <internal token>
```

### 2.1. Payload schema

| Field | Kiểu | Bắt buộc | Quy tắc |
| --- | --- | --- | --- |
| `jobId` | string | Có | UUID của job Worker vừa xử lý |
| `sessionId` | string | Có | UUID session tương ứng |
| `objectKey` | string | Có | Đúng dạng `livestream-thumbnails/{sessionId}/{jobId}.jpg` |
| `contentType` | string | Có | `image/jpeg` |
| `sizeBytes` | integer | Có | Dương, kích thước file JPEG thực tế |
| `width` | integer | Có | Dương; Worker mặc định xuất 640 |
| `height` | integer | Có | Dương; Worker mặc định xuất 360 |

Không gửi `capturedAt`, `thumbnail_captured_at` hoặc `next_thumbnail_at`. Stream Service tự dựng URL ảnh từ public R2 base URL và object key, tự đặt `thumbnail_updated_at` theo đồng hồ backend khi áp dụng thành công.

### 2.2. Payload example

```json
{
  "jobId": "11111111-1111-4111-8111-111111111111",
  "sessionId": "22222222-2222-4222-8222-222222222222",
  "objectKey": "livestream-thumbnails/22222222-2222-4222-8222-222222222222/11111111-1111-4111-8111-111111111111.jpg",
  "contentType": "image/jpeg",
  "sizeBytes": 48210,
  "width": 640,
  "height": 360
}
```

### 2.3. Response

```http
HTTP/1.1 200 OK
```

```json
{
  "outcome": "APPLIED"
}
```

- `APPLIED`: kết quả hợp lệ, đúng job, job còn chờ, chưa quá deadline, session vẫn `LIVE` và metadata ảnh hợp lệ.
- `IGNORED`: kết quả trùng, cũ, sai job/trạng thái hoặc không còn đủ điều kiện áp dụng. Client coi như đã xử lý xong và không retry.
- `400`: schema/UUID/timestamp/metadata ảnh sai.
- `401`: thiếu hoặc sai internal token.
- `503`: dependency tạm thời không khả dụng. Worker ghi log, kết thúc job và không retry callback.

Callback chỉ được định nghĩa trong Step 1; Stream Service chưa mở endpoint này.

## 3. Error contract dùng chung

Worker trả lỗi JSON theo cấu trúc:

```json
{
  "statusCode": 400,
  "code": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "details": ["jobs.0.jobId: jobId must be a UUID"]
}
```

`details` là optional, chỉ chứa đường dẫn field và mô tả lỗi; không chứa `readToken`, internal token, Authorization header hoặc credential.

| HTTP status | `code` | Trường hợp |
| --- | --- | --- |
| `400` | `VALIDATION_FAILED` | Sai schema, UUID, timestamp, kiểu dữ liệu hoặc field lạ |
| `400` | `BATCH_TOO_LARGE` | Số job vượt giới hạn batch |
| `400` | `INVALID_PLAYBACK_URL` | URL nguồn không thuộc MediaMTX base URL/path cho phép |
| `400` | `INVALID_JSON` | Body không phải JSON hợp lệ |
| `401` | `INVALID_INTERNAL_TOKEN` | Thiếu hoặc sai `X-Internal-Token` |
| `413` | `REQUEST_BODY_TOO_LARGE` | Body vượt giới hạn byte |
| `503` | `QUEUE_UNAVAILABLE` | Queue/dependency không khả dụng |
| `500` | `INTERNAL_ERROR` | Lỗi không mong đợi; message không chứa chi tiết nội bộ |

## 4. Fixtures

Fixtures nằm trong [`fixtures/`](fixtures/) và được đọc trực tiếp bởi test Node; có thể dùng cho test Java ở các step sau.

| File | Mục đích |
| --- | --- |
| [`thumbnail-jobs-batch.valid.json`](fixtures/thumbnail-jobs-batch.valid.json) | Batch hợp lệ 2 job để kiểm tra contract |
| [`thumbnail-jobs-batch.invalid-job-id.json`](fixtures/thumbnail-jobs-batch.invalid-job-id.json) | UUID sai |
| [`thumbnail-jobs-batch.invalid-timestamp.json`](fixtures/thumbnail-jobs-batch.invalid-timestamp.json) | Timestamp sai định dạng/không tồn tại |
| [`thumbnail-jobs-batch.invalid-playback-url.json`](fixtures/thumbnail-jobs-batch.invalid-playback-url.json) | URL ngoài MediaMTX base URL |
| [`thumbnail-jobs-batch.invalid-schema.json`](fixtures/thumbnail-jobs-batch.invalid-schema.json) | Sai kiểu dữ liệu và thiếu field bắt buộc |
| [`thumbnail-jobs-batch.over-limit.json`](fixtures/thumbnail-jobs-batch.over-limit.json) | 51 job, vượt giới hạn mặc định 50 |
| [`thumbnail-result.valid.json`](fixtures/thumbnail-result.valid.json) | Callback hợp lệ |
| [`thumbnail-result.invalid-schema.json`](fixtures/thumbnail-result.invalid-schema.json) | Callback sai object key/content type/kích thước |
| [`error-queue-unavailable.json`](fixtures/error-queue-unavailable.json) | Error envelope khi queue chưa khả dụng |

Các fixture không chứa internal token hoặc Read JWT thật. Giá trị `readToken` chỉ là chuỗi tổng hợp để kiểm tra việc Worker không làm lộ token.
