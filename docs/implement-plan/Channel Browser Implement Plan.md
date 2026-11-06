# Kế hoạch tách API danh sách channel đã follow và chưa follow

- Ngày lập: 2026-10-18.
- Trạng thái: Đã implement và kiểm chứng local ngày 2026-10-18; các bước deploy môi trường chạy thật tại mục 9 chưa thực hiện.
- Module chính: `channel-service`, `user-service`.
- Phạm vi hiện tại: chỉ backend và tài liệu. Frontend đã được rollback theo yêu cầu ngày 2026-10-19; chuyển caller web/mobile sang contract mới nằm ngoài đợt này.
- Mục tiêu: Tách hai API, hoàn thiện danh sách đã follow trước, sau đó hoàn thiện danh sách chưa follow với bốn trường hợp lọc và ưu tiên sở thích đã thống nhất.

## 1. Yêu cầu và phạm vi

### 1.1. Hai API đích

| API qua Gateway | Chức năng |
|---|---|
| `GET /channel/api/channels/following` | Danh sách channel user đã follow; chọn một tiêu chí sort: số follower hoặc thời gian user follow channel |
| `GET /channel/api/channels` | Danh sách channel user chưa follow; lọc chủ động hoặc ưu tiên sở thích khi không có filter |

Trong Channel Service, context path là `/channel`, nên controller ánh xạ `/api/channels` và `/api/channels/following`.

Tham số `following` của API cũ được loại khỏi contract đích. Cả hai API tiếp tục yêu cầu đăng nhập và lấy user ID từ JWT subject, không nhận user ID do client cung cấp.

### 1.2. Quy tắc chung

- Chỉ trả channel active, chưa soft-delete và không thuộc chính user đang đăng nhập. Giữ cách xử lý channel legacy có owner null như hiện tại.
- Giữ `ApiResponse<PageResponse<ChannelListResponse>>`, bốn trường item `id`, `name`, `avatarUrl`, `numberOfFollower` và kiểu số follower hiện tại.
- Số follower để sort lấy từ `channels.follower_count`, không đếm lại follower cho từng channel.
- `page` mặc định `0`, `size` mặc định `20`; `page >= 0`, `1 <= size <= 100`, giữ giới hạn offset hiện tại.
- Query params là đầu vào của hai API GET; không chuyển thành GET có request body.
- Mọi dữ liệu đầu vào được binding và kiểm tra bằng Spring và Jakarta Bean Validation. Lỗi binding/validation trả `400` qua error handler hiện tại.
- Lọc, ưu tiên, sort thực hiện trong database trước `offset`/`limit`; không lấy một page rồi mới sort hoặc đẩy channel phù hợp lên đầu bằng Java.
- Count query dùng cùng điều kiện lựa chọn channel với content query; thứ tự ưu tiên không làm thay đổi tổng số.
- Offset pagination không bảo đảm snapshot giữa các request khi follower, quan hệ follow hoặc sở thích thay đổi. Tiêu chí phụ chỉ bảo đảm thứ tự ổn định khi dữ liệu không đổi.

### 1.3. Ngoài phạm vi lần này

- Giới hạn tối đa 5 categories cho user/channel và xử lý dữ liệu cũ vượt giới hạn.
- ML, chấm điểm theo lịch sử xem, trộn ngẫu nhiên channel mới, trạng thái live và Recommendation Service riêng.
- Cache sở thích, đồng bộ Kafka và cursor pagination.
- Thay đổi logic ghi follow/unfollow hoặc thêm trường response chỉ phục vụ sort.

## 2. Contract API channel đã follow

### 2.1. Query params

| Param | Mặc định | Quy tắc |
|---|---|---|
| `sortBy` | `FOLLOWER_COUNT` | Một giá trị duy nhất: `FOLLOWER_COUNT` hoặc `FOLLOWED_AT` |
| `sortDirection` | `DESC` | Một giá trị duy nhất: `ASC` hoặc `DESC` |
| `page`, `size` | `0`, `20` | Theo quy tắc chung |

Tên `sortBy` và các enum là contract đã triển khai. Không nhận tên cột database hoặc property tùy ý từ client.

API following chỉ bind bốn param `sortBy`, `sortDirection`, `page`, `size`. Không có `keyword` hoặc `categoryIds` trong DTO/query của API này. Theo cập nhật của user ngày 2026-10-19, cả hai API chỉ dùng Spring binding và Jakarta Bean Validation trên DTO; đã bỏ annotation/validator query tự viết. Param ngoài request model được Spring bỏ qua và không lọc kết quả.

### 2.2. Chỉ một tiêu chí sort nghiệp vụ mỗi request

| Request | Thứ tự nghiệp vụ |
|---|---|
| Không truyền sort | `follower_count DESC` |
| `sortDirection=ASC` | `follower_count ASC` |
| `sortBy=FOLLOWER_COUNT&sortDirection=DESC` | `follower_count DESC` |
| `sortBy=FOLLOWER_COUNT&sortDirection=ASC` | `follower_count ASC` |
| `sortBy=FOLLOWED_AT&sortDirection=DESC` | Mới follow trước |
| `sortBy=FOLLOWED_AT&sortDirection=ASC` | Follow lâu trước |
| `sortBy=FOLLOWED_AT`, không có direction | `followed_at DESC`; chỉ đổi tiêu chí khi user chủ động chọn |

