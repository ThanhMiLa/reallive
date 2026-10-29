# Giai đoạn 5 — Kiểm thử từ OBS đến player trên browser

- Phạm vi: STR-501, STR-503, STR-504, STR-505 theo [Phase 5 Implementation Plan](../Implement%20Plan/Streaming%20MVP%20Phase%205%20Implementation%20Plan.md).
- Dùng stack local thật, account thật và OBS do người dùng thao tác. Các bước tạo/End session chỉ thực hiện với buổi phát test do bạn sở hữu.
- Giao diện xem ở đây là **player fixture**, không phải trang watch production của `web-app` (thuộc Phase 6).
- Gọi session backend đang test là **B**. Session A của fixture M0 không được dùng thay bằng chứng của B.
- Đây là hướng dẫn kiểm thử, không phải xác nhận đã PASS. Kết quả thực tế ghi tại [Streaming MVP Tests](Streaming%20MVP%20Tests.md) và đối chiếu [Acceptance Review](Streaming%20MVP%20Phase%205%20Acceptance%20Review.md).

## 1. Chuẩn bị môi trường

Chạy các lệnh terminal từ root repository:

```sh
cd /Users/ngocthanh/Documents/Project/reallive
```

Chuẩn bị:

- JDK 21, Docker, Python 3.10+, Node, FFmpeg/ffprobe và OBS.
- PostgreSQL/Redis và các service User, Channel, Stream, Gateway đang hoạt động với env local đã nạp. Nếu đã chạy trong IDE thì giữ nguyên các process đó.
- Account **streamer** đủ điều kiện phát: email verified, streamer ACTIVE, channel ACTIVE và category hợp lệ.
- Account **viewer** ACTIVE để xem. Có thể dùng cùng account ở lượt đầu; nên kiểm tra thêm với một viewer không phải owner để xác nhận quyền xem PUBLIC.
- Cặp dev key ổn định trong `.local/streaming/private.pem` và `public.pem` phải khớp bộ khóa backend/MediaMTX đang dùng. Không sinh/đổi key giữa buổi test.
- hls.js đã có trong `web-app/node_modules`. Nếu thiếu, chạy `npm ci` trong `web-app`.

| Thành phần | Địa chỉ local mặc định |
|---|---|
| Gateway — API người dùng | `http://localhost:8888` |
| Stream Service — JWKS/callback | `http://localhost:8082/stream` |
| MediaMTX ingest cho OBS | `rtmp://localhost:1935/live` |
| MediaMTX HLS | `http://localhost:8898` |
| MediaMTX Control API nội bộ | `http://127.0.0.1:9997` |
| Player fixture | `http://localhost:5173` |

Nếu hạ tầng chưa chạy, dùng root Compose với env đã chuẩn bị:

```sh
docker compose up -d --wait
```

Các Java service chạy bằng cấu hình IDE đã dùng ở Phase 0–4, hoặc `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` bên trong từng module với env tương ứng đã nạp. Spring Boot không tự đọc `.env`.

Trong root `.env`, MediaMTX phải dùng JWKS/callback backend thật:

```text
STREAM_MEDIAMTX_JWKS_URL=http://host.docker.internal:8082/stream/api/media/jwks
STREAM_MEDIA_CALLBACK_URL=http://host.docker.internal:8082/stream/api/media/events
STREAM_MEDIA_ALLOWED_ORIGINS=http://localhost:5173
```

Nếu đổi các biến này, cần recreate container MediaMTX trước khi tạo buổi test, không đổi giữa buổi phát. Không dùng `stack.py up` hoặc `compose.fixture.yml` của M0 để thay JWKS/callback sang port 18082 trong lượt nghiệm thu này.

**Kiểm tra đầu vào:** JWKS backend trả JSON có `keys`; Gateway/User/Channel hoạt động; container MediaMTX healthy. Không dùng endpoint Control API hoặc callback để tự gán LIVE.

## 2. Đăng nhập và chuẩn bị cookie

Dùng Postman hoặc API client tương đương:

```http
POST http://localhost:8888/user/api/auth/login
Content-Type: application/json
```

```json
{
  "username": "<USERNAME_TEST>",
  "password": "<PASSWORD_TEST>"
}
```

