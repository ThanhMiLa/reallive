import { ConfigurationError, loadWorkerConfig } from './worker-config';

const INTERNAL_TOKEN = 'test-internal-token-0123456789abcdef';

function baseEnv(): NodeJS.ProcessEnv {
  return {
    NODE_ENV: 'test',
    MEDIA_WORKER_INTERNAL_TOKEN: INTERNAL_TOKEN,
    MEDIA_WORKER_MEDIAMTX_BASE_URL: 'http://media-server:8888',
  };
}

function issuesFrom(env: NodeJS.ProcessEnv): string[] {
  try {
    loadWorkerConfig(env);
    return [];
  } catch (error) {
    expect(error).toBeInstanceOf(ConfigurationError);
    return [...(error as ConfigurationError).issues];
  }
}

describe('loadWorkerConfig', () => {
  it('applies the documented worker defaults', () => {
    const config = loadWorkerConfig(baseEnv());

    expect(config.environment).toBe('test');
    expect(config.http).toMatchObject({
      port: 8090,
      internalToken: INTERNAL_TOKEN,
      mediamtxBaseUrl: 'http://media-server:8888',
      maxBatchSize: 50,
      maxBodyBytes: 262_144,
    });
    expect(config.job).toMatchObject({
      deadlineMs: 240_000,
      readTokenTtlMs: 300_000,
      width: 640,
      height: 360,
      tempDir: '/tmp/media-worker',
      ffmpegPath: 'ffmpeg',
      ffprobePath: 'ffprobe',
    });
    expect(config.timeouts).toEqual({
      ffmpegMs: 20_000,
      uploadMs: 10_000,
      callbackMs: 5_000,
    });
    expect(config.queue).toMatchObject({
      concurrency: 2,
      processorEnabled: false,
      connectTimeoutMs: 1_000,
      commandTimeoutMs: 1_000,
      batchTimeoutMs: 3_000,
      shutdownTimeoutMs: 45_000,
      maxPendingJobs: 1_000,
      retentionMs: 86_400_000,
      prefix: 'media-worker:thumbnail',
      redis: { host: 'localhost', port: 6_379, db: 0 },
    });
    expect(config.cleanup).toEqual({
      intervalMs: 3_600_000,
      minAgeMs: 86_400_000,
    });
    expect(config.storage).toEqual({ region: 'auto' });
    expect(config.callback).toEqual({
      baseUrl: 'http://stream-service:8082',
    });
  });

  it('parses explicit values and R2 configuration', () => {
    const config = loadWorkerConfig({
      ...baseEnv(),
      PORT: '9000',
      MEDIA_WORKER_MAX_BATCH_SIZE: '10',
      MEDIA_WORKER_MAX_BODY_BYTES: '4096',
      MEDIA_WORKER_JOB_DEADLINE_SECONDS: '120',
      MEDIA_WORKER_READ_TOKEN_TTL_SECONDS: '120',
      MEDIA_WORKER_FFMPEG_TIMEOUT_MS: '15000',
      MEDIA_WORKER_UPLOAD_TIMEOUT_MS: '5000',
      MEDIA_WORKER_CALLBACK_TIMEOUT_MS: '1000',
      MEDIA_WORKER_PROCESSOR_ENABLED: 'true',
      MEDIA_WORKER_QUEUE_CONCURRENCY: '4',
      MEDIA_WORKER_REDIS_CONNECT_TIMEOUT_MS: '2500',
      MEDIA_WORKER_REDIS_COMMAND_TIMEOUT_MS: '3500',
      MEDIA_WORKER_BATCH_TIMEOUT_MS: '4500',
      MEDIA_WORKER_SHUTDOWN_TIMEOUT_MS: '60000',
      MEDIA_WORKER_MAX_PENDING_JOBS: '200',
      MEDIA_WORKER_JOB_RETENTION_SECONDS: '172800',
      MEDIA_WORKER_THUMBNAIL_WIDTH: '1280',
      MEDIA_WORKER_THUMBNAIL_HEIGHT: '720',
      MEDIA_WORKER_R2_ENDPOINT: 'https://account.r2.cloudflarestorage.com',
      MEDIA_WORKER_R2_BUCKET: 'thumbnails',
      MEDIA_WORKER_R2_ACCESS_KEY_ID: 'fixture-access-key',
      MEDIA_WORKER_R2_SECRET_ACCESS_KEY: 'fixture-secret-key',
      STREAM_SERVICE_INTERNAL_TOKEN: 'stream-internal-token-0123456789abcdef',
      REDIS_PASSWORD: 'fixture-redis-password',
    });

    expect(config.http.port).toBe(9000);
    expect(config.http.maxBatchSize).toBe(10);
    expect(config.job.deadlineMs).toBe(120_000);
    expect(config.job.width).toBe(1_280);
    expect(config.queue).toMatchObject({
      processorEnabled: true,
      concurrency: 4,
      connectTimeoutMs: 2_500,
      commandTimeoutMs: 3_500,
      batchTimeoutMs: 4_500,
      shutdownTimeoutMs: 60_000,
      maxPendingJobs: 200,
      retentionMs: 172_800_000,
      redis: { password: 'fixture-redis-password' },
    });
    expect(config.storage).toEqual({
      endpoint: 'https://account.r2.cloudflarestorage.com',
      region: 'auto',
      bucket: 'thumbnails',
      accessKeyId: 'fixture-access-key',
      secretAccessKey: 'fixture-secret-key',
    });
    expect(config.callback.internalToken).toBe(
      'stream-internal-token-0123456789abcdef',
    );
  });

  it('requires a sufficiently long internal token', () => {
    const missing = issuesFrom({
      ...baseEnv(),
      MEDIA_WORKER_INTERNAL_TOKEN: '',
    });
    const short = issuesFrom({
      ...baseEnv(),
      MEDIA_WORKER_INTERNAL_TOKEN: 'too-short',
    });

    expect(missing).toContain('MEDIA_WORKER_INTERNAL_TOKEN is required');
    expect(short).toContain(
      'MEDIA_WORKER_INTERNAL_TOKEN must be at least 32 characters',
    );
  });

  it('requires the processor flag to be true or false', () => {
    expect(
      issuesFrom({
        ...baseEnv(),
        MEDIA_WORKER_PROCESSOR_ENABLED: 'yes',
      }),
    ).toContain('MEDIA_WORKER_PROCESSOR_ENABLED must be true or false');
  });

  it.each(['240', '3600', '86399'])(
    'rejects job retention of %s seconds below the 24-hour minimum',
    (retentionSeconds) => {
      expect(
        issuesFrom({
          ...baseEnv(),
          MEDIA_WORKER_JOB_RETENTION_SECONDS: retentionSeconds,
        }),
      ).toContain('MEDIA_WORKER_JOB_RETENTION_SECONDS must be at least 86400');
    },
  );

  it('rejects an invalid MediaMTX base URL', () => {
    expect(
      issuesFrom({
        ...baseEnv(),
        MEDIA_WORKER_MEDIAMTX_BASE_URL: 'http://media-server:8888/live',
      }),
    ).toContain(
      'MEDIA_WORKER_MEDIAMTX_BASE_URL must be an origin without a path',
    );

    expect(
      issuesFrom({
        ...baseEnv(),
        MEDIA_WORKER_MEDIAMTX_BASE_URL:
          'http://user:password@media-server:8888',
      }),
    ).toContain('MEDIA_WORKER_MEDIAMTX_BASE_URL must not contain credentials');
  });

  it('rejects invalid numbers and unsafe timeout relationships', () => {
    const issues = issuesFrom({
      ...baseEnv(),
      PORT: 'not-a-port',
      MEDIA_WORKER_MAX_BATCH_SIZE: '0',
      MEDIA_WORKER_JOB_DEADLINE_SECONDS: '30',
      MEDIA_WORKER_FFMPEG_TIMEOUT_MS: '20000',
      MEDIA_WORKER_UPLOAD_TIMEOUT_MS: '10000',
      MEDIA_WORKER_CALLBACK_TIMEOUT_MS: '5000',
      MEDIA_WORKER_JOB_RETENTION_SECONDS: '20',
      MEDIA_WORKER_READ_TOKEN_TTL_SECONDS: '20',
      MEDIA_WORKER_TEMP_DIR: 'relative/tmp',
    });

    expect(issues).toContain('PORT must be a non-negative integer');
    expect(issues).toContain('MEDIA_WORKER_MAX_BATCH_SIZE must be at least 1');
    expect(issues).toContain(
      'MEDIA_WORKER_JOB_DEADLINE_SECONDS must exceed the sum of MEDIA_WORKER_FFMPEG_TIMEOUT_MS, MEDIA_WORKER_UPLOAD_TIMEOUT_MS and MEDIA_WORKER_CALLBACK_TIMEOUT_MS',
    );
    expect(issues).toContain(
      'MEDIA_WORKER_JOB_RETENTION_SECONDS must be greater than or equal to MEDIA_WORKER_JOB_DEADLINE_SECONDS',
    );
    expect(issues).toContain(
      'MEDIA_WORKER_READ_TOKEN_TTL_SECONDS must be greater than or equal to MEDIA_WORKER_JOB_DEADLINE_SECONDS',
    );
    expect(issues).toContain('MEDIA_WORKER_TEMP_DIR must be an absolute path');
  });

  it('requires all R2 settings when any one is configured', () => {
    expect(
      issuesFrom({
        ...baseEnv(),
        MEDIA_WORKER_R2_BUCKET: 'thumbnails',
      }),
    ).toContain(
      'MEDIA_WORKER_R2_ENDPOINT, MEDIA_WORKER_R2_BUCKET, MEDIA_WORKER_R2_ACCESS_KEY_ID and MEDIA_WORKER_R2_SECRET_ACCESS_KEY must be configured together',
    );
  });

  it('does not include secret values in configuration errors', () => {
    const secret = 'super-secret-internal-token-0123456789';
    const error = (() => {
      try {
        loadWorkerConfig({
          ...baseEnv(),
          MEDIA_WORKER_INTERNAL_TOKEN: secret,
          MEDIA_WORKER_MAX_BATCH_SIZE: '0',
        });
      } catch (caught) {
        return caught as Error;
      }
      return undefined;
    })();

    expect(error).toBeInstanceOf(ConfigurationError);
    expect(error?.message).not.toContain(secret);
  });
});
