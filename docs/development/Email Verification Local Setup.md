# Email verification infrastructure — local setup

Step 4 prepares the infrastructure and configuration for User → Kafka → Notification → SendGrid. OTP generation, the Kafka JSON DTO, email delivery, webhook endpoints and history migrations are implemented in later steps.

## Requirements and environment

Use Java 21 and Docker Compose 2.23.1+. The root Compose file includes Kafka in KRaft mode, Redis, and independent PostgreSQL instances for User and Notification. Kafka is pinned to `apache/kafka:4.2.1`, matching the Kafka client managed by Spring Boot 4.1.1 in this repository. The listener setup follows the [Apache single-node Docker example](https://github.com/apache/kafka/blob/4.2.1/docker/examples/docker-compose-files/single-node/plaintext/docker-compose.yml).

Copy the root `.env.example` to the root `.env` for Docker Compose only. It contains the six PostgreSQL passwords, MongoDB root/chat passwords and port, Redis password and port, and Kafka's published port. Application-specific settings belong in each service's existing `.env`, not the root file. The service's database/Redis credentials must match the corresponding infrastructure credentials.

Configure the following application settings in the relevant service's `.env`:

| Variables | Used by / validation |
|---|---|
| `USER_DB_URL`, `USER_DB_USERNAME`, `USER_DB_PASSWORD` | User's PostgreSQL connection; default host port 5432 |
| `NOTIFICATION_DB_URL`, `NOTIFICATION_DB_USERNAME`, `NOTIFICATION_DB_PASSWORD` | Notification's PostgreSQL connection; default host port 5437 |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_DATABASE` | User's existing Redis connection; default host port 6379 |
| `KAFKA_BOOTSTRAP_SERVERS` | User and Notification broker addresses; default `localhost:9092`, matching the root `KAFKA_PORT` |
| `EMAIL_VERIFICATION_TOPIC` | Shared request topic; default `notification.email-verification.requested.v1` |
| `EMAIL_VERIFICATION_DLT_TOPIC` | Notification's dead-letter topic; default request topic plus `.dlt`; must differ from the request topic |
| `EMAIL_VERIFICATION_CONSUMER_GROUP` | Notification's consumer group; default `notification.email-verification.v1` |
| `EMAIL_VERIFICATION_TOPIC_PARTITIONS`, `EMAIL_VERIFICATION_TOPIC_REPLICAS` | Positive integers; defaults 3 / 1 for a single local broker |
| `EMAIL_VERIFICATION_TOPIC_RETENTION_MS` | Positive retention duration; default 600000 ms for both topics |
| `EMAIL_VERIFICATION_MAX_RETRIES`, `EMAIL_VERIFICATION_RETRY_BACKOFF_MS` | Nonnegative retries / positive delay; defaults 2 retries at 1000 ms |
| `OTP_COOLDOWN_SECONDS` | User only; agreed default 60 seconds |
| `OTP_TTL_SECONDS`, `OTP_MAX_ATTEMPTS` | User only; proposed defaults 300 seconds / 5 attempts, independently configurable |
| `OTP_HMAC_KEY` | User only; independent random secret for the future OTP implementation; keep it separate from `JWT_SIGNER_KEY` |
| `SENDGRID_API_KEY` | Notification only; required nonblank secret |
| `SENDGRID_FROM_EMAIL`, `SENDGRID_FROM_NAME` | Notification only; valid, nonblank email and nonblank name; email must be configured with SendGrid before delivery is implemented |
| `SENDGRID_WEBHOOK_PUBLIC_KEY` | Notification only; required nonblank key; cryptographic format/signature verification comes with the webhook implementation |

Keep the existing JWT and R2 settings required by User Service. Generate independent secrets, for example by running `openssl rand -hex 32` separately for OTP and JWT. Do not commit `.env` or real credentials.

Docker Compose reads the root `.env` for interpolation. User loads its own `.env` through dotenv when launched with `user-service/` as its working directory. Notification requires its own variables to be exported or configured in the IDE; it does not automatically read `.env`. The startup example below exports `notification-service/.env` with shell-compatible values.

## Start infrastructure and services

From the repository root:

```bash
docker compose up -d --wait postgres-user postgres-notification redis kafka
```

PostgreSQL passwords initialize a new volume only. Changing the environment value does not change the password in an existing database. Kafka data survives container recreation through `kafka-data`; keep the configured cluster ID unchanged for that volume.

Both services keep their development configuration in `application-dev.yaml` and require the `dev` profile for local runs. Tests use independent `application-test.yaml` settings. From the repository root, start Notification first so its KafkaAdmin provisions both configured topics:

```bash
cd notification-service
set -a
. ./.env
set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

In another terminal, start User from the repository root, using `user-service/.env`:

```bash
cd user-service
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

User listens on `8080/user`; Notification listens on `8085/notification`. Notification uses `ddl-auto=validate`, `open-in-view=false`, and Flyway against its own database. It currently has no history entities or migration scripts, so Flyway can report “No migrations found” until the persistence step. Do not point Notification at User's database. Notification does not use Redis yet; its Redis health contributor is disabled despite the existing Redis dependency.

## Host and Docker addresses

| Application location | Kafka bootstrap | PostgreSQL URL | Redis |
|---|---|---|---|
| Host machine | `localhost:9092` | User: `jdbc:postgresql://localhost:5432/reallive_user`; Notification: `jdbc:postgresql://localhost:5437/reallive_notification` | `localhost:6379` |
| Container on the Compose `data` network | `kafka:19092` | User: `jdbc:postgresql://postgres-user:5432/reallive_user`; Notification: `jdbc:postgresql://postgres-notification:5432/reallive_notification` | `redis:6379` |

Override `KAFKA_BOOTSTRAP_SERVERS`, datasource URLs and `REDIS_HOST` when running applications in containers. If changing `KAFKA_PORT`, change the host applications' bootstrap address too. The Kafka controller port is not published. This Compose setup uses one broker, replication factor 1 and local plaintext connections; production needs its own deployment configuration.

## Producer and consumer behavior

User sends string keys/values; the JSON message contract is defined in step 5. Kafka sends use `acks=all`, idempotence, at most 5 in-flight requests, 3 retries, a 5-second request/blocking timeout, and a 15-second delivery timeout. Idempotence protects Kafka producer retries; future email deduplication must still use `eventId`.

Notification's future record listener inherits `enable-auto-commit=false` and `ack-mode=record`. It must return normally only after processing has completed, or throw on failure. Returning before asynchronous email work completes would acknowledge too early.

The configured error handler retries a failed record twice by default, then publishes its string payload to the configured dead-letter topic in the same partition. Its producer uses the same acknowledgement/idempotence/timeout settings. The handler requires successful broker acknowledgement for recovery; failed dead-letter publication keeps the original record eligible for redelivery. No dead-letter consumer or automatic replay is installed. Follow the [Spring Kafka error handling contract](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html) when implementing delivery and durable history.

Notification provisions both topics with equal partition counts and 10-minute retention by default. Kafka deletes expired segments asynchronously, so retention is not an exact deletion deadline. These topics will contain raw OTPs once the wire contract is implemented; restrict access and avoid logging values. Database history/debug records will be introduced separately, without relying on long Kafka retention. When changing topic settings after creation, inspect the broker configuration explicitly; startup provisioning is not a general migration system for existing topics.

## Check the setup

```bash
docker compose ps
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --list
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --describe --topic notification.email-verification.requested.v1
```

Both Java services must finish startup without connection or binding errors. User Service keeps Kafka/OTP settings in YAML without a separate startup validation class. Notification validates its broker/group and email/provider settings; its KafkaAdmin fails startup when it cannot provision topics within its configured timeout. No real email is sent by step 4.

Run `./mvnw clean verify` inside each service. Notification tests use H2, fake provider credentials and an embedded Kafka broker; they verify validation, retries, dead-letter delivery and that offsets are not committed before processing finishes. The actual delivery listener exists only in the test configuration at this step.
