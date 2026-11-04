# Media Worker

Ứng dụng NestJS + TypeScript độc lập nhận thumbnail job từ Stream Service, dự kiến xử lý bằng BullMQ/FFmpeg, upload JPEG lên Cloudflare R2 và callback kết quả. Đây là nền tảng của **Step 1 (THM-101–THM-104)**; queue, processor, FFmpeg, upload và callback thật chưa được triển khai.

Contract dùng chung và fixtures nằm tại [`docs/contracts/thumbnail`](../docs/contracts/thumbnail/README.md).

## Phạm vi Step 1

Đã có:

- API nội bộ `POST /internal/api/thumbnail-jobs/batch` với `X-Internal-Token`.
- Validate schema, UUID, timestamp, kích thước batch/body và URL nguồn MediaMTX.
- Contract, error envelope và fixtures dùng chung cho Java/Node.
- Cấu hình qua biến môi trường, validate khi khởi động và fail-fast.
- `GET /health/live` và `GET /health/ready`.
- Lint, unit test, e2e test, build TypeScript và Dockerfile có FFmpeg, chạy bằng user không phải root.

Chưa có:

- Queue BullMQ, Redis connection, deduplication, capacity hoặc processor.
- Chạy FFmpeg, tạo JPEG, upload R2 hoặc gửi callback.
- Callback endpoint tại Stream Service, scheduler/Stream Service changes, cleanup và frontend.

`src/processor`, `src/ffmpeg`, `src/storage`, `src/callback` hiện chỉ chứa port/interface cho các step sau. Không có implementation giả báo xử lý thành công.

## Hành vi API trong Step 1

Request hợp lệ đi qua toàn bộ validation nhưng **luôn trả `503 QUEUE_UNAVAILABLE`**, không trả `202`, không có `acceptedJobIds` và không ghi nhận job giả:

```json
{
  "statusCode": 503,
  "code": "QUEUE_UNAVAILABLE",
  "message": "Thumbnail queue is not available"
}
```

Readiness cũng trả `503` với check queue `down` cho đến khi Step 4 tích hợp queue thật. Liveness vẫn trả `200` và không phụ thuộc Redis/MediaMTX.

Các lỗi chính:

| HTTP | Code | Nguyên nhân |
| --- | --- | --- |
| `400` | `VALIDATION_FAILED` | Sai schema, UUID, timestamp, field lạ |
| `400` | `BATCH_TOO_LARGE` | Vượt `MEDIA_WORKER_MAX_BATCH_SIZE` |
| `400` | `INVALID_PLAYBACK_URL` | URL ngoài MediaMTX origin/path cho phép |
| `400` | `INVALID_JSON` | Body không phải JSON hợp lệ |
| `401` | `INVALID_INTERNAL_TOKEN` | Thiếu hoặc sai `X-Internal-Token` |
| `413` | `REQUEST_BODY_TOO_LARGE` | Vượt `MEDIA_WORKER_MAX_BODY_BYTES` |
| `503` | `QUEUE_UNAVAILABLE` | Queue chưa tích hợp hoặc không khả dụng |

`playbackUrl` được kiểm tra bằng URL parser: protocol/host/port phải khớp `MEDIA_WORKER_MEDIAMTX_BASE_URL`, không có credential/query/fragment, path phải đúng `/live/{sessionId}/index.m3u8`. Không dùng `startsWith` và không nhận URL nguồn tùy ý.

## Cấu trúc

```text
media-worker/
├── src/
│   ├── common/
│   │   ├── auth/              # X-Internal-Token guard
│   │   ├── config/            # Env loading + startup validation
│   │   ├── contracts/         # Shared TypeScript contract types
│   │   ├── errors/            # Error envelope/filter
│   │   └── http/              # JSON body limit, validation pipe
│   ├── thumbnail-jobs/        # Batch API, DTO, source URL validation
│   ├── queue/                 # Queue port + controlled unavailable implementation
│   ├── processor/             # Processor port (Step 5)
│   ├── ffmpeg/                # FFmpeg port (Step 5)
│   ├── storage/               # R2 storage port (Step 5)
│   ├── callback/              # Callback client port (Step 6)
│   ├── health/                # Liveness/readiness
│   ├── app.module.ts
│   ├── app.setup.ts
│   └── main.ts
├── test/                      # HTTP contract e2e tests
├── Dockerfile
└── .env.example
```

## Chạy local

Yêu cầu Node.js 24+ và npm. FFmpeg chỉ cần khi chạy Docker hoặc ở Step 5.

