# Streaming MVP — Kết quả rà soát giai đoạn 0

- Ngày rà soát STR-001: 2026-10-24.
- Nội dung dưới đây ghi nhận thời điểm STR-001 rà soát tài liệu/code, trước khi kiểm chứng runtime. Kết quả M0 sau đó được cập nhật ở [Streaming MVP Tests](../testing/Streaming%20MVP%20Tests.md).
- Nguồn thiết kế/contract dùng tiếp: [Implementation Plan](Streaming%20MVP%20Implementation%20Plan.md), các mục 1, 4–10.
- Theo dõi triển khai: [Task Breakdown](Streaming%20MVP%20Task%20Breakdown.md).

## 1. Phạm vi đã đối chiếu

| Nội dung | Quyết định dùng tiếp |
|---|---|
| Media | Một MediaMTX trong một container; pin `1.21.2` để kiểm chứng |
| Video/audio | OBS → RTMP/RTMPS → MediaMTX → HLS → React/hls.js |
| Backend | Quản lý nghiệp vụ/session/quyền; không proxy media hoặc auth từng segment |
| Media auth | RS256, JWKS chỉ chứa public key; quyền publish/read/api tách biệt |
| Path | Exact path `live/{sessionId}` do backend đăng ký; không catch-all |
| Credential OBS | `serverUrl` + `{sessionId}?token={PublishJWT}`; chỉ cấp sau khi path sẵn sàng |
| Persistence credential | Không lưu JWT thô hoặc stream-key hash trong DB |
| Lifecycle | READY/LIVE/RECONNECTING/ENDED; chỉ nguồn thực tế online mới tạo LIVE |
| Reconnect | Grace 60 giây, cấu hình được; deadline lưu DB, không gia hạn qua callback lặp/restart |
| Slot channel | Một session active; ENDED còn chờ cleanup vẫn giữ slot đến khi media CLOSED |
| End | ENDED terminal; đóng path/ngắt nguồn thật, retry và đối soát nếu lỗi |
| Viewer | PUBLIC; account phải đăng nhập để xin Read JWT |
| Phạm vi loại trừ | CDN, chat, donate, viewer count, recording/VOD, transcoding, DRM, nhiều media instance |

Kiến trúc streaming chuyên biệt là nguồn phạm vi MVP. Các khả năng tổng thể trong tài liệu sản phẩm/System Architecture như rotate key, viewer count, Kafka lifecycle event và moderation không tự trở thành yêu cầu của đợt này.

## 2. Contract dùng chung

Giữ các endpoint và HTTP status ở mục 6 của implementation plan. Các tên dưới đây là contract dự kiến để triển khai, chưa phải API đã tồn tại.

| Trường hợp | Request | Result trong `ApiResponse` |
|---|---|---|
| Tạo | `CreateStreamSessionRequest`: requestId/title/categoryId | `CreateStreamSessionResponse`: session/publishCredentials |
| Sửa metadata | `UpdateStreamSessionRequest`: title/categoryId | Metadata session sau cập nhật |
| Current owner | Không có body | `StreamSessionResponse` hoặc `null` |
| Cấp thông tin OBS | Không có body | `StreamPublishCredentialsResponse`: sessionId/serverUrl/streamKey/publishExpiresAt |
| End | Không có body | Metadata owner có mediaCleanupPending |
| Detail viewer | Không có body | Metadata session, không có credential hoặc trạng thái đồng bộ nội bộ |
| List | page/size | `PageResponse<StreamListItem>`: content/page/size/totalElements/totalPages |
| Quyền xem | Không có body | `StreamPlaybackAccessResponse`: sessionId/playbackUrl/readToken/readExpiresAt |

- Principal account xác định owner; user ID, streamer ID và channel ID là ba định danh khác nhau.
- `requestId` là UUID của một lần tạo; retry dùng lại ID, không sửa metadata ngầm. Request khác khi channel còn giữ slot trả 409.
- PATCH chỉ cho title/categoryId; field thiếu giữ nguyên. Null, title trắng, body rỗng hoặc field ngoài contract trả 400.
- Create lần đầu 201, retry thành công 200; lỗi provisioning sau commit có thể 503. Khôi phục qua current/cùng requestId.
- End trả 200 khi đã xác nhận đóng; 202 khi ENDED nhưng còn pending cleanup. Owner vẫn được kết thúc khi không còn đủ eligibility để tạo/phát.
- Playback access chỉ cấp cho PUBLIC/LIVE; trạng thái khác trả 409. Metadata vẫn mô tả READY/RECONNECTING/ENDED.
- Business API giữ envelope hiện có: status/code/message/result/timestamp. JWKS trả JSON chuẩn không bọc envelope; credential response có `Cache-Control: no-store`.
- `publishReady` và `mediaCleanupPending` thuộc metadata owner. Metadata/list/polling không chứa JWT.
- Read token đi qua Authorization Bearer tới đúng media origin/path, giữ trong memory; không thay account cookie.

