import { Global, Module } from '@nestjs/common';

export class ChatConfig {
  readonly port = Number(process.env.PORT ?? 3001);

  readonly host = process.env.BACKEND_BIND_ADDRESS ?? '127.0.0.1';

  readonly mongoUri =
    process.env.MONGODB_URI ??
    'mongodb://127.0.0.1:27018/reallive_chat?replicaSet=rs0';

  readonly signerKey = process.env.JWT_SIGNER_KEY ?? '';

  readonly issuer = process.env.JWT_ISSUER ?? 'reallive.com';

  readonly cookieName = process.env.ACCESS_TOKEN_COOKIE_NAME ?? 'access_token';

  readonly origins = (
    process.env.AUTH_ALLOWED_ORIGINS ?? 'http://localhost:5173'
  )
    .split(',')
    .map((v) => v.trim())
    .filter(Boolean);

  readonly streamUrl =
    process.env.STREAM_SERVICE_URL ?? 'http://127.0.0.1:8082/stream';

  readonly channelUrl =
    process.env.CHANNEL_SERVICE_URL ?? 'http://127.0.0.1:8081/channel';

  readonly userUrl =
    process.env.USER_SERVICE_URL ?? 'http://127.0.0.1:8080/user';

  readonly redis = {
    host: process.env.REDIS_HOST ?? '127.0.0.1',
    port: Number(process.env.REDIS_PORT ?? 6379),
    username: process.env.REDIS_USERNAME || undefined,
    password: process.env.REDIS_PASSWORD || undefined,
    db: Number(process.env.REDIS_DATABASE ?? 0),
    connectTimeout: 1000,
    commandTimeout: 1000,
    lazyConnect: true,
    enableOfflineQueue: false,
    maxRetriesPerRequest: 1,
  };

  constructor() {
    if (Buffer.byteLength(this.signerKey) < 64)
      throw new Error('JWT_SIGNER_KEY must contain at least 64 UTF-8 bytes');
    if (!Number.isInteger(this.port) || this.port < 1 || this.port > 65535)
      throw new Error('Invalid PORT');
    if (!this.origins.length || this.origins.includes('*'))
      throw new Error('Explicit allowed origins are required');
  }
}

@Global()
@Module({
  providers: [{ provide: ChatConfig, useFactory: () => new ChatConfig() }],
  exports: [ChatConfig],
})
export class ChatConfigModule {}
