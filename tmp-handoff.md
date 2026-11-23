# Handoff: Notification issue #45

## 1. Nhiệm vụ hiện tại

- Người gửi: `opencode-1`.
- Người nhận: `codex-1` trong Agent Team.
- User yêu cầu tổng hợp đầy đủ lịch sử thảo luận và chuyển ngữ cảnh, chưa yêu cầu triển khai tính năng.
- Đây là nhiệm vụ chỉ đọc. Không sửa file, issue, cấu hình, không commit, không migration và không dispatch ngược lại.
- Sau khi đọc, `codex-1` phải gọi `team_report_result` cho đúng `run_id` và `task_id` của assignment, với `status: "succeeded"` và tóm tắt xác nhận đã tiếp nhận cho `opencode-1`.
- `succeeded` ở task này chỉ có nghĩa đã tiếp nhận bàn giao, không phải tính năng đã hoàn thành.
- Không mở CLI/app khác hoặc tạo chat ngoài để thay cho báo cáo Agent Team.

## 2. Repository và trạng thái công việc

- Repository: `/Users/ngocthanh/Documents/Project/reallive`.
- Issue: https://github.com/ThanhMiLa/reallive/issues/45.
- Chỉ tiêu đề và mô tả GitHub issue đã được sửa, đã đọc lại bằng `gh` để xác minh.
- Chưa triển khai code feature, chưa chạy build/test/migration cho feature.
- File này chỉ lưu bản bàn giao theo yêu cầu mới nhất của user.
- `docs/product/Features.md` dòng 35 vẫn có tên cũ `Notify Email Followers When Stream Starts`; chưa sửa tài liệu đó.
- Các tên bảng, topic, API và class mới trong bản bàn giao phần lớn là đề xuất hoặc ví dụ, không được coi là đã tồn tại.

## 3. Nguồn gốc và thay đổi yêu cầu

### 3.1. Yêu cầu ban đầu

Issue ban đầu có tiêu đề:

`[Notification Service] Notify Email Followers When Stream Starts`

Mô tả cũ yêu cầu:

- Backend gửi email cho follower khi streamer bắt đầu livestream.
- Email có thông tin streamer/channel, tiêu đề, category và link xem.
- Có thể tắt email từng streamer mà vẫn follow.
- Mỗi follower nhận tối đa một email mỗi session.
- Delivery lỗi không làm gián đoạn broadcast.
- In-app/push và thay đổi UI web/mobile nằm ngoài phạm vi cũ.

### 3.2. Tham khảo platform

User hỏi nên dùng email hay thông báo trong hệ thống, muốn tham khảo các platform khác.

Đã đọc nguồn chính thức:

- Twitch: https://blog.twitch.tv/en/2025/12/11/giving-you-more-control-over-your-notifications/
  - Có email, mobile và onsite.
  - Per-channel có Always, Go Live Only, Personalized và Off.
  - Smart Delivery có thể chọn nơi nhận thay vì gửi cả ba.
- YouTube: https://support.google.com/youtube/answer/3382248?hl=en
  - Có All, Personalized và None theo kênh.
  - Có mobile, web và inbox notifications.
- KICK: https://help.kick.com/en/articles/7122022-how-to-set-up-kick-live-notifications
  - Hướng dẫn go-live tập trung browser notifications và mobile push.
  - Có chuông từng streamer, tắt thông báo không bắt buộc unfollow.

### 3.3. Những điều đã giải thích

- Chỉ thông báo ở chuông thì người không mở RealLive không biết ngay; họ thấy khi quay lại.
- Web Push có thể hiện khi đã đóng tab, nhưng phụ thuộc hỗ trợ browser/OS và permission.
- Chặn thông báo ở browser hoặc OS thì Web Push có thể không hiện.
- Dù push bị chặn hoặc gửi thất bại, inbox notification vẫn phải được lưu.
- Không tự động chuyển sang email chỉ vì user chặn push.
- User đồng ý bỏ email và yêu cầu sửa issue.

### 3.4. Issue đã cập nhật

