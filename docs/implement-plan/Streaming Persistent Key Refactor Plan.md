# Refactor livestream: Stream Key cố định, OBS tự bắt đầu và kết thúc buổi phát

## 1. Mục tiêu và các quyết định đã chốt

Thay thế luồng tạo session thủ công bằng luồng:

Lần đầu:
Mở Stream Manager → lưu Title/Category → nhận key → cấu hình OBS

Các buổi sau:
Cập nhật Title/Category khi cần → Start Streaming trong OBS

Kết thúc:
Stop Streaming trong OBS
→ RECONNECTING trong 60 giây
→ nối lại trong hạn: tiếp tục session cũ
→ quá hạn: kết thúc session; lần phát tiếp theo tạo session mới

Các lựa chọn bắt buộc:

- Giữ MediaMTX 1.21.2, một instance, RS256/JWKS và authMethod: jwt.
- Publish JWT không có exp, dùng lại cho tới khi reset key.
- Read JWT của viewer/thumbnail và Control JWT tiếp tục có TTL 5 phút.
- Tách ingest khỏi playback, không dùng chung HLS qua nhiều session:

  OBS
  │ Publish JWT cố định
  ▼
ingest/{ingestKeyId}
  │ MediaMTX pull RTMP nội bộ, không transcoding
  ▼
live/{sessionId}
  │ Read JWT theo session
  ▼
HLS → Viewer / Preview / Thumbnail
- Bỏ Create stream, Create new stream, End stream trên web. Người dùng bắt đầu/ngừng phát trong OBS.
- Reset key kết thúc buổi hiện tại; lần phát bằng key mới tạo session mới.
- Chọn phương án A cho category: config được tạo khi mở dashboard, nhưng chưa cấp key hoặc mở ingest path trước lần Save hợp lệ đầu tiên.
- Có tiến trình phục hồi được lưu trong PostgreSQL cho lỗi MediaMTX, callback bị lỡ và reset dang dở.
- Chỉ kiểm tra eligibility khi chuẩn bị phát và khi xử lý nguồn phát mới/reconnect. Không triển khai ban/unban giữa buổi, kiểm tra ban định kỳ hoặc UI quản trị trong đợt này.
- Phạm vi phát vẫn là PUBLIC. Không mở rộng PRIVATE/UNLISTED trong refactor.

Khác với plan cũ: giữ UNIQUE(stream_sessions.playback_path), giữ URL /live/{sessionId}/index.m3u8, không nới regex của player/thumbnail worker sang ingest ID.

## 2. Database, credential và API

### Database và trạng thái

Tạo migration tiếp theo, dự kiến V5, không sửa migration đã tồn tại.

channel_stream_configs

- Một row/channel, unique theo owner và streamer.
- Lưu title, category nullable khi chưa setup, visibility PUBLIC, current ingest key ID và generation.
- Trạng thái: DRAFT, PROVISIONING, ACTIVE, ROTATING, BLOCKED.
- Có media_revision tăng khi thay đổi ý định điều khiển media; không dùng JPA optimistic locking.
- Mọi thao tác lifecycle/media của channel đều khóa row config trước, sau đó mới khóa session.

channel_stream_credentials

