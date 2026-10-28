# Streaming MVP — Kiến trúc và workflow

Mục tiêu: streamer phát bằng OBS và người dùng xem được trên React + hls.js. Chưa dùng CDN.

Cập nhật phạm vi 2026-10-25: các thao tác backend gọi MediaMTX xử lý **đồng bộ từ đầu đến cuối**, chờ kết quả trước response; không task queue, worker retry/lease/revision hoặc reconciler. Giữ scheduler phục vụ deadline reconnect. Mọi cập nhật session dùng pessimistic row lock trong cùng transaction; không dùng version/optimistic locking trong MVP.

Chốt triển khai **một instance MediaMTX, chạy trong một container Docker**. MediaMTX tự verify cả **Publish JWT** của OBS và **Read JWT** của player bằng **public key** do backend cung cấp qua JWKS.

## 1. Kiến trúc tổng thể

```text
Luồng video:
[OBS] -- RTMP/RTMPS --> [MediaMTX] -- HLS --> [React + hls.js]

Luồng API:
[React UI] --> [API Gateway] --> [Stream Service] --> [PostgreSQL]

Kết nối media với backend:
[MediaMTX] -- callback nguồn online / offline ------> [Stream Service]
[MediaMTX] -- lấy public key qua JWKS khi cần ------> [Stream Service]
[Stream Service] -- tạo / đóng path qua Control API --> [MediaMTX]
```

Stream Service quản lý session, kiểm tra nghiệp vụ streamer/channel và ký JWT cấp quyền phát/xem. MediaMTX tự kiểm tra JWT, không gọi Stream Service để verify từng token. OBS và player cùng kết nối tới một MediaMTX; video/audio truyền trực tiếp qua MediaMTX.

## 2. Tạo hoặc cập nhật stream session

Mỗi channel chỉ có tối đa một session đang hoạt động, tức ở trạng thái `READY`, `LIVE` hoặc `RECONNECTING`.

1. Frontend gọi API qua Gateway đến Stream Service.
2. Backend kiểm tra quyền streamer, channel và dữ liệu thiết lập.
3. Nếu hợp lệ, backend mở transaction, reserve slot session **READY**, gọi đăng ký path riêng `live/{sessionId}` trên MediaMTX và chờ kết quả; thành công mới commit READY/OPEN cùng history.
4. Backend dùng private key ký **Publish JWT**, chỉ cấp quyền `publish` vào path đó, rồi trả `serverUrl` và `streamKey` chứa JWT cho frontend. Chỉ trả thông tin OBS sau khi đăng ký path thành công và commit DB. Lỗi media trả 503, rollback DB; không enqueue để xử lý tiếp.
5. Streamer copy hai giá trị đó vào OBS. Cập nhật title/category tiếp tục gọi API của session hiện tại và giữ nguyên thông tin OBS.

Ví dụ thông tin điền vào OBS:

```text
Server:     rtmp://localhost:1935/live
Stream Key: {sessionId}?token={PublishJWT}
```

`serverUrl` là địa chỉ MediaMTX nhận video. `streamKey` chứa session cần phát và Publish JWT đã ký; mỗi buổi stream mới tạo session và token mới. Khi triển khai dùng RTMPS/HTTPS.

Cơ chế này thay thế stream key ngẫu nhiên đối chiếu hash: không lưu JWT thô và không dùng `stream_key_hash` để xác thực. Khi implement cần cập nhật data model tương ứng và dùng `READY` thống nhất trong lịch sử trạng thái.

## 3. Workflow Create Stream Session

```text
1. Client         --> Stream Service: Tạo session; kiểm tra, mở transaction, reserve slot READY
2. Stream Service --> MediaMTX:       Đăng ký path live/{sessionId}, chờ kết quả rồi commit DB
3. Stream Service --> Client:         Trả serverUrl + streamKey chứa Publish JWT sau commit
4. OBS            --> MediaMTX:       Kết nối serverUrl, yêu cầu publish với streamKey
5. MediaMTX:                          Verify Publish JWT bằng public key cache
6. MediaMTX       --> OBS:            RTMP: NetStream.Publish.Start (chấp nhận publish)
7. OBS            --> MediaMTX:       Truyền video/audio
8. MediaMTX       --> Stream Service: Callback nguồn online; backend chuyển LIVE
```