- `sortBy` là một enum đơn trị; danh sách nhiều tiêu chí dạng comma-separated không bind được và trả `400`. Chưa áp dụng custom validation cho raw query có param lặp trên API following.
- `sortDirection` để rỗng hoặc ngoài enum trả `400` qua binding/Bean Validation. Kiểm tra số lần xuất hiện trong raw query được để lại cho lần sau.
- Sau tiêu chí nghiệp vụ duy nhất, dùng `channels.created_at DESC, channels.id DESC` để phá hòa. Đây là thứ tự kỹ thuật ổn định, không phải bật thêm tiêu chí nghiệp vụ.
- Khi sort `FOLLOWED_AT`, không dùng follower count làm tiêu chí phụ; khi sort `FOLLOWER_COUNT`, không dùng followed time làm tiêu chí phụ.
- Thời gian follow là `channel_followers.followed_at` của đúng user hiện tại với channel đó, không phải thời gian channel được tạo hay thời gian người khác follow.
- Khi unfollow rồi follow lại, dùng timestamp của quan hệ follow mới, theo logic ghi hiện có.
- Không lọc keyword hoặc category, không dùng sở thích và không gọi User Service. Chỉ áp dụng điều kiện channel hợp lệ, ownership và quan hệ đã follow của user hiện tại.

Ví dụ:

```http
GET /channel/api/channels/following
GET /channel/api/channels/following?sortBy=FOLLOWER_COUNT&sortDirection=ASC
GET /channel/api/channels/following?sortBy=FOLLOWED_AT&sortDirection=DESC&page=0&size=20
GET /channel/api/channels/following?sortBy=FOLLOWED_AT&sortDirection=ASC&page=0&size=20
```

## 3. Contract API channel chưa follow

### 3.1. Query params và xác định filter

| Param | Mặc định | Quy tắc |
|---|---|---|
| `keyword` | Không lọc | Giữ tìm literal substring trong `name`, `description`, `slug`, không phân biệt hoa thường; null/blank không được tính là có filter; tối đa 255 ký tự trước chuẩn hóa |
| `categoryIds` | Không lọc chủ động | Nếu có phải là collection UUID không rỗng, không chứa null; khớp ít nhất một category active |
| `sortDirection` | `DESC` | Vẫn giữ param nhưng chỉ chấp nhận `DESC`; `ASC`, rỗng và giá trị khác trả `400` |
| `page`, `size` | `0`, `20` | Theo quy tắc chung |

- API này không nhận `sortBy`; chỉ hỗ trợ số follower giảm dần và quy tắc ưu tiên ở TH4.
- `page`, `size`, `sortDirection` không quyết định request có filter hay không.
- `categoryIds` thiếu hoàn toàn là không có filter category. Có truyền nhưng rỗng/sai UUID là request lỗi, không tự chuyển sang TH4.
- Giữ cách nhận một UUID, UUID phân cách dấu phẩy hoặc query param lặp cho collection `categoryIds`. Scalar params dùng cơ chế binding mặc định của Spring, không tự kiểm tra số lần xuất hiện trong raw query.

### 3.2. Bốn trường hợp đã chốt

| Trường hợp | Điều kiện chọn channel | Thứ tự | Gọi User Service |
|---|---|---|---|
| TH1: keyword + categoryIds | Khớp keyword AND ít nhất một category được chọn | Follower DESC | Không |
| TH2: chỉ keyword | Khớp keyword | Follower DESC | Không |
| TH3: chỉ categoryIds | Khớp ít nhất một category được chọn | Follower DESC | Không |
| TH4: không có cả hai | Tất cả channel đủ điều kiện chưa follow | Nhóm trùng sở thích trước, nhóm không trùng sau; mỗi nhóm follower DESC | Tối đa một lần |

TH1–TH3 tập trung hoàn toàn vào filter do user truyền. Không dùng sở thích để lọc, bổ sung, ưu tiên hoặc phá hòa; không khớp thì trả page rỗng.

Trong TH4, sở thích chỉ thay đổi vị trí hiển thị:

- User chưa có sở thích: toàn bộ channel đủ điều kiện sort follower DESC.
- Có sở thích nhưng không channel nào trùng: vẫn trả các channel đủ điều kiện, sort follower DESC.
- Có cả channel trùng và không trùng: toàn bộ nhóm trùng đứng trước nhóm không trùng, kể cả khi channel không trùng có nhiều follower hơn.
- Category sở thích không tồn tại/inactive không tạo ra ưu tiên. Nếu không còn category sở thích hợp lệ, thứ tự tương đương không có sở thích.
- Một channel trùng nhiều sở thích vẫn chỉ xuất hiện một lần, không được cộng thêm điểm hay xếp trên channel trùng một sở thích vì số category trùng.
- Mỗi nhóm dùng `created_at DESC, id DESC` khi follower bằng nhau.

Ví dụ user thích Valorant:

| Channel | Trùng sở thích | Follower | Thứ tự TH4 |
|---|---|---:|---:|
| A | Có | 500 | 1 |
| B | Có | 100 | 2 |
| C | Không | 10.000 | 3 |
| D | Không | 300 | 4 |

Nếu user nhập keyword chỉ khớp C và D, chuyển sang TH2 và trả C, D theo follower; không xét sở thích Valorant.

## 4. Thiết kế query và thuật toán ưu tiên

### 4.1. Specifications lọc dữ liệu

Tái sử dụng và tách rõ trách nhiệm trong `channel.repository.specification`:

- Active và chưa xóa theo quy ước entity hiện tại.
- Không thuộc user hiện tại, giữ channel legacy owner null.
- `matchesKeyword`: chỉ dùng cho API chưa follow; bỏ qua null/blank, strip, lowercase; giữ escape literal `%`, `_`, `!`, backslash như contract hiện có.
- `inActiveCategories`: EXISTS theo `channel_categories` và trạng thái category, chỉ dùng cho filter chủ động TH1/TH3 của API chưa follow.
- Membership follow: EXISTS cho API following, NOT EXISTS cho API chưa follow, luôn scope theo user ID hiện tại.

