# Streaming MVP — Phân chia task triển khai

- Ngày lập: 2026-10-24.
- Cập nhật 2026-10-25: MVP xử lý đồng bộ từ đầu đến cuối theo yêu cầu người dùng. Không có bảng task, worker retry, lease/revision hoặc reconciler.
- Trạng thái: M0 và M1 đã đạt. Giai đoạn 1 đã điều chỉnh theo schema hai bảng và nghiệm thu 77/77 test ngày 2026-10-25; giai đoạn 2–7 chưa hoàn tất. HTTP MediaMTX thật được tích hợp ở giai đoạn 3–4.
- Thiết kế và contract gốc: [Streaming MVP Implementation Plan](Streaming%20MVP%20Implementation%20Plan.md).
- Kiến trúc đã chốt: [Streaming MVP Architecture and Flow](../architecture/Streaming%20MVP%20Architecture%20and%20Flow.md).
- Mục tiêu: triển khai theo từng phần có thể kiểm chứng, đến khi streamer phát được bằng OBS và người dùng đăng nhập xem được trên web.

## 1. Cách sử dụng backlog

- `STR-0xx` đến `STR-7xx` tương ứng giai đoạn 0–7 trong implementation plan. Mã task giữ ổn định để có thể giao việc hoặc yêu cầu thực hiện một task cụ thể.
- Thực hiện theo thứ tự giai đoạn và các task phụ thuộc. Một task chỉ hoàn tất khi đầu ra và kiểm chứng ghi trong task đều đạt.
- Các đường dẫn/class mới là vị trí dự kiến; kiểm tra repository thực tế trước khi tạo, tái sử dụng phần đã có thay vì tạo lại.
- Business API phải tuân thủ ownership/account auth hiện tại. MediaMTX vẫn chỉ có một instance, tự verify Publish JWT và Read JWT bằng public key.
- Giữ nguyên phạm vi MVP và phân biệt các đề xuất mặc định ở mục 1.2 của implementation plan với quyết định đã chốt. TTL token, trình duyệt hỗ trợ và chính sách cấp lại key không được tự ghi thành yêu cầu đã xác nhận.
- Ghi bằng chứng kiểm chứng ngắn theo task: lệnh/kết quả test hoặc kịch bản thực tế. Không tick hoàn tất chỉ vì đã viết code hoặc đã viết tài liệu kiểm thử.
- Task dựng fixture ở giai đoạn 0 là kiểm chứng kỹ thuật. JWKS/callback qua Gateway và backend thật được nghiệm thu tiếp ở giai đoạn 2–4.
- Request gọi MediaMTX trực tiếp và chờ kết quả; thành công mới commit/response, lỗi trả 503 và rollback DB của thao tác. Không trả 202 hoặc giao job xử lý sau. Giữ scheduler hết hạn reconnect theo business.
- Mã 58 task được giữ ổn định; STR-105, STR-302 và STR-406 được đổi nội dung để khớp phạm vi đồng bộ. Các checkbox giai đoạn 1 đã được nghiệm thu lại theo kết quả mới, không dùng kết quả 53 test của thiết kế cũ.

### 1.1. Quy tắc hoàn tất chung

1. Đã xử lý các điều kiện lỗi thuộc phạm vi task, không chỉ happy case.
2. Đúng cấu trúc package, `AppException(ErrorCode)` và English Javadoc cho service interface theo skill của repository.
3. Các test liên quan có ý nghĩa đã chạy; không làm hỏng build hoặc behavior hiện có của module bị sửa.
4. Không đưa token/private key/callback secret vào Git, log, response metadata hoặc frontend persistent storage.
5. Cập nhật contract/tài liệu nếu kết quả triển khai thay đổi nội dung đã mô tả; ghi rõ các việc còn chưa kiểm chứng.

### 1.2. Tổng quan các giai đoạn

| Giai đoạn | Task | Điều kiện bắt đầu | Đầu ra chính |
|---|---|---|---|
| 0. Kiểm chứng nền tảng | STR-001–STR-006 | Đọc kiến trúc/implementation plan | Chứng minh một MediaMTX hoạt động với JWT, OBS, HLS và đóng path |
| 1. Domain/database | STR-101–STR-107 | Hoàn tất giai đoạn 0 | Schema, persistence, state machine và ràng buộc concurrency |
| 2. Auth và tích hợp service | STR-201–STR-208 | Hoàn tất giai đoạn 1 | Eligibility, ownership context, ký JWT, JWKS và security callback |
| 3. Media adapter/provisioning | STR-301–STR-308 | Hoàn tất giai đoạn 2 | API tạo/cập nhật session và cấp thông tin OBS qua backend thật |
| 4. Lifecycle đồng bộ | STR-401–STR-408 | Hoàn tất giai đoạn 3 | LIVE/reconnect/End, callback và lỗi trực tiếp; giới hạn vận hành thủ công |
| 5. Playback API/discovery | STR-501–STR-505 | Hoàn tất giai đoạn 4 | Metadata/list và cấp quyền xem dùng được với HLS thật |
| 6. Frontend | STR-601–STR-610 | Hoàn tất giai đoạn 5 | Dashboard và trang xem kết nối đầy đủ API thật |
| 7. Nghiệm thu/bàn giao | STR-701–STR-706 | Hoàn tất giai đoạn 6 | Bằng chứng MVP hoạt động, runbook và các giới hạn rõ ràng |

## 2. Giai đoạn 0 — Kiểm chứng nền tảng

Mục tiêu: xác nhận cách tích hợp media trước khi xây toàn bộ nghiệp vụ. Fixture chỉ phục vụ kiểm thử và được thay bằng backend thật ở các giai đoạn sau.

### STR-001 — Đối chiếu phạm vi, contract và các mặc định

- **Phụ thuộc:** Không.
- **Module/file:** Implementation plan, architecture flow và data model.
- **Cần làm:** Rà lại một MediaMTX/JWT cho publish và read; bốn trạng thái; grace 60 giây; một slot/channel; PUBLIC + login. Lập danh sách cấu hình mặc định đề xuất và phần ngoài MVP, thống nhất tên request/response dùng tiếp.
- **Hoàn tất khi:** Có bảng quyết định và contract làm nguồn chung; không còn bước verify stream key bằng hash hoặc thiết kế hai MediaMTX trong phạm vi triển khai.
- [x] STR-001 hoàn tất, có ghi nhận kết quả rà soát.
- **Kết quả 2026-10-24:** [Bảng quyết định, contract, mặc định và đối chiếu code](Streaming%20MVP%20Phase%200%20Review.md). Đã làm rõ create/list/PATCH/pagination và header callback trong implementation plan; chưa có kết quả runtime streaming.

### STR-002 — Chuẩn bị key và fixture JWT/JWKS/callback

- **Phụ thuộc:** STR-001.
- **Module/file:** `scripts/dev/generate-stream-media-keys.sh`, `scripts/testing/streaming/`, ignore/env example cần thiết.
- **Cần làm:** Chuẩn bị cặp RSA test ổn định, token publish/read/api với quyền/path khác nhau, JWKS fixture và receiver ghi nhận callback. Đặt địa chỉ/secret fixture riêng, không dùng fixture làm auth production.
- **Hoàn tất khi:** Kiểm tra được chữ ký/claim của token và JWKS chỉ có public parameters; chạy lại fixture không âm thầm đổi bộ khóa, không in private key vào output.
- [x] STR-002 hoàn tất, có hướng dẫn chạy fixture.
- **Kết quả 2026-10-24:** [Hướng dẫn fixture](../../scripts/testing/streaming/README.md), [kết quả kiểm chứng](../testing/Streaming%20MVP%20Tests.md). Bộ test chữ ký/quyền/JWKS/callback chạy đạt; key và secret giữ ổn định, artifact nhạy cảm được ignore.

