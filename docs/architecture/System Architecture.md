# SYSTEM ARCHITECTURE

## 1. System Architecture Design
![Architecture Design](./Architecture%20Design.png)

---

## 2. Service Decomposition

### 2.1 API Gateway

**Responsibility**

- Acts as the single entry point for Web App and Mobile App requests.
- Routes incoming requests to the appropriate backend microservice.
- Performs common authentication checks before forwarding requests.
- Handles CORS configuration.
- Applies rate limiting and basic request protection.
- Propagates request IDs / tracing information across services.
- Centralizes common logging for incoming API requests.
- Does not contain domain-specific business logic.

**Owned Features**

- Request routing.
- Authentication token validation at the gateway level.
- CORS handling.
- Rate limiting.
- Request / response logging.
- Trace and correlation ID propagation.

**Technology**

- Java 21
- Spring Boot
- Spring Cloud Gateway
- Spring Security
- Redis for distributed rate limiting if required
- OpenTelemetry / Micrometer for tracing and metrics

---

### 2.2 User Service

**Responsibility**

- Manages user accounts, authentication, authorization-related account information and user profiles.
- Owns user identity and account status.
- Handles account-level moderation such as reporting users and banning accounts.
- Manages email verification status required for protected features such as livestreaming.

**Owned Features**

- Login.
- Logout.
- Register.
- Forgot Password.
- Change Password.
- User Profile.
- Email Verification Status.
- User Roles and Permissions.
- Report User.
- Ban Account.
- Suspend / Restore Streaming Permission.

**Technology**

- Java 21
- Spring Boot
- Spring Security
- Spring Data JPA / Hibernate
- PostgreSQL
- Redis for token/session-related data if required
- JWT / OAuth2 Resource Server
- BCrypt
- Kafka for publishing user-related domain events

---

### 2.3 Channel Service

**Responsibility**

- Manages streamer channels and channel-related relationships.
- Owns channel profile information.
- Manages follower relationships between users and channels.
- Manages channel-level moderator assignments.

**Owned Features**

- Create Channel.
- Update Channel.
- Follow Channel.
- Unfollow Channel.
- View Followers.
- Assign Channel Moderator.
- Remove Channel Moderator.
- View Channel Information.

**Technology**

- Java 21
- Spring Boot
- Spring Data JPA / Hibernate
- PostgreSQL
- Redis for caching frequently accessed channel information if required
- Kafka for publishing channel and follow-related events

---

### 2.4 Stream Service

**Responsibility**

- Manages the complete lifecycle of a livestream session.
- Coordinates business state between the platform backend and the Media Server.
- Manages stream keys and stream publishing permissions.
- Tracks livestream state such as READY, LIVE, RECONNECTING and ENDED.
- Handles callbacks / webhooks from the Media Server.
- Provides livestream metadata and playback information to clients.

**Owned Features**

- Create Stream Session.
- Set Stream Title.
- Choose Category.
- Configure Stream Visibility.
- Generate Stream Key.
- Rotate Stream Key.
- Validate Stream Key.
- Start Stream.
- Change Stream Status to LIVE.
- Watch Stream Metadata.
- Viewer Count.
- End Stream.
- Handle Unexpected Disconnect.
- Handle Stream Reconnect.
- Report Stream.
- Media Server Webhook / Callback Handling.
- Generate or provide Playback URL.
- Publish StreamStarted / StreamEnded domain events.

**Technology**

- Java 21
- Spring Boot
- Spring Data JPA / Hibernate
- PostgreSQL
- Redis for viewer presence, viewer count and temporary realtime state
- Kafka for stream lifecycle events
- WebClient / HTTP for Media Server integration where required
- MediaMTX or SRS as external media infrastructure
- RTMP for ingest
- HLS for playback

---

### 2.5 Chat Service

**Responsibility**

- Provides realtime communication inside livestream channels.
- Manages WebSocket / Socket.IO connections and chat rooms.
- Handles channel chat moderation.
- Supports horizontal scaling of multiple Chat Service instances through Redis Pub/Sub.
- Persists chat history in MongoDB.
- Stores chat messages with livestream timeline information so previous chat messages can be replayed while users watch recorded VOD.

**Owned Features**

- Connect to Livestream Chat.
- Send Message.
- Receive Message.
- Persist Chat Message History.
- Load Chat History.
- Replay Chat Messages with VOD Timeline.
- Mute User.
- Unmute User.
- Ban User From Channel Chat.
- Unban User.
- Channel Chat Room Management.
- Chat Rate Limiting / Slow Mode if added later.

**Technology**