Không join collection categories trực tiếp để phân trang rồi dùng DISTINCT vá kết quả. Dùng EXISTS để một channel khớp nhiều categories vẫn chỉ có một item và một đơn vị trong count.

### 4.2. Query sort thời gian follow

`ChannelEntity` hiện không có collection follower; `ChannelFollowerEntity` đã có `followedAt`, còn repository channel đã hỗ trợ Specifications.

- Giữ đường query `ChannelEntity` để tái sử dụng các Specifications về eligibility, ownership và membership; không thêm Specifications keyword/category vào API following.
- Với `FOLLOWED_AT`, thêm root `ChannelFollowerEntity` vào Criteria query, liên kết bằng channel ID và điều kiện `follower.id.userId = authenticatedUserId`.
- Composite PK `(channel_id, user_id)` bảo đảm mỗi channel có tối đa một quan hệ cho user đó, tránh nhân bản kết quả.
- Order theo `follower.followedAt` với direction được chọn, sau đó channel creation time và ID.
- Content và count phải tương đương về membership, eligibility và ownership. Count không mang order hoặc fetch chỉ dùng cho content.
- Thực hiện bằng repository fragment/custom Criteria query khi `JpaSpecificationExecutor.findAll(spec, pageable)` không biểu diễn được order trên root liên quan. Không thêm mapping entity collection chỉ để phục vụ sort.

### 4.3. Xác định channel trùng sở thích

Thuật toán nghiệp vụ:

```text
hasInterestMatch(channel, interestCategoryIds)
    = tồn tại ít nhất một category active của channel nằm trong interestCategoryIds
```

Biểu diễn ở database bằng correlated EXISTS và CASE trong ORDER BY:

```sql
-- SQL minh họa; triển khai bằng Criteria trong repository fragment.
ORDER BY
    CASE WHEN EXISTS (
        SELECT 1
        FROM channel_categories cc
        JOIN categories cat ON cat.id = cc.category_id
        WHERE cc.channel_id = channel.id
          AND cc.category_id IN (:interestCategoryIds)
          AND cat.status = 'ACTIVE'
    ) THEN 0 ELSE 1 END ASC,
    channel.follower_count DESC,
    channel.created_at DESC,
    channel.id DESC
```

- EXISTS này chỉ dùng cho order TH4; tuyệt đối không thêm thành điều kiện WHERE bắt buộc.
- Khi interest list rỗng, bỏ biểu thức ưu tiên và dùng follower DESC, tránh `IN ()`.
- TH1–TH3 không tạo biểu thức ưu tiên và không cần dữ liệu sở thích.
- Count TH4 tính toàn bộ channel đủ điều kiện chưa follow; không cần CASE/order hoặc join sở thích.
- Không lấy toàn bộ channel về Java, không gọi User Service theo từng channel, không tính giao categories trên từng item sau khi phân trang.
- Lấy sở thích trước khi bắt đầu transaction đọc channel; dùng query service/transaction boundary phù hợp để không giữ transaction database trong lúc chờ HTTP.
- Đặt query lọc ở Specifications, order và pagination ở repository query. Kiểm chứng SQL được Hibernate sinh ra trên cả H2 và PostgreSQL trước khi chốt implementation.

## 5. Kế hoạch công việc theo thứ tự

### Task 1 — Tách API hiện tại thành hai API

- [x] Thêm handler GET `/api/channels/following`; handler GET `/api/channels` có nghĩa cố định là chưa follow.
- [x] Thay `ChannelListRequest` bằng `FollowingChannelListRequest` chỉ có `sortBy`, `sortDirection`, `page`, `size` và `UnfollowedChannelListRequest` có bộ lọc riêng; cả hai không còn field `following`.
- [x] Tách service method `getFollowingChannels(...)` và `getUnfollowedChannels(...)`; tên class service cụ thể có thể giữ cấu trúc hiện tại, không refactor các nghiệp vụ ghi không liên quan.
- [x] Giữ mapper, response envelope, metadata phân trang và các điều kiện eligibility hiện tại.
- [x] Duy trì authentication ở cả security filter và method security hiện tại; không thêm hai GET vào danh sách public.
- [x] Bỏ field `following` khỏi hai request DTO. Tham số cũ được Spring bỏ qua; đường dẫn endpoint xác định danh sách, không dùng `following` để đổi membership hoặc xác định filter.
- Frontend ngoài phạm vi: đã hoàn tác các thay đổi caller, types và query keys. Backend giữ contract hai endpoint; client cũ còn gửi `following` sẽ không đổi được loại danh sách và cần tích hợp riêng để gọi đúng endpoint.

Đầu ra: Hai endpoint độc lập hoạt động với contract mới; logic ghi follow/unfollow không bị ảnh hưởng. Trong giai đoạn xây dựng, chưa phát hành API chưa follow chưa hoàn thiện cho production.

### Task 2 — Hoàn thiện API danh sách đã follow trước

- [x] Thêm enum whitelist cho `sortBy`: `FOLLOWER_COUNT`, `FOLLOWED_AT`; direction `ASC`, `DESC`.
- [x] Default `FOLLOWER_COUNT + DESC`; chọn `FOLLOWED_AT` chỉ qua request chủ động.
- [x] Dùng binding/Bean Validation cho enum, non-null khi param có truyền và pagination. Theo yêu cầu mới, cả hai API không dùng custom raw-query validator; chỉ giữ Spring binding, `@Valid` và constraint thư viện trên DTO.
- [x] Xây query sort follower count và query sort thời gian follow theo thiết kế mục 4.2.
- [x] DTO và query following không có keyword/category; param ngoài model được bỏ qua theo Spring binding. Không tích hợp sở thích vào API này.
- [x] Kiểm tra user isolation: cùng channel nhưng hai user có thời điểm follow khác nhau phải sort theo quan hệ của từng user.
- [x] Hoàn thiện test following, kể cả hai chiều của từng tiêu chí và giá trị mặc định, trước khi chuyển sang API thứ hai.