- MediaMTX kiểm tra chữ ký, thời hạn và quyền `publish` đúng path. Verify thành công chỉ cho phép publish; session vẫn **READY**.
- Bước 6 được MediaMTX và OBS tự xử lý theo giao thức RTMP. Xem [phản hồi chấp nhận publish](https://github.com/bluenviron/gortmplib/blob/v1.0.4/server_conn.go#L483).
- Khi nguồn OBS thực sự online, hook `runOnOnline` chạy lệnh gọi HTTP về backend. Backend chuyển **READY → LIVE**.

## 4. Workflow Watch Stream

MVP chỉ hỗ trợ stream `PUBLIC`; người xem phải đăng nhập để lấy metadata và xin Read JWT. Ngày 2026-10-28, người dùng chốt **hai endpoint riêng**, do frontend điều phối:

| Endpoint qua Gateway | Mục đích |
|---|---|
| `GET /stream/api/streams/{sessionId}` | Metadata/trạng thái khi mở trang và polling; không cấp token |
| `POST /stream/api/streams/{sessionId}/playback-access` | Kiểm tra điều kiện xem và cấp `playbackUrl` + Read JWT khi PUBLIC/LIVE |

Viewer chỉ click vào livestream một lần. Frontend đọc `status` từ GET, chỉ gọi POST khi LIVE và cần mở phiên HLS. Backend không tự gọi POST sau GET; POST vẫn tự xác thực và kiểm tra lại session PUBLIC/LIVE ở thời điểm xử lý.

Các API business đi qua Gateway; sơ đồ dưới rút gọn thành Stream Service.

```text
Mở phiên xem:
1. Frontend       --> Stream Service  : GET metadata theo sessionId
2. Stream Service --> Frontend        : Metadata/status/time, không credential
3. Frontend                          : Nếu LIVE và cần mở HLS thì gọi tiếp; trạng thái khác hiện thông báo
4. Frontend       --> Stream Service  : POST playback-access
5. Stream Service                    : Tự xác thực account, recheck session PUBLIC/LIVE
6. Stream Service --> Frontend        : playbackUrl + Read JWT + readExpiresAt
7. Player         --> MediaMTX        : GET playlist .m3u8 ban đầu + Authorization Bearer
8. MediaMTX                           : Verify JWT bằng public key cache, tạo phiên HLS
9. MediaMTX       --> Player          : Playlist .m3u8

Trong lúc xem, chạy song song:

Loop metadata khi tab hiển thị, đề xuất khoảng 3 giây:
    Frontend       --> Stream Service: GET metadata, kể cả video đang phát bình thường
    Stream Service --> Frontend:      Status/time mới; không ký JWT hoặc kiểm tra nguồn qua Control API

Loop cập nhật playlist theo thời gian:
    Player   --> MediaMTX: GET playlist .m3u8 cập nhật của phiên hiện tại
    MediaMTX --> Player:   Playlist mới

Loop tải segment theo nhu cầu buffer:
    Player   --> MediaMTX: GET segment mới
    MediaMTX --> Player:   Segment video/audio
```

GET trả HTTP 200 chỉ có nghĩa lấy được metadata. READY hiện chưa bắt đầu, RECONNECTING hiện chờ nối lại và tiếp tục polling; LIVE chỉ xin access khi cần bắt đầu/mở lại HLS. ENDED/404 dừng player và polling. Khi tab ẩn tạm dừng polling, tab hiển thị lại lấy metadata ngay; rời trang thì cleanup. POST không nằm trong vòng polling; không tạo lại player hoặc cấp lại JWT chỉ vì nhận thêm một response LIVE. MVP không dùng WebSocket/SSE cho trạng thái stream.

Trạng thái có thể đổi giữa hai request: GET trả LIVE nhưng POST sau đó vẫn có thể trả 409. Frontend đọc lại metadata để xử lý; không suy diễn rằng GET thành công bảo đảm được cấp token. Lỗi mạng của viewer không đồng nghĩa session đã ENDED.

Player không chờ tải/phát hết các segment mới cập nhật playlist. Verify JWT chỉ thực hiện khi tạo phiên HLS mới, không lặp lại cho từng playlist cập nhật hoặc segment trong cùng phiên.

Read JWT chỉ có quyền `read` đúng path của session; tách biệt với Publish JWT có quyền phát. MediaMTX dùng cùng public key cache để xác thực cả hai loại token.

DB lưu `playback_path`; backend ghép với media base URL để trả `playbackUrl` đầy đủ. Player gửi JWT qua header `Authorization: Bearer ...`.

Xem [workflow và contract chi tiết giai đoạn 5](../Implement%20Plan/Streaming%20MVP%20Phase%205%20Implementation%20Plan.md). Giai đoạn 5 xây backend/kiểm chứng fixture; frontend production triển khai workflow này ở giai đoạn 6.

## 5. Ngắt kết nối và kết thúc

Backend chờ reconnect mặc định **60 giây** kể từ khi chuyển sang `RECONNECTING`. Thời gian này có thể cấu hình.

```text
OBS mất kết nối
  --> MediaMTX runOnOffline --> Backend --> LIVE → RECONNECTING

OBS nối lại trong thời gian cho phép
  --> MediaMTX verify lại Publish JWT, chấp nhận OBS phát lại
  --> MediaMTX runOnOnline --> Backend --> RECONNECTING → LIVE

Hết thời gian chờ hoặc streamer chủ động kết thúc
  --> Backend khóa/recheck session, gọi Control API đóng path và chờ xác nhận
  --> Thành công mới commit ENDED/CLOSED, ngừng cấp token cho session
  --> Callback offline về sau không đổi ENDED thành RECONNECTING
```

OBS reconnect dùng lại serverUrl/streamKey đã lưu nếu Publish JWT còn hạn. Token phát cần có thời hạn phù hợp với buổi stream; nếu hết hạn trước khi reconnect thì streamer phải lấy token mới và cập nhật OBS.

Session đã **ENDED** không mở lại; buổi stream tiếp theo dùng session/path/token mới. Khi kết thúc, backend xóa cấu hình path riêng qua Control API để ngăn kết nối lại bằng JWT cũ còn hạn. MediaMTX chỉ mở các path được backend đăng ký, không dùng path catch-all như `all_others`.

Nếu gọi Control API lỗi/timeout, request End trả 503 và rollback thay đổi DB, UI cho phép người dùng thử lại; không trả 202 hoặc ghi ENDED rồi giao worker xử lý. Scheduler hết hạn reconnect cũng gọi trực tiếp và chỉ commit ENDED khi đóng media thành công; session đã quá deadline không được cấp credential hoặc hồi sinh. DB và MediaMTX không có transaction chung: crash/timeout có thể để lại trạng thái lệch. Mất callback hoặc MediaMTX restart cần kiểm tra/xử lý thủ công trong MVP; tự restore/đóng bù/đối soát được hoãn sau MVP. Xem [Control API quản lý path](https://github.com/bluenviron/mediamtx/blob/v1.21.2/api/openapi.yaml#L2970).

## 6. Xác thực JWT và bảo vệ MVP

- **Backend:** dùng private key RS256 từ biến môi trường/secret để ký Publish JWT và Read JWT; công bố public key qua JWKS. Cặp khóa dùng chung cho nhiều session, không tạo lại mỗi buổi phát.
- **MediaMTX:** chọn `authMethod: jwt`, trỏ `authJWTJWKS` tới endpoint JWKS. Ở v1.21.2, lần xác thực đầu tải JWKS rồi cache chung; lần xác thực tiếp theo sau 1 giờ sẽ tải lại. Xem [cơ chế verify và cache](https://github.com/bluenviron/mediamtx/blob/v1.21.2/internal/auth/manager.go#L234).
- **Token:** luôn có thời hạn `exp` và claim `mediamtx_permissions` giới hạn `action` + `path`. Token phát và token xem có quyền riêng; public key chỉ dùng để kiểm tra chữ ký.
- **HLS:** JWT được kiểm tra khi mở phiên HLS; hết hạn JWT không tự bảo đảm ngắt phiên đang xem. Player cần token còn hạn khi mở lại phiên.
- **CORS + JWT:** dùng `hlsAllowOrigins` giới hạn origin frontend và Read JWT cấp quyền xem. MVP tạm chấp nhận người xem hợp lệ sao chép token để chia sẻ/phát lại.
- **Tích hợp nội bộ:** Control API chỉ dành cho backend với quyền `api` riêng; callback online/offline và JWKS được MediaMTX gọi trực tiếp tới Stream Service trong mạng local/Docker (không đi qua API Gateway, không cần HMAC secret cho MVP local). Hook là lệnh gọi HTTP về backend, độc lập với bước verify JWT. Xem [MediaMTX Hooks](https://mediamtx.org/docs/features/hooks#runononline).

Schema đích của MVP có `stream_sessions` và `stream_status_history`. Code task cũ đã được loại khỏi runtime; migration V2 bỏ bảng `stream_media_sync_tasks` và V3 bỏ cột version đã được kiểm chứng trong DB test theo [kế hoạch điều chỉnh](../Implement%20Plan/Streaming%20MVP%20Phase%201%20Implementation%20Plan.md). Chưa migrate DB ứng dụng. Adapter MediaMTX và API provisioning Giai đoạn 3 đã triển khai/kiểm chứng tự động; [STR-308/M3 chờ người dùng tự nghiệm thu OBS](../testing/Streaming%20MVP%20Phase%203%20Test%20Plan.md). Callback nghiệp vụ, scheduler và End HTTP thuộc Giai đoạn 4 chưa mở.