Tiêu đề hiện tại:

`[Notification Service] Notify Followers When a Stream Goes Live`

Issue giữ trạng thái OPEN, giữ nguyên label `backend`.

Body đã publish và xác minh:

```markdown
### Description

When a stream goes live, notify followers who have not muted that channel. Add an unread notification to the RealLive notification bell with the streamer name, stream title, category, and a link to watch. Update the bell for followers who are already on RealLive and keep the notification available for those who return later.

Followers who have enabled Web Push and granted browser permission can also receive an alert when the RealLive tab is closed. If push is unavailable or blocked, the notification still appears in the bell. Do not send email notifications.

Followers can mute go-live notifications for individual channels without unfollowing them. Each follower should receive at most one bell notification and one push alert per livestream session, even if the streamer reconnects. Notification delivery failures must not interrupt the broadcast.
```

## 4. Requirement notification

- Phiên livestream mới thực sự lên LIVE mới phát sinh go-live notification.
- Chỉ follower đủ điều kiện và chưa tắt thông báo của kênh được nhận.
- Nội dung có streamer, tiêu đề stream, category và link tới stream.
- Tạo inbox notification riêng cho từng người nhận.
- User đang mở web có thể thấy chuông cập nhật gần realtime và toast.
- User offline vẫn có bản ghi để đọc khi quay lại.
- Web Push dành cho người đã đăng ký và cấp quyền, không bảo đảm hiện nếu browser/OS chặn.
- Không gửi email.
- Mute từng kênh không bắt buộc unfollow.
- Reconnect cùng session không tạo lại thông báo bắt đầu live.
- Delivery failures không được làm gián đoạn broadcast.
- Phải chống trùng khi Kafka redelivery hoặc job retry.

## 5. Chuông và trạng thái đã đọc

Hành vi đã giải thích cho user:

- Badge trên chuông là số chưa đọc, không phải tổng số notification.
- Bấm chuông hiển thị cả đã đọc và chưa đọc, sắp mới nhất trước.
- Gợi ý lấy 10-20 item đầu rồi phân trang bằng cursor; con số là ví dụ, chưa chốt.
- Chưa đọc có chấm hoặc nền nổi bật.
- Bấm một notification thì gọi API mark-read, cập nhật UI rồi điều hướng.
- Có thể có nút đánh dấu tất cả đã đọc.
- Chỉ mở chuông không tự đánh dấu tất cả đã đọc.
- SSE/Web Push gửi thành công không đồng nghĩa user đã đọc.
- Read state không liên quan Kafka offset và phải lưu DB, không chỉ localStorage.

Cơ chế:

- `read_at = NULL`: chưa đọc.
- `read_at` có thời gian server: đã đọc.
- Mark-read cần kiểm tra notification thuộc user đang đăng nhập.
- Mark-read idempotent, nếu đã đọc thì giữ timestamp cũ.
- Badge lấy số bản ghi của user có `read_at IS NULL`.
- Các API được nêu chỉ là ví dụ, chưa được triển khai: GET danh sách, GET unread-count, PATCH notification theo ID để mark-read.

## 6. Inbox chung cho nhiều loại notification

User hỏi sau này có cùng chuông cho:

- Become streamer thành công.
- Nạp tiền/mua credits thành công.
- Donate thành công.
- Streamer đang follow bắt đầu live.

Đã khuyến nghị:

- Một bảng `notifications` trong Notification Service cho inbox in-app chung.
- Phân biệt bằng `type`.
- Không dùng `email_notifications` hiện có làm inbox chung.
- Service nghiệp vụ xác nhận thành công rồi phát event; Notification Service không quyết định giao dịch tiền hay việc duyệt streamer thành công.

Schema minh họa, chưa chốt migration:

```text
notifications
  id
  user_id
  source_event_id
  type
  title
  body
  target_url
  metadata
  read_at
  created_at
```

Types minh họa: `STREAMER_APPROVED`, `CREDITS_PURCHASED`, `DONATION_SENT`, `DONATION_RECEIVED`, `STREAM_STARTED`.