Đầu ra: API following có thể nghiệm thu riêng với đúng một tiêu chí sort nghiệp vụ mỗi request.

### Task 3 — Xây bộ khung API danh sách chưa follow

- [x] Thêm DTO riêng gồm `keyword`, `categoryIds`, `sortDirection`, `page`, `size`.
- [x] Dùng enum whitelist chỉ có `DESC` cho direction của API này hoặc constraint Bean Validation tương đương; giữ param để có thể mở rộng sau. `ASC` trả `400`.
- [x] Bảo đảm `@Valid @ModelAttribute` thực sự được gọi; giữ `@Size(max = 255)`, validation collection và các constraint pagination.
- [x] Phân nhánh từ input đã được binding/validation: `hasKeyword = keyword != null && !keyword.isBlank()`, `hasCategories = categoryIds != null` vì tập rỗng đã bị reject.
- [x] Khai báo rõ bốn nhánh TH1–TH4; TH1–TH3 không phụ thuộc User Service.
- [x] Giữ validation kiểm tra offset vượt giới hạn, có thể đặt constraint cấp request bằng Jakarta Validation để kiểm tra tổ hợp page/size. Kiểm tra identity ở service boundary như hiện tại.
- [x] Không khai báo `sortBy` trong DTO API chưa follow; nếu client truyền thì Spring bỏ qua, không ảnh hưởng sort follower DESC.

Đầu ra: Request contract, validation, authentication và cấu trúc response hoàn chỉnh cho API thứ hai.

### Task 4 — Xây Specifications và query database

- [x] Tổ hợp base Specifications chung và membership đúng theo từng endpoint.
- [x] Hoàn thiện TH1: keyword AND EXISTS category được chọn.
- [x] Hoàn thiện TH2: chỉ keyword, không truy vấn hoặc áp dụng sở thích.
- [x] Hoàn thiện TH3: chỉ EXISTS category được chọn, không truy vấn hoặc áp dụng sở thích.
- [x] TH4 chỉ có điều kiện eligibility và chưa follow trong WHERE; không lọc loại channel không trùng sở thích.
- [x] Xây repository fragment cho Criteria order của FOLLOWED_AT và interest priority; tái sử dụng bộ lọc, không tạo hai bộ quy tắc eligibility khác nhau.
- [x] Tách content và count query; bỏ order khỏi count, kiểm tra duplicate và totals với channel có nhiều categories.
- [x] Kiểm tra SQL thực tế để bảo đảm limit/offset sau toàn bộ order, không N+1 khi map channel summary.

Đầu ra: Query đúng điều kiện lọc, sort và pagination, sẵn sàng nhận interest IDs ở TH4.

### Task 5 — Lấy sở thích và xây thuật toán đối chiếu categories

- [x] Tại User Service, thêm `GET /user/internal/api/users/{userId}/category-preferences` và DTO/envelope theo quy ước hiện tại.
- [x] Tái sử dụng `CategoryPreferenceService.getCategoryPreferences(userId)` và `UserCategoryPreferenceRepository.findCategoryIdsByUserId(...)`.
- [x] User chưa có sở thích trả `200` với `categoryIds: []`; user không tồn tại trả `404`; UUID sai trả `400`.
- [x] Endpoint chỉ đọc DB User Service, không gọi ngược Channel Service để validate sở thích khi đọc. Category active được kiểm tra tại query Channel Service.
- [x] Đặt controller tại `user.controller.internal`, cập nhật security đúng GET cụ thể theo cơ chế internal hiện tại; Gateway `/user/api/**` không expose internal path. Quyền truy cập backend tiếp tục dựa trên mạng tin cậy hiện có.
- [x] Tại Channel Service, thêm Feign client, remote DTO và resolver tại `channel.client.user`; không import entity/repository của User Service.
- [x] Dùng `contextId` riêng, cấu hình timeout thực sự áp dụng cho client mới và kiểm tra response envelope/list UUID hợp lệ. Chỉ danh sách rỗng hợp lệ mới mang nghĩa chưa có sở thích; null body/null list không được coi là chưa có sở thích.
- [x] Chuẩn hóa interest IDs thành tập không trùng, cung cấp cho EXISTS/CASE trong query, chỉ một lần cho mỗi request TH4.
- [x] Kiểm tra bảng ví dụ mục 3.2, trường hợp trùng nhiều categories và toàn bộ sở thích inactive/missing.

Đầu ra: Nguồn interest IDs đúng user và biểu thức ưu tiên boolean đúng quy tắc trùng ít nhất một category.

### Task 6 — Kết hợp và hoàn thiện API chưa follow

- [x] Kết hợp routing bốn trường hợp với query và integration client.
- [x] Chỉ TH4 gọi resolver sở thích; request chỉ có page/size/direction hoặc keyword blank vẫn là TH4 nếu không có categoryIds.
- [x] Gắn order interest priority trước follower DESC trong TH4; TH1–TH3 chỉ follower DESC và tiêu chí phụ.
- [x] Khi user chưa có sở thích hoặc không có channel trùng, vẫn trả toàn bộ channel đủ điều kiện chưa follow.
- [x] Áp dụng chính sách lỗi tại mục 6, ghi log lỗi integration không chứa token/credential và không catch mọi exception để coi là không có sở thích.
- [x] Kiểm tra thay đổi sở thích có hiệu lực ở request kế tiếp vì chưa dùng cache; follow/unfollow làm channel chuyển đúng danh sách.
- [x] Kiểm tra request page rơi qua ranh giới hai nhóm có thứ tự đúng và totals vẫn bao gồm cả hai nhóm.

