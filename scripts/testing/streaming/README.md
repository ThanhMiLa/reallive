# Streaming MVP — Fixture kiểm chứng media

Fixture kỹ thuật cho STR-002–006 và chế độ backend account của Giai đoạn 5, không phải Stream Service hoặc cơ chế auth production. Chỉ dùng key/token test sinh riêng trong `.local/streaming/`, đã được Git ignore.

## Chuẩn bị và kiểm tra fixture (STR-002)

Yêu cầu: Python 3.10+, OpenSSL và shell POSIX. Chạy từ root repository:

```sh
chmod +x scripts/dev/generate-stream-media-keys.sh
python3 scripts/testing/streaming/fixture.py prepare
python3 scripts/testing/streaming/fixture.py verify
python3 -m unittest discover -s scripts/testing/streaming -p 'test_*.py' -v
python3 scripts/testing/streaming/fixture.py serve
```

- `prepare` tạo key nếu chưa có; dùng lại key/session ID khi chạy lại, chỉ cấp lại bộ JWT test. Không tự thay một cặp khóa thiếu/mismatched.
- `fixtures.json` chứa serverUrl/streamKey/playbackUrl và JWT publish/read/api. Các token âm tính gồm sai chữ ký, hết hạn và sai path; token read cũng dùng để thử publish sai quyền.
- TTL mặc định đồng bộ với quyết định Giai đoạn 2: publish 8h, read 5m, api 5m. Chạy lại `prepare` để refresh trước đợt test mới; không coi đó là revoke token cũ.
- Key RSA 2048 bit được ký/verify bằng OpenSSL; JWKS chỉ gồm kty/kid/alg/use/n/e. Không có Python dependency ngoài thư viện chuẩn.
- Không `cat` token/key ra log hoặc commit chúng. Các lệnh trên chỉ in trạng thái và đường dẫn.
- Dừng fixture bằng Ctrl+C. Dữ liệu callback test lưu ở `.local/streaming/events.jsonl`, không có token/query.

## HTTP fixture

| Endpoint | Hành vi |
|---|---|
| `GET http://localhost:18082/health` | Trạng thái fixture |
| `GET http://localhost:18082/stream/api/media/jwks` | JWKS public, không bọc ApiResponse |
| `POST http://localhost:18082/stream/api/media/events` | Receiver nhận JSON event hợp lệ và dedupe eventId |

Callback theo contract ở [Implementation Plan](../../../docs/Implement%20Plan/Streaming%20MVP%20Implementation%20Plan.md): gửi POST JSON trực tiếp tới endpoint callback.

Fixture bind loopback mặc định. MediaMTX Docker sẽ dùng `host.docker.internal:18082`; khả năng truy cập thực tế được kiểm ở STR-003. Linux cần `host.docker.internal:host-gateway`; chỉ dùng `--host` khác khi môi trường local yêu cầu và giới hạn network phù hợp.

## Container và smoke giao thức (STR-003–006)

Terminal 1 giữ `fixture.py serve` đang chạy. Terminal 2:

```sh
python3 scripts/testing/streaming/stack.py up
python3 scripts/testing/streaming/media.py probe
python3 scripts/testing/streaming/smoke.py
```

Service MediaMTX được khai báo trực tiếp trong `docker-compose.yml` ở root và chạy mặc định cùng các service khác. `compose.fixture.yml` kế thừa đúng service đó và chỉ override JWKS/callback/origin sang fixture. `stack.py` đặt project directory về root để dùng đúng đường dẫn và `.env` của repository; chuẩn bị `.env` root theo `.env.example` trước khi chạy. Nếu gọi Compose trực tiếp, thêm `--project-directory .` và chạy từ root.

Nếu build timeout khi lấy metadata public image do Docker credential helper, dùng `stack.py up --anonymous-registry`. Script chỉ tạo Docker config `auths={}` trong `.local/streaming/docker-public`, giữ endpoint của context hiện tại và không sửa Docker config của người dùng.

Smoke cần FFmpeg/ffprobe có libx264/AAC trên máy chạy test. Đây chỉ là nguồn media tổng hợp để kiểm chứng, không đưa FFmpeg vào container MediaMTX hoặc luồng runtime MVP. Mỗi lần smoke tạo path UUID riêng và dọn đúng path đó; không thay fixture session/path đang dùng với OBS.

12 mục kiểm chứng gồm Control API permission, path chưa có media chưa online, H264/AAC + ONLINE hook, chặn publisher thứ hai, CORS, HLS master/media playlist + init/segment và decode audio/video, token HLS sai, JWT hết hạn giữa phiên HLS, OFFLINE hook, token publish sai, reconnect và xóa path khi LIVE rồi thử JWT cũ còn hạn.

