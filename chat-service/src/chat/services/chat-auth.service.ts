import { Injectable, OnModuleDestroy } from '@nestjs/common';
import Redis from 'ioredis';
import { ChatConfig } from '../../common/chat-config';
import { ChatError, unavailable, uuid } from '../../common/chat-error';
import type { Principal } from '../interfaces/chat.types';

@Injectable()
export class ChatAuthService implements OnModuleDestroy {
  readonly redis: Redis;

  constructor(private readonly config: ChatConfig) {
    this.redis = new Redis(config.redis);
    this.redis.on('error', () => undefined);
  }

  async onModuleDestroy() {
    this.redis.disconnect();
  }

  token(cookie: string | undefined): string {
    const values = (cookie ?? '')
      .split(';')
      .map((v) => v.trim())
      .filter((v) => v.startsWith(`${this.config.cookieName}=`));
    if (values.length !== 1)
      throw new ChatError('UNAUTHENTICATED', 401, 'Sign in to use chat');
    try {
      return decodeURIComponent(
        values[0].slice(this.config.cookieName.length + 1),
      );
    } catch {
      throw new ChatError('UNAUTHENTICATED', 401, 'Invalid access token');
    }
  }

  async authenticate(cookie: string | undefined): Promise<Principal> {
    return this.verify(this.token(cookie));
  }

  async verify(token: string): Promise<Principal> {
    let principal: Principal;
    try {
      const { jwtVerify } = await import('jose');
      const { payload } = await jwtVerify(
        token,
        new TextEncoder().encode(this.config.signerKey),
        {
          algorithms: ['HS512'],
          issuer: this.config.issuer,
          requiredClaims: ['sub', 'iss', 'iat', 'exp', 'jti', 'type'],
        },
      );
      if (
        payload.type !== 'ACCESS' ||
        typeof payload.jti !== 'string' ||
        !payload.jti.trim() ||
        !Number.isInteger(payload.iat) ||
        !Number.isInteger(payload.exp) ||
        Number(payload.iat) > Math.floor(Date.now() / 1000) ||
        Number(payload.exp) <= Number(payload.iat)
      )
        throw new Error();
      principal = {
        userId: uuid(payload.sub),
        token,
        expiresAt: Number(payload.exp) * 1000,
      };
    } catch {
      throw new ChatError(
        'UNAUTHENTICATED',
        401,
        'Invalid or expired access token',
      );
    }
    try {
      if (this.redis.status === 'wait') await this.redis.connect();
      const { decodeJwt } = await import('jose');
      const claims = decodeJwt(token);
      const [status, revoked, authorities] = await this.redis.mget(
        `auth:status:${principal.userId}`,
        `auth:revoked:access:${String(claims.jti)}`,
        `auth:authorities:${principal.userId}`,
      );
      if (revoked !== null)
        throw new ChatError(
          'UNAUTHENTICATED',
          401,
          'Access token has been revoked',
        );
      if (status === 'BANNED')
        throw new ChatError('ACCOUNT_BANNED', 403, 'Account is banned');
      if (status !== 'ACTIVE' || authorities === null) throw unavailable();
      const roles: unknown = JSON.parse(authorities);
      if (
        !Array.isArray(roles) ||
        roles.some((v) => typeof v !== 'string' || !v.trim())
      )
        throw unavailable();
      return principal;
    } catch (error) {
      if (error instanceof ChatError) throw error;
      throw unavailable();
    }
  }
}