```sh
cd media-worker
cp .env.example .env
npm ci
npm run start:dev
```

Sửa `MEDIA_WORKER_INTERNAL_TOKEN` trong `.env` thành giá trị ngẫu nhiên tối thiểu 32 ký tự. Không commit `.env`.

- Worker chạy trên host: giữ `MEDIA_WORKER_MEDIAMTX_BASE_URL=http://localhost:8898` (port HLS đang map từ container `media-server`).
- Worker chạy trong Compose network: đổi thành `http://media-server:8888`; Redis là `redis:6379`, Stream Service là `http://stream-service:8082`.

Kiểm tra API trong terminal khác, tại thư mục `media-worker/`. Fixture dùng chung giữ URL nội bộ `media-server:8888`; lệnh dưới đọc `.env` để lấy token và đổi URL trong bộ nhớ cho khớp MediaMTX base URL của môi trường local, không sửa file fixture:

```sh
node --env-file=.env <<'NODE'
const { readFileSync } = require('node:fs');
const batch = JSON.parse(readFileSync(
  '../docs/contracts/thumbnail/fixtures/thumbnail-jobs-batch.valid.json', 'utf8',
));
const baseUrl = process.env.MEDIA_WORKER_MEDIAMTX_BASE_URL || 'http://media-server:8888';
for (const job of batch.jobs) {
  job.playbackUrl = new URL(`/live/${job.sessionId}/index.m3u8`, baseUrl).href;
}
fetch(`http://127.0.0.1:${process.env.PORT || 8090}/internal/api/thumbnail-jobs/batch`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Internal-Token': process.env.MEDIA_WORKER_INTERNAL_TOKEN,
  },
  body: JSON.stringify(batch),
}).then(async (response) => {
  console.log(response.status, await response.text());
}).catch(() => { console.error('Cannot connect to Media Worker'); process.exitCode = 1; });
NODE

curl -i http://127.0.0.1:8090/health/live
curl -i http://127.0.0.1:8090/health/ready
```

Request bằng fixture hợp lệ trả `503 QUEUE_UNAVAILABLE` trong Step 1.

## Cấu hình

Mọi giá trị đọc từ biến môi trường và được validate trước khi ứng dụng listen. Cấu hình sai làm ứng dụng dừng với danh sách biến lỗi; giá trị token/secret không xuất hiện trong thông báo lỗi.

| Biến | Mặc định | Ghi chú |
| --- | --- | --- |
| `PORT` | `8090` | Cổng HTTP nội bộ |
| `MEDIA_WORKER_INTERNAL_TOKEN` | bắt buộc | Tối thiểu 32 ký tự |
| `MEDIA_WORKER_MEDIAMTX_BASE_URL` | `http://media-server:8888` | Origin http(s), không path/query/credential |
| `MEDIA_WORKER_MAX_BATCH_SIZE` | `50` | 1–500 job/request |
| `MEDIA_WORKER_MAX_BODY_BYTES` | `262144` | 1 KiB–10 MiB |
| `MEDIA_WORKER_JOB_DEADLINE_SECONDS` | `240` | Phải lớn hơn tổng timeout FFmpeg + upload + callback |
| `MEDIA_WORKER_READ_TOKEN_TTL_SECONDS` | `300` | Phải ≥ deadline |
| `MEDIA_WORKER_FFMPEG_TIMEOUT_MS` | `20000` | Step 5 |
| `MEDIA_WORKER_UPLOAD_TIMEOUT_MS` | `10000` | Step 5 |
| `MEDIA_WORKER_CALLBACK_TIMEOUT_MS` | `5000` | Step 6 |
| `MEDIA_WORKER_QUEUE_CONCURRENCY` | `2` | Step 4 |
| `MEDIA_WORKER_MAX_PENDING_JOBS` | `1000` | Step 4 |
| `MEDIA_WORKER_JOB_RETENTION_SECONDS` | `86400` | Tối thiểu 24 giờ (86400 giây) và phải ≥ deadline |
| `MEDIA_WORKER_QUEUE_PREFIX` | `media-worker:thumbnail` | Prefix Redis riêng |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` | `localhost` / `6379` / `0` | Chưa kết nối ở Step 1 |
| `REDIS_PASSWORD` | rỗng | Không bắt buộc |
| `MEDIA_WORKER_TEMP_DIR` | `/tmp/media-worker` | Phải là đường dẫn tuyệt đối |
| `MEDIA_WORKER_FFMPEG_PATH` / `FFPROBE_PATH` | `ffmpeg` / `ffprobe` | Step 5 |
| `MEDIA_WORKER_THUMBNAIL_WIDTH` / `HEIGHT` | `640` / `360` | JPEG output dự kiến |
| `MEDIA_WORKER_CLEANUP_INTERVAL_SECONDS` | `3600` | Step 7 |
| `MEDIA_WORKER_CLEANUP_MIN_AGE_SECONDS` | `86400` | Step 7 |
| `MEDIA_WORKER_R2_ENDPOINT` | rỗng | Step 5; đặt cùng lúc với bucket/access key/secret |
| `MEDIA_WORKER_R2_REGION` | `auto` | |
| `MEDIA_WORKER_R2_BUCKET` | rỗng | Step 5 |
| `MEDIA_WORKER_R2_ACCESS_KEY_ID` | rỗng | Step 5 |
| `MEDIA_WORKER_R2_SECRET_ACCESS_KEY` | rỗng | Step 5 |
| `STREAM_SERVICE_BASE_URL` | `http://stream-service:8082` | Step 6; origin không path |
| `STREAM_SERVICE_INTERNAL_TOKEN` | rỗng | Step 6; nếu đặt phải ≥ 32 ký tự |