Kết quả an toàn ở `.local/streaming/smoke-results.json` không chứa JWT hoặc HLS session URL. Lần chạy mới chuyển report sang RUNNING, lỗi ghi FAIL, chỉ đủ kiểm chứng mới ghi PASS. FFmpeg stderr có thể chứa URL ingest nên chỉ được giữ tạm trong memory và không in ra log.

Điểm quan sát trên MediaMTX 1.21.2:

- GET/POST/DELETE config path dùng `/v3/config/paths/{get,add,delete}/live/{sessionId}`; nguồn thực tế đọc từ `/v3/paths/get/live/{sessionId}`.
- Field `online`, `source.id`, `source.type`, `tracks`, `onlineTime` phân biệt path đã provision với nguồn online. Hook có MTX_PATH/MTX_SOURCE_ID/MTX_SOURCE_TYPE; không gửi MTX_QUERY.
- fMP4 có thể tách playlist/segment audio và video; phải tải init tương ứng từng rendition. Segment quan sát có đuôi `.mp4`.
- HTTP có định danh phiên HLS trong các URL do MediaMTX tạo. Đó không phải Read JWT; không log/copy các URL phiên này. Read JWT vẫn được gửi qua header khi player tạo request.
- Khi thử publish âm tính, dùng thời lượng đủ dài để client nhận phản hồi/ngắt kết nối; FFmpeg output quá ngắn có thể kết thúc trước khi nhận lỗi. Đối chiếu source thực tế và callback, không dựa vào exit code đơn lẻ.

## OBS và browser thật do người dùng thao tác

```sh
python3 scripts/testing/streaming/generate_test_video.py
```

1. Mở `http://localhost:18082` bằng Chrome/Edge desktop. Fixture phục vụ hls.js 1.7.3 từ node_modules hiện có; nếu thiếu, chạy `npm ci` trong web-app.
2. Bấm **Tạo path thử nghiệm**. Copy Server và stream key **Hợp lệ** từ trang.
3. OBS → Settings → Stream → Custom, dán Server/Stream Key. Dùng H264/AAC; video mẫu `.local/streaming/obs-test.mp4` có chuyển động và tiếng, có thể thêm bằng Media Source và bật Loop.
4. Bấm Start Streaming trong OBS; sau khi nguồn ONLINE, bấm nút ghi nhận OBS trên trang.
5. Ở mục 2, chọn token xem Hợp lệ → Mở phiên xem mới. Xác nhận có hình/tiếng, tích nghe tiếng, bấm Lưu kết quả browser.
6. Giữ OBS phát; thử lần lượt **Thiếu token / Sai quyền / Sai path**. Mỗi lần mở phiên mới, xác nhận HTTP 401/403 và không có video, lưu kết quả. Wrong signature/expiry cũng có sẵn để thử thêm.
7. DevTools Network: playlist/segment tới localhost:8898 với Bearer, không account cookie; kiểm CORS thực tế. Không chụp hoặc chia sẻ header/token nguyên văn.
8. Đưa token xem về Hợp lệ nếu muốn tiếp tục xem. Trong OBS Stop Streaming rồi Start Streaming lại bằng cùng key; bấm ghi nhận reconnect. Source ID phải đổi, có OFFLINE nguồn cũ và ONLINE nguồn mới.
9. Khi đang phát, bấm **Đóng path đang phát**. Thử Start Streaming lại trong OBS bằng key đang lưu: phải thất bại. Bấm ghi nhận old-key bị từ chối; không tạo lại path trước khi xác nhận case này.

Trang chỉ cấp token publish/read test qua POST cùng origin localhost; không cấp token api cho browser, không lưu token vào browser persistent storage. Callback dùng JSON trực tiếp, không HMAC theo contract local MVP từ Giai đoạn 2. Bản ghi quan sát chỉ chứa số đo, xác nhận người dùng và source metadata trong `.local/streaming/manual-results.jsonl`.

Kiểm tra phần còn thiếu:

```sh
python3 scripts/testing/streaming/acceptance.py
```

Lệnh đối chiếu smoke với hook, quan sát OBS và số đo browser; trả PENDING nếu chưa đủ. Không tự đánh dấu checkbox backlog. Bằng chứng OBS/browser phải được review trước khi chốt STR-004–006; protocol smoke không thay thế thao tác thực tế này.

