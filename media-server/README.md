# Media Server cho Streaming MVP

Một container MediaMTX `1.21.2`, custom image có shell/Python standard library/CA certificates để chạy hook HTTP JSON trực tiếp. Không có FFmpeg/transcoding trong container.

Service `media-server` được khai báo trực tiếp trong [docker-compose.yml ở root](../docker-compose.yml), dùng image nội bộ `reallive-media-server:1.21.2` và chạy cùng các service khác bằng `docker compose up -d`. Hook nguồn phát yêu cầu callback secret được cấu hình trước khi chạy backend thật; callback CCU có credential riêng khi được bật. Module này quản lý Dockerfile, cấu hình MediaMTX và helper callback; key ký JWT thuộc Stream Service, không mount private key vào media container.

## Cấu trúc thư mục

```text
media-server/
├── config/
│   └── mediamtx.yml             # Cấu hình MediaMTX (JWT, port RTMP/HLS, paths)
├── docker/
│   ├── Dockerfile               # Multi-stage image đóng gói MediaMTX và Python runtime
│   └── Dockerfile.dockerignore  # Quy tắc lọc build context cho image
├── hooks/
│   ├── media_hook.py            # Webhook script xử lý sự kiện online/offline
│   └── viewer_hook.py           # Hook HLS reader, tra user và callback CCU
└── README.md                    # Tài liệu hướng dẫn module
```

Tên module/service là `media-server`; phần mềm bên trong vẫn là MediaMTX. Giữ nguyên image upstream `bluenviron/mediamtx`, binary `/mediamtx`, cấu hình `mediamtx.yml`, biến `MTX_*` và JWT claim `mediamtx_permissions` theo contract của MediaMTX.

Backend thật (JWKS/callback gọi trực tiếp Stream Service):

Chuẩn bị key RSA, DB/Redis và chạy Stream Service theo [README](../stream-service/README.md), sau đó chạy media container trước khi lưu cấu hình kênh / phát OBS. User/Channel/Gateway cũng phải chạy để dùng luồng account thật. Backend chạy trên host nên không có `depends_on` trong Compose cho các tiến trình này. MediaMTX tải JWKS khi có yêu cầu xác thực; listener healthy không chứng minh backend đã sẵn sàng.

```sh
docker compose up -d --build --wait media-server
```

Giai đoạn 0 dùng fixture thay backend; chạy fixture server trước, rồi:

```sh
docker compose --project-directory . -f scripts/testing/streaming/compose.fixture.yml config --quiet
docker compose --project-directory . -f scripts/testing/streaming/compose.fixture.yml up -d --build --wait
python3 scripts/testing/streaming/media.py probe
```

Có thể dùng `python3 scripts/testing/streaming/stack.py up --anonymous-registry` khi credential helper của Docker bị timeout với các public image. Config tạm riêng trong thư mục được ignore; không đổi credential/config Docker hiện có.

Chỉ chạy một trong hai cấu hình, vì cùng dùng port ingest 1935, HLS 8898 và Control API 9997. Tất cả port local chỉ bind `127.0.0.1`. Port HLS trong container là 8888; không chiếm Gateway 8888 trên host.

- Cấu hình `paths: {}` không có catch-all; chỉ exact path `ingest/{ingestKeyId}` và `live/{sessionId}` được tạo qua Control API mới hoạt động.
- Tách hai đường dẫn: OBS publish vào `ingest/{ingestKeyId}` (tắt `runOnRead`/`runOnUnread`), và Stream Service cấu hình `live/{sessionId}` kéo RTMP nội bộ từ `rtmp://127.0.0.1:1935/ingest/{ingestKeyId}?token={RelayReadJWT}` với `sourceOnDemand: false` và tắt `runOnOnline`/`runOnOffline` để tránh vòng lặp hook.
- `overridePublisher: false`; publish/read vẫn dùng JWT; riêng action `api` được bỏ qua xác thực qua `authJWTExclude`. Control API chỉ dành cho localhost/mạng nội bộ.
- Root mặc định trỏ JWKS và internal media callback trực tiếp tới Stream Service tại `host.docker.internal:8082` (`/stream/internal/api/media/jwks` và `/stream/internal/api/media-events`), không qua Gateway; fixture override sang port 18082. Linux có extra_hosts host-gateway; phải kiểm tra reachability tới host trên môi trường chạy thực tế.
- Khai báo `STREAM_MEDIA_JWKS_URL`, `STREAM_MEDIA_CALLBACK_URL`, `STREAM_MEDIA_ALLOWED_ORIGINS` trong `.env` ở root theo `.env.example`.
- `media_hook.py` gửi callback nội bộ không kèm token về `/stream/internal/api/media-events`, dùng timeout request 2s, timeout tổng 10s và tối đa 3 lần thử cho lỗi tạm thời (`409`, `429`, `5xx`, lỗi mạng) với cùng `eventId`. Không in `docker compose config` đầy đủ vì có thể làm lộ token/env của service khác; dùng `config --quiet`.
- Healthcheck kiểm tra listener RTMP/HLS/Control API. Backend thật dùng integration test với `MediaMTX 1.21.2`; `media.py probe` dùng fixture riêng. Không suy ra auth pass chỉ từ container healthy.
- Stream Service gọi Control API qua `STREAM_MEDIA_CONTROL_API_URL=http://localhost:9997` khi chạy trên host. Timeout connect/request mặc định 2s/5s. Khi chạy backend trong Docker, đổi URL theo network/service name tương ứng.
- Production cần HTTPS/RTMPS và network/secret riêng; cấu hình local này chưa là bằng chứng production pass.

