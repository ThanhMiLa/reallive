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

---

# Giai đoạn 1 — MVP đồng bộ: Kết quả nghiệm thu mới (2026-10-25)

**M1 đạt cho nền tảng domain/database:** đã thực thi STR-101–107 theo [plan mới](../Implement%20Plan/Streaming%20MVP%20Phase%201%20Implementation%20Plan.md). Toàn bộ `stream-service` có **77 tests, 0 failures, 0 errors, 0 skipped**; clean verify và Spring Boot repackage thành công.

```sh
./stream-service/mvnw -f stream-service/pom.xml clean verify
```

Môi trường kiểm chứng: Java 21, PostgreSQL 17 qua Testcontainers (`postgres:17-alpine`). Các suite PostgreSQL dùng schema riêng; cleanup chỉ DROP schema `stream_it_<uuid>` do test tạo và không bỏ qua lỗi cleanup. Không dùng database ứng dụng để nghiệm thu lần này.

| Suite | Kết quả | Bằng chứng hành vi |
|---|---|---|
| StreamMigrationPostgresTest | 17/17 PASS | V1 → V3 từ schema sạch và schema cũ; giữ session/history, bỏ bảng task, constraint và unique slot; chặn upgrade khi còn PENDING_* chưa xác minh |
| StreamRepositoryPostgresTest | 5/5 PASS | Hibernate validate, UUID/enum/source round-trip, slot/current/deadline/list và history theo thời gian Clock |
| StreamStateMachineServiceTest | 12/12 PASS | READY/OPEN, LIVE, reconnect trước/đúng/sau deadline, first startedAt, duplicate/stale source/event, ENDED/CLOSED terminal |
| StreamConcurrencyAndTransactionPostgresTest | 11/11 PASS | Request đồng thời khác/cùng ID, conflict khác channel, rollback session/history/End, bắt buộc transaction End/expire, deadline reload, gỡ task runtime và ONLINE chờ khóa End |
| Các suite common/security/context giữ lại | 32/32 PASS | Auth state, Redis authorities, exception mapping và application context |

## Schema và code đã thay đổi

- Giữ V1/V2 nguyên trạng, thêm `V3__remove_stream_session_version.sql` bỏ cột version. Schema cuối có hai bảng session/history, không cột version hoặc optimistic locking; Flyway vẫn giữ trường version riêng để quản lý migration.
- Test upgrade từ V2 với version=7 xác nhận V3 bỏ cột mà giữ session/history; test DB sạch chạy V1–V3 và migrate lần hai không thay đổi gì.
- V2 từ chối khi còn PENDING_OPEN/PENDING_CLOSE để không giả định MediaMTX đã mở/đóng; hai test âm tính xác nhận migration rollback và dữ liệu/task cũ còn nguyên. Các log migration failed trong hai case này là kết quả mong đợi, không phải suite thất bại.
- Runtime chỉ dùng OPEN/CLOSED. Slot/current chỉ dựa trên READY/LIVE/RECONNECTING. Bỏ năm class entity/repository/service/implementation/DTO task và cấu hình media-sync lease/retry.
- History nhận createdAt từ Clock của domain; cùng transaction với session.
- Create dùng transaction REQUIRED qua TransactionTemplate: khi tự sở hữu transaction, unique conflict được xử lý bằng đọc lại sau rollback; khi tham gia transaction của caller, lỗi được đẩy ra để caller rollback trước khi xử lý.
- End/expire dùng MANDATORY để yêu cầu transaction của use case; caller phải giữ khóa và xác nhận đóng media trước khi gọi mutation domain. ONLINE quá hạn không tự ghi CLOSED.

## Kiểm chứng tranh chấp và rollback

