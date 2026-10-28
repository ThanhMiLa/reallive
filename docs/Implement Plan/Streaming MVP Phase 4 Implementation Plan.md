# Giai đoạn 4 — Lifecycle, callback và End đồng bộ

- Ngày triển khai: 2026-10-27.
- Phạm vi: STR-401–STR-408 trong [Task Breakdown](Streaming%20MVP%20Task%20Breakdown.md).
- Toàn bộ code và kiểm thử tự động đã được triển khai và kiểm chứng; nghiệm thu OBS thủ công (STR-408) dành cho người dùng tự chạy theo hướng dẫn.
- Tuân thủ nghiêm ngặt kiến trúc MVP: xử lý đồng bộ, không queue/worker/reconciler/background retry ngầm.

---

## 1. STR-401 — Hook callback gửi trực tiếp tới Stream Service

Tập tin `mediamtx/media_hook.py` được tinh chỉnh theo thiết kế đồng bộ (Implementation Plan §9.1 & §14):
- Gửi HTTP POST JSON trực tiếp tới Stream Service endpoint `http://host.docker.internal:8082/stream/api/media/events`.
- Thực hiện một lần duy nhất (**single-shot**) với `timeout=10` giây; bỏ vòng lặp 5 lần thử lại cũ để tránh cạnh tranh và nghẽn tiến trình MediaMTX.
- Trả mã thoát rõ ràng:
  - Thoát mã `0`: khi nhận HTTP status 2xx (`APPLIED` hoặc `IGNORED`).
  - Thoát mã `1`: khi gặp lỗi kết nối, timeout, status 4xx/5xx hoặc URL cấu hình không hợp lệ.
- Bảo mật: không gửi query string (`MTX_QUERY`), không ghi log token, stream key hay secret.
- Kiểm chứng tự động: `scripts/testing/streaming/test_hook.py` với 4 test case (single-shot success, client error, server error, invalid path format) đạt 100%.

---

## 2. STR-402 — Xử lý ONLINE và chuyển LIVE

- Controller tiếp nhận: `stream/controller/media/MediaEventController` tiếp nhận `POST /stream/api/media/events` (permitAll cho MediaMTX).
- Log kiểm toán: ghi `eventId`, `streamId`, `type`, `outcome`, `status`; tuyệt đối không log header nhạy cảm hay payload query.
- DTO & Mapping: `MediaEventMapper` phân tích path `live/{streamId}`, validate timestamp UTC và map source type (`RTMP_CONN`, `RTSP_SESSION`, v.v.).
- Logic điều phối nghiệp vụ (`StreamMediaLifecycleServiceImpl`):
  1. Mở transaction, áp dụng `PESSIMISTIC_WRITE` lock trên `StreamSessionEntity` theo `streamId`.
  2. Bỏ qua nếu session đã ở trạng thái terminal (`ENDED`).
  3. Kiểm tra nguồn thực tế từ MediaMTX Control API (`mediaServerAdapter.getSource(streamId)`) ngay dưới khóa:
     - Nguồn phải `online == true`.
     - Phải khớp `sourceType` và `sourceId` từ event (nếu event cung cấp).
  4. Nếu nguồn không hợp lệ hoặc không online: bỏ qua event (`IGNORED`).
  5. Nếu nguồn hợp lệ: gọi domain `onMediaOnline(stream, event.sourceId(), event.sourceType(), event.actionTime())`:
     - Chuyển trạng thái sang `LIVE`.
     - Thiết lập `startedAt` ở lần LIVE đầu tiên (không bị ghi đè khi reconnect).
     - Xóa `disconnectedAt` và `reconnectDeadline`.
     - Ghi bản ghi `StreamStatusHistoryEntity`.
  6. Commit transaction và trả `MediaEventResponse` với outcome `APPLIED`.
  7. Xử lý event trùng lặp (duplicate ONLINE cùng sourceId): trả `IGNORED` an toàn.

---

## 3. STR-403 — Xử lý OFFLINE và bắt đầu reconnect grace

