# MediaMTX cho Streaming MVP

Một container MediaMTX `1.21.2`, custom image có shell/Python standard library/CA certificates để chạy hook HMAC. Không có FFmpeg/transcoding trong container.

Chuẩn bị key và env local bằng [fixture README](../../scripts/testing/streaming/README.md), hoặc cung cấp callback env riêng qua `STREAM_MEDIA_ENV_FILE`. File env này phải tồn tại trước khi chạy Compose. Service `mediamtx` được khai báo trực tiếp trong [docker-compose.yml ở root](../../docker-compose.yml), chạy cùng các service khác bằng `docker compose up -d`. Thư mục này giữ Dockerfile, cấu hình MediaMTX và helper callback.

Backend thật (JWKS/callback qua Gateway):

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
- Root mặc định trỏ JWKS/callback qua `host.docker.internal:8888`; fixture override sang port 18082. Linux có extra_hosts host-gateway; phải kiểm tra reachability tới host trên môi trường chạy thực tế.
- `STREAM_MEDIAMTX_JWKS_URL`, `STREAM_MEDIA_CALLBACK_URL`, `STREAM_MEDIA_ALLOWED_ORIGINS` có thể override cấu hình backend thật.
- Callback secret đọc từ env file được ignore, mặc định `.local/streaming/mediamtx.env`; override đường dẫn bằng `STREAM_MEDIA_ENV_FILE`. Không in `docker compose config` đầy đủ vì có thể làm lộ env; dùng `config --quiet`.
- Healthcheck kiểm tra listener RTMP/HLS/Control API. JWT/JWKS phải kiểm thêm bằng `media.py probe`, không suy ra auth pass chỉ từ container healthy.
- Production cần HTTPS/RTMPS và network/secret riêng; cấu hình local này chưa là bằng chứng production pass.

Dừng riêng fixture bằng `docker compose --project-directory . -f scripts/testing/streaming/compose.fixture.yml down` (không dùng `-v`, không tác động DB khác). Chạy các lệnh trên từ root; `--project-directory .` giúp kế thừa service và nạp `.env` của root đúng vị trí. Path tạo qua API không được coi là dữ liệu bền vững qua restart; backend thật sẽ phục hồi từ DB ở giai đoạn 4.