- Không bắt mọi loại phải có `session_id`.
- Metadata có thể chứa sessionId, donationId hoặc transactionId tùy loại.
- Dedupe tổng quát gợi ý `UNIQUE(user_id, source_event_id, type)`.
- ID sự kiện phải ổn định khi retry.
- Riêng go-live cần bảo vệ logical session để duplicate start có eventId khác cũng không tạo lại.
- Push subscriptions, delivery jobs và delivery history nên tách khỏi inbox.
- Hiện Notification Service có `email_notifications` và `email_delivery_events` cho email/SendGrid; không tự thay đổi flow OTP/email này.

## 7. Đề xuất Kafka và trách nhiệm các service

### 7.1. Ý tưởng ban đầu của user

```text
MediaMTX runOnOnline
  -> Stream Service chuyển LIVE
  -> Lấy toàn bộ userId follower
  -> Đưa Kafka
  -> Notification Service gửi cho từng người
```

### 7.2. Đề xuất điều chỉnh

- Kafka phù hợp.
- Không quét toàn bộ follower hay fanout hàng nghìn message trong callback/lifecycle bắt đầu stream.
- Không nhét một danh sách userId khổng lồ vào một Kafka message.
- Per-recipient message có thể dùng khi scale, nhưng fanout nằm sau domain event, không trong callback.

Ownership:

| Service | Trách nhiệm |
| --- | --- |
| Stream Service | Xác nhận phiên bắt đầu, tạo domain event StreamStarted. |
| Channel Service | Sở hữu follower và dữ liệu mute theo kênh theo hướng đề xuất, cung cấp internal API phân trang. |
| Notification Service | Fanout recipients, lưu inbox, realtime/push delivery, retry và read state. |

- Không query DB service khác, không import entity/repository service khác.
- Theo phương án MVP được trao đổi, Channel Service không cần consume Kafka, chỉ cung cấp internal API.
- Vị trí lưu mute/global preferences và API cụ thể chưa chốt hoàn toàn.

Flow:

```text
Stream Service
  -> Ghi LIVE + history + outbox cùng transaction
  -> Commit
  -> Publisher gửi Kafka StreamStarted
Notification Service
  -> Consume
  -> Lưu durable fanout job trước khi ACK
  -> Worker gọi Channel Service lấy follower từng trang
  -> Lọc mute/eligibility
  -> Batch tạo inbox và durable delivery work
  -> Sau commit gửi SSE và Web Push
```

Payload minh họa:

```text
eventId
eventType = stream.started.v1
sessionId
channelId
streamerId
title
categoryId
startedAt
```

Không chứa danh sách follower.

Các lưu ý:

- Không để consumer handler bị giữ quá lâu bởi fanout kênh lớn.
- Lưu job/checkpoint/cursor để restart có thể tiếp tục.
- Inbox batch cần chống trùng.
- Push retry độc lập, không tạo lại toàn bộ inbox hoặc quét lại cả kênh chỉ vì push lỗi.
- Push go-live nên có TTL, tránh báo khi đã quá muộn hoặc session kết thúc.
- MVP có thể dùng DB workers, chưa bắt buộc thêm per-user Kafka topic.
- Có thể bổ sung delivery topic theo user hoặc batch nhỏ để scale sau.
- Các topic/schema/job/API này chưa tồn tại trong feature code.

## 8. Streaming code thực tế đã kiểm tra

### 8.1. MediaMTX và hook

`media-server/config/mediamtx.yml` có `runOnOnline` gọi:

```text
python3 /hooks/media_hook.py online
```

`media-server/hooks/media_hook.py`:

- `MediaHook.run(action)` nhận online/offline.
- Default callback là `http://host.docker.internal:8082/stream/internal/api/media-events`.
- Chỉ xử lý `ingest/{ingestKeyId}`.
- Body có eventId, eventType ONLINE/OFFLINE, path, sourceId, sourceType, occurredAt.
- Bounded retries dùng cùng body/eventId trong một lần hook.
- Không gửi Kafka tại hook.

