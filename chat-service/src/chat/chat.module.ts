import { Module } from '@nestjs/common';
import { MongooseModule } from '@nestjs/mongoose';
import { ChatConfig, ChatConfigModule } from '../common/chat-config';
import { ChatAuthService } from './services/chat-auth.service';
import { ChatClients } from '../clients/chat-clients.service';
import { ChatAccessService } from './services/chat-access.service';
import { ChatRepository } from './repositories/chat.repository';
import { ChatService } from './services/chat.service';
import { ChatController } from './controllers/chat.controller';
import { ChatGateway } from './gateways/chat.gateway';
import { ChatCounterSchema } from './schemas/chat-counter.schema';
import { ChatMessageSchema } from './schemas/chat-message.schema';
import { RoomQueue } from './utils/room-queue';
import { ChatMapper } from './mappers/chat.mapper';
import { ChatHistoryService } from './services/chat-history.service';
import { ChatDatabaseService } from './services/chat-database.service';

@Module({
  imports: [
    ChatConfigModule,
    MongooseModule.forRootAsync({
      inject: [ChatConfig],
      useFactory: (config: ChatConfig) => ({
        uri: config.mongoUri,
        autoIndex: false,
        autoCreate: false,
        serverSelectionTimeoutMS: 1000,
        retryAttempts: 1,
      }),
    }),
    MongooseModule.forFeature([
      { name: 'ChatMessage', schema: ChatMessageSchema },
      { name: 'ChatCounter', schema: ChatCounterSchema },
    ]),
  ],
  controllers: [ChatController],
  providers: [
    ChatAuthService,
    ChatClients,
    ChatAccessService,
    ChatRepository,
    ChatService,
    ChatGateway,
    RoomQueue,
    ChatMapper,
    ChatHistoryService,
    ChatDatabaseService,
  ],
  exports: [ChatDatabaseService, ChatAuthService],
})
export class ChatModule {}