### STR-003 — Dựng một MediaMTX container và kiểm tra mạng local

- **Phụ thuộc:** STR-002.
- **Module/file:** `infra/mediamtx/Dockerfile`, `infra/mediamtx/mediamtx.yml`, Docker Compose và fixture config.
- **Cần làm:** Pin MediaMTX 1.21.2; custom image có shell/Python helper; bật RTMP/HLS/Control API, JWT auth, không catch-all path. Map HLS host 8898, giới hạn Control API và kiểm tra container truy cập được fixture trên host.
- **Hoàn tất khi:** Một container khởi động được, không trùng Gateway 8888, đọc JWKS được và Control API chấp nhận đúng token api. Có ghi lại cấu hình mạng macOS/Linux cần dùng.
- [x] STR-003 hoàn tất, có kết quả startup/network check.
- **Kết quả 2026-10-24:** Một container healthy với binary `v1.21.2`; JWKS từ host truy cập được; Control API nhận token api và từ chối token publish/read. HLS host 8898, RTMP 1935 và Control API 9997 chỉ bind loopback. [Cấu hình/hướng dẫn](../../infra/mediamtx/README.md), [bằng chứng](../testing/Streaming%20MVP%20Tests.md).

### STR-004 — Kiểm chứng OBS phát bằng Publish JWT

- **Phụ thuộc:** STR-003.
- **Module/file:** Cấu hình MediaMTX, script fixture/token và hướng dẫn OBS test.
- **Cần làm:** Tạo exact path test; nhập server URL/stream key chứa JWT vào OBS; phát H.264/AAC. Thử token sai chữ ký, sai path, quyền read và token hết hạn.
- **Hoàn tất khi:** Token hợp lệ phát được; token không hợp lệ bị từ chối; có thể phân biệt RTMP chấp nhận publish với thời điểm nguồn thực sự online.
- [x] STR-004 hoàn tất, có bằng chứng OBS và kết quả auth âm tính.
- **Kết quả 2026-10-24:** Người dùng đã phát bằng OBS và xác nhận bằng ảnh browser có video; MediaMTX ghi source `rtmpConn`, tracks H264/MPEG-4 Audio, callback ONLINE khớp source. Các case publish sai chữ ký/path/quyền/hết hạn/thiếu token đã bị từ chối trong smoke RTMP. [Bằng chứng và phân biệt nguồn kiểm thử](../testing/Streaming%20MVP%20Tests.md).

### STR-005 — Kiểm chứng HLS với Read JWT trên browser

- **Phụ thuộc:** STR-004.
- **Module/file:** Player fixture trong `scripts/testing/streaming/`, hls.js của web và cấu hình CORS MediaMTX.
- **Cần làm:** Mở playlist bằng Authorization Bearer; phát hình/tiếng, kiểm tra request playlist/segment và CORS. Thử thiếu token/sai quyền/sai path khi mở phiên xem mới.
- **Hoàn tất khi:** Browser thuộc phạm vi MVP xem được media; playlist/segment đi trực tiếp tới MediaMTX; không có đường phát công khai được thêm vào để lách lỗi auth.
- [x] STR-005 hoàn tất, có kết quả browser và Network check.
- **Kết quả 2026-10-24:** Chrome phát được hình/tiếng, có bản ghi frame/playlist/fragment và xác nhận audio. Người dùng xác nhận hoàn thành toàn bộ các bước trên fixture, gồm case thiếu token/sai quyền/sai path và Network check; ba case browser âm tính không có bản lưu HTTP riêng, được ghi rõ là xác nhận thủ công. Smoke HTTP kiểm chứng độc lập tất cả case auth âm tính. [Bằng chứng và giới hạn](../testing/Streaming%20MVP%20Tests.md).

### STR-006 — Kiểm chứng đóng path, nguồn online/offline và khả năng reconnect

- **Phụ thuộc:** STR-004, STR-005.
- **Module/file:** MediaMTX fixture, Control API script và ghi nhận kết quả giai đoạn 0.
- **Cần làm:** Thu thập path/source ID/type từ hook; thử OBS ngắt/nối lại. Xóa cấu hình path trong lúc phát, ngắt nguồn nếu cần rồi thử lại Publish JWT cũ vẫn còn hạn.
- **Hoàn tất khi:** Biết chính xác endpoint, field và thứ tự cần dùng; nguồn dừng và JWT cũ không mở lại được path đã xóa. Kết quả auth/playlist/hook/path được ghi lại với version đã pin.
- [x] STR-006 hoàn tất, đủ bằng chứng kết thúc giai đoạn 0.
- **Kết quả 2026-10-24:** OBS tạo các source ID khác nhau sau reconnect, ONLINE/OFFLINE khớp nguồn; đã lưu xác nhận reconnect và OBS bị từ chối với key cũ sau đóng path. Đối chiếu thực tế: config path không còn và source=null. Smoke riêng đã verify JWT cũ vẫn còn hạn trước khi thử lại và bị từ chối. [Bằng chứng](../testing/Streaming%20MVP%20Tests.md).

**Mốc nghiệm thu giai đoạn 0:** Một container đã được kiểm chứng với OBS + JWT + HLS + đóng path. Fixture không được nhầm thành implementation nghiệp vụ hoàn chỉnh.

**M0 đạt ngày 2026-10-24:** 12/12 kiểm chứng giao thức, 16/16 test fixture/helper, OBS/browser do người dùng thao tác và xác nhận; chi tiết nguồn bằng chứng ở tài liệu kiểm thử. Ghi nhận tại thời điểm M0: task tiếp theo khi đó là STR-101. Tiến độ sau đó được cập nhật ở mục giai đoạn 1.

## 3. Giai đoạn 1 — Domain và database

Mục tiêu: có nền tảng persistence và transition đáng tin cậy trước khi tích hợp I/O media vào workflow.

Kế hoạch chi tiết và thứ tự điều chỉnh code đã có: [Streaming MVP Phase 1 Implementation Plan](Streaming%20MVP%20Phase%201%20Implementation%20Plan.md). Data Model hai bảng là schema đích. Giai đoạn này gỡ task và chuẩn bị transaction/domain; việc gọi HTTP MediaMTX thật được nối và nghiệm thu ở giai đoạn 3–4.

### STR-101 — Điều chỉnh migration và data model về hai bảng MVP

