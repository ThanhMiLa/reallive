# MediaMTX cho Streaming MVP

Một container MediaMTX `1.21.2`, custom image có shell/Python standard library/CA certificates để chạy hook HTTP JSON trực tiếp. Không có FFmpeg/transcoding trong container.

Service `mediamtx` được khai báo trực tiếp trong [docker-compose.yml ở root](../docker-compose.yml), chạy cùng các service khác bằng `docker compose up -d`. Không cần env file callback secret hoặc fixture trước khi chạy backend thật. Thư mục này giữ Dockerfile, cấu hình MediaMTX và helper callback; key ký JWT thuộc Stream Service, không mount private key vào media container.

Backend thật (JWKS/callback gọi trực tiếp Stream Service):

Chuẩn bị key/DB/Redis và chạy Stream Service theo [README](../stream-service/README.md), sau đó chạy media trước khi gọi Create API. User/Channel/Gateway cũng phải chạy để dùng luồng account thật. Backend chạy trên host nên không có `depends_on` trong Compose cho các tiến trình này. MediaMTX tải JWKS khi có yêu cầu xác thực; listener healthy không chứng minh backend đã sẵn sàng.

```sh
docker compose up -d --build --wait mediamtx
```

Giai đoạn 0 dùng fixture thay backend; chạy fixture server trước, rồi:

```sh
docker compose --project-directory . -f scripts/testing/streaming/compose.fixture.yml config --quiet
docker compose --project-directory . -f scripts/testing/streaming/compose.fixture.yml up -d --build --wait
python3 scripts/testing/streaming/media.py probe
```

Có thể dùng `python3 scripts/testing/streaming/stack.py up --anonymous-registry` khi credential helper của Docker bị timeout với các public image. Config tạm riêng trong thư mục được ignore; không đổi credential/config Docker hiện có.

Chỉ chạy một trong hai cấu hình, vì cùng dùng port ingest 1935, HLS 8898 và Control API 9997. Tất cả port local chỉ bind `127.0.0.1`. Port HLS trong container là 8888; không chiếm Gateway 8888 trên host.

- Cấu hình `paths: {}` không có catch-all; chỉ exact path được tạo qua Control API mới nhận publish.
- `overridePublisher: false`; publish/read/api đều dùng JWT, không có action được bỏ qua xác thực.
- Root mặc định trỏ JWKS/callback trực tiếp tới Stream Service tại `host.docker.internal:8082`, không qua Gateway; fixture override sang port 18082. Linux có extra_hosts host-gateway; phải kiểm tra reachability tới host trên môi trường chạy thực tế.
- Khai báo `STREAM_MEDIAMTX_JWKS_URL`, `STREAM_MEDIA_CALLBACK_URL`, `STREAM_MEDIA_ALLOWED_ORIGINS` trong `.env` ở root theo `.env.example`. Compose đọc ba biến bắt buộc này và map sang env của container; không giữ URL mặc định trong Compose.
- Local MVP không yêu cầu callback HMAC/secret. Không in `docker compose config` đầy đủ vì có thể làm lộ env của service khác; dùng `config --quiet`.
- Healthcheck kiểm tra listener RTMP/HLS/Control API. Backend thật dùng provisioning integration test; `media.py probe` dùng fixture riêng. Không suy ra auth pass chỉ từ container healthy.
- Stream Service gọi Control API qua `STREAM_MEDIA_CONTROL_API_URL=http://localhost:9997` khi chạy trên host. Timeout connect/request mặc định 2s/5s; token api chỉ backend giữ. Khi chạy backend trong Docker, đổi URL theo network/service name tương ứng.
- Production cần HTTPS/RTMPS và network/secret riêng; cấu hình local này chưa là bằng chứng production pass.

Dừng riêng fixture bằng `docker compose --project-directory . -f scripts/testing/streaming/compose.fixture.yml down` (không dùng `-v`, không tác động DB khác). Chạy các lệnh trên từ root; `--project-directory .` giúp kế thừa service và nạp `.env` của root đúng vị trí. Path tạo qua API có thể mất khi MediaMTX restart; MVP chưa tự phục hồi từ DB hoặc đối soát. Khi mất path, current báo publishReady=false và API cấp credential trả 503; cần kiểm tra/xử lý thủ công.

Giai đoạn 4 đã hoàn tất hook callback single-shot (`media_hook.py` với timeout 10s, exit code 0/1) và tích hợp lifecycle handler đầy đủ trên Stream Service (`POST /api/media/events`): ONLINE chuyển `LIVE`, OFFLINE chuyển `RECONNECTING` (grace 60s), và API End `POST /api/streams/{id}/end` đóng path/ngắt nguồn đồng bộ. Xem [Phase 4 Implementation Plan](../docs/Implement%20Plan/Streaming%20MVP%20Phase%204%20Implementation%20Plan.md) và [Kết quả kiểm chứng](../docs/testing/Streaming%20MVP%20Tests.md).