- Logic điều phối nghiệp vụ (`StreamMediaLifecycleServiceImpl.onOffline`):
  1. Khóa bi quan session (`PESSIMISTIC_WRITE`).
  2. Kiểm tra trạng thái hiện tại:
     - Nếu đã `ENDED` hoặc `READY`: bỏ qua (`IGNORED`).
     - Nếu session đang `LIVE`:
       - Kiểm tra source ID: nếu event offline thuộc về một connection cũ đã bị thay thế bởi connection mới hơn, bỏ qua event (`IGNORED`).
       - Gọi domain `onMediaOffline(stream, event.sourceId(), event.sourceType(), event.actionTime())`:
         - Trạng thái chuyển sang `RECONNECTING`.
         - Ghi nhận `disconnectedAt` và tính `reconnectDeadline = actionTime + 60s`.
         - Ghi bản ghi `StreamStatusHistoryEntity`.
  3. Nếu session đã ở `RECONNECTING`:
     - Giữ nguyên `reconnectDeadline` gốc của đợt đứt kết nối hiện tại; không cấp thêm thời gian (không reset deadline).
  4. Commit transaction và trả `MediaEventResponse(outcome = APPLIED)`.

---

## 4. STR-404 — Quét deadline reconnect và đóng path trực tiếp

- Cấu hình kích hoạt: `stream.lifecycle.reconnect-sweep-enabled: true` (mặc định bật trong `application-dev.yaml`, tắt trong `application-test.yaml`).
- Bộ lập lịch: `ReconnectExpirationScheduler` chạy định kỳ mỗi 1 giây (`fixedDelayString = "${stream.lifecycle.reconnect-sweep-delay:1s}"`).
- Cơ chế quét:
  1. Truy vấn batch IDs hết hạn: `streamSessionRepository.findExpiredReconnectingIds(now, PageRequest.of(0, batchSize))`.
  2. Với từng session ID hết hạn, gọi `streamSessionClosureService.expireReconnectIfDue(sessionId)`:
     - Thực hiện trong transaction độc lập.
     - Khóa session (`PESSIMISTIC_WRITE`).
     - Recheck trạng thái dưới khóa: chỉ xử lý nếu status vẫn là `RECONNECTING` và `reconnectDeadline <= now`.
     - Gọi `closeMedia(stream)`:
       - Gọi Control API `deletePath(streamId)`.
       - Kiểm tra nguồn thực tế qua `getSource(streamId)`. Nếu nguồn vẫn còn, gọi `kick` connection.
       - Xác nhận nguồn không còn online.
     - Áp dụng domain transition `expireReconnectIfDue`:
       - Trạng thái chuyển thành `ENDED`.
       - Thiết lập `endedAt` và `closedAt`.
       - Ghi bản ghi `StreamStatusHistoryEntity`.
     - Commit transaction.
  3. Nếu MediaMTX trả lỗi hoặc timeout:
     - Rollback transaction, giữ nguyên trạng thái trong DB để sweep ở chu kỳ sau hoặc can thiệp thủ công.
     - Không dùng hàng đợi retry ngầm hoặc reconciler.

---

## 5. STR-405 — Xây End Stream API và đóng nguồn thật

- Endpoint: `POST /stream/api/streams/{sessionId}/end`.
- Quyền truy cập: Bắt buộc đăng nhập (`@PreAuthorize("isAuthenticated()")`), người gọi phải là owner của channel/session (`AuthenticationUserId.require(authentication)`).
- Xử lý nghiệp vụ (`StreamSessionClosureServiceImpl.endSession(sessionId, ownerId)`):
  1. Mở transaction, khóa session (`PESSIMISTIC_WRITE`).
  2. Xác thực ownership: nếu user không sở hữu session, ném `AppException(ErrorCode.ACCESS_DENIED)`.
  3. Kiểm tra tính idempotent: nếu session đã ở trạng thái `ENDED`, trả về thông tin session ngay lập tức mà không gọi thêm I/O tới MediaMTX.
  4. Thực hiện đóng media đồng bộ:
     - Gọi `deletePath(streamId)`.
     - Kiểm tra và `kick` connection nếu còn tồn tại.
     - Xác nhận nguồn đã offline hoàn toàn.
  5. Áp dụng domain transition:
     - Trạng thái chuyển sang `ENDED`.
     - Cập nhật `endedAt`, `closedAt`.
     - Ghi bản ghi lịch sử `StreamStatusHistoryEntity`.
  6. Commit transaction và trả `ApiResponse<StreamSessionResponse>` với HTTP 200.
  7. Lỗi media hoặc timeout: trả HTTP 503 (`MEDIA_SERVER_UNAVAILABLE`), rollback DB; không trả 202 hay xử lý bất đồng bộ.