**Mong đợi:** HTTP 200; response có Set-Cookie cho account. Lấy giá trị `access_token` của streamer cho các request Create/Update/End. Nếu dùng viewer khác, đăng nhập viewer để lấy access token riêng cho GET metadata và POST playback-access.

Các ví dụ sau dùng biến local của API client:

```text
ownerAccessToken  = access token của streamer
viewerAccessToken = access token của viewer
sessionId        = session B sẽ tạo ở bước 3
categoryId       = category ACTIVE
requestId        = UUID tạo một lần cho lần Create này
```

Mỗi request chỉ gửi đúng một `access_token`. Tránh cookie tự động trong cookie jar làm lẫn owner/viewer hoặc khiến case không đăng nhập vẫn gửi cookie.

Tạo file cookie cho fixture, chỉ chứa account viewer:

```sh
mkdir -p .local/streaming
(umask 077; touch .local/streaming/account-cookie.txt)
chmod 600 .local/streaming/account-cookie.txt
```

Mở file bằng editor, nhập đúng một dòng:

```text
access_token=<VIEWER_ACCESS_TOKEN>
```

Không thêm `set-cookie:`, `Cookie:`, `Path`, `Expires`, `HttpOnly`, `SameSite` hoặc refresh token. Không đưa token vào shell history, báo cáo hay ảnh gửi review. Khi cookie hết hạn hoặc đã logout, cập nhật file rồi khởi động lại fixture vì cookie được đọc lúc process bắt đầu.

## 3. Tạo session B qua API thật

Nếu chưa có category ID, lấy từ dữ liệu test đã dùng hoặc API:

```http
GET http://localhost:8888/channel/api/categories
Cookie: access_token={{ownerAccessToken}}
```

Sinh một UUID cho `requestId` (có thể dùng `uuidgen`), lưu lại để giữ nguyên nếu retry. Sau đó gọi:

```http
POST http://localhost:8888/stream/api/streams
Cookie: access_token={{ownerAccessToken}}
Content-Type: application/json
```

```json
{
  "requestId": "{{requestId}}",
  "title": "Phase 5 acceptance - B",
  "categoryId": "{{categoryId}}"
}
```

**Mong đợi:** HTTP 201 lần đầu (200 khi replay cùng request ID), `Cache-Control: no-store` và `result` có:

- `session.id`: lưu thành `sessionId`, đây là B.
- `session.status = READY`, `visibility = PUBLIC`.
- `publishCredentials.serverUrl`, `streamKey`, `publishExpiresAt`.

Copy nguyên credential vào OBS ở bước 6. Không lấy stream key từ nút fixture M0. Nếu Create trả 409 vì channel còn session active, kiểm tra `GET /stream/api/streams/me/current` bằng owner; chỉ tiếp tục buổi test đúng hoặc chủ động End buổi test cũ, không sửa DB để vượt slot.

## 4. Kiểm tra khi session còn READY

Trước khi Start OBS, dùng viewer gọi trực tiếp hai API:

```http
GET http://localhost:8888/stream/api/streams/{{sessionId}}
Cookie: access_token={{viewerAccessToken}}
```

**Mong đợi:** HTTP 200, `Cache-Control: no-store`, `result.status = READY`. Metadata có ID/title/category/channel/time, không có `playbackUrl`, `readToken`, `streamKey` hoặc publish credential.

```http
POST http://localhost:8888/stream/api/streams/{{sessionId}}/playback-access
Cookie: access_token={{viewerAccessToken}}
```

Không cần body. **Mong đợi:** HTTP 409, code 1009, không cấp Read JWT. GET metadata thành công không có nghĩa đã được xem video.

## 5. Khởi động lại fixture và chọn B

Dừng Vite/web dev server đang dùng port 5173 để fixture có thể dùng đúng origin đã allowlist. Không đổi HLS CORS sang wildcard. Chạy:

```sh
python3 scripts/testing/streaming/fixture.py serve \
  --port 5173 \
  --account-cookie-file .local/streaming/account-cookie.txt \
  --gateway-base-url http://localhost:8888
```

Nếu fixture cũ đang chạy, Ctrl+C rồi chạy lại để nạp code/cookie mới. Mở hoặc reload `http://localhost:5173` bằng Chrome/Edge desktop.

Trong mục **2. Kiểm chứng HLS trên browser**:

1. Chọn **Backend account thật (giai đoạn 5)**.
2. Nhập UUID của B vào **Session ID backend**.
3. Bấm **Đọc metadata backend**.
4. Kiểm tra ID đúng B, status READY; khung trạng thái/acceptance phải ghi đúng backend session B.
5. Bấm **Mở phiên xem mới** khi READY: phải báo HTTP 409, chưa phát được video.

Trong backend mode, các nút tạo/xóa path và copy key M0 bị tắt. Đó là hành vi đúng. Chọn sai B hoặc chưa nhập ID không được âm thầm hiển thị acceptance của A.

Lưu ý Network ở browser thấy request `/fixture/backend-metadata`, `/fixture/backend-access`, `/fixture/status` và `/fixture/acceptance`. Fixture server dùng cookie trong file để gọi tiếp Gateway; không phải mọi request `/stream/api/...` đều xuất hiện trực tiếp trong browser Network.

## 6. Cấu hình OBS và bắt đầu phát

1. Dùng profile/scene test riêng. Có thể tạo video mẫu bằng `python3 scripts/testing/streaming/generate_test_video.py`, thêm `.local/streaming/obs-test.mp4` làm Media Source và bật Loop để có chuyển động/âm thanh.
2. OBS → Settings → Stream → Service **Custom**.
3. Server: dán `serverUrl` từ Create API.
4. Stream Key: dán nguyên `streamKey` từ Create API, gồm session ID và JWT. Không ghép thêm `live/` hoặc `?token=`.
5. Dùng H.264 + AAC, keyframe interval khởi điểm 2 giây. Kiểm tra audio source có tín hiệu và không bị mute.
6. Bấm **Start Streaming**.
7. Chờ metadata của B chuyển READY → LIVE qua polling/GET; OBS có bitrate và thời gian phát tăng.
8. Trong fixture, bấm **Ghi nhận: tôi đã phát thành công từ OBS**.

**Mong đợi:** source online; OBS observation mang `accessSource=backend`, `sessionId=B`, backend status LIVE và source của B. Có thể ghi nhận OBS trước khi mở player, không cần cấp Read JWT để ghi OBS.

Nếu OBS báo phát nhưng metadata vẫn READY, kiểm tra URL callback MediaMTX, khả năng container gọi Stream Service và log handler; không chuyển sang M0 hoặc tự sửa status DB để đi tiếp.

## 7. Kiểm tra playback access khi LIVE

Dùng viewer gọi POST playback-access trực tiếp như bước 4, không cần một GET ngay trước đó.

**Mong đợi:** HTTP 200, `Cache-Control: no-store`, `result` có:

```text
sessionId = B
playbackUrl = http://localhost:8898/live/B/index.m3u8
readToken = Read JWT
readExpiresAt = thời hạn token
```

URL và JWT tách riêng; URL không có token. Read JWT có quyền read đúng B và TTL mặc định 5 phút. Viewer không cần là owner/streamer.

Trong API client, giữ URL/token của lần cấp này ở biến local nếu cần test expiry. Không export các biến credential vào collection hoặc tài liệu chia sẻ.

## 8. Xem hình và nghe tiếng trên browser

1. Giữ OBS đang phát B, fixture vẫn chọn Backend account thật và đúng B.
2. Bấm **Mở phiên xem mới**.
3. Nếu browser chặn autoplay, bấm Play trên video.
4. Xem ít nhất 5–10 giây: hình có chuyển động, audio nghe được, frames/seconds/playlist/fragment tăng.
5. Tích **Tôi đã nghe được tiếng từ video test** chỉ khi thực sự nghe được.
6. Bấm **Lưu kết quả browser đang hiển thị** khi nguồn vẫn online.

**Mong đợi:** browser observation của B được lưu, acceptance `browser` và `obs-publish` chuyển PASS. `obs-reconnect`/`obs-end` còn PENDING ở thời điểm này là đúng.

Mỗi lần poll trả LIVE không được tự mở thêm player hoặc xin thêm JWT. Fixture chỉ tự đọc metadata/status; người dùng bấm mở phiên HLS. Khả năng tự mở/recovery đầy đủ của trang watch production thuộc Phase 6.

## 9. Kiểm tra Network và polling

Giữ trang hiển thị và OBS đang phát. Mở DevTools → Network:

