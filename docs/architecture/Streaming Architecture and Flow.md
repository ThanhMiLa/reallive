# Streaming — Kiến trúc và Workflow

## 1. Giới thiệu và Hướng dẫn sử dụng

Hệ thống cho phép streamer phát trực tiếp từ **OBS** tới **MediaMTX** qua RTMP/RTMPS và người xem xem trực tiếp trên **React + hls.js** qua HLS. **Stream Service** quản lý cấu hình kênh, vòng đời phiên phát (`StreamSession`), kiểm tra quyền nghiệp vụ và cấp/ký JWT bằng khóa RSA; **MediaMTX** tự xác thực Publish JWT và Read JWT qua public key từ JWKS.

```text
Luồng video:   [OBS] -- RTMP/RTMPS --> [MediaMTX] -- HLS --> [React + hls.js]
Luồng API:     [React UI] --> [API Gateway] --> [Stream Service] --> [PostgreSQL]
Media <-> BE:  [MediaMTX] -- JWKS / Webhook ONLINE, OFFLINE --> [Stream Service]
               [Stream Service] -- Control API (ensurePath / kick / deletePath) --> [MediaMTX]
```

### Hướng dẫn livestream từng bước

1. **Mở Stream Manager**: Đăng nhập tài khoản đã là streamer và vào trang **Stream Manager**. Ở lần đầu tiên, hệ thống tự khởi tạo cấu hình kênh (`channel_stream_configs`) và cấp **Stream Key** cố định cho kênh.
2. **Cấu hình OBS (chỉ làm 1 lần)**: Copy **Server URL** (`rtmp://localhost:1935/live`) và **Stream Key** (`{ingestKeyId}?token={PublishJWT}`) dán vào **OBS → Settings → Stream → Custom**. Các buổi live sau không cần nhập lại.
3. **Thiết lập thông tin buổi phát**: Nhập **Title** và chọn **Category**, sau đó bấm lưu (**Save**) trên Stream Manager.
4. **Bắt đầu phát từ OBS**: Bấm **Start Streaming** trong OBS. MediaMTX xác thực kết nối và báo về backend; **Stream Service** tự động tạo phiên livestream mới ở trạng thái **LIVE** và Stream Manager tự bật Preview, Chat, CCU.
5. **Cập nhật khi đang phát**: Có thể chỉnh sửa **Title** hoặc **Category** ngay trong lúc đang live; thông tin mới áp dụng cho cả phiên hiện tại lẫn cấu hình mặc định cho các lần live sau.
6. **Kết thúc livestream**: Bấm **Stop Streaming** trên OBS. Phiên chuyển sang trạng thái chờ kết nối lại (`RECONNECTING`) trong **60 giây**; hết 60 giây không phát lại, hệ thống tự động đóng phiên thành **ENDED**.
7. **Reset Stream Key khi bị lộ**: Nếu nghi ngờ lộ Stream Key, bấm **Reset Stream Key** trên Stream Manager để khóa đường dẫn cũ trên MediaMTX, sinh key mới và dán lại key mới vào OBS.

---

## 2. Workflow Start Livestream

### 2.1. Chuẩn bị tại Stream Manager (Lazy Auto-Provision & Lưu cấu hình)

![Chuẩn bị tại Stream Manager](./image/Setup%20Stream%20Manager.png)

<details>
<summary>Mã nguồn Mermaid</summary>

```mermaid
sequenceDiagram
    autonumber
    participant UI as Stream Manager
    participant GW as API Gateway
    participant SS as Stream Service
    participant UC as User / Channel Service
    participant DB as PostgreSQL
    participant MTX as MediaMTX

    UI->>GW: GET /stream/api/streams/me/setup
    GW->>SS: Forward request (userId)
    SS->>DB: Tìm channel_stream_configs theo ownerUserId
    alt Chưa có cấu hình kênh (lần đầu)
        SS->>UC: Kiểm tra điều kiện streamer & lấy channelId
        UC-->>SS: Hợp lệ (channelId, streamerId)
        SS->>DB: Tạo channel_stream_configs (ingestKeyId ngẫu nhiên)
    end
    SS->>MTX: Control API ensurePath(live/ingestKeyId)
    MTX-->>SS: 200 OK (path sẵn sàng)
    SS-->>UI: Trả title, categoryId, serverUrl, streamKey

    opt Streamer cập nhật Title / Category
        UI->>GW: PUT /stream/api/streams/me/setup (title, categoryId)
        GW->>SS: Forward request
        SS->>UC: Validate category & quyền kênh
        SS->>DB: Cập nhật channel_stream_configs (và session nếu đang LIVE)
        SS-->>UI: 200 OK
    end
```

</details>

### 2.2. Bấm Start Streaming trên OBS (Tự tạo session & lên LIVE)

![Bấm Start Streaming trên OBS](./image/Start%20Streaming%20Workflow.png)

<details>
<summary>Mã nguồn Mermaid</summary>