- **Phụ thuộc:** STR-006.
- **Module/file:** `stream-service/src/main/resources/db/migration/`, `docs/data-model/Data Model.md`.
- **Cần làm:** Giữ `stream_sessions`, `stream_status_history`; bỏ `stream_media_sync_tasks`. Giữ owner/source/deadline/idempotency và unique slot/path/request; bỏ cột version bằng V3. Media state đích OPEN/CLOSED, constraint/index theo Data Model; không lưu JWT/key/hash. Không sửa migration đã áp dụng; mặc định thêm version tiếp theo nếu chưa rõ lịch sử triển khai. Không tự chuyển PENDING_* chưa xác minh thành OPEN/CLOSED.
- **Hoàn tất khi:** PostgreSQL 17 migrate/validate từ schema sạch và schema cũ có dữ liệu tương thích; schema cuối có hai bảng nghiệp vụ, không mất session/history; một channel tối đa một session active. Dữ liệu pending chưa xử lý khiến migration báo lỗi rõ.
- [x] STR-101 hoàn tất 2026-10-25: V1–V3 migrate/validate trên PostgreSQL 17; schema cuối hai bảng, dữ liệu session/history được giữ; 17/17 test migration PASS, gồm chặn PENDING_* và rollback upgrade.

### STR-102 — Điều chỉnh entity, enum, repository và mapper

- **Phụ thuộc:** STR-101.
- **Module/file:** `stream/entity/`, `entity/enums/`, `repository/`, `mapper/`, `dto/`.
- **Cần làm:** Giữ session/history và enum/source converter; loại entity/repository/DTO task cùng STR-105. MediaPathState dùng OPEN/CLOSED; bỏ JPA @Version và field snapshot tương ứng; giữ PESSIMISTIC_WRITE trong cùng transaction, query slot/current/deadline và UUID/UTC. Slot/current chỉ lọc status active, bỏ predicate pending cleanup cũ.
- **Hoàn tất khi:** JPA validate schema mới; persistence round-trip đúng; không còn bean hoặc query phụ thuộc bảng task.
- [x] STR-102 hoàn tất 2026-10-25: hai entity và repository/mapper validate schema OPEN/CLOSED; slot/current bỏ predicate pending, 5/5 repository test PASS.

### STR-103 — Giữ state machine, tách khỏi task đồng bộ

- **Phụ thuộc:** STR-102.
- **Module/file:** `stream/service/`, `service/impl/`, lifecycle policy và tests.
- **Cần làm:** Giữ READY/LIVE/RECONNECTING/ENDED, source/deadline/first startedAt, terminal ENDED và Clock. Bỏ upsert task khi End/hết hạn; domain tham gia transaction use case, không commit riêng trước media. Chuẩn bị contract End/expire chỉ áp dụng sau xác nhận đóng media; ONLINE quá hạn không tự ghi ENDED/CLOSED chưa được xác nhận. Test mốc đúng deadline.
- **Hoàn tất khi:** Transition đúng, callback lặp không reset thời gian; session quá deadline không nhận credential/ONLINE để hồi sinh; không có task side effect.
- [x] STR-103 hoàn tất 2026-10-25: domain không còn task, End/expire yêu cầu transaction hiện có; Clock và mốc deadline có 12/12 state-machine test PASS.

### STR-104 — Bảo vệ slot channel và concurrent idempotency

- **Phụ thuộc:** STR-103.
- **Module/file:** Session repository, service transaction/locking.
- **Cần làm:** Unique slot và request ID; cùng owner/request trả cùng session kể cả đồng thời, không sửa title/categoryId ngầm. Phân biệt lỗi constraint và trường hợp request ID đã dùng cho channel khác; request thua đọc lại kết quả trong transaction mới sau rollback, không self-invocation @Transactional hoặc đọc trong transaction rollback-only. End lỗi giữ trạng thái cũ nên vẫn chiếm slot.
- **Hoàn tất khi:** Hai create khác request chỉ một thành công; cùng request trả cùng session; không chỉ có test retry tuần tự.
- [x] STR-104 hoàn tất 2026-10-25: test thực sự giữ request đầu chưa commit và chờ insert cạnh tranh; cùng request trả cùng session/title/category, khác request/channel trả đúng lỗi.

### STR-105 — History cùng transaction và loại bỏ task cũ

- **Phụ thuộc:** STR-102.
- **Module/file:** History repository, domain service, task service/config/test cũ.
- **Cần làm:** Ghi history cùng transaction trạng thái; rollback không để history lẻ. Bỏ StreamMediaSyncTaskService/Impl, ClaimedMediaSyncTask, lease/retry config và test chỉ dành cho task; giữ/viết lại assertion slot/history/transaction còn phù hợp. Sửa Javadoc/error wording còn mô tả pending/task; không sửa revision của auth ở service khác.
- **Hoàn tất khi:** Domain không còn phụ thuộc task; history nhất quán khi rollback; build không cần bảng hoặc cấu hình media-sync.
- [x] STR-105 hoàn tất 2026-10-25: đã bỏ năm class task và cấu hình lease/retry; history dùng Clock và cùng transaction, lỗi ghi history/rollback không để session thay đổi lẻ.

### STR-106 — PostgreSQL integration cô lập

- **Phụ thuộc:** STR-101.
- **Module/file:** `pom.xml`, test profile, PostgreSQL test support.
- **Cần làm:** Dùng PostgreSQL 17/Testcontainers, migration thật, schema test cô lập theo class hoặc lifecycle được quản lý rõ; chỉ cleanup schema test. Giữ unit test không cần OBS/MediaMTX.
- **Hoàn tất khi:** Kiểm chứng constraint/Flyway/JPA/locking; không tác động schema public ứng dụng; lệnh và prerequisite chính xác.
- [x] STR-106 hoàn tất 2026-10-25: Testcontainers PostgreSQL 17, schema riêng từng suite/context; cleanup chỉ nhận schema test và không nuốt lỗi; cả migration/JPA/concurrency suite PASS.

### STR-107 — Kiểm chứng nền tảng đồng bộ và cập nhật nghiệm thu

- **Phụ thuộc:** STR-103, STR-104, STR-105, STR-106.
- **Module/file:** `stream-service/src/test/.../stream/`, tài liệu kiểm thử.
- **Cần làm:** Chạy ma trận trong plan giai đoạn 1: migration sạch/nâng schema cũ, concurrent create khác/cùng request, rollback/history, terminal ENDED, duplicate/stale events, deadline trước/đúng/sau mốc và đọc lại từ DB. Chạy clean verify sau khi bỏ task; bỏ yêu cầu lease/revision/SKIP LOCKED, ghi số test thực tế thay vì giữ cố định 53.
- **Hoàn tất khi:** Nền tảng hai bảng đạt kiểm chứng; không còn dependency task; M1 chỉ được tick theo kết quả mới. Chờ giai đoạn 3–4 để nghiệm thu HTTP MediaMTX thật.
- [x] STR-107 hoàn tất 2026-10-25: clean verify và repackage PASS: 77/77 test, 0 failures/errors/skipped; suite concurrency/transaction mới 11/11 PASS. HTTP MediaMTX được kiểm chứng tiếp ở giai đoạn 3–4.

**M1 đạt ngày 2026-10-25 theo phạm vi mới:** hai bảng session/history, OPEN/CLOSED, không task runtime; slot/concurrent idempotency/history/deadline đạt 77/77 test. [Bằng chứng và giới hạn](../testing/Streaming%20MVP%20Tests.md). V2/V3 chỉ được chạy trong DB test cô lập, chưa migrate DB ứng dụng; bước tiếp theo STR-201.

## 4. Giai đoạn 2 — Auth, eligibility và JWT media

Mục tiêu: backend thật xác định đúng người được cấp quyền và ký được token MediaMTX kiểm tra được.

