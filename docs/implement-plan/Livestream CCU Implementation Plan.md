# Livestream CCU — Implementation plan

Ngày bắt đầu: 2026-11-11. Trạng thái: đã hoàn tất triển khai và kiểm chứng trong môi trường test cô lập. Feature flag mặc định tắt; bật theo CCU contract khi cấu hình môi trường.

## Contract và phạm vi

- Một user tối đa một CCU trên một stream, dù F5 hoặc nhiều tab/thiết bị.
- Loại chủ stream theo ownerUserId và technical identity như media-worker.
- Watch page và Streamer Dashboard hiển thị currentViewerCount từ GET metadata. Null hiển thị —; READY/ENDED là 0.
- Không sửa discovery/card, mobile, không thêm heartbeat/Kafka hay migration PostgreSQL.
- Giữ binary MediaMTX 1.21.2; script READ tra user bằng Control API loopback rồi callback, UNREAD dùng Redis mapping.
- Có đối soát tự phục hồi; cleanup ENDED sau commit không làm rollback kết thúc stream.

## Phân công

| Owner | Phạm vi |
|---|---|
| Agent chính | Callback/security, metadata/lifecycle, scheduler đối soát, publisher Control API JWT, cấu hình backend, contract và tích hợp |
| Redis subagent | Presence store, Lua, tombstone/generation, integration tests Redis thật |
| Media subagent | Hook Python, Docker/Compose, HTTP fixture và isolated MediaMTX E2E |
| Frontend subagent | Types/cache normalization, badge Watch/Dashboard và Vitest/RTL tests |
| QA sau tích hợp | Review contract/race/failure, thực thi gates và ghi evidence; không tự đổi nghiệp vụ |

Chỉ một Maven runner trong cùng checkout. Không chỉnh Java trong lúc compile/test backend. Python và frontend có thể chạy song song. Không dùng hoặc dừng stack local hiện có để E2E; dùng port động, Redis container riêng và image ccu-test riêng.

## Thứ tự

1. Khóa DTO callback, metadata và presence store port.
2. Thực thi song song Redis, media hook, frontend; agent chính tích hợp backend.
3. Kiểm tra callback auth, owner exclusion, thứ tự sự kiện, lỗi count và lifecycle.
4. Chạy Java, Python, frontend gates; sửa regression trong scope.
5. Chạy MediaMTX E2E opt-in bằng môi trường cô lập và smoke UI; cập nhật evidence trước nghiệm thu.

## Các gate

- Stream Service: ./mvnw clean verify, gồm unit và Redis Testcontainers.
- Python: python3 -m unittest discover -s scripts/testing/streaming -p 'test_*.py' -v.
- Frontend: npm run test:streaming; npm test; npm run build; npm run lint.
- Image test: docker build -f media-server/docker/Dockerfile -t reallive-media-server:ccu-test .
- Media E2E: ./mvnw -Dstream.ccu.it=true -Dstream.media.test-image=reallive-media-server:ccu-test -Dtest=StreamViewerPresenceMediaIT test.
- Regression Media auth/provisioning chạy opt-in riêng; clean verify không tự chạy *IT.

## Nghiệm thu

Hai viewer phải cho count 2; 100 reader của một user vẫn count 1. Reader cũ kết thúc sau F5 không xóa user đang có reader mới. Owner preview và worker không tăng count. UNREAD trước READ, callback trùng/trễ không hồi sinh reader. Redis lỗi trả null trong metadata, không ngắt player; auth errors giữ contract hiện tại. Mất callback/Redis/MediaMTX restart được đối soát. End chủ động và hết hạn reconnect đều trả count 0 và cleanup sau commit. Read JWT expiry không đóng reader đang hoạt động. Control API JWT phải được rotate và script đọc lại credential.

## Evidence

