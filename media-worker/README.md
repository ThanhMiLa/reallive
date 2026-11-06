# Media Worker

Ứng dụng NestJS + TypeScript nhận thumbnail job từ Stream Service và lưu vào BullMQ/Redis. Consumer được thiết lập nhưng mặc định tắt; đã có công cụ trích JPEG bằng FFmpeg độc lập. Upload Cloudflare R2 và callback thuộc các bước tiếp theo.

Contract dùng chung và fixtures nằm tại [`docs/contracts/thumbnail`](../docs/contracts/thumbnail/README.md).

## Khả năng hiện tại

Đã có:

- API nội bộ `POST /internal/api/thumbnail-jobs/batch` với `X-Internal-Token`.
- Validate schema, UUID, timestamp, kích thước batch/body và URL nguồn MediaMTX.
- Contract, error envelope và fixtures dùng chung cho Java/Node.
- Cấu hình qua biến môi trường, validate khi khởi động và fail-fast.
- BullMQ/Redis với prefix riêng, chống trùng theo `jobId`, giới hạn capacity và giữ completed/failed ít nhất 24 giờ.
- Consumer kiểm tra expiry/deadline, giới hạn concurrency, `attempts=1`, `maxStalledCount=0` và mặc định tắt.
- `GET /health/live` và `GET /health/ready`.
- Lint, unit test, HTTP contract test, integration test, build TypeScript và Dockerfile có FFmpeg, chạy bằng user không phải root.

Chưa có:

- Pipeline consumer production hoàn chỉnh; upload R2 hoặc gửi callback.
- Callback endpoint tại Stream Service, cleanup và frontend.

`src/ffmpeg` có implementation trích và kiểm tra JPEG. Các port processor/storage/callback chưa có pipeline production hoàn chỉnh. Không có implementation giả báo xử lý thành công.

## Hành vi API

Request hợp lệ được thêm vào BullMQ; API trả `202` với `acceptedJobIds` và `rejectedJobs`. ID đã tồn tại được xác nhận kể cả khi queue đầy. Nếu Redis lỗi hoặc kết quả enqueue không xác định, API trả `503 QUEUE_UNAVAILABLE`; client gửi lại cùng ID.

`202` chỉ xác nhận tiếp nhận, chưa có nghĩa ảnh đã được tạo. Consumer mặc định tắt; không bật scheduler gửi job liên tục cho đến khi pipeline Step 6–7 sẵn sàng. Bật consumer khi chưa đăng ký pipeline sẽ làm ứng dụng từ chối khởi động. Readiness kiểm tra Redis và khả năng tiếp nhận; liveness không phụ thuộc Redis.

Các lỗi chính:

| HTTP | Code | Nguyên nhân |
| --- | --- | --- |
| `400` | `VALIDATION_FAILED` | Sai schema, UUID, timestamp, field lạ |
| `400` | `BATCH_TOO_LARGE` | Vượt `MEDIA_WORKER_MAX_BATCH_SIZE` |
| `400` | `INVALID_PLAYBACK_URL` | URL ngoài MediaMTX origin/path cho phép |
| `400` | `INVALID_JSON` | Body không phải JSON hợp lệ |
| `401` | `INVALID_INTERNAL_TOKEN` | Thiếu hoặc sai `X-Internal-Token` |
| `413` | `REQUEST_BODY_TOO_LARGE` | Vượt `MEDIA_WORKER_MAX_BODY_BYTES` |
| `503` | `QUEUE_UNAVAILABLE` | Queue không khả dụng |

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
│   ├── queue/                 # BullMQ/Redis queue + queue port
│   ├── processor/             # Processor port (Step 6–7)
│   ├── ffmpeg/                # FFmpeg runner, capture service, CLI (Step 5)
│   ├── storage/               # R2 storage port (Step 6)
│   ├── callback/              # Callback client port (Step 7)
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