Khoảng cập nhật thumbnail `X` (dev 60 giây, production 300 giây) thuộc cấu hình Stream Service, không phải Worker.

## Kiểm tra

```sh
cd media-worker
npm run lint
npm test
npm run test:e2e
npm run build
```

Test hiện có:

- Config hợp lệ/không hợp lệ, defaults, retention tối thiểu 24 giờ, quan hệ timeout/retention/token TTL, không lộ secret trong lỗi.
- Thiếu/sai internal token; schema, UUID, timestamp đầy đủ có múi giờ, field lạ; batch vượt giới hạn; body quá lớn; JSON lỗi.
- URL ngoài MediaMTX origin/path, prefix attack, credential/query/fragment.
- Request hợp lệ trả `503`, không có `acceptedJobIds` giả.
- Fixture dùng chung được đổi URL theo cấu hình local vẫn đi qua validation và trả `503 QUEUE_UNAVAILABLE`.
- Liveness `200`; readiness `503` khi queue chưa tích hợp.

## Docker

Build từ thư mục `media-worker` (không cần build context của toàn repo):

```sh
docker build -t reallive-media-worker ./media-worker

docker run --rm -p 127.0.0.1:8090:8090 \
  --env-file media-worker/.env \
  -e MEDIA_WORKER_INTERNAL_TOKEN=replace-with-a-random-internal-token-at-least-32-chars \
  -e MEDIA_WORKER_MEDIAMTX_BASE_URL=http://media-server:8888 \
  -e MEDIA_WORKER_TEMP_DIR=/var/lib/media-worker/tmp \
  reallive-media-worker

docker run --rm --entrypoint ffmpeg reallive-media-worker -version
```

Image dùng multi-stage build, cài FFmpeg và chạy bằng user `mediaworker` (UID/GID 10001). Healthcheck của image chỉ gọi liveness; readiness được kiểm tra riêng vì queue chưa sẵn sàng trong Step 1. Chạy container với một MediaMTX reachable và token thật qua biến môi trường; không bake secret vào image.

Step 1 không thêm service vào `docker-compose.yml`. Việc nối Worker với MediaMTX/Redis/Stream Service, port nội bộ và healthcheck Compose thuộc Step 4/Step 7.

## Bảo mật

- API nội bộ chỉ dùng `X-Internal-Token`, không dùng account cookie/JWT người dùng.
- So sánh token bằng SHA-256 + `timingSafeEqual`.
- Không log request body, `readToken`, internal token hoặc credential.
- Chỉ nhận `playbackUrl` thuộc MediaMTX base URL đã cấu hình.
- Giới hạn batch và body trước khi xử lý.
- R2 credential và callback token chỉ đọc từ biến môi trường khi các module tương ứng được triển khai.

## Bước tiếp theo

- **Step 2–3 (Stream Service):** migration/DTO, scheduler, ký Read JWT, FeignClient gửi batch.
- **Step 4:** thay `UnavailableThumbnailQueue` bằng BullMQ thật, deduplication theo `jobId`, capacity, retention và processor.
- **Step 5:** triển khai FFmpeg runner, JPEG validation và upload R2.
- **Step 6:** callback một lần tới Stream Service và cập nhật thumbnail nguyên tử.
- **Step 7:** cleanup, Compose/config môi trường, metrics và nghiệm thu end-to-end.
