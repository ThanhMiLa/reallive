# OVERVIEW

## 1. Project Introduction
Xây dựng một Live Streaming Platform lấy cảm hứng từ Twitch, NimoTV và các nền tảng livestream hiện đại. Hệ thống cho phép người dùng xem livestream theo từng chủ đề hoặc category như Talk Show, Naraka, CS2, Valorant,... Người xem có thể tương tác với streamer và những người xem khác thông qua kênh chat realtime của từng phiên livestream, đồng thời có thể nạp xu và sử dụng xu để mua vật phẩm donate trực tiếp cho streamer.

## 2. Project Goals
- Xây dựng và học tập kiến trúc Microservices trong một hệ thống thực tế.
- Nghiên cứu và xử lý các bài toán realtime, concurrency và high concurrent connections.
- Áp dụng Redis, Message Broker và asynchronous processing trong các use case thực tế.
- Tìm hiểu cách thiết kế và scale một hệ thống livestream.
- Áp dụng các distributed system patterns và observability trong microservices.

## 3. Scope
- Xây dựng 1 hệ thống đầy đủ end-to-end (Backend, Frontend Web, Mobile Android).
- Hỗ trợ livestream qua phần mềm như OBS, chat realtime, donate...
- Thiết kế kiến trúc Microservices và Clean Architecture.
- Có khả năng scale theo chiều ngang (Horizontal Scaling).
- Áp dụng distributed system patterns thực tế.
- Deploy lên VPS (dự kiến).

## 4. Core Capabilities
- **Live Streaming**: Phát sóng trực tiếp chất lượng cao qua OBS.
- **Realtime Chat**: Tương tác trò chuyện thời gian thực.
- **Donation & Virtual Gift**: Nạp xu và tặng quà trực tiếp cho streamer.
- **Concurrent Viewer Tracking**: Thống kê số lượng người xem đồng thời.
- **Channel & Stream Management**: Quản lý kênh và quản lý phiên stream.
- **Follow & Notification**: Theo dõi channel và nhận thông báo stream.
- **Moderation**: Kiểm duyệt nội dung, chat và tài khoản.
- **Stream Category & Discovery**: Phân loại chủ đề và gợi ý livestream.
- **VOD (Video on Demand)**: Lưu trữ và phát lại bản ghi livestream.
- **Basic Analytics**: Thống kê số liệu hiệu năng và doanh thu.

## 5. Target Users
- **Viewer**: Người xem livestream.
- **Streamer**: Người phát sóng nội dung.
- **Channel Moderator**: Người kiểm duyệt Channel
- **Platform Moderator**: Người kiểm duyệt Platform
- **Admin**: Quản trị viên hệ thống.

## 6. Technical Overview

| Category | Technologies / Frameworks |
| :--- | :--- |
| **Architecture** | Microservices, Event-driven |
| **Backend** | Java 21, Spring Boot, JPA/Hibernate |
| **Database** | PostgreSQL, MongoDB |
| **Frontend** | React, TypeScript |
| **Android** | Kotlin |
| **Cache** | Redis |
| **Realtime** | WebSocket |
| **Message Broker** | Kafka |
| **CI/CD & DevOps** | Git, GitHub, GitHub Actions, Docker, VPS, Cloudflare, Firewall |
| **Media** | MediaMTX / SRS, RTMP, HLS, FFmpeg |
| **Payment** | PayOS or VNPay |
| **Observability** | Prometheus, Grafana, OpenTelemetry |
| **Testing** | JUnit, Testcontainers, k6 |