- Test End giữ PESSIMISTIC_WRITE chưa commit và callback ONLINE đồng thời: quan sát callback chờ row lock qua pg_stat_activity, chỉ xử lý sau commit và giữ ENDED/CLOSED, history không lặp.
- Test cùng request ID giữ request đầu chưa commit, quan sát request sau đang chờ unique index qua `pg_stat_activity`, rồi mới cho commit. Kết quả là cùng session, giữ cả title/categoryId và chỉ một history READY; đây là kiểm thử đồng thời thực tế.
- Khác request ID cùng channel: một session thắng, request còn lại nhận STREAM_SESSION_ALREADY_ACTIVE. Cùng owner/request ID nhưng khác channel: nhận STREAM_CREATION_REQUEST_CONFLICT, không tạo session ở channel thua.
- Lỗi CHECK title không bị đổi thành lỗi slot. Chủ động gây lỗi sau reservation hoặc trong history khi End chứng minh session/history/slot rollback cùng nhau.
- Sau commit End domain với tiền điều kiện đóng media đã được caller đáp ứng, session ENDED/CLOSED và slot được giải phóng; End lặp/callback muộn không tạo thêm transition.
- Deadline được đọc lại từ PostgreSQL, offline lặp không gia hạn; ONLINE tại deadline không hồi sinh hoặc giả báo đã đóng path. Kiểm thử Clock không đợi 60 giây thực.

## Giới hạn bằng chứng

- Đây là nghiệm thu Giai đoạn 1: chưa implement adapter HTTP MediaMTX, API Create/End thật hoặc worker deadline của giai đoạn 3–4. Test domain không chứng minh media path thật đã mở/đóng.
- Migration đã chạy trong database/schema test cô lập; chưa áp dụng V2/V3 vào database ứng dụng đang chạy. Khi nâng DB cũ có PENDING_*, cần xác minh media và xử lý row đó trước khi migrate.
- Không có task queue/lease/retry worker/reconciler trong runtime. Các phần auth/JWKS/eligibility và workflow media thật vẫn theo backlog giai đoạn 2–7.

---

# Lưu trữ — Báo cáo Giai đoạn 1 theo thiết kế task cũ (2026-10-25)


> Lưu trữ báo cáo trước thay đổi phạm vi. Các kết quả sau đây thuộc schema ba bảng/code task đã được thay thế; không dùng để mô tả implementation hiện tại. Kết quả nghiệm thu mới nằm ở phần ngay phía trên.

**Kết quả đã báo cáo trước review/thay đổi phạm vi:** 53/53 test PASS trên PostgreSQL 17/Testcontainers; nhãn M1 cũ không còn là nghiệm thu cho thiết kế đồng bộ hiện tại.

## STR-101 — Flyway Migration và Data Model: PASS

```sh
./stream-service/mvnw -f stream-service/pom.xml test -Dtest=StreamMigrationPostgresTest
```

- **Database:** PostgreSQL 17 (container `postgres:17-alpine`).
- **Migration script:** `V1__create_stream_sessions.sql` (schema: `stream_sessions`, `stream_status_history`, `stream_media_sync_tasks`).
- **Kết quả kiểm chứng (`StreamMigrationPostgresTest` - 3/3 test pass):**
  - PASS: Migration chạy trơn tru từ schema sạch, tạo đúng primary key, unique constraints và check constraints.
  - PASS: Partial unique index `uq_stream_sessions_channel_slot` trên `channel_id` ngăn chặn hai session hoạt động đồng thời (trạng thái `READY`, `LIVE`, `RECONNECTING` hoặc `media_path_state` thuộc `PENDING_OPEN`, `OPEN`, `PENDING_CLOSE`), trong khi cho phép nhiều session `ENDED` với media path `CLOSED`.
  - PASS: CHECK constraints hoạt động chuẩn xác: từ chối title rỗng/khoảng trắng, từ chối visibility không hợp lệ, yêu cầu `disconnected_at` + `reconnect_deadline_at` khi `RECONNECTING`, bắt buộc `started_at` và media source khi `LIVE`, bắt buộc `ended_at` khi `ENDED`.
  - PASS: Cập nhật tài liệu data model khớp 100% với Flyway DDL.

