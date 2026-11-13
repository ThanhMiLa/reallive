# Media Worker

Ứng dụng NestJS + TypeScript nhận thumbnail job từ Stream Service, lưu vào BullMQ/Redis, trích JPEG bằng FFmpeg, upload R2 và callback để backend cập nhật thumbnail. Consumer mặc định tắt và cần bật có chủ đích sau khi cấu hình đầy đủ.

Contract dùng chung và fixtures nằm tại [`docs/contracts/thumbnail`](../docs/contracts/thumbnail/README.md).

## Khả năng hiện tại

Đã có:

- API nội bộ `POST /internal/api/thumbnail-jobs/batch` không yêu cầu xác thực service.
- Validate schema, UUID, timestamp, kích thước batch/body và URL nguồn MediaMTX.
- Contract, error envelope và fixtures dùng chung cho Java/Node.
- Cấu hình qua biến môi trường, validate khi khởi động và fail-fast.
- BullMQ/Redis với prefix riêng, chống trùng theo `jobId`, giới hạn capacity và giữ completed/failed ít nhất 24 giờ.
- Consumer kiểm tra expiry/deadline, giới hạn concurrency, `attempts=1`, `maxStalledCount=0` và mặc định tắt.
- Pipeline FFmpeg → JPEG → R2 → callback nội bộ; backend cập nhật thumbnail nguyên tử và trả APPLIED/IGNORED.
- `GET /health/live` và `GET /health/ready`.
- Lint, unit test, HTTP contract test, integration test, build TypeScript và Dockerfile có FFmpeg, chạy bằng user không phải root.

Chưa có:

- Cleanup, metrics vận hành và frontend.

`src/processor` nối capture/upload với callback thật. Callback gửi đúng một lần, có timeout và hỗ trợ hủy request; không retry khi backend lỗi hoặc không nhận được response.

## Hành vi API

Request hợp lệ được thêm vào BullMQ; API trả `202` với `acceptedJobIds` và `rejectedJobs`. ID đã tồn tại được xác nhận kể cả khi queue đầy. Nếu Redis lỗi hoặc kết quả enqueue không xác định, API trả `503 QUEUE_UNAVAILABLE`; client gửi lại cùng ID.

`202` chỉ xác nhận tiếp nhận, chưa có nghĩa ảnh đã được tạo. Consumer mặc định tắt; khi bật phải có đủ R2 credential và callback URL. Bật callback backend trước, sau đó consumer và scheduler dispatch. Readiness kiểm tra Redis và khả năng tiếp nhận; liveness không phụ thuộc Redis.

Các lỗi chính:

| HTTP | Code | Nguyên nhân |
| --- | --- | --- |
| `400` | `VALIDATION_FAILED` | Sai schema, UUID, timestamp, field lạ |
| `400` | `BATCH_TOO_LARGE` | Vượt `MEDIA_WORKER_MAX_BATCH_SIZE` |
| `400` | `INVALID_PLAYBACK_URL` | URL ngoài MediaMTX origin/path cho phép |
| `400` | `INVALID_JSON` | Body không phải JSON hợp lệ |
| `413` | `REQUEST_BODY_TOO_LARGE` | Vượt `MEDIA_WORKER_MAX_BODY_BYTES` |
| `503` | `QUEUE_UNAVAILABLE` | Queue không khả dụng |

`playbackUrl` được kiểm tra bằng URL parser: protocol/host/port phải khớp `MEDIA_WORKER_MEDIAMTX_BASE_URL`, không có credential/query/fragment, path phải đúng `/live/{sessionId}/index.m3u8`. Không dùng `startsWith` và không nhận URL nguồn tùy ý.

## Cấu trúc

```text
media-worker/
├── src/
│   ├── common/
│   │   ├── config/            # Env loading + startup validation
│   │   ├── contracts/         # Shared TypeScript contract types
│   │   ├── errors/            # Error envelope/filter
│   │   └── http/              # JSON body limit, validation pipe
│   ├── thumbnail-jobs/        # Batch API, DTO, source URL validation
│   ├── queue/                 # BullMQ/Redis queue + queue port
│   ├── processor/             # Capture/upload/callback pipeline + ports
│   ├── ffmpeg/                # FFmpeg runner, capture service, CLI (Step 5)
│   ├── storage/               # R2 storage adapter (Step 6)
│   ├── callback/              # One-shot HTTP callback client
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

API nội bộ không cần service token. Không commit `.env`.

- Worker chạy trên host: giữ `MEDIA_WORKER_MEDIAMTX_BASE_URL=http://localhost:8898` (port HLS đang map từ container `media-server`).
- Worker chạy bằng Compose ở root: Compose đặt MediaMTX thành `http://media-server:8888`, Redis thành `redis:6379` và callback tới Stream Service trên host tại `http://host.docker.internal:8082`.

Kiểm tra API trong terminal khác, tại thư mục `media-worker/`. Fixture dùng chung giữ URL nội bộ `media-server:8888`; lệnh dưới đọc `.env` và đổi URL trong bộ nhớ cho khớp MediaMTX base URL của môi trường local, không sửa file fixture:

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

