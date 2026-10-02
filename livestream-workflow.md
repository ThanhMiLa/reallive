# Livestream Workflow

## 1. Mục tiêu

Tài liệu này mô tả ngắn gọn workflow chính của chức năng livestream trong hệ thống Streaming Platform.

Phạm vi gồm:

- Tạo phiên livestream
- Tạo và sử dụng Stream Key
- OBS gửi video tới Media Server
- Media Server xác thực Stream Key
- Backend quản lý trạng thái livestream
- Frontend lấy `playbackUrl`
- Video Player lấy HLS playlist và các segment
- Stop stream
- Xử lý mất kết nối và reconnect
- Webhook giữa Media Server và Backend
- Vai trò của Kafka trong lifecycle của livestream

---

# 2. Các thành phần chính

```text
Streamer
   |
   v
Frontend Web
   |
   v
Spring Boot Backend
   |
   v
Media Server (MediaMTX / SRS)
   ^
   |
  RTMP
   |
  OBS

Viewer Frontend
   |
   v
Video Player (hls.js)
   |
   v
Media Server / CDN
```

### Backend

Quản lý business của livestream:

- Stream Session
- Stream Key
- Channel
- Permission
- Trạng thái `READY`, `LIVE`, `RECONNECTING`, `ENDED`
- Metadata của livestream
- `playbackUrl`

### OBS

Dùng để:

- Capture màn hình / camera / microphone
- Encode video/audio
- Gửi luồng media liên tục tới Media Server qua RTMP

### Media Server

Ví dụ:

- MediaMTX
- SRS

Media Server chịu trách nhiệm:

- Nhận stream RTMP từ OBS
- Xác thực quyền publish
- Quản lý luồng media đang active
- Tạo HLS playlist
- Tạo các video segment
- Phục vụ video cho viewer
- Detect publisher connect/disconnect
- Gọi webhook về Backend
- Có thể record stream để tạo VOD

### Video Player

Video Player là code chạy ngay trong frontend.

Ví dụ:

- `hls.js`

Nó không phải một backend service riêng.

Nhiệm vụ:

- Nhận `playbackUrl`
- Đọc HLS playlist
- Tải các segment video mới
- Đưa dữ liệu video vào player để phát

---

# 3. Workflow tổng thể

```text
Streamer
   |
   | Create Stream
   v
Frontend
   |
   | POST /streams
   v
Stream Service
   |
   | Create Stream Session
   | Generate Stream Key
   v
Return RTMP URL + Stream Key
   |
   v
Streamer nhập vào OBS
   |
   | Start Streaming
   v
OBS
   |
   | RTMP
   v
Media Server
   |
   | Validate Stream Key
   v
Stream Service
   |
   | VALID
   v
Media Server accepts stream
   |
   | webhook: stream published
   v
Stream Service
   |
   | status = LIVE
   | publish StreamStarted
   v
Kafka
```

Viewer:

```text
Viewer
   |
   | Open livestream
   v
Frontend
   |
   | GET /streams/{id}
   v
Backend
   |
   | playbackUrl
   v
Frontend Video Player
   |
   | HLS requests
   v
Media Server / CDN
   |
   | playlist + video segments
   v
Video Player renders livestream
```

---

# 4. Tạo Stream Session

Streamer tạo một phiên livestream mới trên Web/App.

```text
Frontend
   |
   | POST /streams
   v
Stream Service
```

Backend tạo dữ liệu như:

```text
streamId
channelId
title
category
status = READY
startedAt
endedAt
```

Một Channel có thể có nhiều Stream Session khác nhau.

Ví dụ:

```text
Channel: ThanhMiLa

Stream 101 - Playing Valorant
Stream 102 - Spring Boot Talk
Stream 103 - CS2 Night
```

---

# 5. Stream Key

Stream Key là secret dùng để xác định OBS nào được phép publish stream.

Ví dụ:

```text
RTMP Server:
rtmp://media.example.com/live

Stream Key:
sk_8f92abcxyz
```

Streamer nhập hai giá trị này vào OBS.

Backend chịu trách nhiệm:

- Generate Stream Key
- Lưu mapping giữa Stream Key và Stream Session
- Rotate / revoke Stream Key
- Validate Stream Key

Có thể hiểu:

```text
Stream Key
   |
   v
Stream Session
   |
   v
Channel
   |
   v
User
```

---

# 6. OBS gửi livestream như thế nào?

OBS không gửi từng file video nhỏ.

OBS tạo một luồng video/audio liên tục:

```text
Camera / Screen / Microphone
             |
             v
            OBS
             |
             | Encode
             v
        Video + Audio
             |
             | RTMP
             v
        Media Server
```