## STR-102 — Entity, Enum và Repository Nền Tảng: PASS

```sh
./stream-service/mvnw -f stream-service/pom.xml test -Dtest=StreamRepositoryPostgresTest
```

- **Kết quả kiểm chứng (`StreamRepositoryPostgresTest` - 5/5 test pass):**
  - PASS: `StreamSessionEntity`, `StreamStatusHistoryEntity`, `StreamMediaSyncTaskEntity` pass kiểm tra `spring.jpa.hibernate.ddl-auto=validate` trên PostgreSQL 17 schema thật.
  - PASS: Roundtrip persistence bảo toàn chính xác các enum `StreamVisibility`, `StreamSessionStatus`, `MediaPathState`, `MediaSourceType`.
  - PASS: Unique constraint `uk_stream_sessions_owner_creation_request` trên `(owner_user_id, creation_request_id)` ngăn trùng lặp request ID của cùng owner.
  - PASS: Query pessimistic write lock `findSlotOccupyingByChannelIdForUpdate` phát hiện chính xác session đang chiếm slot.
  - PASS: Repository `StreamStatusHistoryRepository` lưu và truy vấn theo thứ tự thời gian tăng dần (`createdAt`, `id`).
  - PASS: Repository `StreamMediaSyncTaskRepository` claim task đến hạn với JPQL pessimistic write lock kết hợp lock timeout `-2` (`FOR UPDATE SKIP LOCKED`).

## STR-103 — Vòng Đời và State Machine: PASS

```sh
./stream-service/mvnw -f stream-service/pom.xml test -Dtest=StreamStateMachineServiceTest
```

- **Clock:** Tiêm `java.time.Clock` mô phỏng bước nhảy thời gian chính xác (không dùng `Thread.sleep`).
- **Kết quả kiểm chứng (`StreamStateMachineServiceTest` - 9/9 test pass):**
  - PASS: Khởi tạo session ở trạng thái `READY` và `PENDING_OPEN`.
  - PASS: Media online lần đầu chuyển session sang `LIVE`, gán `mediaSourceId`, `mediaSourceType` và `startedAt`.
  - PASS: Media online lặp cùng source ID là idempotent no-op; lặp `mediaEventId` bị bỏ qua.
  - PASS: Media offline chuyển session sang `RECONNECTING`, thiết lập hạn chót grace period 60 giây (`reconnectDeadlineAt = now + 60s`).
  - PASS: Media online trở lại trong hạn 60s chuyển về `LIVE`, giữ nguyên `startedAt` ban đầu, cập nhật `mediaSourceId` mới và xóa `reconnectDeadlineAt`.
  - PASS: Khi grace period 60s hết hạn, `onGracePeriodExpired` chuyển session sang `ENDED`, gán `endedAt` và đặt `mediaPathState = PENDING_CLOSE`.
  - PASS: Grace period chưa hết hạn từ chối kết thúc sớm.
  - PASS: `endSession` chủ động kết thúc session thành `ENDED` và `PENDING_CLOSE`.
  - PASS: Mọi sự kiện media sau khi session đã `ENDED` đều bị bỏ qua (terminal immutability).

## STR-104 & STR-105 & STR-106 & STR-107 — Concurrency, Sync Task và PostgreSQL Integration: PASS

```sh
./stream-service/mvnw -f stream-service/pom.xml test -Dtest=StreamConcurrencyAndSyncPostgresTest
./stream-service/mvnw -f stream-service/pom.xml clean verify
```