- Lưu từng thế hệ credential: ingest key ID, channel, generation, trạng thái PENDING/ACTIVE/REVOKED, thời điểm cấp/thu hồi.
- Lưu Publish JWT đã cấp dưới dạng mã hóa, để GET luôn trả đúng chuỗi key đã cấp.
- Credential REVOKED không bao giờ được kích hoạt lại.
- Dùng AES-256-GCM với secret từ environment; không lưu secret trong repository.
- Publish JWT chỉ có quyền publish vào ingest/{ingestKeyId}.
- Credential kỹ thuật dùng cho relay chỉ có quyền read vào đúng ingest path, không có exp, không cấp cho frontend. Lưu mã hóa cùng thế hệ credential.
- Read JWT của viewer không có quyền đọc ingest/*.
- Giữ signing key qua các lần deploy; không tự sinh lại RSA key lúc khởi động. Thay signing/encryption key là thao tác vận hành riêng, phải được tài liệu hóa.

stream_sessions

- Thêm ingest_key_id để snapshot nguồn ingest của buổi phát; nullable cho lịch sử cũ.
- playback_path vẫn unique và luôn là /live/{sessionId}/index.m3u8.
- Cho creation_request_id nullable ở cả SQL và Entity; bỏ logic phụ thuộc request tạo session thủ công.
- Giữ constraint một session hoạt động/channel.
- Không tạo session mới ở READY. Session mới được ghi LIVE sau khi xác nhận ingest và playback sẵn sàng.
- media_path_state chỉ mô tả playback path của session, không mô tả ingest path của channel.
- Điều chỉnh constraint để RECONNECTING có thể có playback path OPEN hoặc CLOSED; LIVE phải OPEN, ENDED phải CLOSED.
- Thêm thông tin yêu cầu kết thúc để reset/cleanup không thể bị một ONLINE đến trễ hồi sinh session.

stream_media_operations

- Lưu các sự kiện/tiến trình media để xử lý lặp an toàn và phục hồi sau crash.
- Có operation ID, channel, credential generation, media revision, loại thao tác, target session/ingest/source, payload, trạng thái, số lần thử, lần thử tiếp theo và lease.
- Payload không chứa JWT thô.
- Trạng thái: PENDING, RUNNING, SUCCEEDED, CANCELLED.
- Các loại thao tác: xử lý ONLINE/OFFLINE, provision ingest, đóng playback, kết thúc session, reset key.
- Unique deduplication key cho event ID; reset dùng idempotency key.
- Khi bắt đầu một buổi mới, giữ candidate session ID cố định trong operation để retry không sinh nhiều session.
- Mỗi channel chỉ có một candidate tạo session chưa hoàn tất.

### Public API

Tất cả response chứa credential phải dùng Cache-Control: no-store và không để token lọt vào log/toString.

| API qua Gateway | Hành vi |
|---|---|
| GET /stream/api/streams/me/setup | Lazy tạo config bằng cơ chế xử lý insert conflict; trả setup và credential hiện hành nếu đủ điều kiện |
| PUT /stream/api/streams/me/setup | Validate và lưu title/category; đồng bộ session LIVE/RECONNECTING nếu có |
| POST /stream/api/streams/me/stream-key/reset | Nhận Idempotency-Key, khởi tạo tiến trình reset, trả 202 và operation ID |
| GET /stream/api/streams/me/operations/{operationId} | Owner kiểm tra tiến trình reset/provision |
| GET /stream/api/streams/me/current | Trả session hiện tại hoặc null; không giả định media path chứa session ID ở phía ingest |
| Hai API metadata/playback hiện tại | Giữ contract theo session ID |

StreamSetupResponse gồm:

- channelId, title, categoryId, visibility.
- status, generation, operationId nếu đang xử lý.
- serverUrl, streamKey, publishReady.
- streamKey = null khi DRAFT, PROVISIONING, ROTATING hoặc BLOCKED.

Quyền:

- GET setup: STREAM_VIEW_OWN và STREAM_PUBLISH.
- PUT setup: STREAM_UPDATE và STREAM_PUBLISH.
- Reset: STREAM_PUBLISH.
- Mọi API kiểm tra ownership ở service.

GET setup không tự gọi ensurePath từ dữ liệu đọc trước đó. Nó chỉ tạo config lần đầu, đọc trạng thái hoặc đăng ký yêu cầu phục hồi được kiểm soát bằng lock/generation.

Gỡ các public mapping cũ:

- POST /api/streams.
- POST /api/streams/{sessionId}/publish-credentials.
- PATCH /api/streams/{sessionId}.
- POST /api/streams/{sessionId}/end.

Không để client cũ tiếp tục tạo READY hoặc cập nhật metadata ngoài cấu hình channel. Sau cutover, các route này không được còn side effect.

### Internal API

- Chuyển media callback sang POST /stream/internal/api/media-events.
- Bảo vệ bằng internal token riêng; không dùng account cookie hoặc Publish JWT làm credential webhook.
- Không expose route này qua Gateway.
- ACK 202 chỉ sau khi event đã được lưu bền vững; event lặp trả kết quả thành công, không tạo operation thứ hai.
- Response cho phép sessionId/status vắng mặt; không dereference session khi event bị bỏ qua hoặc chưa tạo session.

## 3. MediaMTX, lifecycle và phục hồi

### Cấu hình hai loại path

Giữ paths: {}, không dùng catch-all.

Ingest path

ingest/{ingestKeyId}
source: publisher
overridePublisher: false

- Chỉ ingest path chạy hook ONLINE/OFFLINE.
- Không dùng ingest path cho CCU hoặc cấp playback URL cho người xem.
- Server URL OBS mới có suffix /ingest.
- Stream key: {ingestKeyId}?token={PublishJWT}.

Playback path

live/{sessionId}
source: rtmp://127.0.0.1:1935/ingest/{ingestKeyId}?token={RelayReadJWT}
sourceOnDemand: false

- Dùng RTMP pull có sẵn trong MediaMTX; không thêm FFmpeg/transcoding vào luồng production.
- Tắt hook lifecycle ONLINE/OFFLINE trên playback path để tránh vòng lặp tạo session.
- Chỉ playback path chạy viewer READ/UNREAD hook.
- JWT relay là credential nội bộ, không dùng lại Publish JWT hoặc viewer JWT.
- Adapter phân biệt publisher observation (rtmpConn/rtmpsConn, connection UUID) với playback observation (rtmpSource); không ép source ID của static source thành publisher UUID.

MediaMTX có triển khai RTMP static source đọc từ URL; đây là cơ sở cho chuyển tiếp nội bộ. Mã nguồn phiên bản 1.21.2 (https://github.com/bluenviron/mediamtx/blob/v1.21.2/internal/staticsources/rtmp/source.go)

### Provision lần đầu và lưu thông tin

1. GET đầu tiên tạo config DRAFT bằng insert/upsert có xử lý conflict; không dựa vào lock một row chưa tồn tại.
2. Save đầu tiên kiểm tra owner, streamer/channel/category và title.
3. Lưu credential một lần, chuyển PROVISIONING và ghi operation provision trong cùng transaction.
4. Worker tạo và xác nhận ingest path đúng cấu hình.
5. Chuyển ACTIVE; UI lấy lại setup và hiển thị key.
6. Save các lần sau giữ nguyên key; cập nhật config và session đang hoạt động dưới cùng lock.

### ONLINE và tạo session

1. Lưu event, xác định credential generation, rồi xử lý dưới lock config.
2. Bỏ qua event thuộc key đã thu hồi hoặc source không còn là nguồn hiện tại.
3. Xác minh ingest online, đúng source ID/type; đọc cả thời điểm online của nguồn.
4. Hậu kiểm eligibility:
   - Lỗi nghiệp vụ: chặn tạo/resume, kick đúng nguồn, đóng ingest, đưa config BLOCKED.
   - Timeout/503 dependency: retry; không coi lỗi transport là bị ban hoặc thu hồi credential.
5. Chọn session:
   - Đang LIVE và cùng source: idempotent.
   - RECONNECTING, chưa có yêu cầu kết thúc, nguồn trở lại trước deadline: dùng lại session.
   - RECONNECTING đã hết hạn: hoàn tất đóng playback/kết thúc session cũ trước khi tạo session mới.
   - Chưa có session: cấp candidate session ID một lần trong operation.
6. Tạo/khôi phục playback path cho đúng session, xác nhận playback online và ingest vẫn là nguồn đã kiểm tra.
7. Trong transaction cuối, recheck generation/revision, snapshot title/category và ghi session LIVE/history. Chỉ lúc này session mới được public qua discovery/current.

Không tạo session LIVE cho một nguồn đã biến mất trong lúc provision. Nếu operation bị hủy hoặc rollback, reconciler xóa playback path mồ côi.

### OFFLINE và grace period

- Ghi nhận sự kiện của đúng publisher, không để OFFLINE cũ làm ngắt nguồn mới.
- Chuyển LIVE → RECONNECTING, giữ session ID và startedAt.
- Deadline bằng thời điểm offline đáng tin cậy cộng 60 giây; event lặp không gia hạn deadline.
- Đóng playback path khi offline để relay cũ không tự nhận nguồn mới ngoài quyết định của backend.
- Resume trong hạn tạo lại playback path cùng session ID; player mở phiên HLS mới khi cần.
- Hết hạn: yêu cầu đóng session, xác nhận playback path đã đóng rồi ghi ENDED, cleanup CCU và thumbnail pending.
- Giữ ingest path ACTIVE cho lần phát tiếp theo.
- Không kick một publisher mới chỉ vì nó đang phát trên cùng ingest path. Chỉ kick connection được xác định thuộc thao tác cần đóng.

ONLINE handler và expiration scheduler dùng cùng quy tắc deadline, lock và generation. Không phụ thuộc scheduler chạy trước hay sau ONLINE.

### Reset key

1. Transaction khóa config, kiểm tra idempotency key.
2. Đánh dấu credential cũ REVOKED, lưu credential thế hệ mới PENDING, chuyển config ROTATING và ghi operation.
3. Chặn resume/session creation theo thế hệ cũ; ghi yêu cầu kết thúc session hiện tại.
4. Operation đóng playback cũ, kick đúng publisher cũ, xóa ingest path cũ và xác nhận đã vắng mặt.
5. Hoàn tất session cũ thành ENDED.
6. Tạo/xác minh ingest path mới; chuyển credential/config sang ACTIVE.
7. Chỉ sau bước này operation mới SUCCEEDED và UI nhận key mới.

Nếu MediaMTX lỗi, giữ ROTATING và retry; không rollback về credential đã thu hồi. Trả 202 nghĩa là đang xử lý, không khẳng định media đã bị ngắt thành công.

Reset lặp cùng idempotency key trả cùng operation. Reset khác trong lúc đang ROTATING trả operation đang chạy, không tạo thêm thế hệ key.

### Worker, reconciliation và lỗi

- Worker PostgreSQL chạy mỗi giây; dùng lease 60 giây, mỗi lượt xử lý có budget tối đa 20 giây.
- Retry lỗi transport với backoff 1, 2, 5, 10, tối đa 30 giây.
- Trước mỗi media mutation phải kiểm tra generation, revision và desired state. Task cũ bị cancel hoặc tính lại, không được mở lại path đã thu hồi/ENDED.
- Reconciliation chạy khi backend khởi động và mỗi 5 giây:
  - Hoàn tất operation dang dở.
  - Xóa ingest bị thu hồi và playback ENDED/mồ côi trước khi phục hồi path.
  - Đăng ký lại ingest ACTIVE sau MediaMTX restart.
  - Đối chiếu nguồn thực tế để bù ONLINE/OFFLINE bị lỡ, đi qua cùng lifecycle handler.
- Phát hiện MediaMTX restart qua Control API info. Nếu không xác định được khoảng ngắt của publisher cũ, kết thúc session cũ và tạo session mới khi OBS nối lại; không đoán rằng vẫn trong grace.
- Không phục hồi playback của session đã ENDED.
- Không thực hiện polling ban/eligibility đối với nguồn LIVE đã được chấp nhận. Config BLOCKED chỉ được mở lại qua Save hợp lệ.
- Hook dùng timeout hữu hạn, tối đa ba lần gửi trong 10 giây, giữ nguyên event ID/body khi retry. Reconciler chịu trách nhiệm khi hết retry.
- Log chỉ chứa operation/event/channel/session/source ID và lỗi đã làm sạch; không log JWT, source URL chứa token hoặc response body nhạy cảm.

## 4. Frontend, kiểm thử và triển khai

### Frontend và các consumer

- Form title/category luôn dùng Save; giữ dữ liệu người dùng đang sửa khi query refetch.
- Hiển thị trạng thái DRAFT/đang provision/đang reset/BLOCKED bằng thông báo sản phẩm đơn giản.
- OBS panel chỉ Copy key hiện hành và Reset có xác nhận.
- Giữ key qua ENDED; chỉ xóa cache credential khi logout/đổi account hoặc bị thay thế bởi generation mới.
- Không persist credential vào localStorage hay cache bền vững.
- Poll /me/current mỗi 3 giây khi tab hiển thị, kể cả khi chưa có session; không dựa vào snapshot LIVE cũ để ngừng phát hiện session mới.
- Query/cache phải có user ID và session ID phù hợp; response generation cũ không được ghi đè kết quả reset mới.
- Khi phát hiện session mới, chuyển preview/chat/CCU sang session mới.
- Khi RECONNECTING, đóng player cũ; khi LIVE trở lại, mở lại playback access.
- Nếu player gặp lỗi media tạm thời dù polling chưa kịp thấy RECONNECTING, refresh metadata và thử mở lại có giới hạn; không bắt người dùng refresh trang.
- Chỉ cập nhật trạng thái LIVE trên UI sau khi backend commit; độ trễ hiển thị mục tiêu ≤3 giây sau commit, chưa tính buffer HLS.

Vì playback vẫn theo session:

- Giữ kiểm tra URL chính xác của StreamPlayer.
- Giữ kiểm tra /live/{sessionId}/index.m3u8 của media-worker.
- Giữ CCU Redis theo session ID; viewer hook chỉ nhận live/*, bỏ qua technical readers.
- Thumbnail vẫn dùng session ID/job ID và Read JWT theo live/{sessionId}. Kết quả job của session đã kết thúc bị bỏ qua.
- Không áp dụng phần mapping ingest ID sang session cho viewer/thumbnail trong plan cũ.

### Kiểm thử bắt buộc

| Nhóm | Trường hợp phải chứng minh |
|---|---|
| Media thực tế | RTMP → ingest → RTMP pull nội bộ → HLS hoạt động trên MediaMTX 1.21.2; relay không gây vòng lặp hook |
| Credential | Cùng key qua reload, Save và nhiều session; Publish JWT thiếu exp vẫn được verify; Read/Control JWT vẫn hết hạn |
| Phân quyền | Publish JWT không đọc media; viewer JWT không đọc ingest hoặc session khác; technical credential không lộ qua API |
| Setup | Hai GET đầu tiên đồng thời; chưa Save category không có key/path; Save lại không đổi key |
| Lifecycle | Start tự tạo LIVE; Stop; resume trước deadline; đúng deadline; quá hạn khi scheduler chạy chậm |
| Thứ tự sự kiện | ONLINE/OFFLINE lặp, đảo thứ tự, source cũ, mất hook, source đổi trong lúc provision |
| Session isolation | Token S1 không đọc live/S2; sau S1 ENDED, path S1 bị đóng và không được phục hồi |
| Reset | Reset LIVE/RECONNECTING/offline; GET setup đồng thời; reset lặp; callback key cũ; không còn session LIVE bị kẹt |
| Partial failure | Lỗi/timeout ở từng bước Control API, crash trước/sau DB commit, lease hết hạn; recovery không mở lại key cũ |
| Restart | Restart backend/MediaMTX; ingest được khôi phục; key OBS giữ nguyên; không hồi sinh playback cũ |
| Frontend | Hai buổi liên tiếp không reload dashboard; reconnect nhanh hơn polling; form dirty; đổi account |
| Consumer | CCU đúng session, không đếm relay; thumbnail không áp dụng kết quả cũ |

Các lệnh kiểm chứng tối thiểu:

# Trong stream-service
./mvnw clean verify

# Trong media-worker
npm run build
npm run lint
npm test
npm run test:e2e

# Trong web-app
npm test
npm run lint
npm run build
npm run test:streaming

# Từ repository root
python3 -m unittest discover -s scripts/testing/streaming -p 'test_*.py'

Bổ sung integration test bằng MediaMTX thật và publisher fixture trong stack cô lập; chỉ dùng FFmpeg ở fixture nếu cần phát video mẫu. Mock test không thay thế nghiệm thu media thực tế.

### Tài liệu và cutover

- Lưu plan mới tại docs/implement-plan/Streaming Persistent Key Refactor Plan.md.
- Cập nhật docs/architecture/Streaming Architecture and Flow.md, data model, API/README và cấu hình mẫu liên quan.
- Mermaid trong tài liệu là nguồn chuẩn. Thay các sơ đồ cũ không còn đúng; không xóa ảnh hoặc thay đổi chưa liên quan của người dùng.
- Migration kiểm chứng trên DB cô lập, gồm lịch sử ENDED hiện có.
- Cutover yêu cầu không có session READY/LIVE/RECONNECTING hoặc publisher đang phát; nếu còn thì preflight/migration từ chối, không tự kết thúc buổi live.
- Giữ lịch sử session/history. Không tự chạy migration trên database ứng dụng.
- Không rollback bằng cách đưa binary cũ chạy trên dữ liệu/luồng mới; rollback cần maintenance và bản sao lưu hoặc forward-fix đã kiểm chứng.
- Theo dõi số operation pending, tuổi operation, retry, lỗi reconciliation, callback delay và thời gian provision/reset. Không gắn token làm metric label.