Đầu ra: API thứ hai đáp ứng đầy đủ TH1–TH4 từ HTTP xuống database và response.

### Task 7 — Viết test đầy đủ và nghiệm thu

- [x] Viết test binding/Bean Validation, service branching, HTTP integration, repository/query và client HTTP theo ma trận mục 7.
- [x] Chuyển các test cũ dùng `following=true/false` sang endpoint tương ứng, giữ regression của authentication, follow/unfollow, ownership, counter và metadata.
- [x] Mock client sở thích trong test không kiểm tra integration thật; verify số lần gọi và `never()` cho API following/TH1–TH3.
- [x] Với query CASE/EXISTS và sort followedAt, chạy fixture thực tế trên PostgreSQL dùng database/schema test dùng một lần theo convention repository, bên cạnh H2 mặc định.
- [x] Chạy các build/check ở mục 8 và cập nhật README/Javadoc cùng contract cuối.

Đầu ra: Có bằng chứng test cho các nghiệp vụ đã chốt, không chỉ test lại các dòng implementation.

## 6. Chính sách lỗi và các lựa chọn kỹ thuật cần ghi nhận

Các quy tắc lọc/sort ở mục 1–3 và chính sách lỗi dưới đây đã được triển khai. Chính sách fallback được chọn theo kế hoạch khi user yêu cầu implement: timeout, lỗi kết nối và HTTP 5xx chỉ bỏ ưu tiên sở thích tại TH4; các nhánh filter chủ động và API following không phụ thuộc User Service.

| Tình huống | Chính sách đã triển khai |
|---|---|
| Request binding/validation sai | `400` theo contract lỗi Channel Service hiện tại |
| Không có authentication/subject user UUID hợp lệ | `401` như hiện tại |
| Database Channel Service lỗi | `503` như hiện tại; không fallback thành page rỗng |
| User Service trả sở thích rỗng hợp lệ | TH4 không ưu tiên sở thích, vẫn follower DESC |
| User Service timeout, lỗi kết nối hoặc HTTP 5xx | TH4 fallback về toàn bộ channel đủ điều kiện với follower DESC; ghi log nguyên nhân |
| User Service trả user không tồn tại | Trả lỗi user không tồn tại, không coi là chưa có sở thích |
| User Service trả 4xx ngoài hợp đồng, envelope/null list/UUID không hợp lệ | Lỗi integration, map thành `503` phù hợp; không âm thầm coi là sở thích rỗng |

Fallback chỉ được tác động đến ưu tiên sở thích ở TH4, không bỏ authentication, ownership, trạng thái channel hoặc điều kiện chưa follow. Nếu business sau này đổi sang trả `503` thay vì fallback cho timeout/5xx, chỉ đổi policy resolver và test liên quan; bốn trường hợp lọc/sort không thay đổi.

Dùng `AppException(ErrorCode)` theo service-local `common.exception`; tái sử dụng error code phù hợp, chỉ thêm enum constant khi cần và không tạo exception class riêng. Không lặp lại kiểm tra user status/authorities vốn thuộc Gateway và security.

## 7. Ma trận kiểm thử bắt buộc

### 7.1. Routing, security và validation

| ID | Trường hợp | Kết quả cần chứng minh |
|---|---|---|
| V01 | Không đăng nhập, cookie/JWT sai, subject không phải user UUID | Cả hai endpoint trả lỗi authentication phù hợp |
| V02 | Request hợp lệ không truyền page/size | Default page 0, size 20 |
| V03 | Page âm, size 0/101, giá trị rỗng/sai kiểu, offset vượt giới hạn | `400`, không gọi User Service trước khi validation thành công |
| V04 | API chưa follow: keyword null, rỗng, whitespace | Không lọc keyword; chọn đúng TH3 hoặc TH4 |
| V05 | API chưa follow: keyword quá 255 ký tự, gồm cả whitespace | `400` trước strip |
| V06 | API chưa follow: category sai UUID, rỗng, có phần tử null/rỗng | `400`, không tự chuyển sang TH4 |
| V07 | API chưa follow: UUID một giá trị, comma-separated, lặp categoryIds, ID trùng | Binding hợp lệ, any-match và không duplicate |
| V08 | SortBy/direction không hợp lệ hoặc rỗng | `400`, không âm thầm dùng default |
| V09 | Comma-separated hai tiêu chí sortBy | `400` do enum binding; cả hai API không dùng custom validation raw query lặp |
| V10 | API chưa follow truyền ASC hoặc sortBy | ASC trả `400` qua enum binding; sortBy không có trong DTO nên được bỏ qua, vẫn sort follower DESC |
| V11 | Request còn param following cũ | Bỏ qua param ngoài model; chỉ đường dẫn xác định danh sách, không đổi membership hoặc user |
| V12 | Gọi endpoint internal qua Gateway; gọi endpoint user public không token | Internal không được route public, public endpoint vẫn cần authentication |

### 7.2. API đã follow