- **Môi trường:** Testcontainers PostgreSQL 17-alpine, cấp phát dynamic isolated schema `stream_it_<uuid>` cho mỗi test run và dọn dẹp bằng `DROP SCHEMA ... CASCADE`.
- **Kết quả kiểm chứng (`StreamConcurrencyAndSyncPostgresTest` - 4/4 test pass):**
  - PASS: Concurrency create với 2 thread cùng channel và khác request ID: đúng 1 thread tạo thành công (201), thread còn lại bị từ chối với `STREAM_SESSION_ALREADY_ACTIVE` (409) do partial unique index `uq_stream_sessions_channel_slot`.
  - PASS: Idempotency create với cùng `creationRequestId`: request thứ hai trả về session đã tạo trước đó (`created = false`), không sửa đổi metadata và không tạo thêm history record.
  - PASS: Session ở trạng thái `ENDED` nhưng `mediaPathState = PENDING_CLOSE` tiếp tục chặn tạo session mới trên channel cho tới khi media sync hoàn tất và chuyển sang `CLOSED`.
  - PASS: Worker hoàn thành task mở media cũ bị từ chối (`rejected`) khi session đã chuyển sang `ENDED` trước khi worker báo cáo (monotonic revision bump và optimistic lease check).
- **Tổng kết kiểm thử toàn dịch vụ `stream-service`:**
  - 53 tests run, 0 failures, 0 errors, 0 skipped.
  - Build jar và repackage Spring Boot hoàn tất thành công (`stream-service-0.0.1-SNAPSHOT.jar`).

---

# Giai đoạn 2 — Auth, eligibility và JWT media (2026-10-26)

**M2 đạt, STR-201–208 hoàn tất.** [Plan chi tiết](../Implement%20Plan/Streaming%20MVP%20Phase%202%20Implementation%20Plan.md).

## Build và regression

Chạy tại repository root:

```sh
./stream-service/mvnw -f stream-service/pom.xml clean verify
./user-service/mvnw -f user-service/pom.xml clean verify
./channel-service/mvnw -f channel-service/pom.xml clean verify
./api-gateway/mvnw -f api-gateway/pom.xml clean verify
python3 -m unittest discover -s scripts/testing/streaming -p 'test_*.py'
```

| Bộ kiểm thử | Tổng số | Đạt | Bỏ qua | Failure/error |
|---|---:|---:|---:|---:|
| Stream Service | 103 | 103 | 0 | 0/0 |
| User Service | 722 | 686 | 36 | 0/0 |
| Channel Service | 492 | 492 | 0 | 0/0 |
| API Gateway | 84 | 83 | 1 | 0/0 |
| Fixture/helper Python | 16 | 16 | 0 | 0/0 |

Bốn lệnh Maven đều BUILD SUCCESS và đóng gói JAR. 37 test bị bỏ qua là các kiểm chứng Redis tùy chọn hiện hữu, cần các system property Redis tương ứng; không tính chúng là PASS. Các test mới của giai đoạn 2 đều đã chạy. Stream vẫn chạy đầy đủ migration/persistence/concurrency trên PostgreSQL 17 Testcontainers với database/schema cô lập.

## STR-201–203 — Contract và eligibility: PASS

- DTO Create/PATCH/pagination: trim title, null/blank/max length, phân biệt field vắng với explicit null, chặn payload rỗng/field lạ/cờ nội bộ.
- Metadata không chứa credential; thời gian mapper UTC; toString credential che token.
- User controller/service/repository thật: verified + ACTIVE streamer hợp lệ; thiếu user/streamer, email chưa verified và trạng thái streamer khác bị từ chối. Endpoint `/active` cũ giữ hành vi riêng, không vô tình bị thêm điều kiện email.
- Channel controller/service/repository thật: context đúng identity/owner; từ chối inactive/deleted/missing/ownerless. Batch summary giới hạn 100 và bỏ channel không được công bố.
- Feign gọi HTTP server cô lập: resolve đúng user → streamer → channel, kiểm tra owner và category; 403/404 nghiệp vụ khác với upstream 401/500/response sai; timeout không retry, không gọi Channel khi User thất bại; summary chỉ một batch.
- Test producer dùng H2; test Feign dùng HTTP stub. Chưa chạy deployment đầy đủ cả User và Channel qua mạng production.

