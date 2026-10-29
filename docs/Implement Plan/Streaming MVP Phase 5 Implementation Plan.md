# Kế hoạch Giai đoạn 5 — Metadata và Playback Access

- Cập nhật: 2026-10-29.
- Trạng thái: STR-501, STR-503 và STR-504 đã triển khai, kiểm thử tự động đạt ngày 2026-10-29; STR-505 mới hoàn thiện fixture backend mode, chưa nghiệm thu media/browser thật.
- Nguồn: [Implementation Plan](Streaming%20MVP%20Implementation%20Plan.md), [Task Breakdown](Streaming%20MVP%20Task%20Breakdown.md#7-giai-đoạn-5--playback-api-và-xem-stream-trực-tiếp), [Streaming MVP Architecture and Flow](../architecture/Streaming%20MVP%20Architecture%20and%20Flow.md).
- Phạm vi: STR-501, STR-503, STR-504 và STR-505 theo Task Breakdown.
- Tiền đề: tái sử dụng nền tảng database, authentication, JWT, provisioning và lifecycle của giai đoạn 0–4.
- Quyết định đã chốt: hai endpoint riêng; frontend điều phối GET metadata và POST playback-access.

## 1. Mục tiêu và phạm vi triển khai

Giai đoạn 5 hoàn thiện backend để viewer đăng nhập có thể đọc thông tin session, xin quyền xem session PUBLIC/LIVE và xem HLS bằng token backend cấp.

| Task | Vai trò | Đầu ra |
|---|---|---|
| **STR-501** | Cung cấp thông tin và trạng thái buổi phát | GET metadata phục vụ mở trang và polling |
| **STR-503** | Kiểm tra điều kiện và cấp quyền xem | POST trả playback URL, Read JWT và expiry |
| **STR-504** | Bảo vệ contract, authentication và tính nhất quán | Kiểm thử tự động cho hai API và trường hợp cạnh tranh với End |
| **STR-505** | Chứng minh backend hoạt động với media thật | Kiểm chứng API → Read JWT → MediaMTX → player fixture |

Thứ tự thực hiện: **STR-501 → STR-503 → STR-504 → STR-505**. Viết test liên quan trong từng task; STR-504 hoàn thiện ma trận regression trước khi nghiệm thu media thật.

Giai đoạn này chưa xây trang watch hoặc component `StreamPlayer` production. Polling, giao diện trạng thái và recovery trên React được triển khai ở giai đoạn 6. Fixture chỉ phục vụ kiểm chứng kỹ thuật.

Không bổ sung WebSocket/SSE, discovery/list LIVE, viewer count, migration, queue, worker hoặc recovery tự động.

## 2. Thành phần tái sử dụng và workflow chung

### Thành phần đã có

| Thành phần | Cách sử dụng |
|---|---|
| `StreamSessionController` | Bổ sung hai endpoint viewer, giữ API owner hiện tại |
| `StreamSessionService` và implementation | Bổ sung truy vấn metadata |
| `StreamSessionRepository` | Dùng `findById` cho metadata và `findByIdForUpdate` khi cấp access |
| `StreamMetadataResponse`, `StreamSessionMapper` | Trả metadata và thời gian UTC |
| `StreamPlaybackAccessResponse` | Trả URL, Read JWT và expiry; giữ cơ chế che token trong `toString` |
| `MediaJwtIssuer.issueRead` | Ký Read JWT đúng viewer, action, path và TTL |
| `MediaProperties` | Lấy HLS public base URL và TTL đã cấu hình |
| Security, `ApiResponse`, `AppException(ErrorCode)` | Giữ authentication và error contract hiện tại |

Tạo `StreamPlaybackService` và implementation trong module `stream` để quản lý nghiệp vụ cấp quyền xem. Mọi method bổ sung vào service interface phải có English Javadoc.

### Workflow đã chốt

```text
Viewer mở trang livestream
    ↓
Frontend gọi GET metadata
    ↓
Frontend đọc status
    ├─ READY          → hiện chưa bắt đầu, tiếp tục polling GET
    ├─ RECONNECTING   → hiện chờ kết nối lại, tiếp tục polling GET
    ├─ ENDED          → hiện đã kết thúc, dừng polling/player
    └─ LIVE           → nếu cần bắt đầu xem, gọi POST playback-access
                            ↓
                       Backend tự kiểm tra lại điều kiện
                            ↓
                       Trả playbackUrl + Read JWT + expiry
                            ↓
                       Player tải HLS trực tiếp từ MediaMTX
```

Frontend quyết định thời điểm gọi POST. Backend không tự gọi POST sau GET và không yêu cầu client phải gọi GET trước đó.

Trong lúc mở trang, frontend production sẽ poll riêng GET metadata khoảng 3 giây khi tab hiển thị. POST không nằm trong vòng polling.

## 3. STR-501 — Xây API Metadata cho Viewer

**Vai trò:** Cung cấp dữ liệu để frontend biết session là gì và đang ở trạng thái nào, phục vụ cả lần mở trang đầu tiên và polling.

**Phụ thuộc:** Nền tảng session/lifecycle giai đoạn 4.

### Công việc triển khai

Bổ sung endpoint:

```http
GET /stream/api/streams/{sessionId}
```

1. Giữ yêu cầu đăng nhập theo security hiện tại. Viewer không cần là owner hoặc có quyền streamer.
2. Bổ sung method `metadata(UUID sessionId)` vào `StreamSessionService`.
3. Đọc session trong transaction read-only bằng `findById`; không lấy khóa ghi cho polling.
4. Chỉ phục vụ phạm vi PUBLIC của MVP; session ngoài phạm vi này trả `ACCESS_DENIED`.
5. Dùng mapper hiện có để tạo `StreamMetadataResponse`, lấy `serverTime` từ Clock.
6. Trả HTTP 200, envelope `ApiResponse` và `Cache-Control: no-store`.

Metadata gồm:

- `id`, `channelId`, `title`, `categoryId`, `visibility`, `status`.
- `startedAt`, `endedAt`, `reconnectDeadlineAt`, `serverTime`.

READY, LIVE, RECONNECTING và ENDED đều đọc được metadata. HTTP 200 không đồng nghĩa session được cấp quyền xem video.

Endpoint không trả playback URL, JWT, stream key, source ID hoặc thông tin quản trị. Không gọi MediaMTX/User/Channel Service, ký token hoặc thay đổi session/history.

### Kiểm thử và điều kiện hoàn tất

- Viewer đăng nhập đọc được metadata PUBLIC ở cả bốn trạng thái.
- Thiếu authentication trả 401; UUID sai trả 400; không tồn tại trả 404.
- Metadata và thời gian đúng DTO, có no-store, không chứa credential.
- GET lặp không phát sinh JWT, media I/O hoặc thay đổi lifecycle.
- Route `/me/current` và API owner hiện có tiếp tục hoạt động.

**Đầu ra STR-501:** API metadata có thể dùng làm contract cho trang watch và polling giai đoạn 6.

## 4. STR-503 — Xây API Playback Access

**Vai trò:** Quyết định viewer có được mở phiên HLS hay không và cấp credential đúng session.

**Phụ thuộc:** STR-501 và bộ ký JWT của STR-205.

### Công việc triển khai

Bổ sung endpoint:

```http
POST /stream/api/streams/{sessionId}/playback-access
```

Request không cần body. Controller lấy viewer user ID từ principal bằng helper authentication hiện có rồi gọi `StreamPlaybackService`.

Use case xử lý:

1. Mở transaction do use case sở hữu, theo mẫu transaction hiện có.
2. Đọc session bằng `findByIdForUpdate` để serialize với End và lifecycle.
3. Kiểm tra session tồn tại, visibility PUBLIC và status LIVE dưới khóa.
4. Ghép HLS public base URL cấu hình với `playback_path` trong DB, xử lý dấu `/` đúng.
5. Gọi `MediaJwtIssuer.issueRead(sessionId, viewerUserId)`.
6. Tạo response và hoàn tất transaction trước khi controller trả kết quả.

Response dùng `ApiResponse<StreamPlaybackAccessResponse>`, HTTP 200 và `Cache-Control: no-store`:

```text
sessionId
playbackUrl
readToken
readExpiresAt
```

Quy tắc credential:

- RS256 và bộ khóa media hiện có.
- Subject là viewer user ID.
- Quyền duy nhất `read` tại exact path `live/{sessionId}`.
- TTL mặc định 5 phút theo cấu hình đã chốt.
- URL không chứa JWT; player gửi token qua header `Authorization: Bearer ...`.
- Không nhận media URL/path từ client và không trả hostname Docker cho browser.

Use case không gọi Control API, provision/restore path, kiểm tra eligibility streamer, đếm viewer hoặc thay đổi session/history.

### HTTP và lỗi

| Trường hợp | Kết quả |
|---|---|
| Account chưa xác thực | 401 theo security hiện tại |
| UUID không hợp lệ | 400 theo error handler hiện tại |
| Session không tồn tại | `STREAM_SESSION_NOT_FOUND` — 404 |
| Visibility ngoài PUBLIC | `ACCESS_DENIED` — 403 |
| READY, RECONNECTING hoặc ENDED | `STREAM_SESSION_STATE_INVALID` — 409 |
| PUBLIC/LIVE hợp lệ | 200 và playback access |

Tái sử dụng ErrorCode hiện có; không tạo exception riêng.

GET vừa trả LIVE không bảo đảm POST thành công. Nếu End đã commit trước khi POST lấy khóa, POST phải trả 409. Nếu POST lấy khóa trước, End chờ transaction cấp access hoàn tất; token đã cấp không bảo đảm path tiếp tục tồn tại sau khi End thành công.

### Kiểm thử và điều kiện hoàn tất

- Viewer hợp lệ, kể cả người không sở hữu session, nhận được access cho PUBLIC/LIVE.
- POST trực tiếp không cần GET trước vẫn áp dụng đầy đủ business rules.
- URL, subject, action, path và expiry đúng.
- Response không chứa Publish/API JWT và không làm lộ token qua log.
- Session/history không thay đổi sau cấp access.

**Đầu ra STR-503:** Backend cấp được Read JWT theo quyền xem hiện tại của session, độc lập với quyết định phía frontend.

## 5. STR-504 — Kiểm thử Contract, Security và Concurrency

**Vai trò:** Chứng minh hai API tuân thủ contract và không cấp quyền dựa trên trạng thái cũ.

**Phụ thuộc:** STR-501 và STR-503.

### Công việc triển khai

Bổ sung kiểm thử theo ba lớp:

| Lớp | Nội dung |
|---|---|
| Controller/security | Authentication, principal, HTTP/envelope, serialization, no-store và bảo vệ các route mới |
| Service/JWT | Điều kiện PUBLIC/LIVE, URL builder, viewer subject, TTL, action/path và không có quyền thừa |
| PostgreSQL integration | Đọc trạng thái mới, khóa cấp access cạnh tranh với End, không thay đổi session/history |

Các tình huống bắt buộc:

1. GET thành công cho bốn trạng thái; POST chỉ thành công khi PUBLIC/LIVE.
2. Session sai/không tồn tại, account thiếu hoặc không hợp lệ.
3. POST trực tiếp khi chưa GET.
4. GET trả LIVE, sau đó End commit, POST bị từ chối.
5. End đang giữ khóa: POST chờ và đọc ENDED sau commit.
6. POST đang giữ khóa: End chờ đến khi transaction cấp access kết thúc.
7. GET lặp không ký token; cấp access không gọi media hoặc ghi history.
8. Read JWT chỉ đọc đúng session, không có quyền publish/api.
9. Regression Create/Update/current/publish credentials/End và lifecycle hiện có.

Test concurrency dùng latch/barrier theo hạ tầng test hiện có; không dựa vào sleep để giả lập thứ tự.

### Điều kiện hoàn tất

- Các test mới và regression liên quan đạt.
- PostgreSQL thật xác nhận hành vi khóa; không dùng riêng H2 để kết luận concurrency.
- Ghi số test thực tế, prerequisite và test bị skip.
- Chưa coi controller/service test là bằng chứng browser phát được hình/tiếng.

**Đầu ra STR-504:** Contract backend đủ ổn định để kiểm chứng MediaMTX thật và bàn giao frontend.

## 6. STR-505 — Kiểm chứng Playback với Backend và MediaMTX Thật

**Vai trò:** Chứng minh Read JWT lấy qua API nghiệp vụ thật mở được HLS, thay cho việc fixture tự ký token happy case.

**Phụ thuộc:** STR-504.

### Công việc triển khai

1. Bổ sung chế độ backend cho fixture/helper hiện có: nhận session ID và account cookie qua file riêng, gọi Gateway GET metadata rồi POST playback-access khi LIVE.
2. Đưa access nhận từ backend vào player fixture; không dùng token fixture tự ký cho happy case.
3. Phục vụ fixture từ origin frontend đã được allowlist, mặc định `http://localhost:5173`. Nếu port bận, báo rõ để người vận hành xử lý; không tự dừng ứng dụng hoặc nới CORS.
4. Giữ JWKS và callback MediaMTX trỏ tới Stream Service thật. Không chạy cấu hình fixture cũ để thay các endpoint này.
5. Dùng nguồn OBS do người dùng điều khiển để xác nhận hình/tiếng trên Chrome/Edge và Network tải HLS trực tiếp từ MediaMTX.
6. Gọi GET metadata lặp trong lúc xem, xác nhận không cần gọi POST theo mỗi lần poll.
7. Thử source reconnect và End, đối chiếu metadata với lifecycle backend.
8. Kiểm chứng thiếu/sai/hết hạn token khi mở phiên HLS mới; dùng test môi trường cô lập cho các case cần TTL ngắn.

Fixture giữ token trong memory, không in account cookie/JWT hoặc lưu chúng vào báo cáo. Không tự mở hoặc điều khiển OBS.

Kiểm thử media tự động dùng container/path/process cô lập theo mẫu integration hiện có. Phải phân biệt bằng chứng tự động có stub account với nghiệm thu account thật qua Gateway.

### Điều kiện hoàn tất

- Account thật lấy metadata và playback access qua Gateway.
- Player fixture phát được hình và tiếng bằng Read JWT backend cấp.
- Token không hợp lệ bị từ chối khi mở phiên mới.
- Phiên HLS đang chạy không bị coi là phải dừng chỉ vì JWT tới expiry.
- GET phản ánh READY/LIVE/RECONNECTING/ENDED mà không cấp token.
- Có ghi nhận kết quả, cấu hình và giới hạn; không đánh dấu browser/OBS PASS khi chưa kiểm chứng.

**Đầu ra STR-505:** Đủ bằng chứng nghiệm thu M5 — xem HLS thật qua session ID bằng backend API.

## 7. Kiểm chứng cuối và bàn giao Giai đoạn 6

Trong `stream-service`, chạy:

```sh
./mvnw clean verify
```

Chạy riêng media integration opt-in theo test được bổ sung và ghi lệnh chính xác vào README. Docker là prerequisite của PostgreSQL Testcontainers; kiểm chứng media cần FFmpeg/ffprobe, còn kiểm chứng browser cần hls.js hiện có và nguồn OBS.

Chỉ chạy kiểm tra các module khác khi có thay đổi thực tế. Không cần build frontend production để kết luận phần backend giai đoạn 5.

Ghi bằng chứng vào [Streaming MVP Tests](../testing/Streaming%20MVP%20Tests.md); chỉ tick STR-501/503/504/505 sau khi đạt điều kiện tương ứng.

Bàn giao cho giai đoạn 6:

- Contract metadata và playback access.
- Luồng frontend đọc LIVE rồi chủ động gọi POST.
- Polling riêng GET, đề xuất khoảng 3 giây khi tab hiển thị.
- Không cấp token hoặc tạo lại player theo mỗi response LIVE.
- Xử lý POST 409 bằng cách lấy lại metadata.
- Dừng player/polling khi ENDED; không coi lỗi mạng viewer là session ENDED.
- Trang watch, player React và recovery production vẫn thuộc giai đoạn 6.

**M5 hoàn tất khi:** Hai API đạt kiểm thử, viewer dùng account thật lấy access và xem được hình/tiếng qua MediaMTX bằng fixture, có bằng chứng nghiệm thu rõ ràng.

## 8. Kết quả triển khai (2026-10-29)

Trạng thái thực tế: **STR-501, STR-503 và STR-504 đã triển khai và đạt kiểm thử tự động**. **STR-505 mới hoàn thiện fixture/helper backend mode; chưa nghiệm thu MediaMTX/browser/OBS thật, nên M5 chưa hoàn tất.**

### 8.1. Thay đổi backend

- `StreamSessionService.metadata(UUID)` đọc session PUBLIC trong transaction read-only, dùng `StreamSessionMapper` và `Clock`; không cấp token, không gọi media, không ghi history.
- `StreamPlaybackService` và `StreamPlaybackServiceImpl` mở transaction riêng, dùng `findByIdForUpdate` để serialize với End, kiểm tra PUBLIC/LIVE dưới khóa, ghép `STREAM_HLS_PUBLIC_BASE_URL` với `playback_path` và gọi `MediaJwtIssuer.issueRead`.
- `StreamSessionController` bổ sung:
  - `GET /api/streams/{sessionId}` — `ApiResponse<StreamMetadataResponse>`, `Cache-Control: no-store`.
  - `POST /api/streams/{sessionId}/playback-access` — `ApiResponse<StreamPlaybackAccessResponse>`, `Cache-Control: no-store`.
- Lỗi dùng `AppException(ErrorCode)` hiện có: session không tồn tại `1006/404`, visibility ngoài PUBLIC `1002/403`, trạng thái không LIVE `1009/409`, UUID sai `1004/400`.
- Không thêm migration, WebSocket/SSE, queue/worker, viewer count hoặc frontend production.

### 8.2. Kiểm thử đã chạy

```sh
cd stream-service
./mvnw clean verify
```

- **164 tests, 0 failures, 0 errors, 0 skipped**; build và Spring Boot repackage thành công.
- `StreamSessionControllerTest`: **11/11** — auth cho hai route mới, UUID sai, metadata bốn trạng thái, no-store, không có credential, POST trả token/URL/expiry và map lỗi 404/403/409.
- `StreamPlaybackPostgresTest`: **5/5** — PostgreSQL 17 thật; metadata bốn trạng thái không đổi history/không ký JWT; visibility; Read JWT đúng signature/subject/action/path/TTL; POST trực tiếp; GET LIVE rồi End commit thì POST bị từ chối.
- `StreamPlaybackConcurrencyPostgresTest`: **2/2** — End giữ khóa làm POST chờ rồi nhận 409; POST giữ khóa làm End chờ đến khi transaction cấp access commit. Test dùng latch và `pg_stat_activity`, không dùng sleep để giả lập thứ tự.
- `MediaJwtIssuerTest`: **3/3** — giữ nguyên regression chữ ký, claim, TTL và redaction.
- Không có test bị skip trong Maven.

```sh
python3 -m unittest discover -s scripts/testing/streaming -p 'test_*.py' -v
node --check scripts/testing/streaming/player.js
```

- **23/23 Python fixture tests PASS**, gồm backend client, cookie file private, same-origin, direct metadata → playback-access, từ chối khi chưa LIVE và không rò cookie/token trong lỗi.
- JS syntax PASS.

### 8.3. STR-505 — phần đã làm và phần còn chờ

Đã bổ sung:

- `scripts/testing/streaming/backend_access.py`: đọc Cookie header từ file private, gọi Gateway `GET metadata` rồi `POST playback-access`, giữ token trong memory, không in/lưu token.
- `fixture.py serve --account-cookie-file ... --gateway-base-url ...`: endpoint same-origin `/fixture/backend-metadata` và `/fixture/backend-access`; metadata polling không cấp token.
- `player.html`/`player.js`: chọn nguồn `Backend account thật`, nhập session ID, polling metadata 3 giây, chỉ gọi POST khi mở phiên HLS.
- `inspect_playback.py --access-mode backend --account-cookie-file ...`: dùng đúng Read JWT backend cấp để kiểm tra master/rendition/init/segment và decode audio/video.

Chưa nghiệm thu:

- Chưa có account thật/cookie hợp lệ trong môi trường hiện tại; chưa chạy MediaMTX với nguồn OBS thật.
- Chưa xác nhận hình/tiếng trên Chrome/Edge bằng Read JWT backend cấp.
- Chưa kiểm chứng token hết hạn/thiếu/sai trên phiên HLS mới với MediaMTX thật trong cấu hình Phase 5.
- Vì vậy **không đánh dấu STR-505, M5 hoặc STR-408 hoàn tất**.

Lệnh nghiệm thu còn lại được ghi trong [Streaming MVP Tests](../testing/Streaming%20MVP%20Tests.md) và [fixture README](../../scripts/testing/streaming/README.md).
