import { Controller, Get, Headers, Param, Query, Res } from '@nestjs/common';
import type { Response } from 'express';
import { ChatService } from '../services/chat.service';
import type { HistoryQuery } from '../dto/chat-history.dto';

@Controller('chat/api/streams')
export class ChatController {
  constructor(private readonly chat: ChatService) {}

  @Get(':sessionId/messages')
  async history(
    @Headers('cookie') cookie: string | undefined,
    @Param('sessionId') id: string,
    @Query() query: HistoryQuery,
    @Res({ passthrough: true }) response: Response,
  ) {
    response.setHeader('Cache-Control', 'no-store');
    return {
      status: 200,
      code: 1000,
      message: 'Chat history retrieved',
      result: await this.chat.history(cookie, id, query),
      timestamp: new Date().toISOString(),
    };
  }
}