---

## 6. STR-406 — Giới hạn lỗi/restart và hướng dẫn thao tác thủ công

Xem chi tiết runbook tại tài liệu kiểm thử [Streaming MVP Tests](../testing/Streaming%20MVP%20Tests.md#str-406--runbook-van-hanh-thu-cong-khi-loi--restart).
Tóm tắt các nguyên tắc vận hành:
- **MediaMTX Restart**: Toàn bộ dynamic path trong RAM của MediaMTX bị mất. Backend Stream Service không tự phục hồi path. Streamer cần tạo session mới hoặc gọi End để kết thúc phiên cũ.
- **Stream Service Restart**: Dữ liệu DB được bảo toàn (PostgreSQL). Sau khi khởi động lại, `reconnectDeadline` và session key được giữ nguyên; scheduler tiếp tục quét các phiên hết hạn dựa trên mốc thời gian đã lưu.
- **Mất Callback / Timeout mạng**: Nếu callback ONLINE/OFFLINE không tới được Stream Service, trạng thái trong DB có thể lệch tạm thời với MediaMTX. Thao tác End qua API luôn đóng path trực tiếp và giải phóng tài nguyên.

---

## 7. STR-407 — Kiểm thử concurrency và lỗi lifecycle

Các kịch bản kiểm thử được tổ chức trong `StreamMediaLifecyclePostgresTest`:
1. `ONLINE` hợp lệ từ `READY` chuyển thành `LIVE`, gán `startedAt` và tạo history record.
2. `ONLINE` với source không khớp Control API bị từ chối (`IGNORED`), giữ nguyên `READY`.
3. `ONLINE` trùng lặp cho cùng một source connection được xử lý idempotent (`IGNORED`).
4. Reconnect thành công: `RECONNECTING` chuyển thành `LIVE`, giữ nguyên `startedAt` ban đầu.
5. `OFFLINE` hợp lệ chuyển `LIVE` thành `RECONNECTING`, thiết lập `disconnectedAt` và `deadline = +60s`.
6. `OFFLINE` lặp lại không reset deadline reconnect.
7. `OFFLINE` từ connection cũ (stale connection) bị bỏ qua nếu connection mới đang online.
8. Scheduler quét phiên hết hạn: đúng deadline gọi đóng path và chuyển `ENDED`.
9. Phiên chưa đến deadline: scheduler không can thiệp.
10. Owner End session qua API: đóng path, kick nguồn, chuyển `ENDED/CLOSED`.
11. End session lặp lại (idempotent): trả về trạng thái `ENDED` ngay mà không gọi thêm MediaMTX.
12. Người dùng không phải owner gọi End session bị từ chối `ACCESS_DENIED`.

Toàn bộ 12 test case chạy trên PostgreSQL 17 Testcontainers thực tế và đều PASS.

---

## 8. STR-408 — Bàn giao nghiệm thu OBS thủ công

Phần kiểm thử tự động với synthetic FFmpeg và Testcontainers đã hoàn tất.
Kiểm chứng thực tế cuối cùng với OBS Studio do người dùng thực hiện theo quy trình:
1. Tạo phiên stream qua API `POST /stream/api/streams`.
2. Cấu hình OBS với RTMP Server URL và Stream Key nhận được.
3. Bấm **Start Streaming** trên OBS -> Kiểm tra DB chuyển sang `LIVE`.
4. Ngắt kết nối mạng hoặc tắt OBS tạm thời -> Kiểm tra DB chuyển sang `RECONNECTING` với thời gian chờ 60 giây.
5. Kết nối lại OBS trong vòng 60 giây -> Kiểm tra DB quay lại `LIVE`.
6. Gọi API `POST /stream/api/streams/{sessionId}/end` -> Kiểm tra OBS bị ngắt phát và DB chuyển sang `ENDED`.