| Quan sát | Mong đợi |
|---|---|
| Các request metadata/status fixture | Lặp định kỳ, cập nhật B; không chứa Read JWT trong response metadata |
| `/fixture/backend-access` | Chỉ xuất hiện khi bấm mở phiên xem mới, không lặp theo polling |
| Request playlist `.m3u8`, init và segment | Đi tới MediaMTX `localhost:8898`, không qua Gateway/Java |
| Header HLS | `Authorization: Bearer ...`; không có account cookie |
| URL HLS | Không chứa Read JWT; query định danh phiên HLS do MediaMTX tạo có thể tồn tại |
| CORS | Origin fixture `http://localhost:5173` được cho phép; playlist/segment đọc được |
| Token/account | Account cookie chỉ dùng phía fixture server → Gateway; browser không nhận account cookie từ fixture |

Kiểm tra response metadata và playback-access có `Cache-Control: no-store`. Có thể kiểm tra CORS preflight độc lập khi B đang LIVE:

```sh
curl -i -X OPTIONS 'http://localhost:8898/live/<B_UUID>/index.m3u8' \
  -H 'Origin: http://localhost:5173' \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: authorization'
```

**Mong đợi:** preflight thành công, `Access-Control-Allow-Origin` khớp origin và cho phép header Authorization. Chạy lại với `Origin: http://localhost:5174`: không được cấp allow-origin cho origin đó. Không yêu cầu HTTP 403 ở case này; browser chặn vì không có CORS permission phù hợp.

Fixture có vòng status và metadata riêng, nên số GET có thể nhiều hơn một request/3 giây. Tiêu chí chính là không cấp JWT theo mỗi lần poll.

Ẩn tab vài giây rồi quay lại: polling chỉ chạy khi tab hiển thị. Không coi sự thay đổi nguồn OBS là hậu quả của việc ẩn tab. Bấm **Dừng player** chỉ dừng xem; GET của B và OBS vẫn phải cho thấy LIVE.

## 10. Kiểm chứng helper và artifact

Khi B đang LIVE, có thể chạy thêm kiểm tra HTTP/codec độc lập:

```sh
python3 scripts/testing/streaming/inspect_playback.py \
  --session-id '<B_UUID>' \
  --access-mode backend \
  --account-cookie-file .local/streaming/account-cookie.txt
```

**Mong đợi:** source online, playlist/init/segment HTTP 200, decode audio/video PASS, playlist tiến triển và `tokenSource` là backend playback-access. Report/sample nằm trong `.local/streaming/playback-check/<B_UUID>/`.

Helper PASS không thay việc xem và nghe trên Chrome/Edge. Các bản ghi browser/OBS nằm trong `.local/streaming/manual-results.jsonl`; chỉ đối chiếu record `sessionId=B`, `accessSource=backend`.

## 11. Kiểm tra lỗi authentication và request

Giữ B ở LIVE. Thực hiện các request độc lập trong API client:

| Case | Cách thử | Mong đợi |
|---|---|---|
| Không đăng nhập | GET và POST không Cookie, không cookie jar tự động | 401/code 1001, không có credential |
| Account token sai | Dùng access cookie không hợp lệ cho GET/POST | 401; không nhầm với lỗi Read JWT của MediaMTX |
| UUID sai | Thay ID bằng `not-a-uuid` | 400/code 1004 |
| Không tồn tại | Dùng UUID hợp lệ khác chưa tạo | 404/code 1006 |
| POST trực tiếp | Gọi playback-access cho B mà không GET trước | 200 nếu vẫn PUBLIC/LIVE |
| Viewer không phải owner | Gọi GET/POST B bằng viewer thường | Được xem PUBLIC/LIVE |
| Viewer gọi End | Gọi End B bằng account không phải owner | 403; B vẫn LIVE |

PRIVATE/UNLISTED không nằm trong chức năng tạo stream MVP. Guard 403 được kiểm tra qua test service/integration; không sửa DB ứng dụng để giả dữ liệu private phục vụ lượt test tay này.

## 12. Kiểm tra JWT media sai trên đúng session B

Dropdown **Trường hợp token xem** chỉ áp dụng khi chọn fixture M0. Trong backend mode, nút mở player luôn lấy access hợp lệ từ API. Không chuyển sang M0 rồi lấy kết quả của A để kết luận auth của B.