## STR-204–207 — Khóa, token, JWKS và callback: PASS

- Nạp cặp RSA ổn định, verify cặp khóa; thiếu/sai cấu hình thất bại với thông báo không chứa key. Code yêu cầu RSA ít nhất 2048 bit, TTL dương, media URL đúng protocol. Cho MVP local, MediaMTX gọi trực tiếp Stream Service nên bỏ yêu cầu callback secret và HMAC.
- JWT RS256 đúng kid/iss/aud/sub/iat/exp/jti, exact path/action; TTL Publish 8h, Read 5m, Control API 5m theo quyết định mới. Không lưu token vào DB.
- JWKS chỉ public key, JSON không bọc ApiResponse; được Stream Service phục vụ trực tiếp (`permitAll` trên cổng 8082). Gateway không mở route `/stream/api/media/**`.
- Cookie gửi kèm route public vẫn theo Auth State nếu gửi cookie; Redis unavailable giữ 503, không bỏ qua cookie lỗi.
- Callback nhận trực tiếp DTO JSON từ MediaMTX trên `POST /stream/api/media/events` (permitAll, không yêu cầu account cookie). DTO sai format trả 400.
- Callback hợp lệ tới 404 vì chưa có handler nghiệp vụ (không trả 200 giả).
- Hướng dẫn chuyển PEM thành Base64 DER đã được kiểm tra lại trong thư mục tạm, không đổi key ứng dụng.

## STR-208 — Stream HTTP + MediaMTX thật: PASS

Chạy sau khi có local image MediaMTX:

```sh
docker build -f infra/mediamtx/Dockerfile -t reallive-mediamtx:1.21.2 .
./stream-service/mvnw -f stream-service/pom.xml -Dtest=MediaMtxAuthenticationIT -Dstream.media.it=true test
```

`MediaMtxAuthenticationIT`: **1 test PASS, không failure/error/skipped**. Đây là lệnh opt-in riêng, không cộng vào 97 test mặc định.

- Stream Service chạy HTTP thật, H2 cô lập và key sinh trong bộ nhớ; một container MediaMTX 1.21.2 dùng cổng ngẫu nhiên.
- MediaMTX tải public JWKS trực tiếp từ Stream Service, chấp nhận JWT do `MediaJwtIssuer` Java ký. Control API JWT gọi API/provision path thành công; Publish/Read JWT không được gọi Control API.
- FFmpeg H.264/AAC publish RTMP thành công với Publish JWT; nguồn thực sự online. Read JWT lấy HLS playlist có `#EXTM3U` thành công.
- HLS từ chối thiếu token, sai action/path/signature/issuer/audience và token hết hạn. Read JWT không publish RTMP được.
- Gateway không mở route media; MediaMTX gọi trực tiếp Stream Service. Callback JSON hợp lệ không cần account cookie trả 404 đúng phạm vi hiện tại (chưa có handler nghiệp vụ).
- Hook nguồn trong container test này được tắt để không gọi lifecycle handler chưa tồn tại; callback được gửi bằng HTTP test riêng. Không coi đây là nghiệm thu workflow ONLINE/OFFLINE → DB.
- Dọn path, FFmpeg và container test; không đổi container MediaMTX, OBS, browser hoặc database ứng dụng đang dùng.

## Giới hạn và bước tiếp theo