| ID | Trường hợp | Kết quả cần chứng minh |
|---|---|---|
| F01 | Không truyền sort | Follower DESC, không mặc định sort followedAt |
| F02 | FOLLOWER_COUNT ASC/DESC | Đúng hai chiều; thay đổi followedAt không đổi thứ tự khi follower khác nhau |
| F03 | FOLLOWED_AT ASC/DESC | Đúng hai chiều; thay đổi follower không đổi thứ tự khi timestamp khác nhau |
| F04 | FOLLOWED_AT không có direction | DESC theo contract |
| F05 | Hai user follow cùng channels ở thời điểm khác nhau | Sort theo timestamp của đúng user; không lộ quan hệ user khác |
| F06 | Timestamp hoặc follower bằng nhau | Đúng createdAt DESC, id DESC; không thêm tiêu chí nghiệp vụ còn lại |
| F07 | Truyền keyword, categoryIds hoặc param ngoài model vào API following | Không lọc theo các param này; Spring bỏ qua và vẫn trả danh sách theo bốn param request model |
| F08 | Không follow channel nào, page vượt cuối | Page rỗng với totals đúng |
| F09 | Unfollow rồi follow lại | Timestamp mới quyết định vị trí khi sort FOLLOWED_AT |
| F10 | Nhiều pages với từng tiêu chí sort; channel có hoặc không có categories | Không duplicate, không mất item trong dữ liệu đứng yên, totals đúng; categories không ảnh hưởng kết quả |
| F11 | User Service không hoạt động | Endpoint vẫn chạy, verify không gọi client sở thích |

### 7.3. API chưa follow — TH1 đến TH3

| ID | Trường hợp | Kết quả cần chứng minh |
|---|---|---|
| U01 | Keyword + categoryIds | Chỉ item khớp cả keyword và ít nhất một category, follower DESC |
| U02 | Keyword khớp nhưng category không khớp và ngược lại | Không hiển thị item chỉ khớp một điều kiện của TH1 |
| U03 | Chỉ keyword | Chỉ item khớp keyword, follower DESC |
| U04 | Keyword không có kết quả, ví dụ dsdsadsa | Page rỗng, không thêm channel ngoài keyword |
| U05 | Chỉ categoryIds | Chỉ any-match category active, follower DESC |
| U06 | Categories không match, missing hoặc inactive | Không match trả rỗng; mixed IDs chỉ tính active hợp lệ |
| U07 | User có sở thích khác categories/keyword đang tìm | Không áp dụng sở thích, verify không gọi User Service |
| U08 | User Service lỗi hoặc có sở thích ưu tiên channel follower thấp | TH1–TH3 không bị ảnh hưởng; sort vẫn thuần follower DESC |
| U09 | Chữ hoa/thường, surrounding whitespace, %, _, !, backslash | Giữ hành vi keyword literal hiện tại |

### 7.4. API chưa follow — TH4 và interest priority

| ID | Trường hợp | Kết quả cần chứng minh |
|---|---|---|
| I01 | Không có filter, channel trùng và không trùng | Toàn bộ nhóm trùng trước, không loại nhóm còn lại |
| I02 | Channel không trùng có nhiều follower hơn channel trùng | Channel trùng vẫn đứng trước |
| I03 | Nhiều channel trong từng nhóm | Follower DESC độc lập trong từng nhóm; tiêu chí phụ đúng |
| I04 | User chưa có sở thích | Toàn bộ đủ điều kiện, follower DESC |
| I05 | Có sở thích nhưng không có channel trùng | Toàn bộ đủ điều kiện vẫn hiển thị, follower DESC |
| I06 | Trùng một và nhiều categories | Cùng một nhóm boolean, không thưởng số category, mỗi channel xuất hiện một lần |
| I07 | Sở thích gồm active/missing/inactive hoặc toàn bộ missing/inactive | Chỉ active tạo ưu tiên; không làm mất channel khỏi tổng kết quả |
| I08 | Chỉ có page/size/direction hoặc keyword blank | Vẫn TH4, gọi sở thích tối đa một lần |
| I09 | Page nằm trong nhóm trùng, qua ranh giới nhóm, trong nhóm không trùng | Đúng global order trước phân trang và đúng totals của cả hai nhóm |
| I10 | User A/B có sở thích khác nhau; đổi sở thích giữa hai request | Thứ tự theo đúng user và dữ liệu cập nhật, không dùng lại cache user khác |
| I11 | Timeout, network error, 5xx User Service | Theo policy mục 6; test fallback đầy đủ khi policy được áp dụng |
| I12 | User không tồn tại, HTTP lỗi, envelope/list/UUID hỏng | Không coi là sở thích rỗng; trả lỗi đúng policy |

### 7.5. Regression và integration

- Channel banned, soft-deleted, thuộc chính user và legacy self-follow không xuất hiện trong hai danh sách; owner null tiếp tục xử lý như hiện tại.
- Follow/unfollow làm membership và follower counter cập nhật đúng như trước; kiểm tra lại kết quả hai endpoint sau thao tác.
- Follower lớn hơn giới hạn int, follower bằng 0, URL ảnh null và response field không thay đổi.
- API chưa follow có count đúng với nhiều category, sở thích trùng và mọi nhánh TH1–TH4; API following có count đúng với từng mode sort, không lọc keyword/category. Page cuối/page ngoài phạm vi đúng.
- SQL CASE/EXISTS và query root follower chạy trên PostgreSQL, không chỉ H2; không có lỗi ORDER BY trong count.
- API nội bộ User Service: user có/không có sở thích, user không tồn tại, UUID sai và response envelope đúng; API đọc không gọi Channel Service.
- Feign integration: đúng URL/context path, user ID, timeout, response envelope, danh sách ID; contextId không xung đột và timeout config mới thực sự được dùng.

## 8. File/module dự kiến và cách xác minh