Kết nối RTMP được giữ liên tục cho đến khi stream kết thúc hoặc mất kết nối.

---

# 7. Media Server xác thực Stream Key

Khi OBS cố publish stream:

```text
OBS
 |
 | RTMP + Stream Key
 v
Media Server
```

Media Server có thể gọi Backend:

```text
Media Server
     |
     | Validate Stream Key
     v
Stream Service
```

Backend kiểm tra:

- Stream Key có tồn tại không
- Key có bị revoke không
- User có quyền livestream không
- Channel có bị suspend không
- Stream Session có hợp lệ không

Nếu hợp lệ:

```text
ALLOW
```

Media Server chấp nhận stream.

Nếu không:

```text
DENY
```

Media Server từ chối OBS.

---

# 8. Stream chuyển sang LIVE

Sau khi Media Server nhận stream thành công:

```text
Media Server
     |
     | webhook/callback
     v
Stream Service
```

Backend cập nhật:

```text
READY -> LIVE
```

Sau đó có thể publish event:

```text
StreamStarted
```

vào Kafka.

---

# 9. playbackUrl là gì?

`playbackUrl` là địa chỉ để viewer xem livestream.

Ví dụ:

```text
https://media.example.com/live/123/index.m3u8
```

Frontend thường lấy URL này từ Backend:

```text
Frontend
   |
   | GET /streams/123
   v
Backend
   |
   | playbackUrl
   v
Frontend
```

Backend có thể:

- Tự build URL theo quy tắc đã cấu hình
- Hoặc nhận playback information từ Media Server

Quan trọng:

`playbackUrl` trỏ tới Media Server hoặc CDN, không trỏ tới Spring Boot Backend.

---

# 10. Frontend phát video như thế nào?

Frontend chỉ gọi Backend một lần đầu để lấy `playbackUrl`.

Sau đó Video Player giao tiếp trực tiếp với Media Server/CDN.

```text
Frontend
   |
   | GET stream metadata
   v
Backend
   |
   | playbackUrl
   v
Video Player
   |
   | GET index.m3u8
   v
Media Server / CDN
```

Sau bước này Backend không truyền video.

---

# 11. HLS Playlist và Segment

Media Server nhận một stream RTMP liên tục từ OBS.

Sau đó Media Server tạo:

```text
index.m3u8
segment_100.ts
segment_101.ts
segment_102.ts
...
```

Trong đó:

```text
index.m3u8
```

là playlist.

Còn:

```text
.ts / .m4s
```

là các đoạn video/audio thật dưới dạng binary data.

Ví dụ:

```text
index.m3u8
   |
   +-- segment_100
   +-- segment_101
   +-- segment_102
```

Một lúc sau:

```text
index.m3u8
   |
   +-- segment_101
   +-- segment_102
   +-- segment_103
```

URL `index.m3u8` vẫn giữ nguyên nhưng nội dung playlist thay đổi liên tục.

---

# 12. Video Player biết segment mới bằng cách nào?

Video Player như `hls.js` tự động gọi lại playlist định kỳ.

```text
hls.js
  |
  | GET index.m3u8
  v
Media Server

segment 100
segment 101
segment 102
```

Sau đó:

```text
hls.js
  |
  | GET segment 100
  | GET segment 101
  | GET segment 102
```

Một lúc sau:

```text
GET index.m3u8
```

playlist mới có:

```text
segment 101
segment 102
segment 103
```

Player thấy `103` là mới nên tải tiếp.

Đây chính là lý do khi mở F12 sẽ thấy request liên tục.

Những request đó chủ yếu là:

```text
Frontend Video Player
    ->
Media Server / CDN
```

không phải:

```text
Frontend
    ->
Spring Boot Backend
```

---

# 13. Workflow video hoàn chỉnh

```text
OBS
 |
 | RTMP stream liên tục
 v
Media Server
 |
 | tạo HLS
 |
 +--> index.m3u8
 |
 +--> segment 1
 +--> segment 2
 +--> segment 3
 |
 v
Frontend Video Player
 |
 | tải playlist + segment mới
 v
Viewer xem livestream
```

---

# 14. Stop Stream

## 14.1 Streamer Stop trên OBS

Khi streamer bấm `Stop Streaming`:

```text
OBS
 |
 | đóng RTMP connection
 v
Media Server
 |
 | detect publisher disconnected
 v
Webhook Backend
```

Backend có thể chuyển:

```text
LIVE -> RECONNECTING
```

sau đó chờ grace period.

---

# 15. Unexpected Disconnect

Các trường hợp:

- Mất mạng
- OBS crash
- Máy tính tắt
- Network error

