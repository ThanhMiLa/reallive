# ACTORS & ROLES

## 1. User

- Là người dùng thông thường của hệ thống.
- Có thể sử dụng các chức năng dành cho viewer như:
  - Xem livestream
  - Xem lại VOD
  - Chat trong livestream
  - Follow / Unfollow channel
  - Donate cho streamer
  - Báo cáo user hoặc livestream vi phạm
- USER chưa có quyền livestream mặc định.
- Nếu muốn trở thành streamer, USER phải thực hiện quy trình đăng ký / kích hoạt streamer và hoàn thành các yêu cầu cần thiết như xác thực email.

---

## 2. Streamer

- Là một USER đã được kích hoạt quyền livestream trên hệ thống.
- Khi một USER trở thành STREAMER, hệ thống sẽ tự động tạo một channel riêng cho streamer đó.
- Mỗi STREAMER chỉ sở hữu một channel chính trong hệ thống.
- STREAMER có thể:
  - Quản lý thông tin channel
  - Thiết lập livestream
  - Tạo stream session
  - Thiết lập title, category và visibility
  - Quản lý stream key
  - Bắt đầu và kết thúc livestream
  - Quản lý các VOD được tạo từ livestream cũ
  - Xem doanh thu và donation
  - Gửi yêu cầu rút tiền
  - Chỉ định hoặc thu hồi Channel Moderator cho channel của mình
- Quyền livestream của STREAMER có thể bị suspend hoặc revoke nếu vi phạm chính sách của nền tảng.

---

## 3. Channel Moderator

- Là một USER được STREAMER cấp quyền kiểm duyệt cho một channel cụ thể.
- Có trách nhiệm hỗ trợ streamer quản lý cộng đồng và nội dung chat trong các phiên livestream của channel đó.
- Có thể thực hiện các hành động như:
  - Xóa message vi phạm
  - Mute user
  - Timeout user
  - Ban user khỏi chat của channel
  - Unban user
  - Xử lý spam hoặc hành vi vi phạm trong livestream
- Quyền của CHANNEL MODERATOR chỉ có hiệu lực trong những channel mà user được cấp quyền.
- CHANNEL MODERATOR không có quyền quản trị toàn hệ thống.

---

## 4. Platform Moderator

- Là nhân sự kiểm duyệt ở cấp độ toàn nền tảng và được ADMIN cấp quyền.
- Có trách nhiệm xử lý report, khiếu nại và các nội dung vi phạm trên toàn hệ thống.
- Có thể thực hiện các hành động như:
  - Review report của user
  - Review report của livestream
  - Tạm dừng hoặc gỡ livestream vi phạm
  - Suspend quyền livestream của STREAMER
  - Suspend hoặc ban account
  - Xử lý channel hoặc nội dung vi phạm chính sách
- Quyền của PLATFORM MODERATOR có phạm vi toàn platform, không giới hạn trong một channel cụ thể.

---

## 5. Admin

- Là role có quyền quản trị cao nhất trong hệ thống.
- Có toàn quyền quản lý platform.
- Có thể thực hiện các chức năng như:
  - Quản lý user
  - Quản lý streamer
  - Quản lý PLATFORM MODERATOR
  - Quản lý category
  - Quản lý role và permission
  - Quản lý cấu hình hệ thống
  - Theo dõi hoạt động toàn hệ thống
  - Theo dõi platform analytics
  - Can thiệp vào các trường hợp moderation đặc biệt
  - Quản lý các chức năng cấp hệ thống khác