Request hợp lệ trả `202` khi Redis sẵn sàng, hoặc `503` nếu không thể xác nhận tiếp nhận.

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
| `MEDIA_WORKER_UPLOAD_TIMEOUT_MS` | `10000` | Step 6 |
| `MEDIA_WORKER_CALLBACK_TIMEOUT_MS` | `5000` | Step 7 |
| `MEDIA_WORKER_PROCESSOR_ENABLED` | `false` | Chỉ bật khi đã đăng ký pipeline thật |
| `MEDIA_WORKER_QUEUE_CONCURRENCY` | `2` | Số job chạy đồng thời mỗi consumer |
| `MEDIA_WORKER_REDIS_CONNECT_TIMEOUT_MS` | `1000` | Redis producer connect timeout |
| `MEDIA_WORKER_REDIS_COMMAND_TIMEOUT_MS` | `1000` | Redis producer command timeout |
| `MEDIA_WORKER_BATCH_TIMEOUT_MS` | `3000` | Ngân sách tiếp nhận batch bao gồm chờ mutex |
| `MEDIA_WORKER_SHUTDOWN_TIMEOUT_MS` | `45000` | Thời gian tối đa chờ shutdown |
| `MEDIA_WORKER_MAX_PENDING_JOBS` | `1000` | Step 4 |
| `MEDIA_WORKER_JOB_RETENTION_SECONDS` | `86400` | Tối thiểu 24 giờ (86400 giây) và phải ≥ deadline |
| `MEDIA_WORKER_QUEUE_PREFIX` | `media-worker:thumbnail` | Prefix Redis riêng |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` | `localhost` / `6379` / `0` | Kết nối Redis |
| `REDIS_PASSWORD` | rỗng | Không bắt buộc |
| `MEDIA_WORKER_TEMP_DIR` | `/tmp/media-worker` | Phải là đường dẫn tuyệt đối |
| `MEDIA_WORKER_FFMPEG_PATH` / `FFPROBE_PATH` | `ffmpeg` / `ffprobe` | Step 5 |
| `MEDIA_WORKER_THUMBNAIL_WIDTH` / `HEIGHT` | `640` / `360` | JPEG output dự kiến |
| `MEDIA_WORKER_CLEANUP_INTERVAL_SECONDS` | `3600` | Step 8 |
| `MEDIA_WORKER_CLEANUP_MIN_AGE_SECONDS` | `86400` | Step 8 |
| `MEDIA_WORKER_R2_ENDPOINT` | rỗng | Step 6; đặt cùng lúc với bucket/access key/secret |
| `MEDIA_WORKER_R2_REGION` | `auto` | |
| `MEDIA_WORKER_R2_BUCKET` | rỗng | Step 6 |
| `MEDIA_WORKER_R2_ACCESS_KEY_ID` | rỗng | Step 6 |
| `MEDIA_WORKER_R2_SECRET_ACCESS_KEY` | rỗng | Step 6 |
| `STREAM_SERVICE_BASE_URL` | `http://stream-service:8082` | Step 7; origin không path |
| `STREAM_SERVICE_INTERNAL_TOKEN` | rỗng | Step 7; nếu đặt phải ≥ 32 ký tự |

Khoảng cập nhật thumbnail `X` (dev 60 giây, production 300 giây) thuộc cấu hình Stream Service, không phải Worker.

## Kiểm tra

```sh
cd media-worker
npm run lint
npm test
npm run test:e2e
npm run test:integration -- --runInBand
npm run build
```

Test hiện có:

- Config hợp lệ/không hợp lệ, defaults, retention tối thiểu 24 giờ, quan hệ timeout/retention/token TTL, không lộ secret trong lỗi.
- Thiếu/sai internal token; schema, UUID, timestamp đầy đủ có múi giờ, field lạ; batch vượt giới hạn; body quá lớn; JSON lỗi.
- URL ngoài MediaMTX origin/path, prefix attack, credential/query/fragment.
- HTTP contract test dùng queue giả lập để kiểm tra độc lập.
- Integration test dùng Testcontainers với Redis thật để kiểm tra enqueue, chống trùng, capacity, xử lý hết hạn và lỗi. Cần Docker đang chạy.
- Liveness `200`; readiness phản ánh khả năng tiếp nhận của queue.

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