| Khu vực | Công việc |
|---|---|
| `channel-service/.../channel/controller/external/ChannelController.java` | Tách GET handler, authentication và DTO validation |
| `channel-service/.../channel/dto/request/` | Hai request DTO, sort enums/validation cần thiết |
| `channel-service/.../channel/service/` và `service/impl/` | Tách use case, branch TH1–TH4, transaction boundary, Javadoc tiếng Anh cho mọi method trên interface bị sửa |
| `channel-service/.../channel/repository/specification/` | Bộ lọc tái sử dụng, membership và category EXISTS |
| `channel-service/.../channel/repository/` | Custom Criteria fragment cho sort followedAt/interest priority, content/count |
| `channel-service/.../channel/client/user/` | Feign client, DTO, resolver đọc sở thích |
| `channel-service/src/main/resources/application-dev.yaml` | Timeout/config cho client mới; không commit credential |
| `user-service/.../user/controller/internal/` | API nội bộ đọc sở thích, tái sử dụng service/repository hiện có |
| `user-service/.../common/security/SecurityConfig.java` | Cho phép đúng GET nội bộ theo cơ chế internal hiện tại |
| `channel-service/src/test/`, `user-service/src/test/` | Validation, client HTTP, integration và query tests theo ma trận |
| README hai service và tài liệu contract liên quan | Hai endpoint, ví dụ sort/filter, error policy và migration |

Không thêm bảng hoặc cột: `follower_count`, `followed_at`, categories và preferences đã tồn tại. Không thêm index theo suy đoán; nếu EXPLAIN trên dữ liệu đại diện cho thấy cần index thì bổ sung bằng migration riêng, ghi rõ bằng chứng và cập nhật data model.

Lệnh xác minh khi thực hiện implementation:

```bash
# Chạy trong channel-service và user-service, mỗi module một lần.
./mvnw clean verify

# Chạy ở repository root.
git diff --check
```

Kết quả build/test thực tế được ghi tại mục 11; các test tùy chọn bị skip và phạm vi kiểm chứng được ghi riêng.

## 9. Triển khai, nghiệm thu và chuyển contract

1. Hoàn tất Task 1–2 và nghiệm thu following trước; có thể chia PR theo mốc nhưng không phát hành phần chưa follow chưa đủ TH1–TH4.
2. Hoàn tất Task 3–6 và toàn bộ ma trận Task 7.
3. Deploy API nội bộ User Service trước, rồi phát hành Channel Service. Frontend không được sửa trong đợt này; caller dùng contract cũ cần được tích hợp riêng trước khi sử dụng hai API mới.
4. Contract đích không giữ alias `following=true/false`; đây là param ngoài DTO và được bỏ qua. Caller cũ phải chọn đúng endpoint; nếu cần giai đoạn tương thích thì triển khai handler có chủ đích trong đợt riêng.
5. Smoke test qua Gateway với hai user có sở thích và thời điểm follow khác nhau: default follower DESC, cả hai chiều FOLLOWED_AT, TH1–TH4, phân trang qua hai nhóm, thay đổi sở thích và follow/unfollow.
6. Nghiệm thu implementation backend khi cả hai endpoint và validation/test hoàn tất. Tích hợp frontend và deploy môi trường chạy thật được theo dõi riêng, không nằm trong phạm vi hiện tại.

Điều kiện nghiệm thu cuối:

- [x] Hai endpoint backend độc lập, user ID chỉ lấy từ principal; param following cũ không còn tác dụng và được bỏ qua khi binding. Việc chuyển caller frontend nằm ngoài phạm vi.
- [x] API following chỉ nhận sortBy, sortDirection, page, size; không có keyword/categoryIds. Mặc định follower DESC, hỗ trợ follower ASC/DESC hoặc followedAt ASC/DESC; mỗi request chỉ một tiêu chí nghiệp vụ.
- [x] API chưa follow chỉ chấp nhận DESC và đáp ứng đúng TH1–TH4; chỉ TH4 sử dụng sở thích để ưu tiên, không lọc loại channel không trùng.
- [x] Lọc/order/count/pagination đúng tại database, không duplicate, không N+1 và không sort sau phân trang.
- [x] Request dùng library validation đầy đủ; lỗi tích hợp theo policy được ghi rõ, không coi lỗi thành sở thích rỗng.
- [x] Test và tài liệu đủ cho business mới; không thay đổi các nghiệp vụ ngoài phạm vi.

## 10. Tài liệu tham chiếu

- [Product overview](../product/Overview.md).
- [Features](../product/Features.md).
- [System architecture](../architecture/System%20Architecture.md).
- [Data model](../data-model/Data%20Model.md).
- [Channel Service README](../../channel-service/README.md).
- [User Service README](../../user-service/README.md).
- [Code architecture](../../.agents/skills/code-architecture/SKILL.md).
- [Code style](../../.agents/skills/code-style/SKILL.md).
- [Service Javadoc](../../.agents/skills/service-javadoc/SKILL.md).
- [Exception handling](../../.agents/skills/exception-handling/SKILL.md).


## 11. Kết quả thực hiện và kiểm chứng

Đã hoàn tất phần backend của Task 1–7 và cập nhật README hai service cùng Javadoc. Ngày 2026-10-19 đã rollback toàn bộ thay đổi frontend của tính năng theo yêu cầu; backend được giữ nguyên. Không thêm migration hoặc thay đổi nghiệp vụ ghi follow/unfollow. Frontend hiện vẫn dùng contract cũ và cần được tích hợp riêng khi sử dụng API mới.