- Stream Service clean verify: 325 tests, 0 failures/errors/skipped (2026-11-11), gồm metric và kiểm tra credential lúc startup.
- Redis presence: 17 tests chạy Redis7.4 Testcontainers thật, gồm TTL tombstone cho UNREAD chưa có state, nằm trong bộ Java trên.
- Python regression: 61 tests pass, gồm20 viewer hook tests.
- Frontend streaming: 57 tests pass; build và lint pass.
- npm test: 41/41 pass sau khi cập nhật assertions Category Preferences bị stale so với UI poster card đã có trên HEAD; không đổi source UI category.
- Image reallive-media-server:ccu-test đã build, Compose config hợp lệ; không đổi container/tag đang dùng.
- MediaMTX CCU E2E: pass 1/1, không skip; 57.636s ở lượt CCU restart cuối trên MediaMTX1.21.2 + Redis7.4 + PostgreSQL17 cô lập. Bao gồm user dedupe/F5, inactivity thật, Read JWT expiry, publisher rotation, owner/worker exclusion, mất dữ liệu Redis, crash/restart Redis thật với client reconnect, mất UNREAD và crash/restart MediaMTX, late READ sau ENDED.
- Browser smoke: Chrome157, Watch+Dashboard hiển thị0/17/null và lỗi refresh đúng; video DOM/MediaSource giữ nguyên, playback-access không tăng khi count đổi. Screenshot/evidence local tại .local/streaming/ccu-ui-test/report.json và screenshots/.
- Regression Media authentication 1/1 và provisioning tự động3/3 pass với ccu-test image; lệnh chọn đúng3 case tự động, tổng5 Media IT pass và0 skip. Case OBS thủ công của suite provisioning không thuộc nghiệm thuCCU và không bật trong lượt kiểm tra tự động.
- Cải thiện kiểm thử cuối: chờ query metadata đầu tiên settle trước khi gây lỗi refresh trong dashboard test, tránh race test từ initialData owner.
- Feature flag vẫn mặc địnhfalse; chưa ghi secret/.env hoặc restart stack local đang dùng.

## Lệnh Media regression đã chạy đạt

Trong stream-service, chọn các case tự động (case OBS thủ công không nằm trong lệnh này):

```sh
./mvnw -Dstream.ccu.it=true -Dstream.media.it=true -Dstream.provisioning.it=true \
  -Dstream.media.test-image=reallive-media-server:ccu-test \
  '-Dtest=StreamViewerPresenceMediaIT,MediaMtxAuthenticationIT,StreamProvisioningMediaIT#realHttpCreateUpdateReplayCredentialsAndActualPublisherUseTheProvisionedPath+ownerSmokeScriptExercisesTheRealHttpApiWithoutPrintingCredentials+realMediaTimeoutReturns503AndRollsBackBeforeTheUserRetries' test
```

Lượt CCU cuối chạy lại riêng StreamViewerPresenceMediaIT sau khi thêm restart Redis thật; pass, không skip. Toàn bộ testcontainers và fixture server đã dọn, các container local ban đầu không bị thay đổi.

## Tối ưu truy vấn đối soát CCU

- Thay hai vòng `findById()` trong cleanup/kiểm tra cuối bằng query `IN` theo batch tối đa 100 ID; chỉ lấy `id + status`.
- Tải session hoạt động bằng projection `id + ownerUserId`, dùng `Slice` và thứ tự ID ổn định để không cần query COUNT hoặc load entity đầy đủ.
- Vẫn đọc lại trạng thái sau xử lý reader và giữ generation/CLOSED fencing; session vừa ENDED hoặc bị xóa không được công bố presence ACTIVE.
- Test scheduler với 250 session xác nhận 3 query discovery và 6 query status batch, không gọi `findById()`; kiểm tra batch cuối 50 ID, danh sách rỗng, trạng thái thay đổi và lỗi DB.
- Test PostgreSQL/Hibernate statistics xác nhận một SQL mỗi batch/slice và không load entity từ các projection.

Kết quả sau tối ưu: `./mvnw clean verify` đạt 333 tests, 0 failures/errors/skipped; `StreamViewerPresenceMediaIT` chạy lại đạt 1/1, không skip, gồm dedupe/F5, owner exclusion, Redis restart và MediaMTX restart.