### STR-201 — Chốt DTO, response envelope và error mapping

- **Phụ thuộc:** STR-107.
- **Module/file:** `stream/dto/`, `mapper/`, `common/exception/ErrorCode.java`.
- **Cần làm:** Tạo request/response theo implementation plan, validation title/category/request ID, quy tắc PATCH và DTO tách metadata khỏi credential. Thêm error code độc nhất, HTTP 400/401/403/404/409/503; không response pending cleanup hoặc 202.
- **Hoàn tất khi:** Contract có thể dùng chung cho backend/frontend; không có metadata response chứa JWT, không thêm exception class riêng theo feature.
- [ ] STR-201 hoàn tất, DTO và lỗi được rà soát.

### STR-202 — Tích hợp kiểm tra điều kiện streamer

- **Phụ thuộc:** STR-201.
- **Module/file:** `stream/client/user/`, API/service eligibility trong `user-service` nếu thiếu.
- **Cần làm:** Resolve streamer từ principal user ID; kiểm tra ACTIVE và điều kiện email theo business. Giữ account-active check ở Gateway; map lỗi eligibility/unavailable đúng contract.
- **Hoàn tất khi:** User không đủ điều kiện không xin được quyền phát; user ID và streamer ID không bị dùng lẫn; endpoint eligibility không làm đổi hành vi caller hiện có ngoài phạm vi.
- [ ] STR-202 hoàn tất, client và business eligibility có test.

### STR-203 — Tích hợp channel ownership và category

- **Phụ thuộc:** STR-201.
- **Module/file:** `stream/client/channel/`, controller/service nội bộ Channel Service.
- **Cần làm:** Lookup channel theo streamer, xác nhận sở hữu/trạng thái; reuse category validation. Bổ sung batch summary nhỏ khi danh sách cần channel name/avatar, không tạo N request theo N card.
- **Hoàn tất khi:** Không cấp stream cho channel khác/không hợp lệ; category không active bị chặn; response remote sai hoặc unavailable không bị coi là hợp lệ.
- [ ] STR-203 hoàn tất, contract/client Channel Service có test.

### STR-204 — Nạp và validate cấu hình RSA/JWT media

- **Phụ thuộc:** STR-201.
- **Module/file:** `common/configuration/`, cấu hình Stream Service, `.env.example`.
- **Cần làm:** Bind/validate key, kid, issuer/audience và TTL; parse private/public key, kiểm tra cặp khóa khớp, Clock UTC và public media URLs. Giữ bộ khóa qua restart, không dùng account signer làm media signer.
- **Hoàn tất khi:** Cấu hình sai/thiếu báo lỗi rõ; cấu hình hợp lệ không sinh key mới; không làm lộ private key qua startup log hoặc example.
- [ ] STR-204 hoàn tất, cấu hình có kiểm chứng.

### STR-205 — Xây bộ ký Publish/Read/Control API JWT

- **Phụ thuộc:** STR-204.
- **Module/file:** `stream/adapter/media/` và test JWT.
- **Cần làm:** Ký RS256 với kid/issuer/audience/subject/expiry/jti; phân quyền publish/read theo exact path và api cho backend. Tạo stream key đúng định dạng đã chốt.
- **Hoàn tất khi:** Verify được chữ ký bằng public key; claim/TTL/path đúng; token api không đi vào response client, token publish/read không có quyền ngoài mục đích.
- [ ] STR-205 hoàn tất, bộ ký có test phân quyền/expiry.

### STR-206 — Phục vụ JWKS thật và mở đúng đường qua Gateway

- **Phụ thuộc:** STR-205.
- **Module/file:** JWKS controller, Stream Service SecurityConfig, Gateway AuthenticationGlobalFilter.
- **Cần làm:** Trả JWKS JSON chuẩn không bọc ApiResponse, chỉ public parameters. Allow đúng GET JWKS khi không có account cookie; giữ Auth State behavior đối với token cookie được gửi kèm.
- **Hoàn tất khi:** MediaMTX lấy được JWKS qua Gateway thật; endpoint business khác vẫn yêu cầu đăng nhập; có test đảm bảo không lộ private parameters.
- [ ] STR-206 hoàn tất, JWKS qua Gateway đã được kiểm chứng.

### STR-207 — Chuẩn bị xác thực callback độc lập với account cookie

- **Phụ thuộc:** STR-201.
- **Module/file:** Callback DTO/HMAC verifier, security Stream Service và Gateway.
- **Cần làm:** Kiểm tra HMAC/timestamp/raw body/kích thước; cấu hình method/path callback và ngoại lệ CSRF chính xác nếu bật. Tách secret callback khỏi RSA/account signer; chuẩn bị contract cho handler ở giai đoạn 4.
- **Hoàn tất khi:** Sai/thiếu chữ ký không tới business handler; credential hợp lệ không cần account cookie. Không mở anonymous toàn bộ `/stream/**` hoặc trả thành công giả khi chưa xử lý nghiệp vụ.
- [ ] STR-207 hoàn tất, security callback có test.

### STR-208 — Kiểm chứng auth/client với backend thật

- **Phụ thuộc:** STR-202, STR-203, STR-206, STR-207.
- **Module/file:** Test Stream/User/Channel/Gateway liên quan, fixture MediaMTX giai đoạn 0.
- **Cần làm:** Thay JWKS fixture bằng endpoint thật; kiểm tra token backend ký được MediaMTX chấp nhận. Chạy regression account cookie/public route, lỗi client remote và phân biệt media token với account token.
- **Hoàn tất khi:** Bộ auth/token/client đạt kiểm chứng, không ảnh hưởng login/refresh hoặc ownership contract của service khác.
- [ ] STR-208 hoàn tất, kết thúc giai đoạn 2.

**Mốc nghiệm thu giai đoạn 2:** Backend thật phục vụ JWKS và ký credential dùng được; eligibility và ranh giới account/media auth đã được bảo vệ.

## 5. Giai đoạn 3 — Media adapter và session provisioning

Mục tiêu: tạo session/cấp thông tin OBS qua API thật bằng lời gọi MediaMTX đồng bộ; lỗi/timeout trả ngay, người dùng chủ động thử lại.

### STR-301 — Xây MediaMTX Control API adapter

- **Phụ thuộc:** STR-208.
- **Module/file:** `stream/adapter/media/`, remote DTO và HTTP configuration.
- **Cần làm:** Get/ensure/delete exact path, đọc nguồn thực tế, list phân trang và kick đúng RTMP/RTMPS ID. Dùng token api, timeout hữu hạn; map lỗi/404/idempotency đúng contract đã kiểm chứng ở giai đoạn 0.
- **Hoàn tất khi:** Adapter test đạt cho success/auth/error/timeout; ensure không replace path LIVE vô điều kiện, URL/path không do client tùy ý điều khiển.
- [ ] STR-301 hoàn tất, adapter có test contract.

### STR-302 — Xây use case gọi MediaMTX đồng bộ