### 8.2. Controller

`stream-service/src/main/java/com/reallive/streamservice/stream/controller/media/MediaEventController.java`:

- Mapping `/internal/api/media-events`.
- `handleEvent()` nhánh ONLINE gọi `lifecycleService.onOnline(command)`, khoảng dòng 43-44.
- Code hiện tại trả `ResponseEntity.ok()` sau xử lý.
- Không khẳng định callback hiện chỉ enqueue PROCESS_ONLINE/202 dựa vào tài liệu cũ.

### 8.3. Lifecycle và điểm cần tạo event

`stream-service/src/main/java/com/reallive/streamservice/stream/service/impl/StreamMediaLifecycleServiceImpl.java`:

- `onOnline()` khoảng dòng 49: validate, lấy config, kiểm tra eligibility, rồi `TransactionTemplate.execute(onlineInTransaction)`.
- `onlineInTransaction()` khoảng dòng 67: lock config/session, dedup mediaEventId, kiểm tra source.
- Dòng 96: `boolean creating = existing == null`.
- Dòng 97: sessionId mới khi creating, reuse existing khi reconnect hợp lệ.
- Đảm bảo playback path, `awaitPlayback()`, recheck source.
- Khoảng dòng 107-121: tạo session mới hoặc reuse.
- Dòng 123: `session.setStatus(StreamSessionStatus.LIVE)`.
- Dòng 129: `sessions.saveAndFlush(session)`.
- Dòng 130-136: ghi history với lý do OBS started broadcasting hoặc OBS reconnected.
- Dòng 137: return kết quả.

Điểm khuyến nghị bổ sung:

```text
Sau save session và history, trước return, vẫn cùng transaction:

if (creating) {
    streamStartedOutbox.enqueue(session);
}
```

Đây là pseudo-code, chưa có bean/method/table đó.

- Chỉ creating=true mới tạo StreamStarted.
- RECONNECTING về LIVE cùng session không tạo lại event bắt đầu.
- Không tạo event ngay khi nhận ONLINE vì có thể stale, duplicate, invalid, fail eligibility hoặc playback chưa sẵn sàng.
- Đã grep `stream-service/src/main/java`: chỉ thấy một chỗ set LIVE này, chưa thấy StreamStarted, KafkaTemplate hoặc outbox.
- Tài liệu streaming có phần mô tả operation worker/PROCESS_ONLINE khác code hiện tại; cần đối chiếu actual code trước khi triển khai.

## 9. Outbox: nội dung user vừa được giải thích kỹ

User hỏi Kafka chỉ gọi sau commit hay không, có gọi trực tiếp trong lifecycle class không, và outbox là gì.

Giải thích đã đưa:

- Outbox là một bảng trong DB Stream Service ghi các sự kiện cần gửi Kafka.
- Có thể hiểu là sổ ghi việc cần gửi.
- Không phải Kafka, không bắt buộc thư viện hay microservice mới.

Vấn đề nếu làm trực tiếp:

```text
DB commit LIVE
  -> Ứng dụng crash
  -> Chưa kịp Kafka send, mất event và không còn dấu vết để retry
```

Giải pháp:

```text
Một transaction:
  Lưu session LIVE
  Lưu status history
  Insert outbox_events trạng thái PENDING
  -> Cùng commit hoặc cùng rollback

Worker/OutboxPublisher:
  Đọc row đã commit, chưa published
  -> Gửi Kafka
  -> Broker xác nhận thành công
  -> Mark published
```

- Publisher có thể chạy ngay trong Stream Service.
- Kafka lỗi thì giữ event để thử lại, restart đọc tiếp từ DB.
- Crash sau publish nhưng trước mark published có thể gây gửi trùng, consumer phải idempotent.
- `saveAndFlush()` không phải commit.
- Kafka không tự biết transaction DB; bảo đảm sau commit do thiết kế outbox/publisher.
- Commit thành công không có nghĩa Kafka đã gửi ngay vì publisher bất đồng bộ.
- `StreamMediaLifecycleServiceImpl` quyết định tạo event và ghi outbox.
- `OutboxPublisher` mới thực sự gọi `kafkaTemplate.send()`.
- Không gọi Kafka trực tiếp trong transaction `onlineInTransaction()`.
- Chưa giao triển khai hoặc chốt schema/polling/CDC/claim/lease.

