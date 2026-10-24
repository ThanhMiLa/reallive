# Streaming MVP — Kết quả kiểm chứng

Chỉ ghi PASS cho kiểm chứng đã thực thi. Fixture giai đoạn 0 không thay thế nghiệm thu backend nghiệp vụ ở các giai đoạn sau.

**M0 — giai đoạn 0 đạt ngày 2026-10-24:** STR-001–006, 12/12 smoke giao thức và 16/16 test fixture/helper. Bằng chứng gồm số đo/callback tự động, ảnh browser và xác nhận trực tiếp của người dùng; các case browser âm tính có nguồn xác nhận thủ công được nêu rõ bên dưới. Production/TLS và backend nghiệp vụ chưa được nghiệm thu trong giai đoạn này.

## STR-001 — Rà soát (2026-10-24)

- PASS: [Bảng quyết định và contract](../Implement%20Plan/Streaming%20MVP%20Phase%200%20Review.md) đã đối chiếu tài liệu và code hiện có.
- PASS: liên kết local trong plan/backlog/review tồn tại; 58 mã task duy nhất; trạng thái hoàn tất chỉ ghi cho task đã có bằng chứng.
- Chưa kiểm chứng media runtime trong task này.

## STR-002 — Key/JWT/JWKS/callback fixture (2026-10-24)

Chạy từ root:

```sh
python3 scripts/testing/streaming/fixture.py prepare
python3 scripts/testing/streaming/fixture.py verify
python3 -m unittest discover -s scripts/testing/streaming -p 'test_*.py' -v
```

- PASS: tạo RSA 2048 bit; chạy lại giữ nguyên private/public key và callback secret. Cặp khóa thiếu bị từ chối, không tự thay khóa cũ.
- PASS: JWT publish/read/api verify chữ ký bằng OpenSSL, đúng issuer/audience/TTL và chỉ có quyền dự kiến; token sai chữ ký/hết hạn/sai path/sai action bị từ chối.
- PASS: HTTP JWKS chỉ có kty/kid/alg/use/n/e, modulus ít nhất 2048 bit, exponent 65537.
- PASS: callback HMAC/timestamp hợp lệ được ghi; duplicate giữ một record; HMAC sai, raw body thay đổi, timestamp cũ, field query hoặc body quá lớn bị từ chối.
- PASS: các artifact private.pem/public.pem/callback.secret/fixtures.json/jwks.json trong `.local/streaming/` được Git ignore, file mode 0600; không in token/private key/secret ra output.
- Tại thời điểm STR-002: 8 test, chạy trong thư mục tạm cô lập và HTTP loopback port ngẫu nhiên. Bộ test đã được mở rộng cho player fixture và helper ở các task sau.
- Chưa kiểm chứng các token/receiver này với MediaMTX trong STR-002; phần đó thuộc STR-003–006.

## Môi trường kiểm chứng runtime (2026-10-24)

- macOS/arm64; Docker Engine 29.5.3; MediaMTX binary xác nhận `v1.21.2` trong một custom container.
- FFmpeg/ffprobe 9.0.2 cho nguồn H264/AAC tổng hợp và kiểm decode; không có FFmpeg trong container media.
- Browser do người dùng thao tác: Chrome desktop/macOS, hls.js 1.7.3 từ lockfile/node_modules hiện có.
- Fixture host port 18082; RTMP 1935; HLS host 8898 → container 8888; Control API 9997. Port local bind loopback.
- Không điều khiển OBS/Chrome của người dùng trong nghiệm thu thủ công. Người dùng thao tác trên trang fixture và xác nhận bằng bản ghi/ảnh.

## STR-003 — Container/network/Control API: PASS

```sh
docker compose config --quiet
docker compose --project-directory . -f scripts/testing/streaming/compose.fixture.yml config --quiet
python3 scripts/testing/streaming/media.py probe
python3 scripts/testing/streaming/media.py create
python3 scripts/testing/streaming/media.py info
```

