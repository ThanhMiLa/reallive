# Các vấn đề quản lý Stream Key

| Vấn đề | Mô tả |
| --- | --- |
| **1. Chuyển cấp/reset Stream Key sang xử lý bất đồng bộ** | Hiện endpoint `POST /stream/api/streams/me/stream-key` xử lý đồng bộ: client phải chờ kết thúc phiên đang phát, xóa ingest path cũ, tạo path mới trên MediaMTX và lưu DB rồi mới nhận key. MediaMTX phản hồi chậm có thể khiến request timeout và kéo dài thời gian giữ khóa DB. Cần nâng cấp sang xử lý bất đồng bộ và cho client theo dõi trạng thái xử lý. |
| **2. Bổ sung retry để bảo đảm nhất quán dữ liệu** | Hiện chưa tự retry khi gọi MediaMTX lỗi hoặc timeout. Nếu path cũ đã bị xóa nhưng tạo path mới thất bại, hoặc MediaMTX đã tạo path mới nhưng transaction DB rollback, cấu hình trong DB và whitelist sẽ không khớp nhau. Cần bổ sung retry có kiểm soát, chống xử lý trùng và kiểm tra trạng thái thực tế để bảo đảm dữ liệu nhất quán sau khi cấp/reset key. |