Để thử cả năm case trên đúng URL B đang LIVE, dùng terminal với dev key đã khớp backend. Script dưới chỉ gọi đọc HLS, không sửa path/session và không in JWT. Thay `<B_UUID>`:

```sh
python3 - <<'PYTEST'
import sys
from uuid import UUID, uuid4
sys.path.insert(0, 'scripts/testing/streaming')
import fixture
from backend_access import BackendClient, load_cookie
from inspect_playback import fetch
session = str(UUID('<B_UUID>'))
client = BackendClient('http://localhost:8888', load_cookie('.local/streaming/account-cookie.txt'))
access = client.acquire(session)
state = fixture.DEFAULT_STATE
cases = {
    'missing': None,
    'wrong-action': fixture.issue(state, 'publish', access.path, 300),
    'wrong-path': fixture.issue(state, 'read', 'live/' + str(uuid4()), 300),
    'wrong-signature': fixture.corrupt_signature(access.read_token),
    'expired-test-token': fixture.issue(state, 'read', access.path, -1),
}
failed = []
for name, token in cases.items():
    status = fetch(access.playback_url, token)[0]
    passed = status in (401, 403)
    print(name, status, 'PASS' if passed else 'FAIL')
    if not passed:
        failed.append(name)
raise SystemExit(1 if failed else 0)
PYTEST
```

**Mong đợi:** cả năm case bị MediaMTX từ chối 401/403. Các JWT test sai action/path/expiry dùng dev signer; happy case vẫn dùng JWT backend cấp. Script không tự ghi PASS cho M5.

## 13. Kiểm tra expiry JWT backend thật

Làm khi B vẫn LIVE và trước khi End:

1. Cấp một Read JWT bằng POST playback-access trong API client; giữ nguyên token đó và `readExpiresAt` ở biến local.
2. Mở `playbackUrl` trong API client bằng `Authorization: Bearer <token đó>`: HTTP 200.
3. Mở player fixture, giữ trang hiển thị và video đang tải liên tục. Ghi nhận `readExpiresAt` của response `/fixture/backend-access` mà player nhận, không chỉ expiry của token API client.
4. Chờ qua expiry (mặc định 5 phút kể từ lúc cấp); không bấm mở lại player hoặc tự thay token trong lúc quan sát.
5. Phiên browser đang tải liên tục không được mặc định coi là phải dừng chỉ vì JWT hết hạn.
6. Sau expiry, API client gọi lại **URL master ban đầu** bằng token cũ, không dùng URL playlist con chứa định danh HLS session: phải bị 401/403 vì đây là mở phiên mới.
7. Gọi POST lấy token mới hoặc bấm mở phiên xem mới trong fixture: được xem lại nếu B vẫn LIVE.

Phân biệt token cũ và token mới: bấm mở phiên mới trong fixture sẽ tự xin JWT mới, không kiểm tra được việc dùng lại JWT hết hạn. Một phiên để nhàn rỗi suốt 5 phút có thể đã bị dọn; không dùng nó để kết luận về phiên đang xem liên tục.

## 14. Thử ngắt nguồn và reconnect trong grace

1. B đang LIVE; đã ghi OBS publish và browser evidence.
2. Trong OBS bấm **Stop Streaming**, giữ nguyên server/key.
3. Chờ GET metadata/status của B chuyển sang RECONNECTING, có `reconnectDeadlineAt`.
4. Trong API client gọi POST playback-access trực tiếp: HTTP 409/code 1009.
5. Bấm **Start Streaming** lại trước deadline (mặc định 60 giây). Không cần đổi key khi Publish JWT vẫn còn hạn.
6. Chờ metadata trở lại LIVE; source connection ID phải khác lần đầu, `startedAt` giữ nguyên.
7. Bấm **Ghi nhận: OBS đã nối lại bằng key cũ** trong fixture khi vẫn chọn B.
8. Nếu player fixture chưa tự phát tiếp, bấm **Mở phiên xem mới**, xác nhận hình/tiếng và lưu browser evidence mới.

**Mong đợi:** cùng B chuyển LIVE → RECONNECTING → LIVE; POST access bị chặn khi RECONNECTING, dùng được khi LIVE; acceptance `obs-reconnect` PASS. Không yêu cầu fixture tự recovery như player production Phase 6.