- PASS: custom image có shell/Python/CA, container healthy và binary v1.21.2. HLS host port 8898 không trùng Gateway 8888.
- PASS: container GET JWKS từ host.docker.internal:18082 được, chỉ có public parameters; callback secret được cấu hình riêng.
- PASS: Control API nhận JWT quyền api, từ chối publish/read JWT; exact path add/get thành công, path chưa có nguồn có online=false/source=null.
- PASS: no catch-all; valid JWT cho path chưa đăng ký cũng không publish được trong smoke.
- Docker build ban đầu timeout khi dùng cấu hình credential helper mặc định; build đạt với Docker config auths={} cô lập và giữ nguyên endpoint context. Không sửa Docker config của người dùng. Helper `stack.py --anonymous-registry` tái hiện cách này.
- macOS network đã chạy thực tế. Linux có cấu hình host-gateway và hướng dẫn; chưa chạy nghiệm thu trên Linux.

## STR-004 — OBS publish và auth âm tính: PASS

- Người dùng đã Start Streaming từ OBS và bấm ghi nhận publish trên fixture.
- Quan sát MediaMTX: source type rtmpConn; tracks H264/MPEG-4 Audio; online=true. Hook ONLINE có cùng source ID `f30448f1-c374-4327-be4b-9c2ee445a2d8`.
- Người dùng gửi ảnh browser đang phát: 6.116 frame, 101,8 giây, 28 playlist, 52 fragment; ô xác nhận nghe tiếng đã tích.
- Bản ghi browser hợp lệ đã lưu 5.036 frame, khoảng 83,85 giây, audioConfirmed=true, HTTP error=0.
- Smoke RTMP đã từ chối thiếu token, sai chữ ký, hết hạn, sai path và Read JWT dùng để publish. Các case âm tính này dùng FFmpeg tổng hợp; không ghi thành toàn bộ case đã thao tác trong OBS.
- Provisioning không tự tạo nguồn online. Publisher thứ hai không chiếm nguồn đang phát khi overridePublisher=false.

## Smoke giao thức STR-004–006: PASS 12/12

```sh
python3 scripts/testing/streaming/smoke.py
```

| Kiểm chứng đã chạy | Kết quả |
|---|---|
| Control API chỉ nhận quyền api | PASS |
| Provision path chưa có media không online | PASS |
| H264/AAC tạo source và hook ONLINE khớp ID/type | PASS |
| Publisher thứ hai không thay publisher đang live | PASS |
| CORS preflight cho Authorization; origin ngoài allowlist không được cấp CORS | PASS |
| Read JWT mở master/media playlist, init và segment; decode được audio AAC/video H264 | PASS |
| Phiên HLS mới từ chối thiếu token/sai action/path/signature/expiry | PASS |
| Phiên HLS đã mở tiếp tục khi JWT hết hạn; token đó không mở phiên mới | PASS |
| Hook OFFLINE khớp source đã dừng | PASS |
| Publish từ chối token sai và path chưa đăng ký dù JWT đúng | PASS |
| Reconnect cùng JWT/path tạo source ID mới và hook ONLINE | PASS |
| DELETE exact path đang phát làm nguồn dừng; JWT cũ còn hạn không mở lại path | PASS |

- fMP4 thực tế có audio/video rendition riêng; smoke tải và probe/decode từng rendition. Segment đã quan sát có đuôi .mp4.
- Callback helper có test retry giữ nguyên body/eventId, ký đúng mỗi lần, không gửi MTX_QUERY; SIGINT dừng retry.
- Mỗi smoke dùng path UUID riêng và cleanup đúng path, không ngắt nguồn OBS của người dùng.
- Các kết quả này chứng minh cơ chế media. Deadline 60 giây, ENDED terminal trong DB, worker/reconciler và UI nghiệp vụ thuộc implementation các giai đoạn sau.

## STR-005 — Browser: PASS