- Giai đoạn 2 cung cấp thành phần auth/eligibility, chưa có API nghiệp vụ Create/Update/cấp OBS/End/cấp quyền xem. Không có controller giả luôn báo thành công.
- No-store sẽ được nối ở controller cấp credential giai đoạn 3/5; chưa nghiệm thu header của các API chưa tồn tại.
- Test media chứng minh publish và HLS playlist; không thay nghiệm thu OBS/browser HLS đầy đủ ở giai đoạn 0/7, không nghiệm thu TLS/production hoặc xử lý lifecycle callback.
- Stream Service từ nay cần cấu hình RSA key hợp lệ khi startup. Xem [README](../../stream-service/README.md); không tự sinh lại key khi restart.
- Không đổi schema hai bảng, không thêm version/sync task/background retry/reconciler. STR-301 tiếp theo xây Control API adapter cho runtime ứng dụng.

# Giai đoạn 3 — Media adapter và provisioning (2026-10-26)

**STR-301–307 đạt; STR-308 đã có code/test tự động/smoke script, còn chờ người dùng tự nghiệm thu OBS. Chưa đánh dấu M3 đạt.** [Implementation](../Implement%20Plan/Streaming%20MVP%20Phase%203%20Implementation%20Plan.md), [Phase 3 Test Plan](Streaming%20MVP%20Phase%203%20Test%20Plan.md).

## Build và kiểm chứng tự động

Thực thi trên Java 21.0.11, Docker Desktop và PostgreSQL 17 Testcontainers; dùng schema/container test riêng, không migrate DB ứng dụng.

```sh
./stream-service/mvnw -f stream-service/pom.xml clean verify
./stream-service/mvnw -f stream-service/pom.xml -Dtest=StreamProvisioningMediaIT -Dstream.provisioning.it=true test
python3 -m unittest discover -s scripts/testing/streaming -p 'test_*.py' -v
docker compose config --quiet
docker compose --project-directory . -f scripts/testing/streaming/compose.fixture.yml config --quiet
```

| Kiểm chứng | Kết quả | Phạm vi được chứng minh |
|---|---|---|
| Stream Service clean verify | **125 test, 0 failure/error/skipped; BUILD SUCCESS** | Toàn bộ regression domain/database/auth cùng suite mới; package/repackage thành công |
| MediaMtxAdapterTest | **9/9 PASS** | Exact path, ensure không replace, race add idempotent, source/config, pagination, api JWT, RTMP/RTMPS connection ID, auth/error/404/timeout/no retry |
| StreamSessionControllerTest | **6/6 PASS** | Authenticated owner, create 201/replay 200, response envelope, DTO/PATCH validation, current null, no-store và error mapping |
| StreamProvisioningPostgresTest | **14/14 PASS** | Reserve/provision/commit, response chờ I/O, rollback session/history/slot, concurrent request ID/slot, metadata/reissue/current, grace boundary, closure idempotent/rollback, End cạnh tranh Create/credential, kick actual source |
| StreamProvisioningMediaIT | **3 PASS, 1 OBS opt-in skipped; BUILD SUCCESS** | Stream HTTP + PostgreSQL + MediaMTX thật, Java JWT/Control API, FFmpeg publish, update giữ source, cấp lại credential, exact path/list, close media trước ENDED, missing-path không restore, Create timeout/rollback/retry với media test bị pause; Python smoke gọi API thật |
| Python fixture/helper regression | **15/15 PASS** | Không làm hỏng fixture JWT/callback hiện có; callback retry hữu hạn cũ còn được điều chỉnh ở STR-401 |
| Root Compose và fixture Compose | **Cả hai config --quiet exit 0** | Config parse được sau khi bỏ dependency callback env file |

9 + 6 + 14 là suite Giai đoạn 3 mới trong tổng 125, không cộng lần nữa vào tổng. Integration opt-in là lượt riêng; skip duy nhất trong lượt đó là test OBS chưa được người dùng chạy.

## STR-301–307 — Bằng chứng hành vi