Dừng riêng fixture bằng `docker compose --project-directory . -f scripts/testing/streaming/compose.fixture.yml down` (không dùng `-v`, không tác động DB khác). Chạy các lệnh trên từ root; `--project-directory .` giúp kế thừa service và nạp `.env` của root đúng vị trí. Sau MediaMTX restart không tự khôi phục path; streamer cần yêu cầu key mới khi media server đã hoạt động lại.

Vòng đời persistent stream key hiện xử lý đồng bộ qua `POST /stream/internal/api/media-events`, service đồng bộ; các endpoint cũ `/api/streams`, `/publish-credentials`, `/end` và `/api/media/events` đã được gỡ bỏ theo [Persistent Key Refactor Plan](../docs/implement-plan/Streaming%20Persistent%20Key%20Refactor%20Plan.md).

## Hook đếm CCU

`runOnRead` và `runOnUnread` gọi `viewer_hook.py` trong cùng container. `STREAM_CCU_ENABLED` mặc định `false`; khi tắt, script thoát thành công trước khi gọi HTTP. Hai hook không tự restart script đã kết thúc.

Khi bật, READ lấy `MTX_PATH`, `MTX_READER_ID`, `MTX_READER_TYPE = hlsSession`, rồi gọi `GET http://127.0.0.1:9997/v3/hls/sessions/get/{readerId}` không kèm token. Sau khi kiểm tra ID/path và lấy user UUID, script callback `READ` kèm `sessionId`, `readerId`, `userId`. Reader đã mất hoặc technical identity như `media-worker` được bỏ qua. UNREAD chỉ callback session/reader, không tra user qua Control API; backend dùng mapping Redis đã lưu.

| Biến môi trường | Vai trò |
|---|---|
| `STREAM_CCU_ENABLED` | Bật cùng với backend CCU; mặc định `false` |
| `STREAM_VIEWER_CALLBACK_URL` | URL trực tiếp `/stream/internal/api/viewer-events` của Stream Service |
| `MEDIA_CONTROL_BASE_URL` | Base URL Control API; Compose đặt `http://127.0.0.1:9997`, test có thể override bằng server port ngẫu nhiên |

Backend và hook gọi Control API không cần JWT. Không còn file credential, volume dùng chung hoặc tác vụ làm mới Control JWT. JWT publish/read và xác thực danh tính người xem vẫn được giữ nguyên. Cổng Control API được publish trên `127.0.0.1:9997`; môi trường triển khai cần giới hạn truy cập trong mạng tin cậy.

Mỗi HTTP request chờ tối đa 2 giây, mỗi bước thử tối đa 3 lần và cả lượt hook giới hạn 10 giây. Callback retry giữ nguyên body/event ID, áp dụng cho 409 chờ stream LIVE, 503 hoặc lỗi mạng. Các lỗi client khác kết thúc lượt; script không đi theo redirect. SIGINT/SIGTERM hủy lượt đang xử lý an toàn; callback bị lỡ cần được backend đối soát. Log chỉ chứa lý do đã làm sạch, không chứa token, query hoặc response body.

Test hook dùng HTTP server giả lập trên port ngẫu nhiên, không gọi stack đang chạy:

```sh
python3 -m unittest discover -s scripts/testing/streaming -p 'test_viewer_hook.py' -v
```

Luồng V6: Save chỉ lưu settings; `POST /stream/api/streams/me/stream-key` cấp/thay key đồng bộ và trả một lần. Không lưu token trong DB hay trả lại từ GET setup. Xem [kiến trúc hiện tại](../docs/architecture/Streaming%20Architecture%20and%20Flow.md).