- **Phụ thuộc:** STR-301, STR-105.
- **Module/file:** Session/lifecycle use case, transaction và MediaMTX adapter.
- **Cần làm:** Gọi adapter trực tiếp và chờ kết quả/timeout; không @Async, queue hoặc worker. MVP một replica dùng transaction/row lock serialize thao tác cùng session; reserve slot trước Create, đóng path trước commit End. Các lời gọi User/Channel nằm trước transaction ghi.
- **Hoàn tất khi:** Test chứng minh response chờ I/O; lỗi rollback DB và trả 503; không enqueue task, retry ngầm hoặc trả 202. Ghi rõ DB/media không atomic khi crash/timeout.
- [ ] STR-302 hoàn tất, luồng đồng bộ có test chờ kết quả/lỗi.

### STR-303 — Xây API Create Stream Session

- **Phụ thuộc:** STR-302, STR-202, STR-203, STR-104, STR-201.
- **Module/file:** Session controller/service, repository và DTO.
- **Cần làm:** Check principal/eligibility/channel/category; transaction reserve slot READY/OPEN chưa commit, provision path trực tiếp, ghi history rồi commit; sau đó trả thông tin OBS. Lỗi media rollback và trả 503; xử lý request ID trùng/slot bị chiếm.
- **Hoàn tất khi:** Create hợp lệ trả đúng session/credential; không cấp key cho ENDED hoặc media chưa provision; retry không tạo thêm session.
- [ ] STR-303 hoàn tất, API create có test thành công và lỗi.

### STR-304 — Xây API Update Stream Session

- **Phụ thuộc:** STR-303.
- **Module/file:** Session controller/service, update DTO, category client.
- **Cần làm:** Owner cập nhật title/category ở trạng thái cho phép; validate PATCH theo contract. Chặn sửa field hệ thống hoặc cập nhật session ENDED; không đổi path/key vì đổi metadata.
- **Hoàn tất khi:** Metadata cập nhật đúng, user khác bị chặn, lỗi category không ghi thay đổi một phần và stream hiện tại không bị restart bởi update.
- [ ] STR-304 hoàn tất, API update có test.

### STR-305 — Xây API cấp lại thông tin OBS

- **Phụ thuộc:** STR-303, STR-205.
- **Module/file:** Publish credentials use case/controller và DTO.
- **Cần làm:** Check owner/eligibility/status/grace/path, ký Publish JWT mới và trả serverUrl/streamKey/expiresAt với no-store. Không lưu JWT hoặc hash, không tuyên bố token cũ đã revoke.
- **Hoàn tất khi:** Có thể lấy credential sau reload bằng API; không cấp token cho session ENDED/hết grace hoặc user khác; response không chứa token api.
- [ ] STR-305 hoàn tất, cấp lại credential có test.

### STR-306 — Xây API current session cho owner

- **Phụ thuộc:** STR-303.
- **Module/file:** Current session query/service/controller và metadata DTO.
- **Cần làm:** Trả session active hiện tại hoặc result null. Trả deadline/serverTime/publishReady; không JWT trong polling metadata, không mediaCleanupPending/job status.
- **Hoàn tất khi:** Dashboard có thể phục hồi tiến trình sau create bị mất response hoặc reload, biết lúc nào chưa được tạo session mới, không nhận credential qua API metadata.
- [ ] STR-306 hoàn tất, query/current contract có test.

### STR-307 — Chuẩn hóa Docker/env để tích hợp backend thật

- **Phụ thuộc:** STR-206, STR-207, STR-302.
- **Module/file:** Docker Compose, `infra/mediamtx/`, env examples, hướng dẫn local.
- **Cần làm:** Tái sử dụng artifact đã kiểm chứng ở giai đoạn 0; thay fixture bằng JWKS/backend URL thật. Hoàn thiện healthcheck, mapping env/config, startup order và ranh giới network/Control API; chuẩn bị helper theo HMAC contract.
- **Hoàn tất khi:** Backend gọi được Control API và MediaMTX gọi được JWKS qua Gateway thật; không cần mở trực tiếp cổng business 8082 ra ngoài. Callback nghiệp vụ tiếp tục hoàn thiện ở giai đoạn 4.
- [ ] STR-307 hoàn tất, cấu hình local tái hiện được.

### STR-308 — Kiểm chứng provisioning và các API owner

- **Phụ thuộc:** STR-303, STR-304, STR-305, STR-306, STR-307.
- **Module/file:** Controller/service/adapter integration tests và smoke script.
- **Cần làm:** Kiểm tra API thật với OBS, owner khác, double-submit, mất response sau commit và lỗi/timeout MediaMTX trước commit. Kiểm tra path đúng, JWT đúng và metadata không lộ token.
- **Hoàn tất khi:** Dùng API tạo session rồi copy sang OBS phát được nguồn. Chưa gắn lifecycle handler thì không tự giả lập LIVE; phần đó được nghiệm thu ở giai đoạn 4.
- [ ] STR-308 hoàn tất, kết thúc giai đoạn 3.

**Mốc nghiệm thu giai đoạn 3:** Backend gọi MediaMTX và hoàn tất provisioning trước response; lỗi trả trực tiếp, không còn key mẫu hoặc task nền.

## 6. Giai đoạn 4 — Lifecycle, reconnect và End đồng bộ

Mục tiêu: trạng thái DB/UI theo nguồn thật và phiên ENDED không bị phát lại bằng token còn hạn.

### STR-401 — Helper callback HMAC gửi trực tiếp

- **Phụ thuộc:** STR-207, STR-307.
- **Module/file:** `infra/mediamtx/media_hook.py`, Dockerfile/config và helper tests.
- **Cần làm:** Đọc đúng path/source, ký HMAC và gửi một request HTTP có timeout, chờ response; bỏ vòng retry/backoff hiện có từ M0. Không gửi MTX_QUERY/log key. Callback handler xử lý xong trước response, không enqueue.
- **Hoàn tất khi:** Chữ ký/payload đúng; lỗi được ghi nhận, helper dừng đúng; có đúng một lần gửi cho mỗi lần hook chạy.
- [ ] STR-401 hoàn tất, helper đồng bộ có kiểm chứng.

### STR-402 — Xử lý ONLINE và chuyển LIVE

- **Phụ thuộc:** STR-401, STR-103, STR-301.
- **Module/file:** Media event controller, lifecycle service và history repository.
- **Cần làm:** Kết nối HMAC/DTO vào handler thật; xác nhận nguồn thực tế qua Control API trước khi chuyển READY/RECONNECTING sang LIVE. Ghi source/start/history atomically, chặn online cũ hoặc session ENDED.
- **Hoàn tất khi:** Chỉ nguồn hợp lệ chuyển LIVE; first startedAt được giữ; duplicate ONLINE và callback đến muộn không hồi sinh session.
- [ ] STR-402 hoàn tất, ONLINE có test và thử nguồn thật.

### STR-403 — Xử lý OFFLINE và bắt đầu reconnect grace

- **Phụ thuộc:** STR-402.
- **Module/file:** Lifecycle handler/service và source/deadline persistence.
- **Cần làm:** Phân biệt nguồn hiện tại với connection cũ; LIVE → RECONNECTING, lưu disconnectedAt/deadline 60 giây. Offline lặp giữ nguyên deadline; bỏ qua event của nguồn cũ nếu nguồn mới đang online.
- **Hoàn tất khi:** Dừng/mất kết nối OBS tạo RECONNECTING đúng; callback lặp/sai thứ tự không kéo dài grace hoặc làm hỏng nguồn mới.
- [ ] STR-403 hoàn tất, OFFLINE/source ordering có test.