Để thử mất mạng thật, có thể ngắt mạng của máy OBS riêng rồi nối lại trong grace. Nếu OBS/backend cùng một máy thì ngắt toàn bộ mạng có thể ảnh hưởng cả backend/callback; ưu tiên Stop/Start OBS cho lượt kiểm tra có kiểm soát.

## 15. Owner End và thử key cũ

Khi B đang LIVE, owner gọi:

```http
POST http://localhost:8888/stream/api/streams/{{sessionId}}/end
Cookie: access_token={{ownerAccessToken}}
```

**Mong đợi:** HTTP 200 sau khi media đã đóng; session ENDED. Nếu nhận 503, chưa coi End thành công; giữ thông tin lỗi để điều tra hoặc thử lại có chủ đích.

Sau End thành công:

1. GET metadata bằng viewer: HTTP 200, status ENDED, có `endedAt`.
2. POST playback-access bằng viewer: HTTP 409/code 1009.
3. OBS bị ngắt nguồn. Tự Stop rồi thử Start lại bằng credential cũ còn hạn: phải bị từ chối, B không trở lại LIVE.
4. Sau khi xác nhận OBS bị từ chối, bấm **Ghi nhận: OBS bị từ chối với key cũ sau đóng path** trong fixture, vẫn chọn B.
5. Khung trạng thái phải có source rỗng/path không còn cấu hình; acceptance `obs-end` PASS.
6. Bấm mở phiên xem mới: HTTP 409; nếu player cũ còn buffer vài giây thì không coi đó là stream được mở lại. Có thể bấm Dừng player để cleanup.
7. Gọi End lần hai bằng owner: 200, vẫn ENDED, không mở lại path hoặc tạo buổi phát mới.

Nút **Đóng path đang phát** của M0 không dùng cho backend B. Fixture hiện chỉ hiển thị trạng thái và hỗ trợ thao tác thủ công; không yêu cầu tự destroy player ngay khi GET nhận ENDED ở Phase 5.

## 16. Thử hết grace bằng session mới C

B đã ENDED không được hồi sinh. Tạo **C** qua Create với request ID mới, nhập credential mới vào OBS, đổi Session ID backend trong fixture sang C và phát để C LIVE.

1. Ghi OBS publish của C; kiểm tra dữ liệu không gắn với B hoặc A.
2. Stop OBS và giữ ngắt nguồn quá 60 giây cộng thời gian scheduler/Control API xử lý.
3. Quan sát LIVE → RECONNECTING → ENDED; POST access bị chặn từ RECONNECTING.
4. Chỉ coi kết thúc khi backend trả ENDED, không chỉ vì countdown frontend về 0.
5. Thử Start OBS bằng key C cũ: bị từ chối, không hồi sinh C.

Nếu MediaMTX/Control API lỗi, backend có thể còn RECONNECTING và trả lỗi; ghi kết quả thật, không tự sửa ENDED. Cần một lượt mới với session khác để kiểm tra lại; không tái sử dụng session đã kết thúc.

## 17. Kiểm tra evidence và chốt kết quả

Sau khi hoàn tất các thao tác trên B:

```sh
python3 scripts/testing/streaming/acceptance.py \
  --access-source backend \
  --session-id '<B_UUID>'
```

**Mong đợi:** `browser`, `obs-publish`, `obs-reconnect`, `obs-end` của đúng B đều PASS. Lệnh không có `--access-source backend` là kiểm tra M0; không dùng kết quả đó để chốt B. Evidence C được duyệt riêng bằng UUID C.

Có thể chọn B lại trên UI để duyệt evidence B sau khi đã test C. Không cần nhập lại credential OBS hoặc mở lại B. Bản ghi cũ thiếu context/gắn nhầm A phải được ghi nhận lại, không tự sửa history.

Lưu kết quả theo bảng, không kèm credential:

