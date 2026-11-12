import {
  ConnectedSocket,
  MessageBody,
  OnGatewayConnection,
  OnGatewayDisconnect,
  OnGatewayInit,
  SubscribeMessage,
  WebSocketGateway,
} from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';
import { ChatError, failure, success, uuid } from '../../common/chat-error';
import { ChatAuthService } from '../services/chat-auth.service';
import { ChatAccessService } from '../services/chat-access.service';
import { ChatService } from '../services/chat.service';
import { RoomQueue } from '../utils/room-queue';
import { encodeCursor } from '../utils/chat-cursor';
import type { Message } from '../dto/chat-message.dto';
import type { Principal } from '../interfaces/chat.types';

interface Member {
  socket: Socket;

  principal: Principal;

  sessionId: string;
}

@WebSocketGateway({
  path: '/chat/socket.io',
  transports: ['websocket'],
  maxHttpBufferSize: 16384,
  allowRequest: (request, callback) => {
    const origins = (
      process.env.AUTH_ALLOWED_ORIGINS ?? 'http://localhost:5173'
    )
      .split(',')
      .map((v) => v.trim());
    callback(
      null,
      typeof request.headers.origin === 'string' &&
        origins.includes(request.headers.origin),
    );
  },
})
export class ChatGateway
  implements OnGatewayInit, OnGatewayConnection, OnGatewayDisconnect
{
  private readonly members = new Map<string, Member>();

  constructor(
    private readonly auth: ChatAuthService,
    private readonly access: ChatAccessService,
    private readonly chat: ChatService,
    private readonly queue: RoomQueue,
  ) {}

  afterInit(server: Server) {
    server.use((socket, next) => {
      void this.auth
        .authenticate(socket.handshake.headers.cookie)
        .then((principal) => {
          socket.data.principal = principal;
          next();
        })
        .catch((error: unknown) => {
          const reason = new Error('Chat authentication failed') as Error & {
            data: unknown;
          };
          reason.data = failure(error).error;
          next(reason);
        });
    });
  }

  handleConnection(socket: Socket) {
    if (!socket.data.principal) socket.disconnect(true);
  }

  handleDisconnect(socket: Socket) {
    this.leave(socket);
  }

  private leave(socket: Socket) {
    const member = this.members.get(socket.id);
    if (!member) return;
    this.members.delete(socket.id);
    void socket.leave(`stream:${member.sessionId}`);
    if (
      ![...this.members.values()].some(
        (m) =>
          m.sessionId === member.sessionId &&
          m.principal.userId === member.principal.userId,
      )
    )
      this.access.forget(member.sessionId, member.principal.userId);
  }

  private async checked(socket: Socket): Promise<Principal> {
    const principal = socket.data.principal as Principal | undefined;
    if (!principal || !socket.connected)
      throw new ChatError('UNAUTHENTICATED', 401, 'Chat is disconnected');
    return this.auth.verify(principal.token);
  }

  @SubscribeMessage('chat:join')
  async join(
    @ConnectedSocket() socket: Socket,
    @MessageBody() input: { sessionId?: unknown } | null,
  ) {
    try {
      const sessionId = uuid(input?.sessionId);
      return await this.queue.run(sessionId, async () => {
        const principal = await this.checked(socket);
        this.leave(socket);
        const grant = await this.access.require(sessionId, principal.userId);
        // Joining before the watermark closes the bootstrap/live-event gap.
        await socket.join(`stream:${sessionId}`);
        this.members.set(socket.id, {
          socket,
          principal,
          sessionId,
        });
        const watermark = encodeCursor(
          sessionId,
          await this.chat.watermark(sessionId),
        );
        return success({
          sessionId,
          canRead: grant.permission.canRead,
          canSend: grant.permission.canSend,
          watermark,
        });
      });
    } catch (error) {
      this.leave(socket);
      return failure(error);
    }
  }

  @SubscribeMessage('chat:leave')
  leaveEvent(@ConnectedSocket() socket: Socket) {
    this.leave(socket);
    return success(null);
  }

  @SubscribeMessage('chat:send')
  async send(
    @ConnectedSocket() socket: Socket,
    @MessageBody()
    input: {
      sessionId?: unknown;
      clientMessageId?: unknown;
      content?: unknown;
    } | null,
  ) {
    try {
      const principal = socket.data.principal as Principal | undefined;
      const member = this.members.get(socket.id);
      if (
        !principal ||
        !socket.connected ||
        !input ||
        !member ||
        member.sessionId !== uuid(input.sessionId)
      )
        throw new ChatError(
          'CHAT_FORBIDDEN',
          403,
          'Join the stream chat first',
        );
      return success(
        await this.chat.send(principal.token, input, (message) =>
          this.deliver(message),
        ),
      );
    } catch (error) {
      return failure(error);
    }
  }

  private deliver(message: Message) {
    for (const member of this.members.values()) {
      if (
        member.sessionId !== message.streamId ||
        member.principal.expiresAt <= Date.now()
      )
        continue;
      member.socket.emit('chat:message', message);
    }
  }
}
