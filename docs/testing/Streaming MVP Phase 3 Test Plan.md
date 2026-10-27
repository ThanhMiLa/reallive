# Giai đoạn 3 — Plan test và nghiệm thu OBS

- Ngày: 2026-10-26.
- Người dùng tự mở, cấu hình, Start/Stop OBS. Helper không thao tác OBS.
- Đích: Create API provision exact path trước response; credential API cấp phát đúng; OBS phát được bằng credential đó. Nguồn online chưa chuyển DB thành LIVE khi chưa có handler Giai đoạn 4.
- Kết quả tự động: xem [Streaming MVP Tests](Streaming%20MVP%20Tests.md). Phần OBS chưa được đánh dấu PASS.

## 1. Kiểm thử tự động

Chạy từ repository root, cần JDK 21 và Docker:

```sh
./stream-service/mvnw -f stream-service/pom.xml clean verify
python3 -m unittest discover -s scripts/testing/streaming -p 'test_*.py' -v
docker compose config --quiet
docker compose --project-directory . -f scripts/testing/streaming/compose.fixture.yml config --quiet
```

Suite mặc định dùng PostgreSQL Testcontainers/schema riêng, không dùng DB ứng dụng. Chờ BUILD SUCCESS; không có failure/error/skipped trong suite mặc định.

Kiểm thử thêm MediaMTX thật, cần FFmpeg hỗ trợ libx264/AAC và Python 3.10+:

```sh
docker build -f mediamtx/Dockerfile -t reallive-mediamtx:1.21.2 .
./stream-service/mvnw -f stream-service/pom.xml -Dtest=StreamProvisioningMediaIT -Dstream.provisioning.it=true test
```

Ba test tự động phải PASS: workflow HTTP/publisher, smoke script và Create timeout khi MediaMTX test bị pause (rollback DB rồi retry cùng request ID). Test OBS là opt-in riêng nên được skip trong lệnh này. MediaMTX và PostgreSQL test dùng container/cổng/schema riêng; không thay container đang chạy của bạn. Context Stream Service HTTP là thật; eligibility/channel và Redis authorities được stub với identity test riêng. Đây không thay nghiệm thu toàn bộ account/User/Channel/Gateway trên stack ứng dụng.

## 2. OBS thật trên môi trường cô lập — cách nghiệm thu M3

### 2.1. Khởi động helper

Chạy riêng lệnh dưới đây, không chạy đồng thời với Maven clean/test khác trên cùng `stream-service/target`:

```sh
./stream-service/mvnw -f stream-service/pom.xml '-Dtest=StreamProvisioningMediaIT#obsPublishesCredentialsObtainedFromTheRealCreateApi' -Dstream.provisioning.it=true -Dstream.media.obs=true test
```

Helper sẽ:

1. Khởi động PostgreSQL, Stream Service và MediaMTX test với RSA key/identity riêng.
2. Gọi Create API thật, nhận credential sau provisioning và commit.
3. Ghi URL trang bàn giao vào `.local/streaming/phase3-obs-handoff.json` (file này chỉ có URL và session ID).
4. Chờ tối đa 10 phút để bạn bắt đầu phát. Sau khi có nguồn online, chờ tối đa 10 phút nữa để bạn bấm xác nhận trên trang.

Terminal thứ hai lấy URL an toàn:

```sh
python3 -c 'import json; from pathlib import Path; print(json.loads(Path(".local/streaming/phase3-obs-handoff.json").read_text())["url"])'
```

Nếu file chưa có, đợi Spring Boot/container khởi động xong rồi chạy lại. Tự mở URL được in ra bằng browser. Trang chỉ ở loopback, hiển thị server và stream key dạng password, có nút copy. Credential chỉ ở bộ nhớ/trang test, không có Control API JWT hoặc account cookie trên trang. Dùng URL mới mỗi lần chạy; không lấy credential cũ của fixture Giai đoạn 0.

### 2.2. Tự cấu hình OBS

1. Ghi nhớ profile/scene collection đang dùng. Tạo profile và scene collection test riêng, ví dụ `Streaming Phase 3`; dùng lại profile cũ sau test.
2. Dùng video thử có hình/tiếng làm Media Source và bật Loop. Có thể tạo bằng `python3 scripts/testing/streaming/generate_test_video.py`, file `.local/streaming/obs-test.mp4`. Không cần bật screen capture hoặc microphone.
3. Settings → Stream → Service `Custom`.
4. Trên trang helper bấm **Copy server**, dán vào Server của OBS.
5. Bấm **Copy stream key**, dán nguyên giá trị vào Stream Key. Không thêm `live/`, session ID hoặc `?token=` bằng tay. Port RTMP của container test là port ngẫu nhiên trên trang, không mặc định 1935.
6. Dùng H.264 + AAC; keyframe interval 2 giây. Apply/OK rồi tự bấm **Start Streaming**.

### 2.3. Quan sát và hoàn tất

| Quan sát | Kết quả mong đợi |
|---|---|
| OBS sau Start | Kết nối phát được, có bitrate và bộ đếm thời gian phát |
| Trang helper | Hiện `OBS source online`, source connection ID và DB status READY |
| Update metadata tự động trong helper | Title được đổi qua PATCH, source connection ID giữ nguyên |
| Trạng thái DB | Vẫn READY: Giai đoạn 3 chưa có handler ONLINE → LIVE |
| Token/path | Credential do Create API cấp được MediaMTX xác thực trên đúng path |