| Case | Session | Kết quả cần ghi |
|---|---|---|
| Create/READY | B | HTTP Create, metadata READY, POST access 409 |
| OBS online | B | LIVE, source ID, OBS publish evidence |
| Viewer watch | B | Chrome/Edge, hình chuyển động, nghe tiếng, browser evidence |
| Network/polling | B | Media đi trực tiếp MediaMTX; không account cookie; không POST lặp |
| Auth/request lỗi | B hoặc UUID test | 401/400/404/403 theo từng case |
| JWT media sai | B | Năm HTTP kết quả 401/403 |
| JWT backend hết hạn | B | Phiên hiện tại đang tải liên tục; mở HLS mới bằng token cũ bị chặn |
| Reconnect | B | RECONNECTING → LIVE, source ID mới, startedAt giữ nguyên |
| Owner End | B | ENDED, POST access 409, key cũ bị từ chối |
| Hết grace | C | RECONNECTING → ENDED, key cũ bị từ chối |
| Tách evidence | A/B/C | Acceptance đọc đúng session/backend source, không lẫn M0 |

Chỉ chốt STR-505/M5 sau khi review cả evidence browser/OBS và các case API/media. Các nhãn PASS của acceptance CLI chỉ đánh giá evidence thủ công, không tự bao phủ toàn bộ JWT/CORS/security và không tự cập nhật checkbox.

## 18. Regression tự động và cleanup

Các kiểm tra tự động có thể chạy riêng trước hoặc sau lượt thủ công:

```sh
(cd stream-service && ./mvnw clean verify)
python3 -m unittest discover -s scripts/testing/streaming -p 'test_*.py' -v
node --test scripts/testing/streaming/test_player.mjs
node --check scripts/testing/streaming/player.js
git diff --check
```

Cần Docker cho PostgreSQL Testcontainers. Không chạy Maven clean/test đồng thời với một tiến trình test khác đang dùng cùng `stream-service/target`. Ghi số test thực tế; không mặc định PASS từ kết quả lần trước.

Kết thúc:

1. Kiểm tra các session test còn active; owner End có chủ đích nếu cần dọn.
2. Tự Stop OBS và trả lại profile/scene ban đầu.
3. Ctrl+C fixture, đóng tab test.
4. Xóa file cookie test nếu không còn dùng; giữ report không credential để review.
5. Khởi động lại Vite nếu cần phát triển web-app. Không `docker compose down -v` hoặc xóa database để cleanup một lượt test.

## 19. Tra lỗi nhanh

| Triệu chứng | Kiểm tra |
|---|---|
| Port 5173 bận hoặc hiện web-app thay fixture | Dừng Vite/fixture cũ, chỉ giữ fixture test trên origin này, reload trang |
| Fixture báo file cookie không hợp lệ | File quyền 600, một dòng `access_token=...`, không chứa Set-Cookie attributes |
| Metadata 401/503 | Cookie/account còn hợp lệ, Redis authorities và các backend; cập nhật cookie rồi restart fixture nếu cần |
| OBS online nhưng session READY | Callback trỏ Stream Service thật, hook gọi được backend, kiểm tra log lifecycle |
| Metadata LIVE nhưng HLS 401 | Read JWT đúng session/action, bộ RSA/JWKS/issuer/audience khớp; không dùng account hoặc Publish JWT để đọc HLS |
| HLS 404 hoặc không có segment | Source/path thực tế, codec H.264/AAC và keyframe; nhớ chờ HLS tạo dữ liệu sau Start |
| Browser CORS lỗi nhưng HTTP client xem được | Đúng origin `http://localhost:5173`, allow-origin và Authorization preflight; không đổi sang bỏ auth |
| Metadata đọc được nhưng player báo `networkError/manifestLoadError; HTTP —` ở `127.0.0.1:5173` | Mở lại bằng `http://localhost:5173`, chọn backend session và mở phiên xem mới. Hai hostname là hai origin khác nhau; cấu hình HLS local chỉ cho phép origin localhost |
| Có hình không có tiếng | Audio source/meter OBS, mute/volume browser, autoplay, AAC track; chưa nghe được thì không tích xác nhận |
| Acceptance vẫn hiện A/STR-004–006 | Reload code fixture mới, chọn Backend account và đúng UUID B; CLI thêm `--access-source backend --session-id` |
| Backend evidence bị từ chối | Browser phải mở qua fixture để được cấp access trước khi lưu; OBS cần đúng context và trạng thái source thực tế |
| End xong player còn hiện hình vài giây | Có thể là buffer; kiểm tra metadata ENDED và mở HLS mới/POST access, không chỉ nhìn frame cuối |
