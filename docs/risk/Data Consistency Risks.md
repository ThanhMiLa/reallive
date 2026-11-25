# Rủi ro nhất quán dữ liệu giữa các service

| Giai đoạn                                                           | Thời điểm có thể lệch dữ liệu | Hậu quả |
|---------------------------------------------------------------------| --- | --- |
| **1. Cập nhật tên/avatar: User Service → Channel Service**          | User Service sửa profile, upload avatar mới nếu có, rồi gọi cập nhật channel trước khi commit profile. Channel đã cập nhật nhưng User nhận timeout hoặc transaction profile rollback. | Profile giữ tên/avatar cũ nhưng channel giữ tên/avatar mới. Khi rollback, cleanup có thể xóa avatar mới trên R2 trong khi channel vẫn giữ URL đó, làm ảnh channel không truy cập được. |
| **2. Yêu cầu gửi OTP: User Service → Kafka → Notification Service** | Trong luồng xác thực email hoặc quên mật khẩu, User Service đã lưu OTP/cooldown vào Redis nhưng process crash trước khi publish Kafka. Xử lý exception hiện có không bao phủ được crash giữa hai bước. | OTP/cooldown tồn tại nhưng Notification không nhận được yêu cầu gửi email. Người dùng chưa nhận được OTP và có thể phải chờ cooldown hết trước khi yêu cầu lại. |