- PASS: user Chrome xem được hình/tiếng thật từ OBS; số đo playlist/fragment và frame được lưu.
- PASS ở mức HTTP: preflight/origin, HLS auth âm tính và decode audio/video trong smoke.
- Người dùng xác nhận: “các gia đoạn ở http://localhost:18082/ tôi đã làm theo và đã hoàn thành đúng theo plan”. Xác nhận áp dụng cho các bước đã hướng dẫn: browser thiếu token/sai quyền/sai path khi mở phiên mới và Network check.
- Giới hạn bằng chứng: ba case browser âm tính không được lưu bằng nút record riêng; không có số đo HTTP browser riêng cho các lượt đó. Chúng được ghi là xác nhận trực tiếp của người dùng trong `.local/streaming/user-confirmation.json`, không giả lập thành dữ liệu HTTP tự động. Các status từ chối HTTP đã được smoke kiểm chứng độc lập.

## STR-006 — OBS reconnect/đóng path: PASS

- PASS ở mức giao thức tổng hợp: hook source ID/type, offline/online, reconnect cùng JWT/path, DELETE path khi phát, thử lại JWT cũ còn hạn bị từ chối.
- Callback của fixture session có ba cặp ONLINE/OFFLINE, source ID lần lượt `f30448f1-c374-4327-be4b-9c2ee445a2d8`, `8ac723c5-f61a-4ce5-aaa0-60fae2f2bbbb`, `d36bfc24-bcef-4d67-896b-5a384e6e70b5`.
- Người dùng đã lưu quan sát OBS reconnect với nguồn mới, rồi lưu old-key-rejected với pathConfigured=false/source=null.
- Đối chiếu sau khi người dùng hoàn tất: path config không tồn tại, nguồn không còn; callback OFFLINE cuối khớp source cuối. Người dùng xác nhận đã làm đầy đủ các bước trên fixture.
- Với phiên bản đã pin, DELETE config path trong lúc phát đã dừng nguồn mà không cần gọi kick bổ sung. Kick RTMPS/TLS chưa được chạy trong nghiệm thu local này; contract endpoint vẫn theo OpenAPI, không ghi thành runtime pass.

## Các check bổ sung

```sh
python3 -m unittest discover -s scripts/testing/streaming -p 'test_*.py' -v
node --check scripts/testing/streaming/player.js
sh -n scripts/dev/generate-stream-media-keys.sh
python3 scripts/testing/streaming/acceptance.py
```

- PASS: 16 test, gồm key/JWT/JWKS/callback, same-origin credential fixture, không lộ api token, giới hạn record, giữ PENDING nếu thiếu bằng chứng, helper retry/SIGINT.
- PASS: JS/shell syntax, root/fixture Compose config, link tài liệu và 58 mã task duy nhất.
- Kiểm chứng trước khi bỏ profile: root Compose không yêu cầu env streaming khi profile chưa bật. Cấu hình hiện tại đã bỏ profile theo cách khởi động chung của người dùng; callback env phải được chuẩn bị trước khi chạy `docker compose up -d`.
- PASS sau khi gộp service MediaMTX trực tiếp vào docker-compose.yml root: cấu hình runtime đã resolve của toàn bộ service root và service fixture khớp trước/sau khi di chuyển. Fixture kế thừa root và dùng project directory root; stack.py status chạy được. File infra/mediamtx/compose.yml riêng đã được bỏ.
- PASS: artifact key/token/env/result được Git ignore, mode 0600. Docker build context whitelist chỉ đưa helper cần thiết vào build.
- `acceptance.py` cuối cùng: protocol/STR-004/STR-005/STR-006 đều PASS; STR-005 in rõ nguồn xác nhận người dùng cho ba case thiếu record. Lệnh không tự sửa checkbox và không cho xác nhận thủ công thay thế smoke bị lỗi.

File kết quả local được ignore; tài liệu này chỉ giữ số đo và ID không chứa credential. Môi trường local và path test đã kết thúc không được báo thành backend nghiệp vụ hoặc production đã hoàn tất.
