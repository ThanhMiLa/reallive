import { Global, Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { loadWorkerConfig, WORKER_CONFIG } from './worker-config';

@Global()
@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      cache: false,
      envFilePath: ['.env.local', '.env'],
      ignoreEnvFile: process.env.NODE_ENV === 'test',
    }),
  ],
  providers: [
    {
      provide: WORKER_CONFIG,
      useFactory: () => loadWorkerConfig(process.env),
    },
  ],
  exports: [WORKER_CONFIG],
})
export class WorkerConfigModule {}