- Node.js
- TypeScript
- NestJS
- WebSocket / Socket.IO
- MongoDB for persistent chat history
- Redis Pub/Sub for realtime message distribution between multiple Chat Service instances
- Redis for mute / ban / rate-limit state where appropriate
- Kafka optionally for durable chat-related events, moderation or analytics

---

### 2.6 Payment Service

**Responsibility**

- Owns all financial and virtual-economy operations in the platform.
- Manages coin balances, virtual gifts, donations and streamer revenue.
- Integrates with external payment providers.
- Maintains transaction consistency and idempotency.
- Provides basic donation and revenue statistics.

**Owned Features**

- Buy Coin.
- View Coin Balance.
- Buy Virtual Gift.
- Donate Gift.
- Donation Ranking.
- Donation History.
- Transaction Payment History.
- Refund Gift.
- Streamer Revenue.
- Withdraw Request.
- Withdrawal History.
- Basic Donation Statistics.
- Basic Revenue Statistics.

**Technology**

- Java 21
- Spring Boot
- Spring Data JPA / Hibernate
- PostgreSQL
- Redis where temporary payment state or distributed locking is required
- Kafka for payment and donation domain events
- PayOS / VNPay as external payment gateway
- HMAC signature verification for payment callbacks
- Transactional processing and idempotency mechanisms

---

### 2.7 Notification Service

**Responsibility**

- Centralizes outbound notifications produced by different business services.
- Sends email notifications without requiring other services to know email-provider implementation details.
- Consumes asynchronous domain events and converts them into user notifications.
- Handles notification templates, retry and delivery tracking.

**Owned Features**

- Send Email Verification.
- Send Password Recovery Email.
- Notify Followers When a Stream Starts.
- Send System Notifications to Users.
- Send Broadcast Email Notifications.
- Notification Template Management.
- Notification Delivery Status.
- Retry Failed Notifications.
- Notification History if required.

**Technology**

- Java 21
- Spring Boot
- Kafka for consuming notification-related events
- Email provider such as Brevo, SendGrid or AWS SES
- PostgreSQL for notification history / delivery records if required
- Redis optionally for deduplication or temporary notification state

---

### 2.8 VOD Service

**Responsibility**

- Manages recorded livestream content after a livestream session ends.
- Stores and manages VOD metadata while video files are stored in object storage.
- Provides playback information for previously recorded streams.
- Coordinates recording processing, thumbnail generation and VOD publishing.

**Owned Features**

- Save Recording Metadata.
- Publish VOD.
- View VOD.
- List Previous Streams by Channel.
- Delete VOD.
- Change VOD Visibility.
- Generate Thumbnail.
- Manage VOD Playback URL.
- Manage VOD Processing Status.

**Technology**

- Java 21
- Spring Boot
- Spring Data JPA / Hibernate
- PostgreSQL for VOD metadata
- Object Storage such as Amazon S3 or MinIO
- FFmpeg for video processing and thumbnail generation
- MediaMTX / SRS recording integration
- Kafka for asynchronous VOD processing events
- CDN optionally for VOD delivery

---

### 2.9 Web App

**Responsibility**

- Provides the main browser-based user interface for viewers, streamers, moderators and administrators.
- Communicates with backend services through the API Gateway.
- Connects to realtime chat through WebSocket.
- Plays livestream and VOD media directly from the Media Server, Object Storage or CDN.

**Owned Features**

- User Authentication UI.
- User Profile UI.
- Channel Management UI.
- Livestream Setup UI.
- Livestream Viewer UI.
- Realtime Chat UI.
- Donation / Payment UI.
- VOD Browsing and Playback UI.
- Moderation / Report UI.
- Admin Management UI.

**Technology**

- React
- TypeScript
- HTTP / REST Client
- WebSocket Client
- hls.js for HLS playback
- UI framework / component library to be decided later

---

### 2.10 Mobile App

**Responsibility**

- Provides the Android client for users who access the platform from mobile devices.
- Communicates with backend services through the API Gateway.
- Supports livestream viewing, realtime chat, channel interaction and user-facing features.
- Plays media directly from the Media Server / CDN.

**Owned Features**

- Login / Register.
- User Profile.
- Browse Channels.
- Follow / Unfollow Channels.
- Watch Livestream.
- Realtime Chat.
- Donate Gift.
- View Coin Balance.
- Browse and Watch VOD.
- Report User / Stream.

**Technology**

- Kotlin
- Android SDK
- Jetpack Compose
- Retrofit / OkHttp
- WebSocket Client
- Media3 / ExoPlayer for HLS playback
- Coroutines / Flow