Khi thấy OBS phát ổn định và trang đã báo nguồn online, bấm **Confirm OBS is streaming and finish test**. Helper kiểm tra nguồn vẫn online, gọi use case đóng media đồng bộ để dọn session test, ghi report rồi kết thúc Maven.

Sau xác nhận, OBS mất kết nối vì path được đóng là hành vi cleanup mong đợi. Tự bấm **Stop Streaming**, đóng trang test và chuyển về profile/scene collection ban đầu.

```sh
cat .local/streaming/phase3-obs-result.json
```

Nghiệm thu khi report `status: PASS`, `metadataUpdatePreservedSource: true`, `cleanup: ENDED/CLOSED`, và Maven `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`. Report chỉ chứa session/source metadata, không JWT. Lần chạy mới ghi RUNNING; lỗi ghi FAIL, không tái sử dụng PASS của lần trước.

Chỉ sau kết quả này mới đánh dấu STR-308 và M3 đạt. Nếu cần trao đổi lỗi, gửi status report/HTTP status và thông báo đã che token; không cần gửi stream key.

## 3. Smoke trên stack ứng dụng thật

Dùng khi muốn kiểm chứng cả account/Gateway/User/Channel đang chạy. Cần streamer đủ điều kiện, channel ACTIVE, category ACTIVE và cookie đăng nhập còn hạn. Stack này dùng DB ứng dụng; khác với môi trường cô lập ở mục 2.

1. Cấu hình/export env Stream Service theo README, bao gồm cặp RSA ổn định, DB/Redis/User/Channel và `STREAM_MEDIA_CONTROL_API_URL=http://localhost:9997`.
2. Chạy User/Channel/Stream/Gateway; MediaMTX dùng root Compose với JWKS/callback trỏ thẳng Stream Service port 8082. Container phải gọi được backend qua `host.docker.internal`; `BACKEND_BIND_ADDRESS` cần phù hợp môi trường host/Docker.
3. Tự lưu riêng access token vào `.local/streaming/owner.cookie` (raw JWT hoặc `access_token=<JWT>`, chỉ access token), đặt quyền file 0600. Không đưa token vào command line.
4. Dùng category ID thật chạy:

```sh
python3 scripts/testing/streaming/provisioning.py --cookie-file .local/streaming/owner.cookie --category-id '<ACTIVE_CATEGORY_UUID>'
```

Script mặc định gọi Gateway `http://127.0.0.1:8888/stream`. Có thể chỉ định `--base-url` để đổi host/port. Script gọi Create → replay → current → PATCH → cấp lại OBS, giữ request ID trong `.local/streaming/phase3-smoke.json`, lưu credential mode 0600 ở `.local/streaming/phase3-obs-credentials.json`, không in token ra console.

Giữ cùng state file khi chạy lại sau timeout/mất response. Muốn test một lần tạo mới, chọn state file mới bằng `--state-file`; channel đang có session active sẽ trả 409. Không tự xóa session/history trong DB để vượt constraint.

Tự nhập `serverUrl` và `streamKey` trong file credential vào OBS rồi phát như mục 2. Backend hiện chưa có End HTTP API; script để lại session READY và path mở trên stack thật. Dùng môi trường cô lập nếu cần cleanup tự động. Cleanup session ứng dụng phải xác nhận đóng path/ngắt nguồn trước ENDED/CLOSED/history, theo thiết kế; End HTTP được bổ sung ở Giai đoạn 4.

## 4. Ma trận regression

Các case dưới đây đã có test tự động; có thể lặp bằng API client trên stack test riêng:

| Case | Mong đợi |
|---|---|
| Create hợp lệ | 201, READY/publishReady, path đã có, response credential no-store |
| Gửi lại cùng owner/request ID | 200, cùng session, không sửa title/category ngầm |
| Request ID mới khi slot còn giữ | 409, không tạo session/path thứ hai |
| Mất response sau commit | Current tìm lại session; replay cùng request ID trả session đã commit |
| Media unavailable/timeout khi Create | 503, không credential, rollback session/history và slot |
| Owner khác PATCH/cấp OBS | 403, không sửa metadata hoặc cấp token |
| PATCH null/rỗng/field hệ thống | 400; body thiếu field giữ nguyên field đó |
| Category không hợp lệ | 400, không thay đổi title/category một phần |
| Cấp lại OBS | JWT mới cùng path; token cũ chưa bị revoke; no-store |
| Path mất sau provisioning | Current publishReady=false; cấp credential/replay trả 503; không tự restore |
| Session ENDED hoặc reconnect tại/sau deadline | Không cấp credential; không hồi sinh session |
| Current/polling | Không JWT, không job status; vẫn trả session quá grace đang giữ slot |
| Ensure path đang online | Không replace path, không thay source ID |
| Control API lỗi auth/5xx/JSON sai | 503, không tự retry hoặc trả thành công giả |

Reconnect/LIVE/End HTTP/scheduler và player không nằm trong nghiệm thu Giai đoạn 3.