## 10. SSE, WebSocket và Web Push

### 10.1. Phân biệt vai trò

- Kafka truyền sự kiện giữa services.
- SSE/WebSocket cập nhật web đang kết nối.
- Web Push có thể thông báo ngoài web qua browser/OS nếu đủ điều kiện.
- HTTP API dùng lấy inbox/count, mark-read và settings.

### 10.2. Cơ chế SSE

- Frontend chủ động HTTP GET, thường dùng EventSource.
- Backend trả Content-Type text/event-stream và giữ response mở để gửi nhiều sự kiện.
- Backend không tự mở kết nối ngược tới máy user.
- Với HTTP/1.1 hoặc HTTP/2, SSE vẫn thường sử dụng TCP, không phải transport mới.
- Một chiều là luồng sự kiện SSE server về client; ứng dụng vẫn hai chiều qua các HTTP API khác.
- Response HTTP thường kết thúc; response SSE kéo dài.
- Kết thúc response không đồng nghĩa đóng TCP vì có keep-alive.
- WebSocket thường khởi đầu bằng HTTP handshake rồi giao tiếp hai chiều trên cùng kết nối.
- Không cần port riêng cho SSE hay WebSocket, có thể qua gateway và port 443.

### 10.3. Lifecycle phía web

- Không tự mở SSE cho mọi user lúc backend start.
- Sau khi user mở web và đăng nhập, frontend mở kết nối ở cấp ứng dụng.
- Một tab thường một EventSource; nhiều tab/thiết bị có nhiều kết nối.
- Không mở một kết nối mới cho mỗi notification hay mỗi lần chuyển route.
- Logout/cleanup gọi EventSource.close().
- Đóng tab thì ngắt kết nối.
- EventSource thường tự thử reconnect khi network/server gián đoạn.
- Khi reconnect, gọi API đồng bộ từ DB để bù thông báo bị bỏ lỡ.
- Offline không có SSE nhưng vẫn có inbox DB.

### 10.4. Thread và tối ưu

- Một SSE connection không mặc định giữ một thread đứng chờ.
- Spring MVC SseEmitter dùng Servlet Async, có thể nhả request thread khi chờ.
- Gửi dữ liệu vẫn chạy trên thread và có thể block nếu client chậm.
- Cả SSE và WebSocket đều tốn memory, buffer, network state và heartbeat.
- Không khẳng định SSE luôn ít RAM/CPU hơn WebSocket.
- Notification chủ yếu server về client, thấp tần: SSE + HTTP đơn giản và đủ dùng.
- Nếu có Socket.IO toàn app thì reuse cũng hợp lý; benchmark nếu cần so sánh tài nguyên.
- Không bắt buộc chuyển Notification Service sang WebFlux chỉ để có SSE async.

## 11. SseEmitter và ví dụ đã giải thích

- SseEmitter là class có sẵn trong Spring MVC.
- Nó là handle quản lý một HTTP response SSE đang mở.
- Không phải một user, một notification, một thread hay chính TCP socket.
- new SseEmitter không tự kết nối tới user; frontend phải có request trước.
- emitter.send() ghi sự kiện xuống response đó.
- Một emitter nhận nhiều thông báo liên tiếp.
- Backend giữ `Map<UUID, Set<SseEmitter>>` trong memory.
- User có ba tab/thiết bị có thể có ba emitter.
- Khi gửi cho user, gửi qua các emitter tương ứng.
- onCompletion/onTimeout/onError dọn emitter; set rỗng thì xóa entry.
- HTTP/2 có thể multiplex nên không nói một emitter chắc chắn là một TCP socket.
- Không persist emitter vào DB và không dùng emitter thay Kafka hoặc inbox.