| Kiểm tra | Kết quả |
|---|---|
| `channel-service`: `./mvnw -q clean verify` | 437 test thành công, 0 failures/errors/skips; build thành công |
| `user-service`: `./mvnw -q clean verify` | 672 test thực chạy thành công; 36 test tùy chọn bị skip; 0 failures/errors; build thành công |
| Hai suite `ChannelFollowingIntegrationTest`, `ChannelListIntegrationTest` trên PostgreSQL 14.23 riêng | 87 test thành công; đúng follow-time sort, TH1–TH4, ưu tiên trước phân trang và totals |
| Kiểm tra SQL sinh ra | CASE/EXISTS và follow timestamp nằm trong ORDER BY trước offset/fetch; count không loại nhóm không trùng sở thích |
| `git diff --check` và kiểm tra file mới | Không có lỗi whitespace hoặc link tài liệu local |

36 test tùy chọn của User Service phụ thuộc cấu hình Redis/test integration chưa được bật trong lần chạy này; API sở thích public/internal và toàn bộ test mới đều đã chạy. PostgreSQL dùng cluster/database riêng tại cổng 55441, không chạy test lên database ứng dụng. Phiên bản PostgreSQL kiểm chứng local là 14.23; PostgreSQL 17 của workflow vẫn do CI kiểm chứng khi chạy pipeline.

`UserCategoryPreferenceClientIntegrationTest` đã kiểm tra endpoint discovery qua MockMvc, Feign HTTP thật tới stub và database H2, gồm ưu tiên sở thích, fallback, filter bỏ qua upstream và lỗi upstream sai contract. Các test following thực hiện unfollow/follow lại qua HTTP để kiểm tra timestamp mới. Chưa smoke test bằng một deployment Gateway + hai backend đang chạy; bước đó nằm trong checklist phát hành tại mục 9.


### Cập nhật validation ngày 2026-10-19

Theo yêu cầu mới, đã xóa annotation/validator raw-query tự viết và tham số map query khỏi controller. Hai API chỉ dùng Spring binding, `@Valid` và Jakarta Bean Validation trên DTO. Param ngoài DTO được bỏ qua; test xác nhận chúng không thay đổi user, membership hoặc thứ tự sort. Test không còn yêu cầu reject param lạ/lặp bằng validator riêng. Validation UUID, enum, keyword length, page/size và offset vẫn hoạt động.

Đã chạy `./mvnw -q clean -Dtest=ChannelListIntegrationTest,ChannelFollowingIntegrationTest,UnfollowedChannelListRequestValidationTest test`: 87 test thành công, không failures/errors/skips. Không thay đổi frontend hoặc commit/push thêm trong lần cập nhật này.


### Rà soát toàn bộ test ngày 2026-10-19

Đã đồng bộ test với code hiện tại, gồm Client tại `com.reallive.channelservice.client.user`, repository implementation tại `channel.repository.impl` và controller preferences tại `user.controller.external`. Chỉ sửa/move test và cập nhật tài liệu; nội dung production và các thay đổi đã stage trước đó được giữ nguyên.

- `ChannelFollowingIntegrationTest`: 36 trường hợp cho API following riêng; hai tiêu chí/hai chiều sort, default, timestamp đúng user, tie-breaker createdAt/ID, pagination, eligibility, authentication, DTO binding và extra params được bỏ qua.
- `ChannelListIntegrationTest`: 65 trường hợp; TH1–TH4, AND giữa keyword/category, OR giữa categories, không fallback khi filter không khớp, boolean interest priority không cộng điểm theo số category, giữ nhóm không trùng, pagination qua ranh giới hai nhóm, thay đổi sở thích, ownership/status/membership và validation.
- `ChannelListingServiceTest`: 14 trường hợp về orchestration; chỉ TH4 đọc sở thích một lần trước DB, filter chủ động không phụ thuộc User Service, lỗi preference contract không query DB, một tiêu chí sort và giữ metadata.
- `FollowingChannelListRequestValidationTest`: 10 trường hợp; default/builder, các sort/direction hợp lệ, field bắt buộc và biên pagination/offset.
- `UnfollowedChannelListRequestValidationTest`: 4 trường hợp; category null/empty/null-element, keyword raw length và default/offset của riêng DTO unfollowed.
- `UserCategoryPreferenceClientIntegrationTest`: 24 trường hợp HTTP/Feign thực với stub, timeout/fallback, response không hợp lệ và endpoint discovery kết hợp DB; không còn import package cũ.
- `CategoryPreferenceControllerTest`: 19 trường hợp tại package external, bao gồm public và internal preference APIs.

Kết quả `./mvnw -q clean verify`: Channel Service có 475 test thành công, không failure/error/skip; User Service có 672 test thực chạy thành công và 36 test Redis/integration tùy chọn bị skip, không failure/error. Mọi test của chức năng danh sách channel và preference API đều đã chạy. Lần rà soát này chạy H2 và HTTP stub; không chạy lại PostgreSQL hoặc deploy Gateway. Không commit/push thêm trong lần cập nhật test này.


### Đồng bộ DTO danh sách channel ngày 2026-10-19

Đã bỏ `bannerUrl` khỏi `ChannelListResponse` của cả hai API following/unfollowed. Item chỉ còn `id`, `name`, `avatarUrl`, `numberOfFollower`; MapStruct sinh lại mapper theo DTO mới. Regression test kiểm tra đúng bốn field và không có key bannerUrl, kể cả khi channel lưu banner URL trong database. Phần banner của entity, API cập nhật/detail và storage không đổi. Phạm vi thay đổi tiếp tục chỉ gồm backend và tài liệu.

Kiểm chứng sau khi bỏ bannerUrl: `./mvnw -q clean verify` tại Channel Service thành công với 475 test, không failure/error/skip; mapper sinh lại không ánh xạ banner vào list nhưng vẫn giữ banner trong ChannelResponse.
