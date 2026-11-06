import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import type { ThumbnailJobInput } from '../common/contracts/thumbnail-contract';
import { ThumbnailImageModule } from '../processor/thumbnail-image.module';
import { ThumbnailImageService } from '../processor/thumbnail-image.service';

const controller = new AbortController();
let signalExitCode: number | undefined;
const stop = (code: number) => {
  signalExitCode ??= code;
  controller.abort();
  if (!process.stdin.readableEnded) process.stdin.destroy();
};
const onTerm = () => stop(143);
const onInt = () => stop(130);
process.on('SIGTERM', onTerm);
process.on('SIGINT', onInt);

async function main(): Promise<void> {
  let input = '';
  for await (const chunk of process.stdin) {
    input += String(chunk);
    if (input.length > 262144) throw new Error('INPUT_TOO_LARGE');
  }
  let job: ThumbnailJobInput;
  try {
    job = JSON.parse(input) as ThumbnailJobInput;
  } catch {
    throw new Error('INVALID_INPUT');
  }
  const app = await NestFactory.createApplicationContext(ThumbnailImageModule, {
    logger: false,
  });
  try {
    const service = app.get(ThumbnailImageService);
    const result = await service.create(job, controller.signal);
    if (controller.signal.aborted) throw new Error('UPLOAD_ABORTED');
    process.stdout.write(`${JSON.stringify(result)}\n`);
  } finally {
    await app.close();
  }
}
main()
  .catch((error: unknown) => {
    const allowed = new Set([
      'INPUT_TOO_LARGE',
      'INVALID_INPUT',
      'INVALID_JOB',
      'JOB_EXPIRED',
      'INVALID_READ_TOKEN',
      'INVALID_JPEG',
      'CAPTURE_TIMEOUT',
      'CAPTURE_ABORTED',
      'PROCESS_START_FAILED',
      'PROCESS_OUTPUT_LIMIT',
      'PROCESS_FAILED',
      'R2_NOT_CONFIGURED',
      'INVALID_UPLOAD_INPUT',
      'UPLOAD_FILE_READ_FAILED',
      'UPLOAD_TIMEOUT',
      'UPLOAD_ABORTED',
      'R2_UPLOAD_FAILED',
    ]);
    const code =
      error instanceof Error && allowed.has(error.message)
        ? error.message
        : 'CAPTURE_FAILED';
    process.stderr.write(`${code}\n`);
    process.exitCode = signalExitCode ?? 1;
  })
  .finally(() => {
    process.removeListener('SIGTERM', onTerm);
    process.removeListener('SIGINT', onInt);
  });
