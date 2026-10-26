# Kế hoạch Giai đoạn 2 — Auth, eligibility và JWT media

- Ngày: 2026-10-26.
- Trạng thái: STR-201–208 hoàn tất, M2 đạt; Stream 103/103 test, kiểm chứng Gateway/Stream/MediaMTX thật 1/1. Số test của từng service và các test Redis chưa bật được ghi trong báo cáo kiểm chứng.
- Nguồn: [Implementation Plan](Streaming%20MVP%20Implementation%20Plan.md), [STR-201–208](Streaming%20MVP%20Task%20Breakdown.md#4-giai-đoạn-2--auth-eligibility-và-jwt-media), [Auth State](../contracts/Auth%20State.md).
- Phạm vi: xây contract, kiểm tra điều kiện phát qua User/Channel Service, ký media JWT, phục vụ JWKS và nhận callback trực tiếp từ MediaMTX (cho MVP local gọi trực tiếp Stream Service không qua Gateway và không cần HMAC callback secret). Giữ mô hình MVP gọi đồng bộ, hai bảng session/history và pessimistic locking của M1.

## 1. Các quyết định đã chốt

| Nội dung | Quyết định |
|---|---|
| Publish JWT | 8 giờ |
| Read JWT | 5 phút |
| Control API JWT | 5 phút, chỉ backend giữ |
| Thuật toán/khóa | RS256; RSA ít nhất 2048 bit; private PKCS#8 DER và public X.509 DER được mã hóa Base64 |
| Key lifecycle | Nạp từ cấu hình ổn định, không sinh lại khi restart; không dùng account signer |
| Identity | Principal là user ID; resolve streamer ID rồi channel ID, kiểm tra owner khớp |
| Account status | Gateway tiếp tục kiểm tra ACTIVE theo Auth State |
| Điều kiện phát | User tồn tại, email verified, streamer ACTIVE, channel ACTIVE/chưa xóa/đúng owner, category hợp lệ |
| Client nội bộ | Feign đồng bộ, connect 2 giây/read 5 giây, không retry; không chuyển tiếp account cookie |
| Callback | MediaMTX gọi trực tiếp Stream Service (port 8082, permitAll); cho MVP local đơn giản hóa không dùng HMAC secret |
| Callback business | Chưa có ở giai đoạn 2; callback trực tiếp đi tới 404, không trả 200 giả |

TTL 8h/5m/5m thay thế đề xuất cũ 24h/15m/5m. JWT mới không thu hồi JWT cũ; TTL không bảo đảm ngắt phiên HLS đang mở. Không lưu JWT/stream key/hash vào database.

## 2. STR-201 — Contract và lỗi

- Request Create: `creationRequestId`, title trim/không rỗng/tối đa 255 ký tự, category UUID.
- PATCH title/category: phân biệt không gửi field với gửi null; từ chối null rõ ràng, payload rỗng và field lạ. Không cho phép client gán các cờ theo dõi field nội bộ.
- Tách owner response, metadata, publish credentials và playback access. Metadata không chứa JWT/stream key/Control API token. Mapper trả thời gian UTC.
- Giữ `ApiResponse` cho API nghiệp vụ và `AppException(ErrorCode)` cho lỗi; JWKS là JSON chuẩn riêng.
- Thêm lỗi eligibility/channel/category/callback theo mã độc nhất; dữ liệu remote sai, timeout và lỗi dịch vụ trả 503; từ chối nghiệp vụ trả 400/403 theo contract.
- Chưa mở Create/Update/End/playback API. Header no-store sẽ được đặt ở controller cấp credential khi triển khai giai đoạn 3/5.

## 3. STR-202 — User eligibility

Bổ sung `GET /user/internal/api/streamers/by-user/{userId}/streaming-eligibility` trả `ApiResponse<{userId, streamerId}>`.

User Service dùng validator email verified và streamer ACTIVE. Endpoint `/active` hiện hữu giữ hành vi cũ, tránh đổi contract caller khác. Account ACTIVE/BANNED vẫn do Gateway xử lý.

Stream Service xác minh response/envelope, user ID khớp principal và streamer ID không null. Service eligibility chạy trước transaction ghi session; không tự tạo session hoặc cấp token trong bước này.

## 4. STR-203 — Channel context/category

- `GET /channel/internal/api/channels/by-streamer/{streamerId}/streaming-context`: trả channel ID, streamer ID, owner user ID và status. Từ chối channel thiếu, đã xóa, không ACTIVE hoặc không có owner.
- Stream Service kiểm tra streamer ID và owner user ID khớp context đã resolve; không tin ID từ frontend.
- Dùng lại `POST /channel/internal/api/categories/validate` để kiểm tra category.

Các endpoint internal dựa trên ranh giới mạng backend tin cậy hiện hữu; không mở route `/internal/**` qua Gateway. Không thêm role/permission hoặc cookie nội bộ mới.

## 5. STR-204–205 — Cấu hình và phát hành JWT

- `MediaProperties`, `MediaConfiguration`, `MediaKeyMaterial` trong `common.configuration` bind cấu hình, parse cặp khóa, kiểm tra modulus/độ dài và ký/verify thử để xác nhận cặp khóa.
- Thiếu/sai khóa, TTL, URL phải dừng startup bằng thông báo đã loại dữ liệu bí mật. Không in private key qua toString/log.
- `MediaJwtIssuer` thuộc `stream.adapter.media`; dùng Clock UTC, RS256, `typ=JWT`, `kid`, `iss`, `aud`, `sub`, `iat`, `exp`, `jti` mới mỗi lần cấp.
- `mediamtx_permissions` chỉ có một quyền: publish/read tại exact path `live/{sessionId}`, hoặc api cho backend. Không dùng wildcard hay quyền đọc/phát chéo session.
- Server OBS lấy từ cấu hình; stream key `{sessionId}?token={PublishJWT}`. DTO chứa credential có toString che token.
- Hướng dẫn chuyển PEM local sang Base64 DER tại [Stream Service README](../../stream-service/README.md). Các khóa test được tạo trong bộ nhớ, không commit key thật.

## 6. STR-206 — Phục vụ JWKS trực tiếp và phân định ranh giới Gateway

- `GET /stream/api/media/jwks` được Stream Service phục vụ trực tiếp (port 8082, permitAll), trả raw `{keys:[...]}` chứa `kty/kid/use/alg/n/e`, không chứa private parameters hoặc ApiResponse.
- Gateway không mở route `/stream/api/media/**`; các request từ ngoài vào toàn hệ thống vẫn qua Gateway theo đúng kiến trúc.
- Cookie gửi kèm vẫn được kiểm tra theo Auth State nếu client gửi token cookie. Media JWT không dùng thay account cookie.
- MediaMTX tự verify bằng JWKS gọi trực tiếp tới Stream Service; không gọi backend để verify từng token. Cấu hình issuer/audience/kid phải thống nhất với MediaMTX.

## 7. STR-207 — Tiếp nhận callback trực tiếp từ MediaMTX

- `POST /api/media/events` được Stream Service cho phép gọi trực tiếp (permitAll, không yêu cầu account cookie). Gateway không proxy route này.
- Cho MVP local, MediaMTX gọi trực tiếp Stream Service trong mạng Docker/host, đơn giản hóa bằng cách bỏ secret HMAC và header ký.
- DTO đúng sáu field: eventId, eventType ONLINE/OFFLINE, path live/UUID, sourceId, sourceType rtmpConn/rtmpsConn, occurredAt.
- DTO sai trả 400; callback hợp lệ tới endpoint chưa có business handler giai đoạn 4 sẽ trả 404 (không trả 200 giả). Chưa ghi session/history, chưa xử lý dedupe/lifecycle.
- ERROR dispatch được phép trả lỗi HTTP gốc thay vì nuốt lỗi.

## 8. STR-208 — Kiểm chứng và điều kiện nghiệm thu

1. `clean verify` tại stream-service, user-service, channel-service và api-gateway: DTO, khóa/JWT/JWKS, ownership/eligibility, lỗi HTTP/timeout và account regression.
2. Producer endpoint tests dùng controller/security/repository thật với H2 cô lập; Stream Feign tests gọi HTTP server cô lập với contract tương ứng. Không coi đây là bốn service production chạy chung.
3. PostgreSQL 17 Testcontainers tiếp tục kiểm tra migration, index, khóa, idempotency và rollback M1.
4. Chạy riêng `MediaMtxAuthenticationIT` với Stream Service HTTP thật và một container MediaMTX dùng cổng ngẫu nhiên. MediaMTX tải JWKS trực tiếp từ Stream Service và nhận JWT Java cho api/publish/read.
5. Kiểm tra thiếu token, sai action/path/chữ ký/issuer/audience, hết hạn; không dùng media JWT làm account cookie; callback trực tiếp từ MediaMTX tới Stream Service.
6. Nguồn H.264/AAC tổng hợp bằng FFmpeg; dọn container/path/process của test. Giữ MediaMTX, OBS và database ứng dụng hiện hữu nguyên cấu hình.
7. Đồng bộ TTL fixture M0 và chạy lại unit test fixture/helper. Không chạy lại thao tác OBS/browser bằng tự động hóa.

Lệnh chạy lại và bằng chứng/giới hạn được ghi tại [kết quả kiểm chứng](../testing/Streaming%20MVP%20Tests.md) và [README](../../stream-service/README.md).

## 9. Phần tiếp theo

STR-301 xây Control API adapter thật cho runtime ứng dụng. Sau đó nối eligibility/token issuer vào Create/Update/cấp lại OBS (giai đoạn 3), callback/End/reconnect (giai đoạn 4), playback access (giai đoạn 5). Không thêm bảng, migration, optimistic version, sync task, queue hoặc worker retry trong giai đoạn 2.