```mermaid
sequenceDiagram
    autonumber
    participant OBS as OBS
    participant MTX as MediaMTX
    participant Hook as media_hook.py
    participant SS as Stream Service
    participant UC as User / Channel Service
    participant DB as PostgreSQL
    participant UI as Stream Manager

    OBS->>MTX: RTMP Publish live/{ingestKeyId}?token={PublishJWT}
    MTX->>MTX: Kiểm tra path tồn tại & verify Publish JWT qua JWKS
    MTX-->>OBS: RTMP NetStream.Publish.Start (chấp nhận phát)
    MTX->>Hook: Kích hoạt runOnOnline
    Hook->>SS: POST /stream/api/media/events (ONLINE, live/{ingestKeyId}, sourceId)

    SS->>DB: Lock row channel_stream_configs (SELECT ... FOR UPDATE theo ingestKeyId)
    SS->>MTX: GET source thực tế trên MediaMTX
    MTX-->>SS: Xác nhận source online & khớp sourceId
    SS->>UC: Hậu kiểm User / Channel còn ACTIVE

    alt Key không khớp hoặc User/Channel không hợp lệ
        SS->>MTX: Control API kick(sourceId) & deletePath(live/ingestKeyId)
        SS-->>Hook: Từ chối tạo session (IGNORED)
    else Đã có session RECONNECTING trong hạn 60s
        SS->>DB: Chuyển RECONNECTING -> LIVE (giữ nguyên sessionId)
    else Chưa có session active
        SS->>DB: Tạo mới StreamSession (status=LIVE, snapshot title/categoryId) & ghi history
    end

    SS-->>Hook: 200 OK
    loop Polling mỗi 3s khi ở màn hình chờ
        UI->>SS: GET /stream/api/streams/me/current
        SS-->>UI: Trả session LIVE -> tự mở Preview, Chat & CCU
    end
```

</details>

---

## 3. Workflow Xem Stream

![Workflow Xem Stream](./image/Watch%20Stream%20Workflow.png)

<details>
<summary>Mã nguồn Mermaid</summary>

```mermaid
sequenceDiagram
    autonumber
    participant V as Viewer (React)
    participant GW as API Gateway
    participant SS as Stream Service
    participant UC as Channel Service
    participant DB as PostgreSQL
    participant P as Player (hls.js)
    participant MTX as MediaMTX

    V->>GW: GET /stream/api/streams/{sessionId}
    GW->>SS: Forward request
    SS->>DB: Đọc metadata & status của session
    SS-->>V: Trả metadata (status, title, CCU) — chưa cấp token

    alt Status == LIVE và cần mở player
        V->>GW: POST /stream/api/streams/{sessionId}/playback-access
        GW->>SS: Forward request (viewerUserId)
        SS->>UC: Kiểm tra quyền xem kênh (không bị ban)
        SS->>DB: Lock & recheck session PUBLIC + LIVE
        SS->>SS: Ký Read JWT (quyền read path live/{ingestKeyId}, TTL 5m)
        SS-->>V: Trả playbackUrl (/live/{ingestKeyId}/index.m3u8) + Read JWT

        P->>MTX: GET index.m3u8 + Authorization Bearer {ReadJWT}
        MTX->>MTX: Verify Read JWT bằng JWKS public key & mở phiên HLS
        MTX-->>P: Trả playlist .m3u8

        par Luồng phát video HLS
            loop Liên tục khi đang xem
                P->>MTX: GET playlist & segment video/audio mới
                MTX-->>P: Dữ liệu media
            end
        and Luồng đồng bộ trạng thái
            loop Mỗi 3s khi tab hiển thị
                V->>SS: GET /stream/api/streams/{sessionId}
                SS-->>V: Status, serverTime, CCU mới
            end
        end
    else Status là RECONNECTING / ENDED
        Note over V: Hiển thị thông báo trạng thái, không xin Read JWT
    end
```

</details>

---

## 4. Workflow Reconnecting và End Stream

![Workflow Reconnecting và End Stream](./image/Workflow%20Reconnecting%20and%20End%20Stream.png)

<details>
<summary>Mã nguồn Mermaid</summary>