### STR-404 — Quét deadline reconnect và đóng path trực tiếp

- **Phụ thuộc:** STR-403, STR-302.
- **Module/file:** Reconnect scheduler/service, query deadline và transaction transition.
- **Cần làm:** Quét deadline, khóa/recheck status/source theo policy; hết hạn gọi MediaMTX đóng path và chờ kết quả rồi ghi ENDED/CLOSED/history. Lỗi rollback/ghi log, không tạo task retry riêng. Không reset deadline sau backend restart, không cạnh tranh mù với ONLINE.
- **Hoàn tất khi:** Test clock chứng minh trước/sau deadline đúng, restart không cho thêm 60 giây và timeout cạnh tranh reconnect có kết quả nhất quán.
- [ ] STR-404 hoàn tất, scheduler deadline và lời gọi đồng bộ có test.

### STR-405 — Xây End Stream API và đóng nguồn thật

- **Phụ thuộc:** STR-404, STR-308.
- **Module/file:** End controller/use case, MediaMTX adapter và transaction session/history.
- **Cần làm:** Cho đúng owner kết thúc READY/LIVE/RECONNECTING; khóa session, gọi MediaMTX đóng path/ngắt nguồn và chờ xác nhận rồi commit ENDED/CLOSED/history. End lặp idempotent; lỗi/timeout trả 503 và rollback DB; không 202 hoặc xử lý sau response.
- **Hoàn tất khi:** Token cũ còn hạn không phát lại sau khi đóng path được xác nhận; không báo hoàn tất giả khi Control API lỗi; callback sau End giữ ENDED.
- [ ] STR-405 hoàn tất, API End và cleanup có test/thử thực tế.

### STR-406 — Chốt giới hạn lỗi/restart và hướng dẫn thao tác thủ công

- **Phụ thuộc:** STR-405, STR-302.
- **Module/file:** Runbook MediaMTX/Stream Service và tài liệu kiểm thử.
- **Cần làm:** Ghi cách kiểm tra DB/path/source khi timeout, crash, mất callback hoặc MediaMTX restart. Nêu rõ không tự restore path, đóng bù, retry queue hoặc reconcile; backend restart vẫn giữ deadline/key.
- **Hoàn tất khi:** Giới hạn MVP đồng bộ được ghi chính xác; có hướng dẫn thủ công, không yêu cầu implementation recovery tự động.
- [ ] STR-406 hoàn tất, giới hạn/runbook được rà soát.

### STR-407 — Kiểm thử concurrency và lỗi lifecycle

- **Phụ thuộc:** STR-406.
- **Module/file:** Service/DB/adapter integration tests.
- **Cần làm:** Test End đấu với create/reconnect, offline cũ sau online mới, callback lặp và lỗi/timeout Control API. Kiểm tra state/history/slot; response chờ kết quả, không task hoặc retry ngầm.
- **Hoàn tất khi:** Các invariant vẫn giữ khi lời gọi thành công; lỗi không báo thành công giả; ENDED không bị callback hồi sinh. Không nghiệm thu tự recovery sau crash/timeout.
- [ ] STR-407 hoàn tất, regression lifecycle đạt.

### STR-408 — Nghiệm thu nguồn phát và lifecycle bằng OBS thật

- **Phụ thuộc:** STR-407.
- **Module/file:** Smoke script, cấu hình chạy thật local và ghi nhận kiểm thử.
- **Cần làm:** Thực hiện create → OBS online → mất mạng/reconnect → End bằng API; thử Stop OBS, tắt tab web và phát lại bằng key cũ. Thử backend restart giữ deadline/key; MediaMTX restart được ghi nhận theo giới hạn vận hành thủ công.
- **Hoàn tất khi:** DB/API phản ánh nguồn thực tế và các khác biệt giữa web/OBS/network đúng kiến trúc; có bằng chứng phiên ENDED không phát lại được.
- [ ] STR-408 hoàn tất, kết thúc giai đoạn 4.

**Mốc nghiệm thu giai đoạn 4:** Streamer phát, reconnect và kết thúc được qua API; vòng đời và media cleanup hoạt động thật, không chỉ dựa trên status trong DB.

## 7. Giai đoạn 5 — Playback API và discovery tối thiểu

Mục tiêu: viewer nhận đúng quyền xem và có API để tìm/mở một buổi phát.

### STR-501 — Xây API metadata cho trang xem

- **Phụ thuộc:** STR-408.
- **Module/file:** Stream detail query/controller và viewer DTO.
- **Cần làm:** Trả metadata/status/time theo session ID; áp dụng account auth và không lộ token/chi tiết nội bộ. Phân biệt không tồn tại, READY, RECONNECTING và ENDED để frontend hiển thị.
- **Hoàn tất khi:** Viewer nhận đủ dữ liệu trạng thái mà không thể lấy Publish JWT; session không tồn tại được xử lý đúng HTTP/error contract.
- [ ] STR-501 hoàn tất, API detail có test.

### STR-502 — Xây danh sách stream PUBLIC/LIVE

- **Phụ thuộc:** STR-501, STR-203.
- **Module/file:** List request/query/response và channel batch client khi cần.
- **Cần làm:** Lọc/sort/pagination/count trong DB; giới hạn page size theo contract. Enrich metadata channel theo batch, không thêm viewer count/recommendation hoặc gọi remote theo từng card.
- **Hoàn tất khi:** Page/content/count đúng, chỉ trả stream thuộc phạm vi, lỗi metadata remote được xử lý rõ và không ảnh hưởng API channel cũ.
- [ ] STR-502 hoàn tất, list/pagination có test.

### STR-503 — Xây API playback access

- **Phụ thuộc:** STR-501, STR-205.
- **Module/file:** Playback service/controller, access DTO và URL builder.
- **Cần làm:** Check user đã đăng nhập, PUBLIC/LIVE; ghép public base URL với path DB và ký Read JWT đúng action/path/expiry. Trả no-store, không kèm Publish JWT hoặc api token.
- **Hoàn tất khi:** Chỉ session xem được mới được cấp access; URL không chứa token, không dùng hostname Docker và không lấy tùy ý từ input client.
- [ ] STR-503 hoàn tất, access API có test.

### STR-504 — Kiểm thử contract/security playback và discovery

- **Phụ thuộc:** STR-502, STR-503.
- **Module/file:** Controller/service/security tests và integration suite.
- **Cần làm:** Test thiếu account token, state không LIVE, session sai/không tồn tại, action/path của Read JWT và pagination. Kiểm tra metadata/polling không chứa credential.
- **Hoàn tất khi:** Contract đủ rõ cho frontend và không có đường cấp quyền xem/phát ngoài phạm vi; test các module bị sửa đạt.
- [ ] STR-504 hoàn tất, playback/discovery regression đạt.

### STR-505 — Kiểm chứng Read JWT thật với MediaMTX

- **Phụ thuộc:** STR-504.
- **Module/file:** Player fixture giai đoạn 0 và backend/media cấu hình thật.
- **Cần làm:** Lấy access bằng account thật, mở HLS bằng token backend ký; thử thiếu/sai/hết hạn token khi mở phiên mới. Kiểm tra phiên đang chạy, tạo lại phiên và không có callback auth cho từng file.
- **Hoàn tất khi:** Fixture player xem được hình/tiếng qua backend/media thật; hành vi expiry/session khớp contract trước khi nối trang watch production.
- [ ] STR-505 hoàn tất, kết thúc giai đoạn 5.