Khi người dùng đã thực hiện và xác nhận trực tiếp các case browser nhưng không bấm lưu từng lượt, review có thể ghi xác nhận đó vào `.local/streaming/user-confirmation.json` với source `user-message`, sessionId, statement nguyên văn và confirmedChecks tương ứng. Validator in rõ các case theo xác nhận này; không sinh số đo HTTP giả và không dùng chúng để bỏ qua smoke lỗi hoặc bằng chứng OBS còn thiếu.

## Kiểm tra HLS của session đã tạo bằng backend thật

Khi OBS đang phát, dùng helper chỉ đọc đúng session hiện tại:

```sh
python3 scripts/testing/streaming/inspect_playback.py --session-id '<SESSION_UUID>'
```

Yêu cầu FFmpeg/ffprobe, Stream Service tại `localhost:8082`, MediaMTX Control API tại `localhost:9997` và HLS tại `localhost:8898`. Helper dùng cặp dev key trong `.local/streaming/`; public key phải khớp JWKS backend. Không tự tạo/đổi key hoặc thay cấu hình media. Đây là token test local; không thay API cấp quyền xem và kiểm tra nghiệp vụ Giai đoạn 5.

Helper chỉ GET source/HLS: xác nhận nguồn online, lấy master/rendition playlist bằng Read JWT đúng path, tải init + segment fMP4, giải mã audio/video và kiểm tra playlist có segment mới. `EXT-X-MEDIA-SEQUENCE` có thể giữ 0 khi cửa sổ HLS chưa đầy, nên tiến trình được xác nhận bằng segment cuối thay đổi.

Report không JWT, clip ngắn `sample.mp4` và frame `sample-frame.jpg` nằm trong `.local/streaming/playback-check/<SESSION_UUID>/`. Tự mở clip/frame để đối chiếu hình/tiếng với nguồn OBS; HLS có độ trễ. Helper không mở/điều khiển OBS, tạo/xóa path hoặc kick publisher. Không chạy smoke tổng hợp để thay bằng chứng của session hiện tại.

URL xem đúng là `http://localhost:8898/live/<SESSION_UUID>/index.m3u8`; URL RTMP port 1935 dùng cho nguồn phát. HLS yêu cầu Read JWT qua `Authorization: Bearer ...`; Publish JWT trong stream key không có quyền read. Tải master playlist rồi giữ nguyên URL/query mà playlist trả về khi tải playlist con, init và segment; không ghi các URL phiên HLS vào report/log.

## Backend account thật cho giai đoạn 5

Quy trình từng bước từ Create session, OBS, browser, JWT, reconnect đến End và hết grace: [Phase 5 Test Plan](../../../docs/testing/Streaming%20MVP%20Phase%205%20Test%20Plan.md).

Chế độ này dùng account đã đăng nhập qua Gateway để gọi đúng hai API viewer. Account cookie chỉ nằm trong file private phía server fixture; Read JWT do backend cấp chỉ giữ trong memory của trang và không ghi vào localStorage/report.

Yêu cầu: Stream Service, Gateway và MediaMTX đang chạy; HLS allow-origin có `http://localhost:5173` (cấu hình local mặc định và `compose.fixture.yml` đã có). Với Docker Compose dev, kiểm tra `STREAM_MEDIA_ALLOWED_ORIGINS` trong `.env` trước khi thử browser.

### Chuẩn bị

1. Đăng nhập web app qua Gateway (`http://localhost:8888`) bằng account test.
2. Tạo session PUBLIC, cho OBS phát để MediaMTX báo nguồn online và backend chuyển session sang `LIVE`.
3. Lấy đúng header `Cookie` của account từ DevTools → Network, chỉ dán một dòng vào file ngoài Git:

   ```sh
   mkdir -p .local/streaming
   # Dán một dòng Cookie header vào file, không thêm tiền tố "Cookie:"
   chmod 600 .local/streaming/account-cookie.txt
   ```

   Không đưa cookie vào command line, shell history, issue, chat hoặc commit. Fixture từ chối file có quyền group/other và file có nhiều dòng.

4. Chạy fixture ở port player (5173 đã có trong HLS allow-origin local):

   ```sh
   python3 scripts/testing/streaming/fixture.py serve \
     --port 5173 \
     --account-cookie-file .local/streaming/account-cookie.txt \
     --gateway-base-url http://localhost:8888
   ```

### Kiểm chứng bằng helper (không cần browser)

Khi session đang `LIVE`:

```sh
python3 scripts/testing/streaming/inspect_playback.py \
  --session-id '<SESSION_UUID>' \
  --access-mode backend \
  --account-cookie-file .local/streaming/account-cookie.txt
```

Helper gọi `GET /stream/api/streams/{id}` rồi `POST /stream/api/streams/{id}/playback-access` qua Gateway, dùng Read JWT backend cấp để tải master/rendition/init/segment, decode audio/video và kiểm tra playlist tiến triển. Report và sample nằm trong `.local/streaming/playback-check/<SESSION_UUID>/`.