Media Server chỉ biết:

```text
Publisher disconnected
```

Media Server không biết chính xác nguyên nhân.

Do đó Backend có thể xử lý:

```text
LIVE
 |
 v
RECONNECTING
 |
 | wait 20-30 seconds
 |
 +--> OBS reconnect
 |       |
 |       v
 |      LIVE
 |
 +--> timeout
         |
         v
        ENDED
```

---

# 16. Reconnect

Nếu OBS reconnect lại đúng:

- Stream Key
- Stream Path
- Stream Session

thì có thể tiếp tục phiên livestream cũ.

```text
OBS reconnect
     |
     v
Media Server nhận video lại
     |
     v
Sinh segment mới
     |
     v
HLS playlist tiếp tục update
     |
     v
Viewer tiếp tục xem
```

Trong thời gian mất kết nối viewer có thể thấy:

- Buffering
- Đứng frame cuối
- Loading
- Thông báo "Streamer is reconnecting..."

---

# 17. Explicit Stop và Unexpected Disconnect

Chỉ dựa vào Media Server thì Backend không thể chắc chắn streamer:

- Chủ động Stop OBS
- Hay bị mất mạng

Cả hai đều có thể chỉ xuất hiện dưới dạng:

```text
publisher disconnected
```

Nếu muốn Stop thật ngay lập tức, hệ thống có thể có action riêng:

```text
Streamer
 |
 | End Stream
 v
Frontend
 |
 | POST /streams/{id}/terminate
 v
Backend
 |
 | terminate stream
 v
Media Server
```

Khi đó Backend biết chắc đây là explicit stop.

---

# 18. Media Server Webhook

Media Server có thể gọi webhook khi:

```text
Publisher connected
Publisher disconnected
Stream ready
Stream not ready
Recording started
Recording completed
```

Các event chính cho MVP:

```text
PUBLISH
UNPUBLISH / DISCONNECT
```

Flow:

```text
Media Server
    |
    | HTTP callback
    v
Stream Service
```

Backend chịu trách nhiệm chuyển callback kỹ thuật thành business state.

Ví dụ:

```text
PUBLISH
→ LIVE

DISCONNECT
→ RECONNECTING

Reconnect timeout
→ ENDED
```

---

# 19. Vai trò của Kafka

Kafka không dùng để truyền video.

Không làm:

```text
OBS
→ Kafka
→ Video Segment
```

Kafka dùng cho các business event quanh livestream.

Ví dụ:

```text
StreamStarted
StreamEnded
ViewerJoined
ViewerLeft
DonationSucceeded
```

Flow:

```text
Stream Service
     |
     | StreamStarted
     v
Kafka
 |
 +--> Notification Service
 +--> Analytics Service
 +--> Recommendation Service
```

Video vẫn đi theo đường riêng:

```text
OBS
→ Media Server
→ HLS / CDN
→ Frontend
```

---

# 20. Kiến trúc tổng hợp

```text
                         BUSINESS PLANE

Frontend
   |
   | REST
   v
API Gateway
   |
   v
Stream Service
   |
   +------ PostgreSQL
   |
   +------ Kafka
   |
   +------ Media Server Webhooks


                          MEDIA PLANE

Streamer
   |
   v
  OBS
   |
   | RTMP
   v
Media Server
   |
   +------ HLS Playlist
   |
   +------ Video Segments
   |
   +------ Recording
   |
   v
 CDN (optional)
   |
   v
Frontend Video Player
   |
   v
 Viewer
```

---

# 21. Tóm tắt

```text
Streamer tạo Stream Session
        ↓
Backend tạo Stream Key
        ↓
Streamer nhập URL + Key vào OBS
        ↓
OBS gửi RTMP tới Media Server
        ↓
Media Server validate Key với Backend
        ↓
Media Server nhận livestream
        ↓
Backend chuyển Stream = LIVE
        ↓
Frontend lấy playbackUrl từ Backend
        ↓
Video Player dùng playbackUrl
        ↓
Media Server cung cấp HLS playlist
        ↓
Player tự tải các segment mới liên tục
        ↓
Viewer xem livestream

OBS disconnect
        ↓
Media Server callback Backend
        ↓
RECONNECTING
        ↓
Reconnect → LIVE
Timeout   → ENDED
```

Điểm cốt lõi:

> Spring Boot quản lý business của livestream.

> Media Server quản lý luồng video/audio.

> OBS gửi media tới Media Server.

> Frontend lấy `playbackUrl` từ Backend nhưng tải video trực tiếp từ Media Server/CDN.

> Kafka xử lý business events xung quanh livestream, không dùng để truyền video.