**Mốc nghiệm thu giai đoạn 5:** Viewer có thể lấy access từ backend và xem HLS thật; API discovery/detail sẵn sàng để frontend dùng.

## 8. Giai đoạn 6 — Nối frontend

Mục tiêu: hoàn thành quy trình streamer/viewer trên giao diện hiện có, không còn phụ thuộc thao tác API thủ công.

### STR-601 — Thêm stream API client, types và form schema

- **Phụ thuộc:** STR-505.
- **Module/file:** `web-app/src/api/stream.api.ts`, `types/stream.types.ts`, `schemas/stream.schema.ts`.
- **Cần làm:** Map đủ API/DTO/error theo contract; reuse Axios account client cho business API, validation form và query keys riêng cho metadata/list. Không persist credential response.
- **Hoàn tất khi:** TypeScript build được, URL/field đúng backend và không trộn account cookie với media Authorization flow.
- [ ] STR-601 hoàn tất, API binding được kiểm chứng.

### STR-602 — Dashboard tải current session và tạo session

- **Phụ thuộc:** STR-601, STR-303, STR-306.
- **Module/file:** `pages/dashboard/StreamerDashboardPage.tsx`, form/components/hook cần thiết.
- **Cần làm:** Chờ auth, tải current, chỉ hiện form create khi không có session; submit chống double-click và retry cùng request ID. Chờ Create hoàn tất, disable submit; 503 hiển thị lỗi và giữ nội dung nhập, không poll tiến trình provisioning nền.
- **Hoàn tất khi:** Tạo session từ UI được; reload hoặc mất response không tạo trùng và không tự tạo session chỉ vì vào dashboard.
- [ ] STR-602 hoàn tất, create/current UI có kiểm chứng.

### STR-603 — Dashboard cập nhật metadata

- **Phụ thuộc:** STR-602, STR-304.
- **Module/file:** Form title/category trên dashboard và mutation/query invalidation.
- **Cần làm:** Nối PATCH, hiển thị lỗi validation/ownership/state, cập nhật metadata sau thành công. Giữ key/path và nguồn phát hiện tại khi đổi title/category.
- **Hoàn tất khi:** Sửa title/category trên session được phép không làm gián đoạn nguồn hoặc tạo session/key mới; lỗi không ghi UI thành công giả.
- [ ] STR-603 hoàn tất, update UI có kiểm chứng.

### STR-604 — Dashboard hiển thị/copy/cấp lại thông tin OBS

- **Phụ thuộc:** STR-602, STR-305.
- **Module/file:** Credential card của dashboard.
- **Cần làm:** Thay server/key mẫu bằng API; che/hiện/copy nguyên key, hiển thị expiry của token cấp cho web. Sau reload cho lấy lại credential qua API, không localStorage hoặc fetch token mỗi lần poll.
- **Hoàn tất khi:** Copy sang OBS dùng được; UI diễn đạt đúng cấp token mới không thu hồi token cũ và không tự cập nhật OBS.
- [ ] STR-604 hoàn tất, không còn credential mock.

### STR-605 — Dashboard trạng thái/reconnect/countdown/End

- **Phụ thuộc:** STR-602, STR-405.
- **Module/file:** Dashboard polling, status banner/countdown và mutation End.
- **Cần làm:** Dùng status/deadline/serverTime từ API, pause polling khi phù hợp; chờ End trả kết quả và chỉ báo hoàn tất khi HTTP 200; 503 hiển thị lỗi/thử lại, không UI job pending cleanup. Owner vẫn có đường kết thúc session theo quyền API nếu không còn đủ quyền tạo stream.
- **Hoàn tất khi:** READY/LIVE/RECONNECTING/ENDED hiện đúng, reload không reset 60 giây và không cho tạo buổi mới khi nguồn cũ chưa đóng xong.
- [ ] STR-605 hoàn tất, lifecycle UI có kiểm chứng.

### STR-606 — Thêm route và màn hình watch theo auth/status

- **Phụ thuộc:** STR-601, STR-501, STR-503.
- **Module/file:** `App.tsx`, `pages/watch/WatchStreamPage.tsx`.
- **Cần làm:** Thêm `/watch/:sessionId`, tải metadata, auth/login-return flow và màn hình READY/RECONNECTING/ENDED/not-found. Khi LIVE mới lấy playback access theo contract.
- **Hoàn tất khi:** Guest không nhận Read JWT; viewer quay lại đúng stream sau login; trạng thái chưa phát/kết thúc không bị coi là lỗi player chung.
- [ ] STR-606 hoàn tất, watch route/state có kiểm chứng.

### STR-607 — Xây StreamPlayer dùng hls.js và Read JWT

- **Phụ thuộc:** STR-606.
- **Module/file:** `components/stream/StreamPlayer.tsx` và hook/helper thực sự cần thiết.
- **Cần làm:** Attach video/load manifest, gắn Bearer bằng loader setup chỉ cho media origin/path hợp lệ; giữ token trong memory. Cho play/volume/fullscreen, xử lý autoplay và browser không hỗ trợ theo phạm vi MVP.
- **Hoàn tất khi:** Hình/tiếng phát từ MediaMTX trên trang watch; không gửi account cookie, Publish JWT hoặc api token; không fallback phát bỏ auth.
- [ ] STR-607 hoàn tất, player cơ bản xem được stream thật.

### STR-608 — Hoàn thiện player recovery và cleanup

- **Phụ thuộc:** STR-607.
- **Module/file:** Player/watch lifecycle, refresh access và error handling.
- **Cần làm:** Phân biệt auth/network/decode lỗi; xin lại access có giới hạn khi cần mở phiên mới, theo metadata để dừng khi ENDED. Destroy instance/request/timer/listener khi đổi route/stream, tránh lỗi StrictMode và retry vô hạn.
- **Hoàn tất khi:** Reload/reconnect/đổi stream/unmount không để tài nguyên cũ chạy; phiên đang xem không bị destroy chỉ vì JWT đến exp; token không bị gửi sang stream/origin khác.
- [ ] STR-608 hoàn tất, recovery/resource lifecycle có kiểm chứng.

### STR-609 — Nối danh sách livestream và link xem

- **Phụ thuộc:** STR-601, STR-502.
- **Module/file:** `pages/browse/tabs/BrowseLivestreamsTab.tsx` và card/query liên quan.
- **Cần làm:** Hiện list/pagination/loading/error/empty state bằng dữ liệu thật, link watch theo session. Không thêm viewer count hoặc preview giả; xử lý card đã cũ khi stream vừa kết thúc.
- **Hoàn tất khi:** Viewer tìm và mở stream từ web được; list rỗng/lỗi/page cuối hiển thị đúng, không gây request dư theo từng card.
- [ ] STR-609 hoàn tất, discovery UI có kiểm chứng.

### STR-610 — Kiểm thử frontend và regression hiện có

