import { Inject, Injectable, OnApplicationShutdown } from '@nestjs/common';
import { spawn, type ChildProcess } from 'node:child_process';
import { lstat, open } from 'node:fs/promises';
import {
  WORKER_CONFIG,
  type WorkerConfig,
} from '../common/config/worker-config';
import type {
  ExtractedThumbnail,
  ExtractThumbnailRequest,
  FfmpegRunner,
} from './ffmpeg-runner.port';

@Injectable()
export class FfmpegRunnerImpl implements FfmpegRunner, OnApplicationShutdown {
  private readonly active = new Map<AbortController, Promise<void>>();
  private stopping = false;
  constructor(@Inject(WORKER_CONFIG) private readonly config: WorkerConfig) {}

  async extractThumbnail(
    request: ExtractThumbnailRequest,
  ): Promise<ExtractedThumbnail> {
    if (this.stopping || request.signal?.aborted)
      throw new Error('CAPTURE_ABORTED');
    if (!request.readToken || /[\r\n]/.test(request.readToken))
      throw new Error('INVALID_READ_TOKEN');
    if (/[\r\n]/.test(request.playbackUrl)) throw new Error('INVALID_JOB');
    const abort = new AbortController();
    let finish!: () => void;
    const finished = new Promise<void>((resolve) => {
      finish = resolve;
    });
    this.active.set(abort, finished);
    const onAbort = () => abort.abort();
    request.signal?.addEventListener('abort', onAbort, { once: true });
    if (request.signal?.aborted) abort.abort();
    const end = Date.now() + request.timeoutMs;
    const remaining = () => Math.max(0, end - Date.now());
    const filter = `scale=${request.width}:${request.height}:force_original_aspect_ratio=decrease,pad=${request.width}:${request.height}:(ow-iw)/2:(oh-ih)/2:black,setsar=1`;
    try {
      await this.run(
        this.config.job.ffmpegPath,
        [
          '-hide_banner',
          '-loglevel',
          'error',
          '-nostdin',
          '-threads',
          String(this.config.job.ffmpegThreads),
          '-filter_threads',
          String(this.config.job.ffmpegThreads),
          '-protocol_whitelist',
          'pipe,http,https,tcp,tls,crypto',
          '-f',
          'concat',
          '-safe',
          '0',
          '-i',
          'pipe:3',
          '-map',
          '0:v:0',
          '-an',
          '-sn',
          '-dn',
          '-vf',
          filter,
          '-frames:v',
          '1',
          '-threads',
          String(this.config.job.ffmpegThreads),
          '-fs',
          String(this.config.job.maxJpegBytes),
          '-y',
          request.outputPath,
        ],
        remaining(),
        abort.signal,
        `ffconcat version 1.0\nfile '${request.playbackUrl.replaceAll("'", "'\\''")}'\noption headers 'Authorization: Bearer ${request.readToken.replaceAll("'", "'\\''")}'\noption live_start_index -1\n`,
      );
      const stat = await lstat(request.outputPath);
      if (
        !stat.isFile() ||
        stat.isSymbolicLink() ||
        stat.size <= 0 ||
        stat.size > this.config.job.maxJpegBytes
      )
        throw new Error('INVALID_JPEG');
      const file = await open(request.outputPath, 'r');
      try {
        const magic = Buffer.alloc(3);
        const result = await file.read(magic, 0, 3, 0);
        if (
          result.bytesRead !== 3 ||
          !magic.equals(Buffer.from([0xff, 0xd8, 0xff]))
        )
          throw new Error('INVALID_JPEG');
      } finally {
        await file.close();
      }
      const output = await this.run(
        this.config.job.ffprobePath,
        [
          '-v',
          'error',
          '-select_streams',
          'v:0',
          '-show_entries',
          'stream=codec_name,width,height',
          '-of',
          'json',
          request.outputPath,
        ],
        remaining(),
        abort.signal,
      );
      let info: {
        streams?: Array<{
          codec_name?: string;
          width?: number;
          height?: number;
        }>;
      };
      try {
        info = JSON.parse(output) as typeof info;
      } catch {
        throw new Error('INVALID_JPEG');
      }
      const stream = info.streams?.[0];
      if (
        stream?.codec_name !== 'mjpeg' ||
        stream.width !== request.width ||
        stream.height !== request.height
      )
        throw new Error('INVALID_JPEG');
      await this.run(
        this.config.job.ffmpegPath,
        [
          '-hide_banner',
          '-loglevel',
          'error',
          '-nostdin',
          '-threads',
          String(this.config.job.ffmpegThreads),
          '-i',
          request.outputPath,
          '-frames:v',
          '1',
          '-f',
          'null',
          '-',
        ],
        remaining(),
        abort.signal,
      );
      return {
        filePath: request.outputPath,
        width: stream.width,
        height: stream.height,
        sizeBytes: stat.size,
        contentType: 'image/jpeg',
      };
    } finally {
      this.active.delete(abort);
      finish();
      request.signal?.removeEventListener('abort', onAbort);
    }
  }

  private run(
    program: string,
    args: string[],
    timeout: number,
    signal: AbortSignal,
    privateInput?: string,
  ): Promise<string> {
    if (timeout <= 0) return Promise.reject(new Error('CAPTURE_TIMEOUT'));
    if (signal.aborted) return Promise.reject(new Error('CAPTURE_ABORTED'));
    return new Promise((resolve, reject) => {
      let child: ChildProcess;
      try {
        child = spawn(program, args, {
          shell: false,
          stdio: ['ignore', 'pipe', 'pipe', 'pipe'],
        });
      } catch {
        reject(new Error('PROCESS_START_FAILED'));
        return;
      }
      if (privateInput) {
        const input = child.stdio[3] as import('node:stream').Writable;
        input.on('error', () => undefined);
        input.end(privateInput);
      }
      let output = '';
      let bytes = 0;
      let failure: string | undefined;
      let escalation: NodeJS.Timeout | undefined;
      const terminate = (reason: string) => {
        if (failure) return;
        failure = reason;
        child.kill('SIGTERM');
        escalation = setTimeout(
          () => child.kill('SIGKILL'),
          this.config.job.terminationGraceMs,
        );
      };
      const timer = setTimeout(() => terminate('CAPTURE_TIMEOUT'), timeout);
      const onAbort = () => terminate('CAPTURE_ABORTED');
      signal.addEventListener('abort', onAbort, { once: true });
      child.stdout?.on('data', (chunk: Buffer) => {
        bytes += chunk.length;
        if (bytes > this.config.job.processOutputBytes)
          terminate('PROCESS_OUTPUT_LIMIT');
        else output += chunk.toString();
      });
      child.stderr?.on('data', (chunk: Buffer) => {
        bytes += chunk.length;
        if (bytes > this.config.job.processOutputBytes)
          terminate('PROCESS_OUTPUT_LIMIT');
      });
      child.on('error', () => {
        failure = failure ?? 'PROCESS_START_FAILED';
      });
      child.on('close', (code) => {
        clearTimeout(timer);
        if (escalation) clearTimeout(escalation);
        signal.removeEventListener('abort', onAbort);
        if (failure) reject(new Error(failure));
        else if (code !== 0) reject(new Error('PROCESS_FAILED'));
        else resolve(output);
      });
    });
  }

  async onApplicationShutdown(): Promise<void> {
    this.stopping = true;
    const running = [...this.active.entries()];
    for (const [controller] of running) controller.abort();
    await Promise.all(running.map(([, finished]) => finished));
  }
}
