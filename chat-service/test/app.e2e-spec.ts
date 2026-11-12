import { Test } from '@nestjs/testing';
import { INestApplication } from '@nestjs/common';
import { getConnectionToken, getModelToken } from '@nestjs/mongoose';
import { Connection, Model, mongo } from 'mongoose';
import { createServer, Server as HttpServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import { resolve } from 'node:path';
import { AddressInfo } from 'node:net';
import request from 'supertest';
import { io, Socket } from 'socket.io-client';
import { AppModule } from '../src/app.module';
import { ChatAuthService } from '../src/chat/services/chat-auth.service';
import { StoredMessage } from '../src/chat/schemas/chat-message.schema';
import { ChatAccessService } from '../src/chat/services/chat-access.service';
import { ChatExceptionFilter } from '../src/common/chat-error';
import type { Message } from '../src/chat/dto/chat-message.dto';
import type {
  Permission,
  SessionContext,
} from '../src/chat/interfaces/chat.types';
import { encodeCursor } from '../src/chat/utils/chat-cursor';

const key = 'chat-e2e-signing-key-'.repeat(5);

const database = `chat_it_${randomUUID().replaceAll('-', '')}`;

const sockets: Socket[] = [];

let app: INestApplication,
  fixture: HttpServer,
  origin: string,
  connection: Connection;

let auth: ChatAuthService,
  access: ChatAccessService,
  model: Model<StoredMessage>;

let sessionId: string, userId: string, token: string;

const sessions = new Map<string, SessionContext>();

const permissions = new Map<string, Partial<Permission>>();

const accessRequests: string[] = [];

const sessionRequests: string[] = [];

let snapshotName = 'Viewer';

let failDependencies = false;

async function issue(id: string, expires = 300, type = 'ACCESS') {
  const { SignJWT } = await import('jose');
  const result = await new SignJWT({ type })
    .setProtectedHeader({ alg: 'HS512' })
    .setIssuer('reallive.com')
    .setSubject(id)
    .setJti(randomUUID())
    .setIssuedAt()
    .setExpirationTime(Math.floor(Date.now() / 1000) + expires)
    .sign(new TextEncoder().encode(key));
  await auth.redis.mset(
    `auth:status:${id}`,
    'ACTIVE',
    `auth:authorities:${id}`,
    '["ROLE_USER"]',
  );
  return result;
}

async function connect(
  accessToken = token,
  allowedOrigin = 'http://localhost:5173',
): Promise<Socket> {
  const socket = io(origin, {
    path: '/chat/socket.io',
    transports: ['websocket'],
    forceNew: true,
    autoConnect: false,
    extraHeaders: {
      Cookie: `access_token=${accessToken}`,
      Origin: allowedOrigin,
    },
  });
  sockets.push(socket);
  await new Promise<void>((resolve, reject) => {
    socket.once('connect', resolve);
    socket.once('connect_error', reject);
    socket.connect();
  });
  return socket;
}

function ack<T = unknown>(
  socket: Socket,
  event: string,
  data: unknown,
): Promise<{ ok: boolean; data: T; error: { code: string; status: number } }> {
  return new Promise((resolve, reject) =>
    socket
      .timeout(3000)
      .emit(event, data, (error: Error | null, value: never) =>
        error ? reject(error) : resolve(value),
      ),
  );
}

async function seed(count: number, id = sessionId) {
  const channelId = sessions.get(id)!.channelId;
  const rows = Array.from({ length: count }, (_, index) => ({
    _id: randomUUID(),
    stream_id: id,
    channel_id: channelId,
    user_id: userId,
    client_message_id: randomUUID(),
    sequence: index + 1,
    content: `Message ${index + 1}`,
    sender_snapshot: {
      display_name: 'Original',
      avatar_url: 'https://example.test/old.png',
    },
    created_at: new Date('2026-11-11T00:00:00Z'),
  }));
  await model.insertMany(rows);
  await connection
    .collection('chat_counters')
    .insertOne({ _id: id as never, value: count });
}

function history(query = '', accessToken = token, id = sessionId) {
  return request(app.getHttpServer())
    .get(`/chat/api/streams/${id}/messages${query}`)
    .set('Cookie', `access_token=${accessToken}`);
}

async function startApplication() {
  const module = await Test.createTestingModule({
    imports: [AppModule],
  }).compile();
  app = module.createNestApplication();
  app.useGlobalFilters(new ChatExceptionFilter());
  await app.init();
  connection = app.get<Connection>(getConnectionToken());
  model = app.get<Model<StoredMessage>>(getModelToken('ChatMessage'));
  await model.createCollection();
  await model.createIndexes();
  await app
    .get<Model<unknown>>(getModelToken('ChatCounter'))
    .createCollection();
  auth = app.get(ChatAuthService);
  if (auth.redis.status === 'wait') await auth.redis.connect();
  access = app.get(ChatAccessService);
  await app.listen(0, '127.0.0.1');
  origin = await app.getUrl();
}

beforeAll(async () => {
  fixture = createServer(async (req, res) => {
    if (req.headers['x-internal-token'] !== undefined) {
      res.writeHead(400).end();
      return;
    }
    if (failDependencies) {
      res.writeHead(503).end();
      return;
    }
    const url = req.url ?? '';
    let value: unknown;
    if (url.endsWith('/chat-context')) {
      sessionRequests.push(url.split('/').at(-2)!);
      value = sessions.get(url.split('/').at(-2)!);
    } else if (url.endsWith('/chat-profile'))
      value = {
        userId: url.split('/').at(-2),
        displayName: snapshotName,
        avatarUrl: 'https://example.test/avatar.png',
      };
    else if (url.endsWith('/chat-access')) {
      let body = '';
      for await (const part of req) body += String(part);
      const request = JSON.parse(body) as { userId: string };
      if (
        typeof request.userId !== 'string' ||
        Object.keys(request).length !== 1
      ) {
        res.writeHead(400).end('{}');
        return;
      }
      accessRequests.push(request.userId);
      value = {
        channelId: url.split('/').at(-2),
        userId: request.userId,
        canView: true,
        canRead: true,
        canSend: true,
        ...permissions.get(request.userId),
      };
    }
    res.setHeader('Content-Type', 'application/json');
    if (!value) res.writeHead(404).end('{}');
    else res.end(JSON.stringify({ status: 200, code: 1000, result: value }));
  });
  await new Promise<void>((resolve) => fixture.listen(0, '127.0.0.1', resolve));
  const fixtureUrl = `http://127.0.0.1:${(fixture.address() as AddressInfo).port}`;
  Object.assign(process.env, {
    JWT_SIGNER_KEY: key,
    MONGODB_URI: `mongodb://127.0.0.1:${process.env.CHAT_TEST_MONGO_PORT ?? '27028'}/${database}?replicaSet=rs0`,
    REDIS_HOST: '127.0.0.1',
    REDIS_PORT: process.env.CHAT_TEST_REDIS_PORT ?? '16388',
    REDIS_DATABASE: '0',
    REDIS_USERNAME: '',
    REDIS_PASSWORD: '',
    STREAM_SERVICE_URL: fixtureUrl,
    CHANNEL_SERVICE_URL: fixtureUrl,
    USER_SERVICE_URL: fixtureUrl,
  });
  await startApplication();
}, 30000);

beforeEach(async () => {
  sessionId = randomUUID();
  userId = randomUUID();
  snapshotName = 'Viewer';
  failDependencies = false;
  permissions.clear();
  sessions.set(sessionId, {
    sessionId,
    channelId: randomUUID(),
    visibility: 'PUBLIC',
    status: 'LIVE',
  });
  token = await issue(userId);
});

afterEach(() => {
  for (const socket of sockets.splice(0)) {
    socket.removeAllListeners();
    socket.disconnect();
  }
});

afterAll(async () => {
  if (connection && /^chat_it_[a-f0-9]{32}$/.test(database))
    await connection.dropDatabase();
  if (app) await app.close();
  if (fixture)
    await new Promise<void>((resolve) => fixture.close(() => resolve()));
});

describe('chat HTTP and Socket.IO with real MongoDB replica set and Redis', () => {
  it('reports database readiness and rejects a missing required index', async () => {
    await request(app.getHttpServer())
      .get('/internal/health/ready')
      .expect(200)
      .expect({ status: 'up' });
    const index = (await model.collection.indexes()).find(
      (entry) => entry.key.stream_id === 1 && entry.key.sequence === 1,
    );
    if (!index?.name) throw new Error('Required test index was not created');
    try {
      await model.collection.dropIndex(index.name);
      await request(app.getHttpServer())
        .get('/internal/health/ready')
        .expect(503);
    } finally {
      await model.createIndexes();
    }
    await request(app.getHttpServer())
      .get('/internal/health/ready')
      .expect(200);
  });

  it('requires a valid current account and token for history and handshakes', async () => {
    await history('', '').expect(401);
    await history('', await issue(userId, -1)).expect(401);
    await history('', await issue(userId, 300, 'REFRESH')).expect(401);
    await auth.redis.set(`auth:status:${userId}`, 'BANNED');
    await history().expect(403);
    await auth.redis.del(`auth:status:${userId}`);
    await history().expect(503);
    await auth.redis.set(`auth:status:${userId}`, 'ACTIVE');
    await expect(connect('wrong')).rejects.toThrow();
  });
  it('rejects a WebSocket Origin outside the allowlist', async () => {
    await expect(connect(token, 'https://untrusted.example')).rejects.toThrow();
  });
  it('rolls the sequence counter back when message persistence fails', async () => {
    const socket = await connect();
    await ack(socket, 'chat:join', { sessionId });
    const request = {
      sessionId,
      clientMessageId: randomUUID(),
      content: 'Retry after storage failure',
    };
    const write = jest
      .spyOn(model, 'create')
      .mockRejectedValueOnce(new Error('Injected storage failure'));
    const rejected = await ack(socket, 'chat:send', request);
    expect(rejected.ok).toBe(false);
    expect(rejected.error.status).toBe(503);
    write.mockRestore();
    expect(await model.countDocuments({ stream_id: sessionId })).toBe(0);
    const retry = await ack<Message>(socket, 'chat:send', request);
    expect(retry.ok).toBe(true);
    expect(retry.data.sequence).toBe(1);
  });
  it('retains one message and sequence when MongoDB retries the transaction', async () => {
    const socket = await connect();
    await ack(socket, 'chat:join', { sessionId });
    const error = new mongo.MongoServerError({
      message: 'Injected transaction retry',
      code: 112,
    });
    error.addErrorLabel('TransientTransactionError');
    const write = jest.spyOn(model, 'create').mockRejectedValueOnce(error);
    const received: Message[] = [];
    socket.on('chat:message', (message: Message) => received.push(message));
    try {
      const reply = await ack<Message>(socket, 'chat:send', {
        sessionId,
        clientMessageId: randomUUID(),
        content: 'Automatic transaction retry',
      });
      expect(reply.ok).toBe(true);
      expect(reply.data.sequence).toBe(1);
      expect(write).toHaveBeenCalledTimes(2);
      const firstAttempt = write.mock.calls[0][0] as unknown as StoredMessage[];
      expect(reply.data.id).toBe(firstAttempt[0]._id);
      expect(await model.countDocuments({ stream_id: sessionId })).toBe(1);
      expect(received.map((message) => message.id)).toEqual([reply.data.id]);
    } finally {
      write.mockRestore();
    }
  });

  it('retains idempotency, snapshots and sequence after an application restart', async () => {
    const socket = await connect();
    await ack(socket, 'chat:join', { sessionId });
    const request = {
      sessionId,
      clientMessageId: randomUUID(),
      content: 'Survives restart',
    };
    const first = await ack<Message>(socket, 'chat:send', request);
    socket.disconnect();
    await app.close();
    await startApplication();
    const reconnected = await connect();
    await ack(reconnected, 'chat:join', { sessionId });
    const replay = await ack<Message>(reconnected, 'chat:send', request);
    expect(replay.data.id).toBe(first.data.id);
    expect(replay.data.sequence).toBe(1);
    const next = await ack<Message>(reconnected, 'chat:send', {
      sessionId,
      clientMessageId: randomUUID(),
      content: 'Next after restart',
    });
    expect(next.data.sequence).toBe(2);
    expect((await history().expect(200)).body.result.items).toHaveLength(2);
  });
  it('paginates equal timestamps by sequence in both directions and isolates sessions', async () => {
    await seed(125);
    const first = (await history().expect(200)).body.result;
    expect(first.items).toHaveLength(50);
    expect(first.items[0].sequence).toBe(76);
    expect(first.items.at(-1).sequence).toBe(125);
    const emptyTail = (await history(`?after=${first.newerCursor}`).expect(200))
      .body.result;
    expect(emptyTail.items).toHaveLength(0);
    expect(emptyTail.hasOlder).toBe(true);
    const recoveredTail = (
      await history(`?before=${emptyTail.olderCursor}`).expect(200)
    ).body.result;
    expect(recoveredTail.items.at(-1).sequence).toBe(125);
    const older = (await history(`?before=${first.olderCursor}`).expect(200))
      .body.result;
    expect(older.items[0].sequence).toBe(26);
    expect(older.items.at(-1).sequence).toBe(75);
    const start = (await history(`?before=${older.olderCursor}`).expect(200))
      .body.result;
    expect(start.items).toHaveLength(25);
    expect(start.hasOlder).toBe(false);
    const newer = (await history(`?after=${start.newerCursor}`).expect(200))
      .body.result;
    expect(newer.items[0].sequence).toBe(26);
    await history(`?before=${encodeCursor(randomUUID(), 50)}`).expect(400);
    await history(
      `?before=${first.olderCursor}&after=${first.newerCursor}`,
    ).expect(400);
    await history(`?through=${encodeCursor(sessionId, 999)}`).expect(400);
  });
  it('persists before ACK, retries idempotently and retains the original snapshot', async () => {
    const socket = await connect();
    expect((await ack(socket, 'chat:join', { sessionId })).ok).toBe(true);
    const input = {
      sessionId,
      clientMessageId: randomUUID(),
      content: ' <b>Hello 😀</b> ',
    };
    const first = await ack<Message>(socket, 'chat:send', input);
    expect(first.ok).toBe(true);
    expect(first.data.content).toBe('<b>Hello 😀</b>');
    expect(await model.countDocuments({ stream_id: sessionId })).toBe(1);
    snapshotName = 'Renamed';
    const retry = await ack<Message>(socket, 'chat:send', input);
    expect(retry.data.id).toBe(first.data.id);
    expect(retry.data.sender.displayName).toBe('Viewer');
    const conflict = await ack(socket, 'chat:send', {
      ...input,
      content: 'Different',
    });
    expect(conflict.error.code).toBe('CHAT_MESSAGE_CONFLICT');
    expect(await model.countDocuments({ stream_id: sessionId })).toBe(1);
  });
  it('rejects empty and oversized messages and never trusts supplied sender fields', async () => {
    const socket = await connect();
    await ack(socket, 'chat:join', { sessionId });
    for (const content of [' ', '😀'.repeat(501)])
      expect(
        (
          await ack(socket, 'chat:send', {
            sessionId,
            clientMessageId: randomUUID(),
            content,
          })
        ).error.status,
      ).toBe(400);
    const accepted = await ack<Message>(socket, 'chat:send', {
      sessionId,
      clientMessageId: randomUUID(),
      content: '😀'.repeat(500),
      userId: randomUUID(),
      sender: { displayName: 'Forged' },
    });
    expect(accepted.ok).toBe(true);
    expect(accepted.data.userId).toBe(userId);
    expect(accepted.data.sender.displayName).toBe('Viewer');
  });
  it('enforces read/send separation and checks changed bans on the next action', async () => {
    permissions.set(userId, { canSend: false });
    const socket = await connect();
    const join = await ack<{ canSend: boolean }>(socket, 'chat:join', {
      sessionId,
    });
    expect(join.ok).toBe(true);
    expect(join.data.canSend).toBe(false);
    await history().expect(200);
    expect(
      (
        await ack(socket, 'chat:send', {
          sessionId,
          clientMessageId: randomUUID(),
          content: 'Blocked',
        })
      ).error.code,
    ).toBe('CHAT_BANNED');
    permissions.set(userId, { canSend: true });
    access.forget(sessionId, userId);
    expect(
      (
        await ack(socket, 'chat:send', {
          sessionId,
          clientMessageId: randomUUID(),
          content: 'Allowed',
        })
      ).ok,
    ).toBe(true);
    permissions.set(userId, { canView: false, canRead: false, canSend: false });
    access.forget(sessionId, userId);
    await history().expect(403);
  });
  it('keeps the room during reconnecting and closes reads and writes after end', async () => {
    const socket = await connect();
    await ack(socket, 'chat:join', { sessionId });
    sessions.get(sessionId)!.status = 'RECONNECTING';
    access.forget(sessionId, userId);
    expect(
      (
        await ack(socket, 'chat:send', {
          sessionId,
          clientMessageId: randomUUID(),
          content: 'Still here',
        })
      ).ok,
    ).toBe(true);
    sessions.get(sessionId)!.status = 'ENDED';
    access.forget(sessionId, userId);
    await history().expect(409);
    expect(
      (
        await ack(socket, 'chat:send', {
          sessionId,
          clientMessageId: randomUUID(),
          content: 'Too late',
        })
      ).ok,
    ).toBe(false);
    expect(await model.countDocuments({ stream_id: sessionId })).toBe(1);
  });
  it('does not expose a different room and can fill the bootstrap/live gap', async () => {
    const socket = await connect();
    const joined = await ack<{ watermark: string }>(socket, 'chat:join', {
      sessionId,
    });
    const watermark = joined.data.watermark;
    const sent = await ack<Message>(socket, 'chat:send', {
      sessionId,
      clientMessageId: randomUUID(),
      content: 'After join',
    });
    const old = (await history(`?through=${watermark}`).expect(200)).body
      .result;
    expect(old.items).toHaveLength(0);
    const recovered = (await history(`?after=${watermark}`).expect(200)).body
      .result;
    expect(recovered.items.map((m: Message) => m.id)).toEqual([sent.data.id]);
    expect(
      (
        await ack(socket, 'chat:send', {
          sessionId: randomUUID(),
          clientMessageId: randomUUID(),
          content: 'Other room',
        })
      ).error.status,
    ).toBe(403);
  });
  it('serializes concurrent sends with unique contiguous sequences', async () => {
    const socket = await connect();
    await ack(socket, 'chat:join', { sessionId });
    const results = await Promise.all(
      Array.from({ length: 20 }, (_, n) =>
        ack<Message>(socket, 'chat:send', {
          sessionId,
          clientMessageId: randomUUID(),
          content: `Concurrent ${n}`,
        }),
      ),
    );
    expect(results.every((r) => r.ok)).toBe(true);
    expect(results.map((r) => r.data.sequence)).toEqual(
      Array.from({ length: 20 }, (_, i) => i + 1),
    );
  });
  it('fails closed when dependencies fail, without acknowledging or saving a new message', async () => {
    const socket = await connect();
    await ack(socket, 'chat:join', { sessionId });
    failDependencies = true;
    access.forget(sessionId, userId);
    const reply = await ack(socket, 'chat:send', {
      sessionId,
      clientMessageId: randomUUID(),
      content: 'Do not save',
    });
    expect(reply.ok).toBe(false);
    expect(await model.countDocuments({ stream_id: sessionId })).toBe(0);
    await history().expect(503);
  });
  it('rejects the next action when the access token is revoked', async () => {
    const socket = await connect();
    await ack(socket, 'chat:join', { sessionId });
    const { decodeJwt } = await import('jose');
    await auth.redis.set(
      `auth:revoked:access:${String(decodeJwt(token).jti)}`,
      '1',
    );
    const reply = await ack(socket, 'chat:send', {
      sessionId,
      clientMessageId: randomUUID(),
      content: 'Revoked token',
    });
    expect(reply.error.status).toBe(401);
    await history().expect(401);
    expect(await model.countDocuments({ stream_id: sessionId })).toBe(0);
  });

  it('does not poll idle members and still delivers after the access cache expires', async () => {
    const reader = await connect();
    const writer = await connect();
    await ack(reader, 'chat:join', { sessionId });
    await ack(writer, 'chat:join', { sessionId });
    const sessionCalls = sessionRequests.length;
    const accessCalls = accessRequests.length;
    const closed = jest.fn();
    reader.on('chat:closed', closed);
    await new Promise((resolve) => setTimeout(resolve, 2100));
    expect(sessionRequests).toHaveLength(sessionCalls);
    expect(accessRequests).toHaveLength(accessCalls);
    expect(closed).not.toHaveBeenCalled();
    const received = new Promise<Message>((resolve) =>
      reader.once('chat:message', resolve),
    );
    const reply = await ack<Message>(writer, 'chat:send', {
      sessionId,
      clientMessageId: randomUUID(),
      content: 'Still receiving after idle',
    });
    expect(reply.ok).toBe(true);
    expect((await received).id).toBe(reply.data.id);
    expect(accessRequests.at(-1)).toBe(userId);
    expect(accessRequests.length).toBeGreaterThan(accessCalls);
    sessions.get(sessionId)!.status = 'ENDED';
    access.forget(sessionId, userId);
    await history().expect(409);
  });
  (process.env.CHAT_BASELINE === 'true' ? it : it.skip)(
    'records baseline with persisted history and concurrent socket clients',
    async () => {
      await seed(100000);
      const clients = await Promise.all(
        Array.from({ length: 20 }, () => connect()),
      );
      await Promise.all(
        clients.map((socket) => ack(socket, 'chat:join', { sessionId })),
      );
      const cpu = process.cpuUsage();
      const start = performance.now();
      let maxRss = process.memoryUsage().rss;
      const resourceTimer = setInterval(() => {
        maxRss = Math.max(maxRss, process.memoryUsage().rss);
      }, 20);
      const writes: number[] = [],
        reads: number[] = [];
      async function measure(
        target: number[],
        operation: () => Promise<unknown>,
      ) {
        const before = performance.now();
        await operation();
        target.push(performance.now() - before);
      }
      try {
        for (let batch = 0; batch < 10; batch++) {
          await Promise.all(
            Array.from({ length: 10 }, (_, index) =>
              measure(writes, async () => {
                const reply = await ack(clients[index], 'chat:send', {
                  sessionId,
                  clientMessageId: randomUUID(),
                  content: 'Baseline message',
                });
                expect(reply.ok).toBe(true);
              }),
            ),
          );
          await Promise.all(
            Array.from({ length: 10 }, () =>
              measure(reads, async () => {
                await history(
                  `?before=${encodeCursor(sessionId, 50000)}`,
                ).expect(200);
              }),
            ),
          );
        }
      } finally {
        clearInterval(resourceTimer);
      }
      const elapsed = performance.now() - start,
        cpuUsed = process.cpuUsage(cpu);
      const stats = (samples: number[]) => {
        const sorted = [...samples].sort((a, b) => a - b);
        const at = (percentile: number) =>
          Number(
            sorted[
              Math.min(
                sorted.length - 1,
                Math.ceil(sorted.length * percentile) - 1,
              )
            ].toFixed(2),
          );
        return {
          samples: samples.length,
          p50Ms: at(0.5),
          p95Ms: at(0.95),
          p99Ms: at(0.99),
        };
      };
      const plan = await model.collection
        .find({ stream_id: sessionId, sequence: { $lt: 50000 } })
        .sort({ sequence: -1 })
        .limit(50)
        .explain('executionStats');
      const report = {
        measuredAt: new Date().toISOString(),
        environment:
          'Local Docker MongoDB replica set and Redis; mock Java integrations; Nest server and clients share the Jest process; Gateway excluded',
        node: process.version,
        historyRecords: 100000,
        connections: clients.length,
        writeRtt: stats(writes),
        historyRtt: stats(reads),
        elapsedMs: Number(elapsed.toFixed(2)),
        mixedOperationsPerSecond: Number(
          ((writes.length + reads.length) / (elapsed / 1000)).toFixed(2),
        ),
        cpuMs: Number(((cpuUsed.user + cpuUsed.system) / 1000).toFixed(2)),
        maxRssMiB: Number((maxRss / 1048576).toFixed(2)),
        deepPage: {
          returned: plan.executionStats.nReturned,
          keysExamined: plan.executionStats.totalKeysExamined,
          docsExamined: plan.executionStats.totalDocsExamined,
        },
      };
      const directory = resolve(__dirname, '../../docs/testing');
      mkdirSync(directory, { recursive: true });
      writeFileSync(
        resolve(directory, 'Chat Baseline.json'),
        JSON.stringify(report, null, 2) + '\n',
      );
      expect(report.deepPage.docsExamined).toBeLessThanOrEqual(50);
    },
    60000,
  );
  it('checks actual history plans use the stream sequence index', async () => {
    await seed(2000);
    const plan = await model.collection
      .find({ stream_id: sessionId, sequence: { $lt: 1000 } })
      .sort({ sequence: -1 })
      .limit(50)
      .explain('executionStats');
    expect(plan.executionStats.nReturned).toBe(50);
    expect(plan.executionStats.totalDocsExamined).toBeLessThanOrEqual(50);
    expect(plan.executionStats.totalKeysExamined).toBeLessThanOrEqual(51);
  });
});