Code ví dụ chỉ minh họa, chưa thêm vào repo:

- Controller GET `/api/notifications/stream`, produces text/event-stream.
- Lấy `@AuthenticationPrincipal Jwt jwt`, userId từ UUID subject.
- Return `realtime.connect(userId)`.
- Service dùng concurrent map, tạo SseEmitter timeout 30 phút, đăng ký cleanup.
- sendToUser gọi send event tên notification với JSON chứa title.
- Frontend mở EventSource tới `API_ORIGIN + '/notification/api/notifications/stream'`, `withCredentials: true`.
- Listener notification parse JSON và cập nhật UI.
- close() khi logout/teardown.
- 30 phút chỉ là timeout ví dụ, không phải requirement.

Ví dụ chưa production ready, cần xử lý heartbeat/flush, proxy buffering/timeouts, slow clients/backpressure, auth expiry/logout, reconnect sync/replay và multi-instance routing.

## 12. Stack, auth và gateway đã kiểm tra

`notification-service/pom.xml`:

- Java 21.
- Spring Boot 4.1.1.
- Spring MVC, Security, OAuth2 Resource Server, Kafka, JPA và Redis.
- Có thể dùng SseEmitter, không cần đổi sang WebFlux chỉ vì SSE.

`notification-service/src/main/java/com/reallive/notificationservice/common/security/SecurityConfig.java`:

- BearerTokenResolver lấy access-token từ cookie.
- Các request không public cần authenticated.
- JWT subject là UUID user qua converter hiện tại.
- Không mở endpoint SSE public hoặc để frontend tùy chọn userId người nhận.
- Native EventSource không có API tùy ý đặt Authorization header; cookie và CORS/credentials cần cấu hình phù hợp.
- Không đưa token vào URL.

`notification-service/src/main/resources/application-dev.yaml`:

- Port 8085.
- Context path `/notification`.

`api-gateway/src/main/resources/application-dev.yml`:

- Route `/notification/api/**` tới Notification Service.
- Controller `/api/notifications/stream` tương ứng đường dẫn ngoài `/notification/api/notifications/stream`.

## 13. Chat hiện tại và hiểu nhầm đã được sửa

User tưởng Socket.IO hiện chỉ server về client, gửi chat dùng HTTP.

Đã đọc code thực tế và xác nhận chat đã dùng Socket.IO hai chiều:

`web-app/src/components/chat/useStreamChat.ts`:

- Khoảng dòng 72: io với path `/chat/socket.io`, transport websocket, withCredentials.
- Khoảng dòng 98: client emit chat:join.
- Dòng 123: nhận chat:message.
- Dòng 152: emit chat:leave.
- Dòng 162: timeout.emit chat:send với sessionId, clientMessageId, content và ACK callback.

`chat-service/src/chat/gateways/chat.gateway.ts`:

- WebSocketGateway path `/chat/socket.io`.
- SubscribeMessage chat:join khoảng 105.
- chat:leave khoảng 140.
- chat:send khoảng 146.
- send gọi chat.send với callback deliver.
- deliver emit chat:message tới member cùng session khoảng dòng 188.
- Người gửi cũng nhận broadcast và ACK qua Socket.IO.

`chat-service/src/chat/services/chat.service.ts`:

- await persist transaction trước.
- Chỉ result.created mới deliver.
- Retry cùng clientMessageId không tạo mới thì không broadcast lại.

`chat-service/src/chat/repositories/chat.repository.ts`:

- connection.transaction với readConcern snapshot, writeConcern majority.

HTTP chỉ lấy lịch sử:

- GET `/chat/api/streams/{sessionId}/messages`.
- Nằm trong `web-app/src/api/chat.api.ts` và chat controller.

Flow đã làm rõ:

```text
UI nhập và bấm gửi
  -> Socket.IO chat:send
  -> Auth/validate
  -> Lưu DB, transaction commit
  -> Socket.IO chat:message tới room, gồm sender
  -> ACK kết quả qua Socket.IO
```