```mermaid
sequenceDiagram
    autonumber
    participant OBS as OBS
    participant MTX as MediaMTX
    participant Hook as media_hook.py
    participant SS as Stream Service
    participant Sched as ReconnectScheduler
    participant DB as PostgreSQL
    participant V as Viewer / Stream Manager

    OBS--xMTX: Ngắt kết nối RTMP (Stop Streaming hoặc mất mạng)
    MTX->>Hook: Kích hoạt runOnOffline
    Hook->>SS: POST /stream/api/media/events (OFFLINE, live/{ingestKeyId})
    SS->>DB: Lock channel_stream_configs & session
    SS->>MTX: Kiểm tra lại source không còn online
    MTX-->>SS: Xác nhận source offline
    SS->>DB: Chuyển LIVE -> RECONNECTING (reconnectDeadlineAt = now + 60s)
    SS-->>Hook: 200 OK
    Note over V: Polling GET /{sessionId} nhận RECONNECTING -> hiện chờ kết nối lại

    alt OBS nối lại trong vòng 60s (do rớt mạng tạm thời)
        OBS->>MTX: RTMP Publish lại live/{ingestKeyId}?token={PublishJWT}
        MTX->>MTX: Verify path & Publish JWT qua JWKS
        MTX->>Hook: Kích hoạt runOnOnline
        Hook->>SS: POST /stream/api/media/events (ONLINE, live/{ingestKeyId}, sourceId)
        SS->>DB: Lock channel_stream_configs & session
        SS->>DB: Chuyển RECONNECTING -> LIVE (giữ sessionId, chat, startedAt)
        SS-->>Hook: 200 OK
        Note over V: Polling nhận LIVE -> tiếp tục phát HLS
    else Quá 60s không nối lại (kết thúc stream)
        Sched->>SS: Quét session quá reconnectDeadlineAt (mỗi 1s)
        SS->>DB: Lock channel_stream_configs & session
        SS->>MTX: Kiểm tra & kick(sourceId) nếu nguồn còn online
        SS->>DB: Chuyển RECONNECTING -> ENDED & ghi history
        Note over V: Polling GET /{sessionId} nhận ENDED -> dừng Player & polling
    end
```

</details>

---

## 5. Cơ chế bảo mật

1. **Xác thực chữ ký bất đối xứng RS256 & JWKS**:
   - **Stream Service** giữ private key RS256 (nạp từ biến môi trường/secret) để ký toàn bộ Media JWT và công bố public key tại endpoint `GET /stream/api/media/jwks`.
   - **MediaMTX** tự tải và cache public key từ JWKS để verify mọi token; không lưu private key trên container media.
2. **Bảo vệ Stream Key của OBS (`ingestKeyId` + Publish JWT + Whitelist `paths: {}`)**:
   - Stream Key có dạng `{ingestKeyId}?token={PublishJWT}`. Trong đó `ingestKeyId` là UUID bí mật ngẫu nhiên của riêng kênh (tách biệt với `channelId` hay `sessionId`).
   - Publish JWT chỉ cấp đúng 1 quyền `action: publish` cho đúng path `live/{ingestKeyId}` và luôn có `exp` hợp lệ theo đặc tả của MediaMTX.
   - MediaMTX cấu hình `paths: {}` (không dùng `all_others` hay catch-all): chỉ exact path `live/{ingestKeyId}` do backend đăng ký qua Control API mới được phép kết nối RTMP.
3. **Thu hồi Stream Key khi bị lộ (Reset Key) & Ban tài khoản/kênh**:
   - Khi streamer bấm **Reset Stream Key** hoặc Admin ban kênh/user, **Stream Service** gọi `media.kick(...)` ngắt kết nối hiện tại, gọi `media.deletePath(oldIngestKeyId)` để **chặn key cũ ngay tại bước RTMP handshake của MediaMTX**, sau đó mới cấp `newIngestKeyId` và `ensurePath` mới.
   - Ở `onOnline`, backend hậu kiểm lại trạng thái `ACTIVE` của user/channel một lần nữa làm lớp bảo vệ thứ hai.
4. **Xác thực người xem bằng Read JWT**:
   - Read JWT tách biệt hoàn toàn với Publish JWT: chỉ có quyền `action: read` trên path đang phát, gắn `sub = viewerUserId` và có thời hạn ngắn (`5 phút`).
   - Player gửi Read JWT qua header `Authorization: Bearer ...` khi mở phiên HLS.
5. **Kiểm tra quyền xem & trạng thái tại Backend**:
   - Mọi request lấy quyền xem (`POST /playback-access`) đều đi qua **API Gateway**, xác thực tài khoản đăng nhập, kiểm tra session đang `PUBLIC` + `LIVE` và hỏi **Channel Service** xem user có bị chặn (ban) khỏi kênh hay không trước khi ký Read JWT.
6. **Bảo vệ Control API & Webhook nội bộ**:
   - MediaMTX Control API (`:9997`) yêu cầu Control JWT quyền `api` (TTL 5 phút), chỉ backend và hook nội bộ được giữ, không lộ qua Gateway hay trả về frontend.
   - `media_hook.py` gửi sự kiện `ONLINE`/`OFFLINE` trực tiếp qua mạng nội bộ Docker và tuyệt đối không copy `token`/query của publisher vào log hay payload callback.
7. **Chống chiếm luồng & giới hạn Origin**:
   - Đặt `overridePublisher: false` trên MediaMTX để kết nối RTMP mới không thể đá văng hoặc chiếm quyền phát của OBS đang live.
   - Giới hạn `hlsAllowOrigins` trên MediaMTX để trình duyệt ngoài origin cho phép không thể tải playlist/segment HLS.
