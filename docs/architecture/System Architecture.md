# SYSTEM ARCHITECTURE

## 1. System Architecture Design
![Architecture Design](./image/Architecture%20Design.png)

---

## 2. Service Decomposition

| Service Name | Tech Stack                                                                                           | Description |
| --- |------------------------------------------------------------------------------------------------------| --- |
| API Gateway | Java 21, Spring Boot, Spring Cloud Gateway (WebFlux), Redis, JWT                                     | Acts as the single API entry point for web and mobile clients, routes requests to backend services, validates authentication and handles CORS and shared request protection. |
| User Service | Java 21, Spring Boot, PostgreSQL, Redis, Kafka, JWT / OAuth2, Cloudflare R2 (S3 API)                 | Manages user identity, authentication, account status, profiles, email verification, roles and account-level moderation. |
| Channel Service | Java 21, Spring Boot, PostgreSQL, Redis, Cloudflare R2 (S3 API)                                      | Manages streamer channels, channel profiles, follower relationships and channel moderator assignments. |
| Stream Service | Java 21, Spring Boot, PostgreSQL, Redis, Kafka, WebClient                                            | Manages livestream sessions, publishing credentials, lifecycle state and playback metadata; integrates with Media Server and coordinates thumbnail jobs with Media Worker. |
| Chat Service | Node.js, TypeScript, NestJS, WebSocket / Socket.IO, MongoDB / Mongoose, Redis                        | Provides realtime channel chat, manages chat rooms and moderation, and stores message history with timeline information for VOD replay. |
| Payment Service | Java 21, Spring Boot, PostgreSQL, Redis                                                              | Manages coin balances, virtual gifts, donations, payment transactions, streamer revenue and withdrawals, including external payment integration and idempotent processing. |
| Notification Service | Java 21, Spring Boot, PostgreSQL, Redis, Kafka, SendGrid                                             | Centralizes email and user notifications, consumes domain events, manages templates and tracks delivery and retry status. |
| VOD Service | Java 21, Spring Boot, PostgreSQL, Kafka                                                              | Manages recorded livestream metadata, processing and publishing status, visibility and playback information, while video files remain in object storage. |
| Media Server | MediaMTX, Docker, RTMP, HLS, JWT / JWKS, Python webhook scripts                                      | Receives livestream media from broadcasting clients, authenticates publish and playback access, serves HLS media and reports stream connection events to Stream Service. |
| Media Worker | Node.js, TypeScript, NestJS, BullMQ, Redis, FFmpeg, Cloudflare R2 (S3 API)                           | Processes queued livestream thumbnail jobs, extracts frames from HLS with FFmpeg, uploads images to object storage and sends results back to Stream Service. |
| Web App | React, TypeScript, Vite, Tailwind CSS, React Router, TanStack Query, Axios, Socket.IO Client, hls.js | Provides the browser interface for viewers, streamers, moderators and administrators, including livestream and VOD playback, chat, channel management and payments. |
| Mobile App | Kotlin, Android SDK, Jetpack Compose, Material 3, AndroidX Lifecycle                                 | Provides the Android client for livestream viewing, chat, channel interaction and other user-facing platform features. |