Image dùng multi-stage build, cài FFmpeg và chạy bằng user `mediaworker` (UID/GID 10001). Healthcheck của image chỉ gọi liveness; readiness được kiểm tra riêng theo kết nối Redis. Chạy container với một MediaMTX reachable và token thật qua biến môi trường; không bake secret vào image.

Việc thêm Worker vào `docker-compose.yml` và cấu hình kết nối nội bộ thuộc Step 8.

## Bảo mật

- API nội bộ chỉ dùng `X-Internal-Token`, không dùng account cookie/JWT người dùng.
- So sánh token bằng SHA-256 + `timingSafeEqual`.
- Không log request body, `readToken`, internal token hoặc credential.
- Chỉ nhận `playbackUrl` thuộc MediaMTX base URL đã cấu hình.
- Giới hạn batch và body trước khi xử lý.
- R2 credential và callback token chỉ đọc từ biến môi trường khi các module tương ứng được triển khai.

## Bước tiếp theo

- **Step 5:** FFmpeg/JPEG đã triển khai; còn nghiệm thu OBS do người dùng thực hiện.
- **Step 6:** upload JPEG lên R2.
- **Step 7:** callback một lần tới Stream Service và cập nhật thumbnail nguyên tử.
- **Step 8:** cleanup, Compose/config môi trường, metrics và nghiệm thu end-to-end.


## Chạy thử trích JPEG (Step 5)

Sau `npm run build`, truyền một `ThumbnailJobInput` JSON qua stdin; file đầu vào phải là file private và không commit vì chứa Read JWT:

```sh
npm run thumbnail:capture -- --keep-output < /path/to/private-job.json
```

JSON cần các trường `jobId`, `sessionId`, `playbackUrl`, `readToken`, `readExpiresAt`, `jobDeadlineAt`. URL phải khớp `MEDIA_WORKER_MEDIAMTX_BASE_URL` và `/live/{sessionId}/index.m3u8`. Kết quả gồm đường dẫn JPEG và metadata; không truyền token qua argument. Bỏ `--keep-output` để tự dọn JPEG sau khi kiểm tra. Đường dẫn khi `kept=false` chỉ là metadata và file đã được xóa.

FFmpeg và FFprobe phải có trên PATH hoặc được cấu hình qua `.env`. `MEDIA_WORKER_FFMPEG_THREADS`, `MEDIA_WORKER_MAX_JPEG_BYTES`, `MEDIA_WORKER_PROCESS_OUTPUT_BYTES`, `MEDIA_WORKER_TERMINATION_GRACE_MS` cấu hình giới hạn xử lý. Consumer production vẫn tắt; kết quả capture chưa được upload hoặc callback.

### Shutdown và bảo mật capture

CLI bắt SIGTERM/SIGINT, abort capture, chờ FFmpeg đóng và service dọn file trước khi đóng Nest context; exit code tương ứng 143/130. SIGKILL không chạy cleanup: kill toàn container sẽ dừng tiến trình, file trên volume được Step 8 dọn sau. Không dùng riêng SIGKILL Node trên host để thay cho shutdown có kiểm soát.

Bearer token được truyền bằng nội dung ffconcat qua pipe FD 3; command line FFmpeg chỉ chứa `pipe:3`, không chứa JWT. Pipe chỉ chứa dữ liệu trong bộ nhớ, không ghi token ra file tạm; không log command/input/stderr thô. Điều này giảm lộ qua process arguments, không bảo vệ trước tài khoản root/debugger có quyền đọc bộ nhớ.

Chạy `npm run test:ffmpeg:docker` để build image và kiểm tra FFmpeg bundled với HLS có Bearer, portrait và SIGKILL container khi capture đang chạy. Integration test thông thường tự build trước để test CLI sử dụng đúng code hiện tại. Chưa nghiệm thu OBS thật; việc này do người dùng thực hiện.