### Kiểm chứng trên browser

Regression fixture/player (chạy từ root):

```sh
python3 -m unittest discover -s scripts/testing/streaming -p 'test_*.py' -v
node --test scripts/testing/streaming/test_player.mjs
```

Player gắn session và nguồn access đã mở vào kết quả browser. Browser evidence backend chỉ được nhận cho session đã được fixture cấp access. OBS evidence dùng session/backend mode đang chọn, đối chiếu metadata và source của session đó, kể cả khi chưa mở player. Backend mode từ chối cả browser/OBS observation thiếu context. Backend client từ chối redirect để giữ account cookie tại Gateway đã cấu hình.

1. Mở `http://localhost:5173` bằng Chrome/Edge desktop.
   Dùng đúng hostname `localhost`; `http://127.0.0.1:5173` là origin khác, không nằm trong HLS allow-origin local mặc định và có thể gây `networkError/manifestLoadError` dù metadata vẫn đọc được.
2. Ở mục 2, chọn **Backend account thật**, nhập Session UUID.
3. Bấm **Đọc metadata backend**: chỉ gọi GET, không cấp token. Lặp lại để xác nhận polling không sinh credential.
4. Bấm **Mở phiên xem mới**: fixture gọi POST playback-access, player dùng Read JWT backend cấp để mở HLS. Xác nhận hình/tiếng, tích nghe tiếng và lưu kết quả browser.
5. DevTools → Network: request playlist/segment phải tới MediaMTX `localhost:8898`, có `Authorization: Bearer`, **không** mang account cookie; response metadata/POST có `Cache-Control: no-store`; token không xuất hiện trong URL.

Khung trạng thái và acceptance dùng session/backend mode đang chọn, hiển thị rõ session ID. Backend acceptance đọc riêng các evidence `browser`, `obs-publish`, `obs-reconnect`, `obs-end` của đúng session, không lấy kết quả protocol/hooks/confirmation M0 để thay thế. Đây là kiểm tra evidence thủ công, chưa tự chốt STR-505/M5.

Chạy kiểm tra evidence backend qua CLI:

```sh
python3 scripts/testing/streaming/acceptance.py --access-source backend --session-id '<SESSION_UUID>'
```

Không truyền lựa chọn nào thì CLI giữ chế độ nghiệm thu M0. Truyền session ID mà thiếu `--access-source backend` sẽ bị từ chối thay vì âm thầm kiểm tra session M0. Các OBS record cũ bị gắn nhầm session A không thể suy ra lại B: cần ghi nhận lại cho session đúng, không tự đổi lịch sử.

Trong backend mode, các nút tạo/xóa path và copy key M0 bị tắt. Kết thúc buổi phát backend bằng End API với account owner rồi ghi nhận OBS/key cũ bị từ chối; không dùng nút xóa path M0 để kết thúc session backend.

### Case âm tính và expiry

- Không có cookie hoặc cookie hết hạn: GET metadata phải trả 401; fixture báo lỗi an toàn, không in cookie.
- Session `READY`/`RECONNECTING`/`ENDED`: POST trả 409; session PRIVATE trả 403.
- Token thiếu/sai path/sai chữ ký/hết hạn trên **phiên HLS mới** phải bị MediaMTX từ chối (401/403). Có thể dùng các case token fixture cho phần HLS; happy case bắt buộc dùng Read JWT backend cấp.
- Read JWT mặc định hết hạn sau 5 phút. Kiểm tra bằng cách mở phiên HLS mới sau khi hết hạn; không coi phiên HLS đang mở là bị thu hồi ngay, vì đây là hành vi của MediaMTX cần ghi nhận riêng.

Không đánh dấu STR-505/M5 hoàn tất nếu chưa có xác nhận account thật, nguồn LIVE và hình/tiếng trên browser.

## Dừng fixture

Với backend mode: nếu muốn kết thúc buổi phát, dùng End API của session backend, rồi dừng fixture bằng Ctrl+C. Quy trình `stack.py down` dưới đây chỉ dành cho stack thử nghiệm M0.

Stop Streaming trong OBS, đóng path trên trang, sau đó:

```sh
python3 scripts/testing/streaming/stack.py down
```

Dừng fixture server bằng Ctrl+C ở terminal 1. Không xóa volume/database của các service khác. JWKS/callback gọi trực tiếp Stream Service và vòng đời DB thật được triển khai ở giai đoạn 2–4, không coi fixture là backend nghiệp vụ hoàn chỉnh.