Callback dùng tên `MediaStreamEventRequest` với eventId/eventType/path/sourceId/sourceType/occurredAt; eventType là ONLINE hoặc OFFLINE. Header `X-Media-Timestamp` dùng Unix seconds, `X-Media-Signature` dùng `sha256=<hex>` trên timestamp + newline + raw body. Retry giữ body/eventId và ký lại timestamp. Không gửi MTX_QUERY hoặc JWT trong callback.

## 3. Mặc định làm việc và trạng thái xác nhận

Các giá trị sau tiếp tục là đề xuất có thể cấu hình. Việc bắt đầu triển khai không ghi nhận chúng thành lựa chọn đã được người dùng xác nhận riêng.

| Nhóm | Đề xuất dùng khi thử nghiệm |
|---|---|
| JWT TTL | Publish 24h; Read 15m; Control API 5m |
| JWT identity | kid `stream-rs256-v1`; iss `reallive-stream`; aud `reallive-media` |
| Cấp lại credential | Ký JWT mới trên cùng path; token cũ vẫn có thể còn hiệu lực |
| READY | Giữ đến khi owner kết thúc/hủy; không tự tạo session khi mở dashboard |
| Media | fMP4, một chất lượng; OBS H.264/AAC, keyframe khởi điểm 2 giây |
| Browser | Chrome/Edge desktop với hls.js; Safari/native HLS ngoài nghiệm thu đầu |
| UI | Poll metadata khoảng 3 giây khi tab hiển thị |
| Worker | Sync/reconcile 5 giây; reconnect sweep 1 giây; lease 30 giây |
| Callback | Cửa sổ timestamp 60 giây; secret riêng, body nhỏ |
| HTTP media adapter | Connect 2 giây; read 5 giây |

Reconnect grace 60 giây là mặc định đã chốt trong kiến trúc, khác với các đề xuất trên. TTL JWT không phải thời lượng cứng của livestream; cấp token mới không revoke token cũ, exp không bảo đảm ngắt một phiên HLS đang chạy.

## 4. Những điểm đã xác nhận từ repository

| Quan sát | Việc triển khai tiếp theo |
|---|---|
| Gateway route `/stream/api/**`, port 8888; Stream Service context `/stream`, port 8082, bind loopback | HLS dùng host port 8898; JWKS/callback chỉ mở ngoại lệ đúng method/path ở STR-206/207 |
| Stream Service security hiện yêu cầu auth cho mọi request; chưa có controller streaming | Fixture ở STR-002 phục vụ JWKS/callback trước; backend thật triển khai giai đoạn 2–4 |
| User Service active-streamer lookup chưa kiểm tra email verified | STR-202 bổ sung eligibility; không dùng active lookup để giả định mọi điều kiện đã đạt |
| Channel internal controller hiện có create/update avatar; chưa có lookup context streaming | STR-203 bổ sung lookup ownership/status và batch khi cần |
| `ApiResponse` hiện có; Channel Service có `PageResponse` | Tái sử dụng convention; không import DTO implementation xuyên service |
| hls.js trong lockfile là 1.7.3; web đã có npm test | Fixture/player dùng dependency đã có; không nâng dependency chỉ để kiểm chứng |
| Data model không có stream_key_hash; history còn CREATED và chưa có các field/task đồng bộ đề xuất | STR-101 đổi thành READY và cập nhật schema/tài liệu; không dùng history CREATED cho implementation mới |
| System Architecture nêu cả MediaMTX/SRS và nhiều feature dài hạn | MVP này chỉ theo một MediaMTX và phạm vi ở mục 1 |

MediaMTX ở `stream/adapter/media`; User/Channel API ở `stream/client/user` và `stream/client/channel`, theo skill code-architecture. Không tạo các package/class rỗng trong task rà soát.

## 5. Bằng chứng và giới hạn STR-001

- Đã đọc implementation plan, toàn bộ backlog, streaming architecture, Overview/Features/Actor Roles, System Architecture, Auth State và phần Stream Service trong data model.
- Đã đối chiếu Gateway filter/route, Stream Service security/envelope, User Service active lookup, Channel internal controller/PageResponse và dependency web.
- Đã đối chiếu các tên cấu hình JWT, HLS và hook với [config chính thức MediaMTX v1.21.2](https://github.com/bluenviron/mediamtx/blob/v1.21.2/mediamtx.yml); endpoint quản trị có nguồn [OpenAPI cùng phiên bản](https://github.com/bluenviron/mediamtx/blob/v1.21.2/api/openapi.yaml).
- Đã thống nhất tên create result, loại bỏ tên wrapper list dư thừa, làm rõ PATCH/pagination và định dạng header callback trong implementation plan.
- Tại thời điểm STR-001, STR-002–006 còn cần bằng chứng thực thi: chữ ký/JWKS, container/network, OBS, browser HLS, hooks/reconnect và old-token-after-delete. Đọc config/OpenAPI không được tính là runtime pass; kết quả thực thi được ghi riêng trong tài liệu kiểm thử.