DB thất bại thì không broadcast tin mới.

Socket chat hiện gắn active/session trong useStreamChat, chưa phải connection toàn app.
Không tự mặc định reuse cho chuông mọi trang; cần thiết kế thêm.
Không chuyển ownership notification sang Chat Service chỉ vì đã có Socket.IO.

## 14. Những điểm chưa chốt và giới hạn guarantee

Đã có yêu cầu issue, còn phần kiến trúc trao đổi là hướng đề xuất, chưa có lệnh triển khai.

Chưa chốt:

- Kafka topic/version contract, partition key, producer/consumer configs, retry/DLT.
- Outbox schema, polling/CDC, claims/leases, cleanup.
- Fanout job/checkpoint và snapshot semantics.
- Follower tại startedAt hay tại thời điểm fanout; xử lý follow/unfollow/mute giữa các trang.
- Global preferences và schema/API mute.
- Pagination limit, read-all concurrency và retention.
- Web Push service worker, subscriptions, VAPID/provider, TTL/retry/dedupe.
- Auth expiry/thu hồi quyền trên long-lived connection.
- Multi-instance routing cho SSE.

Các tradeoff cần nhớ:

- At-most-one push/session là yêu cầu issue; Kafka và external push retry không tự bảo đảm exactly-once khi timeout mơ hồ.
- Cần stable notification identity và cơ chế dedupe phù hợp, có thể ở client.
- Emitter chỉ local memory; Kafka consumer group có thể xử lý trên instance khác instance giữ SSE user.
- Cần routing/pubsub/shared realtime distribution nếu nhiều instance, không cho rằng map local là đủ.
- Outbox atomic với LIVE có thể khiến transaction LIVE rollback nếu ghi DB/outbox lỗi.
- Phân biệt DB transaction failure với downstream delivery failure; không hứa mọi lỗi đều không ảnh hưởng state/broadcast.
- Lưu durable inbox trước rồi mới delivery; mất SSE không được làm mất thông báo.

## 15. Quy tắc repository và tài liệu liên quan

Trước khi làm implementation hoặc review tương lai:

- Đọc root AGENTS.md và local AGENTS của module.
- Java áp dụng style, architecture, Lombok, records, query formatting, service-interface Javadoc và exception rules khi phù hợp.
- Gateway là entrypoint web/mobile.
- Internal API chỉ private service network, không expose client qua gateway.
- Giữ bảo vệ callback/provider hiện có theo đúng rule.
- Không import entity/repo hoặc query DB chéo service.
- Chat: gateway/controller gọi service, repository phụ trách persistence/transaction; idempotency và broadcast sau commit.
- Web: giữ style/UI conventions, chạy build/lint/test khi thực sự thay đổi.
- Skill write-issue: title theo `[Service Name]`, body chỉ `### Description`, giữ labels.

Tài liệu đã đọc/liên quan:

- `docs/architecture/System Architecture.md`.
- `docs/architecture/Streaming Architecture and Flow.md`.
- `docs/product/Features.md`.
- `docs/data-model/Data Model.md`.
- `docs/contracts/README.md`.
- `docs/risk/Data Consistency Risks.md`, có rủi ro publish Kafka sau khi lưu OTP mà process crash.

Không có code feature, test/build hoặc migration nào đã hoàn tất trong cuộc trao đổi.

## 16. Báo cáo xác nhận yêu cầu từ codex-1

Ngay sau khi đọc, gọi `team_report_result` cho assignment hiện tại:

- Status: succeeded.
- Summary: xác nhận codex-1 đã nhận đầy đủ ngữ cảnh từ opencode-1.
- Nêu ngắn: không email; inbox + realtime + Web Push; producer chỉ lần đầu LIVE; outbox khác bước Kafka send; Notification phối hợp Channel fanout; SSE emitter là response; kiến trúc chi tiết còn mở và chưa triển khai.
- Files/tests: trống nếu không sửa file/chạy kiểm tra.
- Không dispatch ngược lại, không sửa file/issue, không commit.
