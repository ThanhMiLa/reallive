# Step 10 — Kafka consumer và gửi OTP qua SendGrid

Trạng thái: đã triển khai và kiểm thử tự động; kiểm tra mailbox thật còn thực hiện thủ công.

## Phạm vi

Luồng: `User Service → Kafka → Notification Service → SendGrid`.
User Service sở hữu OTP, Redis và xác thực. Notification chỉ gửi email bằng Dynamic Template,
lưu kết quả tiếp nhận vào `email_notifications`. Webhook thuộc step 11.

## Các thành phần

| Thành phần | Trách nhiệm |
|---|---|
| `EmailVerificationConsumer` / `EmailVerificationMessageReader` | Nhận, deserialize và validate message Kafka |
| `EmailVerificationDeliveryService` / implementation | Gọi util build payload, kiểm tra expiry, phân loại response/lỗi và điều phối history |
| `email.util.SendGridMailRequestBuilder` | Chuyển message, template đã lưu và cấu hình sender thành SendGridMailRequest |
| `service.impl.EmailNotificationHistory` | Transaction ngắn để tạo, khóa, cập nhật và recovery history |
| `SendGridClient` | Chỉ gọi HTTP với SendGridMailRequest đã được service chuẩn bị |
| `EmailKafkaRecovery` / `EmailKafkaConfig` | Backoff, recovery và DLT có broker acknowledgement |
| `EmailSendingConfig` / `EmailDeliveryProperties` | HTTP client, Clock, template và timeout |

## Luồng xử lý

1. Validate schema version 1, UUID, email, OTP String 6 số, timestamp và Kafka key khớp user ID.
   Message sai vào DLT; không tạo history thiếu dữ liệu, không log raw payload.
2. Tạo history `PENDING` trước HTTP. `UNIQUE(event_id)` bảo vệ insert đồng thời;
   nếu insert trùng, đọc lại bản ghi đã commit.
3. Khóa row bằng `PESSIMISTIC_WRITE`, so sánh metadata và kiểm tra expiry, lịch retry, attempt limit.
   Chuyển `PENDING → PROCESSING`, tăng attempt và commit. Chỉ kết quả claim `PROCESSING` cấp quyền gửi.
   Một event đã `PROCESSING` chuyển `UNKNOWN`, không được claim lại.
4. Delivery service gọi `SendGridMailRequestBuilder.build()` để tạo `SendGridMailRequest` với sender từ cấu hình,
   template đã lưu, recipient và OTP từ message.
   `dynamic_template_data` chứa `otp` / `expiresInMinutes`; `custom_args` chỉ chứa `eventId`.
5. Gọi `POST /v3/mail/send`, không giữ transaction DB quanh HTTP, không tự retry POST hoặc follow redirect.
   Timeout tối đa 10 giây và không vượt thời gian còn lại của OTP; connect timeout 2 giây.
6. Persist kết quả dưới lock, chỉ cập nhật đúng attempt. Nếu DB lỗi sau HTTP 202, chỉ retry ghi DB.
   Worker cũ có thể xác nhận `UNKNOWN → ACCEPTED` nhưng không mở lại lịch gửi sau recovery.
7. Listener chỉ kết thúc sau khi history được lưu; recovery persist trước DLT, DLT được broker xác nhận
   trước khi cho phép commit offset. Lỗi DB/DLT không được nuốt.

## Trạng thái và retry

| Kết quả | History / hành động |
|---|---|
| HTTP 202, body trống hoặc thiếu `X-Message-ID` | `ACCEPTED`; không gửi lại |
| HTTP lỗi cố định, kể cả redirect | `FAILED`; DLT |
| HTTP 429/5xx, hoặc lỗi kết nối chắc chắn trước gửi | `PENDING`; Kafka retry hữu hạn |
| Timeout/reset không rõ SendGrid đã nhận chưa | `UNKNOWN`; DLT, không tự gửi lại |
| OTP hết hạn, hoặc lịch retry vượt expiry | `EXPIRED`; kết thúc, không gọi HTTP |
| Message trùng `ACCEPTED` / `EXPIRED` | Kết thúc, không thay đổi kết quả hoặc gửi lại |
| Message trùng `FAILED` / `UNKNOWN` | Vẫn thử DLT nếu lần trước chưa publish được |

Giới hạn bền vững: tối đa `1 + maxRetries` attempt, mặc định 3.
Migration V3 thêm `next_attempt_at`; backoff đọc lại lịch cho từng retry và tôn trọng `Retry-After`.
Timestamp persist/compare được chuẩn hóa microseconds để tương thích PostgreSQL.

`email.dto.response.SendGridSubmissionResult` do service tạo, tái sử dụng `EmailNotificationStatus`; `PENDING` biểu thị kết quả có thể retry.
Không lưu OTP, hash, raw provider body hoặc credential vào history. Chỉ giữ HTTP status và mã lỗi an toàn.
Kafka/DB và SendGrid không có transaction chung; `UNKNOWN` cần được tra cứu thay vì tự resend.

## Kiểm thử và vận hành

- HTTP stub: payload template, 202/body trống, 4xx, 429/5xx, Retry-After, timeout, connection refusal, expiry.
- DB: unique event, claim đồng thời, metadata conflict, rollback, attempt fencing, retry schedule và late response.
- Embedded Kafka: success/duplicate, retry/exhaustion, invalid message, timeout, DB sau 202 và DLT failure.
- Chạy `./mvnw clean verify`; persistence/concurrency còn được kiểm tra trên PostgreSQL 17 cô lập.
- Test tự động dùng credentials giả và không gửi mail thật. Smoke test mailbox theo
  [local setup](../development/Email%20Verification%20Local%20Setup.md).

Cấu hình template cần active version với biến `otp` / `expiresInMinutes`:
[SendGrid Dynamic Templates](https://www.twilio.com/docs/sendgrid/ui/sending-email/how-to-send-an-email-with-dynamic-templates).