- **Phụ thuộc:** STR-603, STR-604, STR-605, STR-608, STR-609.
- **Module/file:** Web tests hiện có, test mới phù hợp và browser chạy local.
- **Cần làm:** Chạy test/build/lint; kiểm tra auth/dashboard/profile/browse cũ không bị hỏng. Thử hai browser session với OBS và kiểm tra Network, cleanup, autoplay và lỗi quyền trên UI thật.
- **Hoàn tất khi:** Toàn bộ luồng streamer/viewer thao tác được bằng UI; có kiểm chứng runtime, không chỉ test kiểm tra chuỗi source.
- [ ] STR-610 hoàn tất, kết thúc giai đoạn 6.

**Mốc nghiệm thu giai đoạn 6:** Người dùng thực hiện đầy đủ tạo/cập nhật/copy OBS/phát/xem/kết thúc từ giao diện web.

## 9. Giai đoạn 7 — Nghiệm thu và bàn giao

Mục tiêu: chứng minh các phần đã tích hợp hoạt động cùng nhau, có hướng dẫn chạy lại và giới hạn được công bố đúng.

### STR-701 — Chạy toàn bộ check tự động của module bị ảnh hưởng

- **Phụ thuộc:** STR-610, STR-504, STR-407.
- **Module/file:** Stream/Gateway/User/Channel tests khi bị sửa, web scripts và Docker configuration.
- **Cần làm:** Chạy `./mvnw clean verify` theo module, PostgreSQL integration, `npm test`, build/lint và Compose config với env đã nạp. Xử lý lỗi thực tế, ghi rõ test nào cần prerequisite riêng.
- **Hoàn tất khi:** Các check bắt buộc đạt; không báo đã chạy những suite chưa thực thi hoặc dùng sai môi trường dữ liệu.
- [ ] STR-701 hoàn tất, có lệnh và kết quả kiểm chứng.

### STR-702 — Nghiệm thu end-to-end happy case

- **Phụ thuộc:** STR-701.
- **Module/file:** Môi trường local đầy đủ và `docs/testing/Streaming MVP Tests.md`.
- **Cần làm:** Dùng một streamer/một viewer: tạo session, đổi metadata, copy OBS, phát, tìm stream, xem hình/tiếng, kết thúc và tạo buổi tiếp theo. Kiểm tra status/API/DB/media đồng thời.
- **Hoàn tất khi:** Quy trình tái hiện được từ UI, video không đi qua Java backend và session mới dùng path/token mới.
- [ ] STR-702 hoàn tất, happy case có bằng chứng.

### STR-703 — Nghiệm thu lỗi trực tiếp và bảo vệ media

- **Phụ thuộc:** STR-702.
- **Module/file:** Môi trường thật local, smoke script và ma trận kiểm thử trong implementation plan.
- **Cần làm:** Thử invalid token/path/action, user khác, CORS, mất mạng, OBS Stop, callback cũ/lặp, timeout grace, backend/media restart và Control API lỗi lúc End. Dùng lại key cũ còn hạn sau End và xác nhận bị chặn.
- **Hoàn tất khi:** Ma trận bắt buộc có kết quả cụ thể, không còn lỗi khiến ENDED hồi sinh, báo thành công giả khi media lỗi hoặc chiếm hai slot; giới hạn replay/recording được ghi đúng.
- [ ] STR-703 hoàn tất, các tình huống lỗi có bằng chứng.

### STR-704 — Hoàn thiện runbook local và tài liệu theo code cuối

- **Phụ thuộc:** STR-703.
- **Module/file:** README Stream Service/MediaMTX, env examples, architecture flow, data model và testing docs.
- **Cần làm:** Ghi thứ tự chạy, nạp env/key, thiết lập OBS, URL/port, điều tra lỗi JWKS/hook/Control API và kiểm tra path thủ công. Đồng bộ contract/API/field/tham số thực tế với plan và bỏ fixture khỏi đường chạy chính.
- **Hoàn tất khi:** Có thể dựng lại môi trường sạch theo hướng dẫn; tài liệu không chứa secret và không mô tả code/protocol khác thực tế.
- [ ] STR-704 hoàn tất, runbook đã được đi thử.

### STR-705 — Chuẩn bị cấu hình triển khai và checklist vận hành

- **Phụ thuộc:** STR-704.
- **Module/file:** Cấu hình mẫu deploy, runbook TLS/network/secrets/recovery/rollback.
- **Cần làm:** Chuẩn bị public ingest/playback URL, HTTPS/RTMPS, private Control API, signing key ổn định và cleanup trước rollback. Nếu đã có môi trường đích thì xác minh runtime; nếu chưa có thì ghi rõ phần production chưa kiểm chứng.
- **Hoàn tất khi:** Có cấu hình/hướng dẫn vận hành có thể review; local-pass không bị báo thành production-pass. Việc chưa có môi trường production không ngăn nghiệm thu MVP local khi được ghi rõ.
- [ ] STR-705 hoàn tất, cấu hình/checklist đã được rà soát.

### STR-706 — Bàn giao kết quả và khóa phạm vi MVP

- **Phụ thuộc:** STR-704, STR-705.
- **Module/file:** Task breakdown, implementation plan, kết quả kiểm thử và ghi nhận bàn giao.
- **Cần làm:** Đối chiếu checklist nghiệm thu MVP, đánh dấu task theo bằng chứng, liệt kê lỗi/giới hạn còn lại. Tách task queue/lease/revision/retry/reconciler/restore tự động, rotate/revoke trong cùng session, Safari/native HLS, moderation, CDN và recording khỏi kết quả đã hoàn tất.
- **Hoàn tất khi:** Người review biết rõ tính năng nào chạy được, đã test ở đâu và phần nào còn ngoài phạm vi; không có checkbox hoàn thành dựa trên giả định.
- [ ] STR-706 hoàn tất, kết thúc giai đoạn 7.

**Mốc nghiệm thu giai đoạn 7:** MVP có bằng chứng phát/xem/kết thúc/reconnect đúng, runbook tái hiện được và các giới hạn được ghi rõ.

## 10. Các mốc theo dõi tiến độ

| Mốc | Task cuối cần đạt | Có thể kiểm chứng được |
|---|---|---|
| M0 — Chứng minh kiến trúc media — ĐẠT 2026-10-24 | STR-006 | OBS/JWT/HLS/đóng path bằng fixture |
| M1 — Nền tảng dữ liệu đúng — ĐẠT 2026-10-25 | STR-107 | Hai bảng session/history, constraint/concurrency/deadline, 77/77 test |
| M2 — Auth backend thật | STR-208 | JWT/JWKS/eligibility/account boundary |
| M3 — Tạo stream bằng API | STR-308 | Backend mở path trực tiếp và cấp thông tin OBS sau commit |
| M4 — Vòng đời và kết thúc đồng bộ | STR-408 | LIVE/reconnect/End với OBS thật, lỗi trực tiếp và giới hạn thủ công |
| M5 — Xem bằng API thật | STR-505 | Read JWT backend cấp phát được trên player fixture |
| M6 — Dùng trọn luồng trên web | STR-610 | Dashboard + browse/watch không còn dữ liệu giả |
| M7 — MVP được nghiệm thu | STR-706 | Check tự động, E2E, runbook và giới hạn rõ ràng |

Mỗi mốc được đánh giá bằng hành vi quan sát được. Việc hoàn thành một mốc trung gian không có nghĩa đã đủ điều kiện triển khai bản MVP cho người dùng nếu các bước lifecycle/cleanup hoặc nghiệm thu còn thiếu.