- Adapter gọi HTTP thật đến server contract test; backend Control API JWT chỉ có quyền api. Endpoint/path được tạo từ UUID, không nhận URL/path tùy ý. API auth lỗi, 5xx, JSON sai và timeout map sang 503 với exception không chứa upstream body/cause/token.
- Ensure existing publisher path chỉ GET, không replace/add. Race add kiểm tra lại path; incompatible config bị từ chối. Actual runtime ensure trong lúc FFmpeg phát giữ nguyên source connection ID.
- PostgreSQL test giữ media I/O bằng latch: response chưa hoàn tất và query từ connection khác chưa thấy session/history cho tới khi cho phép commit. Media failure rollback cả slot/session/history, sau đó retry cùng request ID được.
- HTTP integration pause đúng MediaMTX container test: Create thật chờ timeout, trả 503/code 1014/result null, DB session/history không đổi và slot trống. Unpause rồi retry cùng request ID trả 201 và mở được path. Không pause hoặc restart MediaMTX ứng dụng.
- Hai Create đồng thời cùng request ID chỉ có một session/history/provision; request thua đọc lại sau transaction rollback. Khác request ID cùng channel nhận 409.
- User/Channel checks được thực hiện trước transaction ghi. PATCH giữ các field thiếu và playback path; category failure không lưu title một phần. Unauthorized owner không gọi media/cấp token.
- Credential kiểm tra path hiện có, status và deadline dưới khóa. Tại deadline reconnect, current vẫn trả session đang chiếm slot nhưng publishReady=false, không cấp thêm token.
- End foundation giữ khóa trong lúc xóa/kiểm tra nguồn; lỗi giữ DB READY/slot/history cũ. Create chờ End commit mới lấy slot; credential chờ End rồi recheck ENDED và bị chặn. Kick sử dụng actual RTMPS connection ID trong contract test, không dùng session ID.
- MediaMTX test lấy JWKS trực tiếp từ Stream Service. Create HTTP trả no-store và credential phát được nguồn H.264/AAC tổng hợp. Metadata PATCH không đổi nguồn; nguồn online vẫn giữ DB READY đúng giới hạn Giai đoạn 3. Xóa path xác nhận trước ENDED/CLOSED; session ENDED không được cấp credential hoặc mở lại.
- Python owner smoke gọi Create/replay/current/PATCH/publish-credentials trên HTTP Stream thật, kiểm tra response/status/no-store, lưu credential riêng và không in JWT/account cookie. Temp cookie/state/credential được dọn sau test.
- Compose reuse một image MediaMTX 1.21.2, direct JWKS/callback; không route media hoặc Control API qua Gateway. Local callback không HMAC, root Compose không còn đòi env file callback secret.

## Giới hạn nghiệm thu

- Theo yêu cầu người dùng, **không mở/điều khiển OBS**. OBS opt-in chưa chạy, không coi FFmpeg là bằng chứng OBS. [Test plan](Streaming%20MVP%20Phase%203%20Test%20Plan.md) có lệnh/helper chỉ chờ người dùng tự cấu hình và phát, kèm report PASS/FAIL không JWT.
- Integration test dùng Redis authorities và eligibility/channel stub riêng; không tuyên bố đã nghiệm thu stack account/Gateway/User/Channel thực tế của ứng dụng trong lượt Giai đoạn 3 này. Client/auth integration Giai đoạn 2 vẫn có regression. Test plan có smoke cho stack thật.
- RTMPS endpoint/ID được test theo contract; TLS/RTMPS thật chưa chạy, không ghi runtime pass.
- End foundation chỉ là service dùng bởi luồng đồng bộ và test cleanup. Chưa có End HTTP, callback lifecycle hoặc scheduler; callback hợp lệ vẫn 404, chưa chuyển READY → LIVE.
- DB/media không atomic khi timeout/crash/DB commit failure. Không task, worker, tự retry, restore path hoặc reconciler. Credential issuance không khôi phục path bị mất.
- Không thay schema/migration hay DB ứng dụng; không chỉnh OBS, player hoặc dashboard. Bản MVP vẫn chờ Giai đoạn 4–7.