Khoảng cập nhật thumbnail `X` (dev 60 giây, production 300 giây) thuộc cấu hình Stream Service, không phải Worker.

## Kiểm tra

```sh
cd media-worker
npm run lint
npm test
npm run test:e2e
npm run test:integration -- --runInBand
npm run build
npm run start:dev
```

Test hiện có:

- Config hợp lệ/không hợp lệ, defaults, retention tối thiểu 24 giờ, quan hệ timeout/retention/token TTL, không lộ secret trong lỗi.
- Request nội bộ không token; schema, UUID, timestamp đầy đủ có múi giờ, field lạ; batch vượt giới hạn; body quá lớn; JSON lỗi.
- URL ngoài MediaMTX origin/path, prefix attack, credential/query/fragment.
- HTTP contract test dùng queue giả lập để kiểm tra độc lập.
- Integration test dùng Testcontainers với Redis thật để kiểm tra enqueue, chống trùng, capacity, xử lý hết hạn và lỗi. Cần Docker đang chạy.
- Liveness `200`; readiness phản ánh khả năng tiếp nhận của queue.

## Docker

### Docker Compose

Chạy các lệnh sau từ root repo. Nếu chưa có cấu hình Worker, tạo từ template:

```sh
cp media-worker/.env.example media-worker/.env
```

Giữ nguyên file `.env` nếu đã cấu hình. Compose đọc cấu hình R2, queue và cờ consumer từ `media-worker/.env`; mật khẩu Redis lấy từ `.env` ở root để khớp service `redis`. Compose ghi đè địa chỉ kết nối, cổng HTTP, đường dẫn FFmpeg/file tạm và thời gian shutdown cho container. Cờ `MEDIA_WORKER_PROCESSOR_ENABLED` mặc định `false` trong template; chỉ bật khi đã có đủ credential R2 và callback.

Stream Service hiện chạy trên host. Trong môi trường chạy Stream Service, đặt:

```dotenv
MEDIA_WORKER_BASE_URL=http://localhost:8090
STREAM_MEDIA_INTERNAL_HLS_BASE_URL=http://media-server:8888
```

Khởi động lại backend sau khi đổi env. `STREAM_MEDIA_INTERNAL_HLS_BASE_URL` dùng để tạo URL gửi cho Worker, nên phải khớp origin MediaMTX trong container; URL HLS public cho player vẫn là `http://localhost:8898`.

Nếu Worker đang chạy bằng `npm run start:dev` trên host, dừng tiến trình đó trước để giải phóng cổng `8090` và tránh chạy đồng thời hai consumer.

```sh
docker compose config --quiet
docker compose up -d --build --wait media-worker
docker compose ps media-worker
curl -fsS http://127.0.0.1:8090/health/ready
```

Container tên `media-worker`, image `reallive-media-worker:0.1.0`. Compose đợi Redis và MediaMTX healthy trước khi chạy Worker; healthcheck Worker gọi readiness và kiểm tra được kết nối Redis. Worker tham gia network `default` để đọc MediaMTX và `data` để dùng Redis. Callback gọi trực tiếp host qua `host.docker.internal`, không qua Gateway. Compose cho phép shutdown trong 60 giây, dài hơn timeout Worker 45 giây. File JPEG tạm nằm trong filesystem container, không lưu vào volume lâu dài.

### Build image riêng

Build từ root repo với context chỉ gồm `media-worker`:

```sh
docker build -t reallive-media-worker ./media-worker

docker run --rm --entrypoint ffmpeg reallive-media-worker -version
```

Image dùng multi-stage build, cài FFmpeg và chạy bằng user `mediaworker` (UID/GID 10001). Healthcheck của image chỉ gọi liveness; readiness được kiểm tra riêng theo kết nối Redis. Chạy container với một MediaMTX reachable và cấu hình R2 qua biến môi trường; không bake secret vào image.

## Bảo mật

- API nội bộ không xác thực token; chỉ cho phép truy cập qua mạng nội bộ và không expose qua Gateway.
- Không log request body, `readToken`, internal token hoặc credential.
- Chỉ nhận `playbackUrl` thuộc MediaMTX base URL đã cấu hình.
- Giới hạn batch và body trước khi xử lý.
- R2 credential chỉ đọc từ biến môi trường; bật consumer khi thiếu cấu hình sẽ bị từ chối lúc khởi động.

## Bước tiếp theo

- **Step 5–7:** đã triển khai capture/upload/callback và nghiệm thu thủ công với livestream, R2, DB thật ngày 2026-11-06. Integration pipeline tự động còn hoãn theo yêu cầu người dùng.
- **Step 8:** người dùng chốt phạm vi hiện tại với log thời gian chờ queue/chụp/upload/xử lý; job quét dọn file tạm, metrics tổng hợp và tổng hợp kiểm thử vẫn chưa triển khai.


## Chạy thử trích JPEG (Step 5)

Sau `npm run build`, truyền một `ThumbnailJobInput` JSON qua stdin; file đầu vào phải là file private và không commit vì chứa Read JWT:

```sh
npm run thumbnail:capture -- --keep-output < /path/to/private-job.json
```

JSON cần các trường `jobId`, `sessionId`, `playbackUrl`, `readToken`, `readExpiresAt`, `jobDeadlineAt`. URL phải khớp `MEDIA_WORKER_MEDIAMTX_BASE_URL` và `/live/{sessionId}/index.m3u8`. Kết quả gồm đường dẫn JPEG và metadata; không truyền token qua argument. Bỏ `--keep-output` để tự dọn JPEG sau khi kiểm tra. Đường dẫn khi `kept=false` chỉ là metadata và file đã được xóa.

FFmpeg và FFprobe phải có trên PATH hoặc được cấu hình qua `.env`. `MEDIA_WORKER_FFMPEG_THREADS`, `MEDIA_WORKER_MAX_JPEG_BYTES`, `MEDIA_WORKER_PROCESS_OUTPUT_BYTES`, `MEDIA_WORKER_TERMINATION_GRACE_MS` cấu hình giới hạn xử lý. Consumer production vẫn tắt; kết quả capture chưa được upload hoặc callback.

### Shutdown và bảo mật capture

CLI bắt SIGTERM/SIGINT, abort capture, chờ FFmpeg đóng và service dọn file trước khi đóng Nest context; exit code tương ứng 143/130. SIGKILL không chạy cleanup: kill toàn container sẽ dừng tiến trình; file trên volume có thể còn sót và hiện chưa có job tự động dọn. Không dùng riêng SIGKILL Node trên host để thay cho shutdown có kiểm soát.

Bearer token được truyền bằng nội dung ffconcat qua pipe FD 3; command line FFmpeg chỉ chứa `pipe:3`, không chứa JWT. Pipe chỉ chứa dữ liệu trong bộ nhớ, không ghi token ra file tạm; không log command/input/stderr thô. Điều này giảm lộ qua process arguments, không bảo vệ trước tài khoản root/debugger có quyền đọc bộ nhớ.

Chạy `npm run test:ffmpeg:docker` để build image và kiểm tra FFmpeg bundled với HLS có Bearer, portrait và SIGKILL container khi capture đang chạy. Integration test thông thường tự build trước để test CLI sử dụng đúng code hiện tại. Chưa nghiệm thu OBS thật; việc này do người dùng thực hiện.


## Chạy thử capture và upload R2 (Step 6)

Cấu hình các biến `MEDIA_WORKER_R2_ENDPOINT`, `MEDIA_WORKER_R2_BUCKET`, `MEDIA_WORKER_R2_ACCESS_KEY_ID`, `MEDIA_WORKER_R2_SECRET_ACCESS_KEY` trong `.env`; region mặc định là `auto`. Dùng credential và bucket thử nghiệm, không commit secret hoặc job JSON.

```sh
npm run build
npm run thumbnail:capture-upload < /path/to/private-job.json
```

CLI đọc job qua stdin, tạo JPEG, upload R2 và dọn file tạm. Kết quả JSON có object key và metadata. Object được giữ lại để tự kiểm tra; CLI không callback, không cập nhật DB. Timeout dùng `MEDIA_WORKER_UPLOAD_TIMEOUT_MS`; upload không tự retry. Khi timeout, kết quả upload có thể chưa xác định.

`npm run test:integration` cần Docker và image `localstack/localstack:3.8.1` để kiểm thử S3 tương thích. LocalStack không phải Cloudflare R2. Chụp/upload/callback với R2 thật đã được nghiệm thu thủ công ở Step 7; consumer vẫn mặc định tắt để bật có chủ đích.

## Chạy pipeline callback trên máy local

Trong Worker trên host, đặt `STREAM_SERVICE_BASE_URL=http://localhost:8082` (origin không có `/stream`). Compose ở root ghi đè URL thành `http://host.docker.internal:8082` để gọi backend trên host. Đổi `.env` cần khởi động lại Worker; với Compose, chạy lại `docker compose up -d media-worker` để nạp env mới.

Backend nhận `POST /stream/internal/api/thumbnail-results` ngoài Gateway; bật bằng `STREAM_THUMBNAIL_CALLBACK_ENABLED=true` và cấu hình `R2_PUBLIC_BASE_URL` của đúng bucket thumbnail. Worker không cần public R2 base URL.

Bật `MEDIA_WORKER_PROCESSOR_ENABLED=true` khi đủ cấu hình R2/Redis/callback, rồi bật `STREAM_THUMBNAIL_ENABLED=true` tại backend. Job chưa có ảnh được xét ngay ở lượt quét tiếp theo; các lượt sau cách ít nhất X từ lần backend cập nhật thumbnail thành công. Chu kỳ quét 10 giây không phải chu kỳ chụp ảnh.

Callback APPLIED/IGNORED kết thúc job bình thường. Callback lỗi hoặc timeout kết thúc job một lần, giữ object đã upload; scheduler backend xử lý job quá hạn. Không xóa ảnh R2 khi callback chưa xác định kết quả.
