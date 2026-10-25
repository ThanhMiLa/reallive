# Kế hoạch Giai đoạn 1 — Domain và Database cho MVP đồng bộ

- Cập nhật: 2026-10-25 theo yêu cầu xử lý đồng bộ từ đầu đến cuối.
- Nguồn: [Implementation Plan](Streaming%20MVP%20Implementation%20Plan.md), [Task Breakdown STR-101–107](Streaming%20MVP%20Task%20Breakdown.md#3-giai-đoạn-1--domain-và-database).
- Schema nguồn: [Data Model — Stream Service](../data-model/Data%20Model.md#3-stream-service-data-model), đã cập nhật về hai bảng và media state OPEN/CLOSED.
- Trạng thái: đã thực thi điều chỉnh STR-101–107 và nghiệm thu M1 ngày 2026-10-25, clean verify 77/77 test PASS. [Kết quả/giới hạn](../testing/Streaming%20MVP%20Tests.md); V2/V3 chưa được áp dụng vào database ứng dụng.

## 1. Mục tiêu

Giữ nền tảng cần để hoàn thành MVP: session, lịch sử trạng thái, state machine, idempotency, một session active/channel và deadline reconnect. Schema đích chỉ có `stream_sessions` và `stream_status_history`.

Mỗi use case gọi MediaMTX trực tiếp và chờ kết quả/timeout; không dùng `stream_media_sync_tasks`, lease, revision của task, worker retry/backoff, reconciler hoặc response 202. Không dùng cột version hoặc JPA @Version cho session; mọi cập nhật session hiện có dùng PESSIMISTIC_WRITE và được ghi trong cùng transaction.

Giai đoạn 1 chuẩn bị domain/database. Adapter và luồng HTTP trực tiếp được nối ở STR-301–308 và STR-401–408; không báo tính năng gọi MediaMTX đã hoàn tất sau riêng giai đoạn 1.

### 1.1. Phần giữ, sửa và bỏ trong code hiện tại

Các đường dẫn trong bảng tính từ `stream-service/src/main/java/com/reallive/streamservice/`, trừ file cấu hình/migration được ghi riêng.

| Thành phần hiện có | Hành động ở giai đoạn 1 |
|---|---|
| `stream/entity/StreamSessionEntity.java`, `StreamStatusHistoryEntity.java` | Giữ; map đúng schema hai bảng; bỏ field/import @Version của session |
| `stream/entity/enums/MediaPathState.java` | Chỉ giữ OPEN/CLOSED; bỏ PENDING_OPEN/PENDING_CLOSE |
| Các enum còn lại trong `stream/entity/enums/`, `stream/entity/MediaSourceTypeConverter.java` | Giữ, không tạo thêm bảng cho converter |
| `stream/repository/StreamSessionRepository.java` | Slot/current chỉ lọc status READY/LIVE/RECONNECTING; bỏ nhánh `mediaPathState <> CLOSED` của thiết kế pending |
| `stream/repository/StreamStatusHistoryRepository.java`, mapper/snapshot, command/result trong `stream/dto/` | Giữ; cập nhật phần bị ảnh hưởng, không chuyển package ngoài nhu cầu |
| `stream/service/StreamSessionDomainService.java` và implementation | Bỏ phụ thuộc task/upsertCloseSyncTask, sửa state và Javadoc theo contract mới |
| Entity/repository/service/implementation `StreamMediaSyncTask*`, `stream/dto/ClaimedMediaSyncTask.java` | Xóa cùng các import/injection/call site và test chỉ phục vụ task |
| `common/configuration/StreamClockConfig.java`, cấu hình reconnect grace/JDBC UTC | Giữ |
| `src/main/resources/application-dev.yaml` | Bỏ nhóm `stream.media-sync.*`; giữ cấu hình lifecycle/UTC |
| `src/main/resources/db/migration/` | Điều chỉnh schema theo STR-101, không thay đổi DB bằng Hibernate |

### 1.2. Thứ tự thực hiện

1. STR-101: chốt migration từ schema hiện tại sang schema hai bảng theo Data Model.
2. STR-102 và STR-105: sửa entity/repository/domain cùng việc gỡ task trong một đợt nhất quán để không để import/bean/query treo.
3. STR-103 và STR-104: hoàn thiện contract domain, state machine và concurrent idempotency.
4. STR-106: cập nhật hạ tầng PostgreSQL test để chạy schema mới cô lập.
5. STR-107: thay các test task bằng regression tương ứng, chạy build/test và cập nhật bằng chứng M1.

Các phụ thuộc trong backlog vẫn giữ nguyên; thứ tự trên là cách gom thay đổi liên quan khi thực hiện, không cho phép bỏ qua điều kiện hoàn tất của task.

## 2. Quy tắc đồng bộ dùng chung

### Create

1. Kiểm tra principal/eligibility/channel/category qua các service sở hữu trước transaction ghi.
2. Bắt đầu transaction, tra request ID; reserve slot bằng insert/flush session READY/OPEN chưa commit (thỏa CHECK/NOT NULL).
3. Gọi MediaMTX tạo exact path trực tiếp và chờ kết quả.
4. Sau xác nhận MediaMTX, ghi history ban đầu và commit READY/OPEN; trước commit request khác chưa thấy session sẵn sàng.
5. Sau commit trả session và thông tin OBS; nếu lời gọi media lỗi, rollback DB và trả 503.

Nếu request đã commit nhưng client mất response, retry cùng owner/request ID trả session đã có. Concurrent retry cần đọc lại kết quả trong transaction mới sau rollback; không chuyển mọi lỗi unique thành lỗi slot và không sửa metadata ngầm.

### End hoặc hết thời gian reconnect

1. Bắt đầu transaction, khóa/reload session, kiểm tra quyền hoặc deadline/source.
2. Gọi MediaMTX đóng path/ngắt nguồn trực tiếp và chờ xác nhận.
3. Thành công mới ghi ENDED/CLOSED, endedAt, history và commit.
4. End trả 200 sau commit; lỗi/timeout trả 503 và rollback thay đổi DB của thao tác. Không có task chạy tiếp sau response. Scheduler hết hạn reconnect ghi log khi lỗi; lần quét business sau có thể gặp lại deadline còn quá hạn, nhưng không có queue/backoff/recovery riêng.

MVP một replica chấp nhận giữ transaction/row lock qua lời gọi media có timeout để serialize thao tác cùng session. Control API không được đợi callback nghiệp vụ hoàn tất theo vòng phụ thuộc; callback có thể chờ khóa rồi nhận thấy ENDED sau commit. User/Channel không được gọi khi đang giữ khóa ghi.

DB và MediaMTX không có transaction chung: crash, mất response hoặc DB commit lỗi sau khi media đã thay đổi có thể để lại path orphan/trạng thái lệch. MVP ghi lỗi để xử lý thủ công; đóng bù/restore/reconciler tự động nằm ngoài phạm vi hiện tại.

### Ranh giới transaction cần chuẩn bị ở giai đoạn 1

- Method domain thực hiện thay đổi DB phải tham gia transaction của use case gọi nó khi được tích hợp ở giai đoạn 3–4; không dùng REQUIRES_NEW để commit READY/ENDED riêng trước thao tác media.
- Create ghi READY/OPEN tạm và history trong cùng transaction chưa commit; việc gọi media và quyết định commit/rollback do use case đồng bộ điều phối sau khi adapter thật được nối.
- Method áp dụng End/expire dùng propagation MANDATORY, chỉ gọi sau xác nhận đóng media thành công trong cùng transaction/khóa session. Không để nhánh ONLINE quá deadline tự commit ENDED/CLOSED mà chưa đóng path; nhánh này từ chối chuyển LIVE để use case hết hạn xử lý.
- Domain test có thể chuẩn bị trạng thái đã xác nhận để kiểm tra mutation DB. Việc gán OPEN/CLOSED trong test persistence không được coi là bằng chứng MediaMTX đã mở/đóng path thật.
- Không xây adapter giả luôn trả thành công, controller Create/End thật hoặc scheduler media trong giai đoạn 1 chỉ để có thể tick M1.

## 3. STR-101 — Migration và Data Model

- Giữ `stream_sessions` với UUID, owner/channel/streamer/category, title, PUBLIC, lifecycle status, playback_path, creation_request_id, source ID/type, thời gian reconnect/start/end và pessimistic locking khi cập nhật.
- MediaPathState đích chỉ có OPEN/CLOSED; trạng thái active đi cùng OPEN, ENDED đi cùng CLOSED. Loại PENDING_OPEN/PENDING_CLOSE dành cho cơ chế xử lý sau.
- Giữ `stream_status_history`: previous_status/status, reason, source/event ID, created_at; dùng READY thay CREATED.
- FK history → session trong cùng database; không FK xuyên service. Không lưu JWT/stream key/hash.
- Unique playback path, unique `(owner_user_id, creation_request_id)` và partial unique slot:

```sql
CREATE UNIQUE INDEX uq_stream_sessions_channel_slot
    ON stream_sessions (channel_id)
    WHERE status IN ('READY', 'LIVE', 'RECONNECTING');
```

- Giữ index list LIVE, owner/current, reconnect deadline và history.
- Giữ nguyên V1/V2, thêm `V3__remove_stream_session_version.sql` để bỏ cột version. Mapper/snapshot không còn field version; không xóa trường version của bảng quản lý Flyway hoặc các service khác.
- Thay constraint enum media state và alignment bằng đúng biểu thức trong Data Model; READY/LIVE/RECONNECTING chỉ đi cùng OPEN, ENDED chỉ đi cùng CLOSED. Không giữ điều kiện pending trong partial index.
- Bỏ bảng/index/constraint `stream_media_sync_tasks`. V1 hiện có ba bảng; nếu đã chạy ở môi trường dùng chung thì tạo migration version tiếp theo, không sửa V1 đã áp dụng.
- Khi chưa xác định V1 đã áp dụng ở đâu, mặc định thêm migration tiếp theo, ví dụ `V2__simplify_streaming_to_synchronous_mvp.sql` nếu version đó còn trống. File V2 này đã được tạo và chạy trên PostgreSQL 17 trong các suite test.
- Trước khi chuyển constraint, kiểm tra các row PENDING_* của implementation cũ. Xác minh media và xử lý dữ liệu chuyển đổi có chủ đích, không đổi hàng loạt thành OPEN/CLOSED dựa trên giả định và không xóa session/history.
- Data Model đã mô tả schema đích hai bảng và constraint đồng bộ; điều chỉnh code/migration cho khớp, rồi kiểm chứng trên PostgreSQL 17.

**Đầu ra STR-101:** migration chạy từ DB sạch và schema cũ có dữ liệu OPEN/CLOSED tương thích; schema cuối có hai bảng nghiệp vụ, không có bảng task. Nếu dữ liệu PENDING_* chưa được xác minh thì migration báo lỗi rõ, không tự suy diễn media đã mở/đóng. Không tính `flyway_schema_history` vào hai bảng nghiệp vụ.

## 4. STR-102 — Entity, enum, repository và mapper

Giữ:

- StreamSessionEntity, StreamStatusHistoryEntity, StreamSessionStatus, StreamVisibility, MediaSourceType, MediaSourceTypeConverter và mapper/snapshot.
- MediaPathState OPEN/CLOSED, pessimistic row lock, BaseEntity khi phù hợp, UUID và thời gian UTC.
- Repository tra owner/request, khóa session, tra slot/current, list LIVE và deadline reconnect.
- Query slot/current dùng cùng predicate status active với unique index; session ENDED/CLOSED không phải current. Không còn trường hợp ENDED nhưng chờ cleanup trong contract mới.

Loại khỏi code:

- StreamMediaSyncTaskEntity và StreamMediaSyncTaskRepository.
- ClaimedMediaSyncTask và mapper/DTO chỉ phục vụ task.
- Query claim task/SKIP LOCKED, lease và lịch nextAttemptAt.

Thời gian lifecycle dùng LocalDateTime theo Clock UTC, JDBC UTC; cấu hình JVM/audit nhất quán UTC. Response chuyển thành ISO-8601 có múi giờ. Không thêm quan hệ JPA tới entity của service khác.

**Đầu ra STR-102:** JPA validate/round-trip đúng schema mới; mapper không tham chiếu task/pending và không tự thực hiện I/O.

## 5. STR-103 — State machine

Giữ các transition:

```text
Create thành công                         → READY / OPEN
Nguồn online hợp lệ                       → LIVE
Nguồn hiện tại offline                    → RECONNECTING
Nguồn reconnect hợp lệ trước deadline     → LIVE
End/hết deadline và đóng media thành công → ENDED / CLOSED
Callback của session ENDED                → Giữ ENDED
```

- Verify Publish JWT không tự chuyển LIVE.
- startedAt chỉ gán lần đầu; reconnect giữ startedAt.
- Offline lặp không gia hạn deadline 60 giây; offline nguồn cũ không làm hỏng nguồn mới.
- Deadline được lưu DB, không cộng lại grace sau restart.
- Session quá deadline không được cấp credential hoặc nhận ONLINE để hồi sinh, kể cả lần đóng media đang lỗi.
- Kiểm tra mốc `now == reconnectDeadlineAt`: deadline đã hết; ONLINE không được đi theo nhánh reconnect còn hạn. Chỉ chấp nhận nguồn thực tế trở lại trước deadline theo policy xác minh của lifecycle ở giai đoạn 4.
- Bỏ upsert task trong End/expire. Use case gọi media trước khi commit ENDED; domain không tự enqueue hoặc spawn thread.
- Tiêm Clock để kiểm thử trước/đúng/sau deadline.

**Đầu ra STR-103:** domain và English Javadoc không còn contract PENDING_CLOSE/task; terminal ENDED, startedAt và deadline nhất quán với Data Model.

## 6. STR-104 — Slot và idempotency

- Unique index là bảo vệ cuối khi hai Create cùng lúc chưa có row để khóa.
- Hai request khác ID cùng channel: một tạo được, một nhận conflict.
- Hai request cùng owner/request ID: cùng session, một history ban đầu; metadata không bị ghi đè.
- Xử lý unique conflict đúng loại. Transaction đã lỗi phải rollback trước khi đọc lại session của request thắng trong transaction mới.
- Nếu request đã được tạo cho channel khác, trả STREAM_CREATION_REQUEST_CONFLICT cả khi xảy ra cạnh tranh. Nếu một request khác chiếm slot, trả STREAM_SESSION_ALREADY_ACTIVE. Không gộp lỗi CHECK/title/schema thành lỗi slot.
- Không catch lỗi rồi đọc lại trong transaction đã rollback-only; đặt ranh giới transaction bên ngoài bước xử lý cạnh tranh. Không dựa vào self-invocation của @Transactional để tạo transaction mới.
- End chưa đóng media thành công thì DB chưa ENDED, slot vẫn bị giữ.
- Khóa/serialize theo cùng một thứ tự để tránh deadlock khi Create/End/callback cạnh tranh. Mọi cập nhật session hiện có phải lấy PESSIMISTIC_WRITE trong cùng transaction; không dùng optimistic locking.
- Test End giữ khóa chưa commit trong khi ONLINE tới: callback phải chờ khóa, sau commit đọc thấy ENDED và không hồi sinh session.

**Đầu ra STR-104:** kiểm chứng được hai request đồng thời cùng ID và khác ID trên PostgreSQL thật; retry cùng ID giữ nguyên cả title lẫn categoryId và chỉ có một history READY.

## 7. STR-105 — History và gỡ cơ chế task cũ

- History được ghi cùng transaction session; lỗi/rollback không để history lẻ.
- Bỏ StreamMediaSyncTaskService/Impl và các lời gọi từ domain.
- Bỏ cấu hình `stream.media-sync.*`, STREAM_MEDIA_SYNC_LEASE, STREAM_MEDIA_SYNC_DELAY, cùng cấu hình reconcile/retry chỉ dành cho task.
- Bỏ test lease/revision/claim/failure/completion của task; thay bằng kiểm chứng transaction/history phù hợp phạm vi mới.
- Không thay đổi auth_revision hay cơ chế đồng bộ account ở service khác.
- Không cần sửa lỗi lease/revision của code sẽ được loại khỏi MVP. Lỗi concurrent idempotency vẫn phải sửa vì nằm trong luồng Create hiện tại.
- Xóa assertion về revision/lease/claim nhưng giữ mục tiêu kiểm tra slot, history, rollback và ENDED; viết lại test tương ứng với state OPEN/CLOSED. Có thể đổi tên `StreamConcurrencyAndSyncPostgresTest` để thể hiện concurrency/transaction thay vì media task.
- Cập nhật lỗi/Javadoc còn nhắc pending cleanup nếu không còn phù hợp; không đổi numeric code hoặc hành vi auth ngoài phạm vi.

**Đầu ra STR-105:** build không tham chiếu class/bảng/cấu hình task; history vẫn atomic với session. Tên bảng task có thể còn trong migration lịch sử V1 và migration loại bỏ nó, nhưng không còn trong schema đích hoặc code runtime.

## 8. STR-106 — PostgreSQL integration

Dùng PostgreSQL 17/Testcontainers với Flyway thật và Hibernate ddl-auto=validate. Mỗi class có schema riêng hoặc lifecycle isolation được quản lý rõ; code hiện tại đã cấp schema riêng cho từng class/context qua @DynamicPropertySource của class đó, thay cho biến SCHEMA chung trong lớp cha.

Chỉ DROP schema test do suite tạo; không cleanup schema public của ứng dụng. Unit test không cần OBS/MediaMTX.

**Đầu ra STR-106:** context cache/cleanup không khiến suite sau mất schema của suite trước; báo lỗi cleanup khi thất bại thay vì mô tả đã dọn sạch mà không có bằng chứng. Ghi prerequisite Docker cho Testcontainers và cách dùng database test cô lập nếu cần.

## 9. STR-107 — Kiểm chứng và điều kiện M1

- Migration từ schema sạch, constraint/index hai bảng, JPA validate và persistence round-trip.
- Concurrent create khác/cùng request ID; không sửa metadata khi retry; owner/request khác channel trả đúng lỗi.
- History/state cùng transaction; chủ động gây rollback để kiểm chứng.
- Source/deadline/startedAt, duplicate/stale callback, terminal ENDED và deadline đọc lại từ DB.
- Không có bean/config/query phụ thuộc task.

### Ma trận kiểm chứng bắt buộc

| Nhóm | Tình huống cần kiểm | Kết quả yêu cầu |
|---|---|---|
| Migration | DB sạch; nâng schema cũ có dữ liệu OPEN/CLOSED hợp lệ; chạy migrate lại | Hai bảng nghiệp vụ, Flyway validate đạt, dữ liệu session/history giữ nguyên |
| Migration | Dữ liệu cũ còn PENDING_* chưa xác minh | Báo lỗi rõ; không tự chuyển thành OPEN/CLOSED và báo migrate thành công |
| Constraint | Enum/field bắt buộc sai, active/CLOSED hoặc ENDED/OPEN | PostgreSQL từ chối |
| Migration V3 | Nâng từ V2 có session/history | Bỏ cột version, dữ liệu còn nguyên |
| Pessimistic lock | ONLINE tới khi End đang giữ khóa chưa commit | Callback chờ khóa, sau commit giữ ENDED/CLOSED |
| Slot | Hai create đồng thời khác request ID trên cùng channel | Một session active, request còn lại nhận lỗi slot |
| Idempotency | Hai create đồng thời cùng owner/request ID; retry đổi title/category | Cùng session, không ghi đè metadata, một history READY |
| Idempotency | Cùng owner/request ID được dùng cho channel khác | Lỗi request conflict đúng contract |
| Transaction | Ghi session/history rồi chủ động gây lỗi trước commit | Cả hai rollback, không history lẻ hoặc slot bị giữ bởi row rollback |
| Persistence | Reload RECONNECTING và deadline từ DB | Deadline cũ được giữ, không thêm 60 giây |
| State machine | ONLINE/OFFLINE lặp, offline nguồn cũ, reconnect trước/đúng/sau deadline | Không reset startedAt/deadline hoặc hồi sinh quá hạn |
| End domain | Áp dụng End sau xác nhận đóng, End lặp, callback đến muộn | ENDED/CLOSED, history không lặp; slot giải phóng sau commit |
| Loại task | Khởi tạo context và truy cập các repository còn lại | Không bean/query/runtime dependency tới bảng task |

Không cố định số test phải bằng 53 vì một số test cũ sẽ bị bỏ/thay thế. Lưu số test thực tế sau khi chạy.

### Lệnh và bằng chứng

- Chạy trong `stream-service/`:

```sh
./mvnw clean verify
```

Chỉ ghi số test và tick task sau khi chạy code mới. Bộ 53 test của thiết kế cũ là bằng chứng lịch sử; test HTTP chờ kết quả, timeout, End đóng path và lỗi rollback thuộc giai đoạn 3–4 khi adapter đã được nối.

### Checklist nghiệm thu M1

- [x] STR-101: migration và Data Model khớp schema đích hai bảng.
- [x] STR-102: entity/repository/mapper validate và round-trip đúng.
- [x] STR-103: state machine/Clock/terminal/deadline theo contract mới.
- [x] STR-104: concurrent idempotency và slot đạt trên PostgreSQL thật.
- [x] STR-105: history atomic, code/config/task được gỡ khỏi runtime.
- [x] STR-106: test PostgreSQL cô lập và cleanup được kiểm chứng.
- [x] STR-107: clean verify đạt; cập nhật kết quả thực tế trong Testing/Task Breakdown.

M1 đạt khi toàn bộ điều kiện trên có bằng chứng. Việc HTTP MediaMTX chờ kết quả, Create/End đóng/mở path thật và rollback khi media lỗi được nghiệm thu ở giai đoạn 3–4. Tự phục hồi sau crash/MediaMTX restart/mất callback nằm ngoài MVP hiện tại.

**Kết quả thực thi:** StreamMigrationPostgresTest 17/17, StreamRepositoryPostgresTest 5/5, StreamStateMachineServiceTest 12/12, StreamConcurrencyAndTransactionPostgresTest 11/11; cộng 32 test common/security/context = 77/77 PASS. Chưa nghiệm thu HTTP MediaMTX hoặc migrate DB ứng dụng.
